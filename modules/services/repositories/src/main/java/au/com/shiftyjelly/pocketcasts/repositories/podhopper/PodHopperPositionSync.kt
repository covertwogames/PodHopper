package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import android.util.Log
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import org.json.JSONArray
import org.json.JSONObject

/**
 * Syncs playback position and completion across devices (phone and car) through Supabase.
 *
 * This restores the cross device resume behaviour that worked in the AntennaPod build, with no
 * dependency on Pocket Casts servers. Pulls run when the playback service starts, when the app
 * comes to the foreground (and on a timer while it stays there), piggybacked on every successful
 * podcast refresh, and from a periodic background worker; pushes run as a periodic sample while
 * playing, immediately on pause and on service shutdown, and as an explicit completion push on
 * natural finish or manual mark-as-played. The background paths also run a cursor-independent
 * completions reconcile (see [reconcileCompletionsBlocking]) so an episode finished on another
 * device is marked played here even when the completion predates this device's first sync.
 *
 * Completion is an explicit fact, not a guess. A finish writes completed=true to the row; the
 * receiver runs a real local mark-as-played (removes from Up Next and auto-archives per the
 * podcast's settings), so finishing on one device removes the episode on the other.
 *
 * Protection rules, both about not destroying newer local state:
 *  - A remote change older than this device's own last change to the episode is ignored, so a
 *    stale row can never rewind your progress or undo a newer local completion.
 *  - The episode actively playing on this device right now is never overwritten.
 *
 * Reliability: the pull drains every page in one open, advances its cursor only after a page is
 * accounted for, and parks rows for episodes not yet in the local database in a small retry list
 * (so a position is never lost just because its feed had not refreshed yet). A fresh sign-in
 * starts from "now" instead of replaying the whole history.
 *
 * Install id, the last pull cursor, and the retry list live in a dedicated SharedPreferences file
 * so nothing here touches the shared app Settings.
 */
@Singleton
class PodHopperPositionSync @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val playbackManager: Lazy<PlaybackManager>,
    private val settings: Settings,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    @Volatile
    private var lastPushAttemptMs = 0L

    @Volatile
    private var lastBrowsePullMs = 0L
    private var lastReconcileMs = 0L

    // Episodes the sync is applying remotely right now. The push hooks consult this so the local
    // changes a remote apply makes (a mark-as-played in particular) are not echoed back as a push.
    private val applyingUuids: MutableSet<String> = ConcurrentHashMap.newKeySet()

    init {
        observeSignIn()
    }

    /** True while the sync is applying a remote change to this episode. Push hooks skip when true. */
    fun isApplyingRemote(uuid: String): Boolean = applyingUuids.contains(uuid)

    /**
     * Pulls the latest cross-device positions the instant sign-in completes, so resume points are
     * correct as soon as a freshly signed-in user starts playing. Watch login state, drop the
     * startup value, and react only to the transition into the signed-in state.
     */
    private fun observeSignIn() {
        supabaseClient.loginState
            .distinctUntilChanged()
            .drop(1)
            .onEach { loggedIn ->
                if (loggedIn) {
                    pullLatestPositions()
                }
            }
            .launchIn(applicationScope)
    }

    /**
     * Push a single episode position to Supabase. Throttled to one push every
     * [MIN_PUSH_INTERVAL_MS] unless [immediate] is true (pause and shutdown). The completed column
     * is deliberately omitted so a position sample can never un-complete an episode another flow
     * just marked finished (Supabase merge-duplicates leaves omitted columns untouched).
     */
    fun pushPosition(episode: BaseEpisode, positionMs: Int, durationMs: Int, immediate: Boolean) {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        if (positionMs <= 0) {
            return
        }
        val now = System.currentTimeMillis()
        // Record that this device was actively playing now. The adopt guard reads this so another
        // device's episode is only adopted when it is genuinely newer than this device's own last
        // activity. Stamped before the throttle so periodic samples keep it fresh while playing.
        stampLocalActivity(now)
        if (!immediate && now - lastPushAttemptMs < MIN_PUSH_INTERVAL_MS) {
            return
        }
        lastPushAttemptMs = now

        val episodeKey = episode.uuid
        val episodeUrl = episode.downloadUrl
        val positionSec = positionMs / 1000
        val totalSec = durationMs / 1000

        applicationScope.launch(Dispatchers.IO) {
            try {
                val userId = supabaseClient.getUserId() ?: return@launch
                val feedUrl = feedUrlForEpisode(episode)
                val row = JSONObject()
                row.put("user_id", userId)
                row.put("episode_key", episodeKey)
                row.put("episode_url", episodeUrl ?: JSONObject.NULL)
                row.put("position_sec", positionSec)
                row.put("total_sec", totalSec)
                row.put("feed_url", feedUrl ?: JSONObject.NULL)
                row.put("device_id", getOrCreateInstallId())
                row.put("device_name", Build.MODEL)
                row.put("updated_at_ms", now)
                supabaseClient.upsert(TABLE_PLAYBACK_STATE, "user_id,episode_key", JSONArray().put(row))
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper position push failed, will retry next cycle: ${e.message}")
            }
        }
    }

    /**
     * Push the current episode's live position. Used for the shutdown push, where the caller does
     * not have the position to hand. Reads the live playback state from PlaybackManager.
     */
    fun pushCurrentPosition(immediate: Boolean) {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        val manager = playbackManager.get()
        val episode = manager.getCurrentEpisode() ?: return
        val state = manager.playbackStateRelay.blockingFirst()
        if (state.episodeUuid != episode.uuid) {
            return
        }
        pushPosition(episode, state.positionMs, state.durationMs, immediate)
    }

    /**
     * Push an explicit completion. Called on natural finish and on manual mark-as-played. Skipped
     * when this completion is itself the result of a remote apply (the echo guard), so a synced
     * completion is not bounced straight back to the backend.
     */
    fun pushCompletion(episode: BaseEpisode) {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        if (isApplyingRemote(episode.uuid)) {
            return
        }
        val durationMs = episode.durationMs
        val totalSec = if (durationMs > 0) durationMs / 1000 else episode.playedUpToMs / 1000
        val episodeKey = episode.uuid
        val episodeUrl = episode.downloadUrl
        val now = System.currentTimeMillis()
        // A completion is local activity too: keep the adopt guard's clock current.
        stampLocalActivity(now)

        applicationScope.launch(Dispatchers.IO) {
            try {
                val userId = supabaseClient.getUserId() ?: return@launch
                val feedUrl = feedUrlForEpisode(episode)
                val row = JSONObject()
                row.put("user_id", userId)
                row.put("episode_key", episodeKey)
                row.put("episode_url", episodeUrl ?: JSONObject.NULL)
                row.put("position_sec", totalSec)
                row.put("total_sec", totalSec)
                row.put("completed", true)
                row.put("feed_url", feedUrl ?: JSONObject.NULL)
                row.put("device_id", getOrCreateInstallId())
                row.put("device_name", Build.MODEL)
                row.put("updated_at_ms", now)
                supabaseClient.upsert(TABLE_PLAYBACK_STATE, "user_id,episode_key", JSONArray().put(row))
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper completion push failed, will retry on next finish: ${e.message}")
            }
        }
    }

    /**
     * The RSS feed url of the episode's podcast, or null for non podcast episodes. Carried in each
     * push so another device can fetch and add the podcast (as not subscribed) on demand when it
     * adopts an episode it does not have locally yet. Blocking; call off the main thread.
     */
    private fun feedUrlForEpisode(episode: BaseEpisode): String? {
        val podcastEpisode = episode as? PodcastEpisode ?: return null
        return podcastManager.findPodcastByUuidBlocking(podcastEpisode.podcastUuid)?.podcastUrl
    }

    /**
     * Pull positions and completions newer than our cursor that were written by other devices, and
     * apply them locally. Drains every page so one open fully catches up. Safe to call from any
     * thread; the work runs on the IO dispatcher.
     */
    fun pullLatestPositions(adoptCurrentEpisode: Boolean = false) {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        applicationScope.launch(Dispatchers.IO) {
            pullLatestPositionsBlocking(adoptCurrentEpisode)
        }
    }

    /**
     * The pull body, run to completion. Callers that must wait for the sync to finish (the
     * periodic sync worker) use this directly; [pullLatestPositions] launches it fire-and-forget.
     */
    private suspend fun pullLatestPositionsBlocking(adoptCurrentEpisode: Boolean) {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        withContext(Dispatchers.IO) {
            try {
                val installId = getOrCreateInstallId()
                var cursor = prefs().getLong(PREF_LAST_PULL_MS, FIRST_SYNC_SENTINEL)
                if (cursor == FIRST_SYNC_SENTINEL) {
                    // First sync on this device: start from the database's own newest timestamp, which
                    // is server time, the one clock every device shares, rather than this device's
                    // clock. A device whose clock runs ahead would otherwise seed the cursor past rows
                    // it has never seen and skip them forever. We begin watching from now in server
                    // time; we do not replay the whole history.
                    cursor = latestServerTs(onlyThisDevice = false)
                    prefs().edit().putLong(PREF_LAST_PULL_MS, cursor).apply()
                }

                while (true) {
                    val query = "select=episode_key,position_sec,total_sec,completed,updated_at_ms,device_id,feed_url" +
                        "&updated_at_ms=gt.$cursor" +
                        "&device_id=neq.$installId" +
                        "&order=updated_at_ms.asc" +
                        "&limit=${PodHopperConfig.PULL_PAGE_LIMIT}"
                    val rows = supabaseClient.select(TABLE_PLAYBACK_STATE, query)
                    val count = rows.length()
                    if (count == 0) {
                        break
                    }
                    val result = applyRows(rows)
                    if (result.maxTs > cursor) {
                        // Advance past everything in this page. Rows we could not apply yet are not
                        // lost: they were parked for retry, so moving the cursor is safe.
                        cursor = result.maxTs
                        prefs().edit().putLong(PREF_LAST_PULL_MS, cursor).apply()
                    } else {
                        // Cursor did not advance (should not happen given the gt filter); stop
                        // rather than risk looping on the same page.
                        break
                    }
                    if (count < PodHopperConfig.PULL_PAGE_LIMIT) {
                        break
                    }
                }

                retryParkedRows()

                // Switch the player to the most recently played episode from another device. This
                // uses the same direct "freshest row across devices" query the resume path uses,
                // rather than only the rows newer than the pull cursor, so a reconcile triggered
                // after the cursor has already advanced past the other device's write still finds
                // it. Guarded so it never changes the now-playing episode while this device is
                // actively playing; the setting gate lives inside adoptLatestForResume. When an
                // episode is adopted, its synced position is applied so the player window reflects it.
                if (adoptCurrentEpisode && !playbackManager.get().isPlaying()) {
                    val adopted = adoptLatestForResume(loadIntoPlayer = true)
                    if (adopted != null) {
                        applyRemotePositionBeforePlay(adopted)
                    }
                }
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper position pull failed: ${e.message}")
            }
        }
    }

    /**
     * PodHopper background sync: the delta position pull plus the completions reconcile, run to
     * completion. This is the entry point for the periodic sync worker and the refresh piggyback.
     * Throttled to one run every [FULL_SYNC_MIN_INTERVAL_MS] unless [force] is set (the periodic
     * worker forces, since WorkManager already spaces its runs), so a burst of refresh triggers
     * cannot hammer the backend. Never adopts the now-playing episode; that belongs to the
     * foreground reconcile.
     */
    suspend fun fullSyncBlocking(force: Boolean = false) {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        val now = System.currentTimeMillis()
        val last = prefs().getLong(PREF_LAST_FULL_SYNC_MS, 0L)
        if (!force && now - last < FULL_SYNC_MIN_INTERVAL_MS) {
            return
        }
        prefs().edit().putLong(PREF_LAST_FULL_SYNC_MS, now).apply()
        pullLatestPositionsBlocking(adoptCurrentEpisode = false)
        reconcileCompletionsBlocking()
    }

    /** Fire-and-forget [fullSyncBlocking], for callers that cannot suspend (the refresh pipeline). */
    fun fullSync() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        applicationScope.launch(Dispatchers.IO) {
            fullSyncBlocking()
        }
    }

    /**
     * Reconciles completed episodes independently of the delta pull's cursor. The delta cursor is
     * deliberately seeded to "now" on a device's first sync and only ever moves forward, so a
     * completion written before that first sync, or inside any window a pull missed, is invisible
     * to it forever; the episode then sits unplayed in this device's playlists even though it was
     * finished elsewhere. This pass walks every completed row for the account with its own
     * cursor, starting from the beginning of history, and runs a real local mark-as-played for any
     * episode that is not already completed here (Up Next removal and this device's auto-archive
     * settings included, via [applyOne]). It is idempotent: rows already applied are skipped by
     * the local completed check, and a completed episode is never regressed. Positions are
     * deliberately NOT replayed from history, because a historical position row can be older than
     * this device's local progress and would rewind it; live positions belong to the delta pull.
     */
    private suspend fun reconcileCompletionsBlocking() {
        withContext(Dispatchers.IO) {
            try {
                val installId = getOrCreateInstallId()
                var cursor = prefs().getLong(PREF_COMPLETIONS_CURSOR, 0L)
                while (true) {
                    val query = "select=episode_key,position_sec,total_sec,updated_at_ms" +
                        "&completed=is.true" +
                        "&device_id=neq.$installId" +
                        "&updated_at_ms=gt.$cursor" +
                        "&order=updated_at_ms.asc" +
                        "&limit=${PodHopperConfig.PULL_PAGE_LIMIT}"
                    val rows = supabaseClient.select(TABLE_PLAYBACK_STATE, query)
                    val count = rows.length()
                    if (count == 0) {
                        break
                    }
                    var maxTs = cursor
                    for (i in 0 until count) {
                        val row = rows.getJSONObject(i)
                        val updatedAtMs = row.optLong("updated_at_ms", 0L)
                        if (updatedAtMs > maxTs) {
                            maxTs = updatedAtMs
                        }
                        val episodeKey = row.optString("episode_key")
                        if (episodeKey.isEmpty()) {
                            continue
                        }
                        val episode = episodeManager.findByUuid(episodeKey)
                        if (episode == null) {
                            // Same guarantee as the delta pull: an episode whose feed has not
                            // refreshed here yet is parked, not dropped, and applied once it exists.
                            parkRow(episodeKey, row.optInt("position_sec", -1), row.optInt("total_sec", 0), completed = true, remoteTs = updatedAtMs)
                        } else if (episode.playingStatus != EpisodePlayingStatus.COMPLETED) {
                            applyOne(episode, row.optInt("position_sec", -1), row.optInt("total_sec", 0), completed = true)
                        }
                    }
                    if (maxTs > cursor) {
                        cursor = maxTs
                        prefs().edit().putLong(PREF_COMPLETIONS_CURSOR, cursor).apply()
                    } else {
                        break
                    }
                    if (count < PodHopperConfig.PULL_PAGE_LIMIT) {
                        break
                    }
                }
                retryParkedRows()
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper completions reconcile failed: ${e.message}")
            }
        }
    }

    /**
     * Refreshes cross-device positions when the car opens a browse page, mirroring the phone's
     * per-page pull so episode lists reflect progress from other devices. Throttled to at most one
     * pull every [BROWSE_PULL_MIN_INTERVAL_MS], because the car requests several browse nodes in a
     * burst when the app opens. Never changes the now-playing episode: the user is browsing to pick
     * something, which is exactly the wrong moment to switch the player out from under them.
     * Adoption belongs to the reconcile and resume paths.
     */
    fun pullForBrowse() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastBrowsePullMs < BROWSE_PULL_MIN_INTERVAL_MS) {
            return
        }
        lastBrowsePullMs = now
        pullLatestPositions(adoptCurrentEpisode = false)
    }

    /**
     * PodHopper: reconcile this device's now-playing window with the freshest cross-device state.
     * Refreshes saved positions and, when this device is not actively playing, switches the current
     * episode to the most recently played one from another device and applies its synced position,
     * so the artwork and scrubber update without forcing the player open or interrupting playback.
     * Throttled by [RECONCILE_MIN_INTERVAL_MS] so bursts of triggers (a session connect immediately
     * followed by a browse request, for instance) collapse into a single pull. Called by every
     * "the user is engaging with us now" trigger: app foreground and the in-app timer on phone, and
     * session connect and the connected timer on the car and Android Auto.
     */
    fun reconcileNowPlaying() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        val now = System.currentTimeMillis()
        if (now - lastReconcileMs < RECONCILE_MIN_INTERVAL_MS) {
            return
        }
        lastReconcileMs = now
        pullLatestPositions(adoptCurrentEpisode = true)
    }

    private data class AdoptCandidate(val episodeKey: String, val feedUrl: String?, val updatedAtMs: Long)

    private data class ApplyResult(val maxTs: Long)

    /**
     * Switches the player to [candidate] if the auto-switch setting is on. If the episode is not on
     * this device yet, its podcast is fetched and added (as not subscribed) from the feed url the
     * row carried, which makes the episode exist locally so it can be loaded. The actual player
     * switch, with its own do-not-interrupt guards, lives in PlaybackManager.
     */
    private suspend fun maybeAdoptLatest(candidate: AdoptCandidate, loadIntoPlayer: Boolean = true): BaseEpisode? {
        if (!settings.autoSwitchPlayerToCurrentPodcast.value) {
            return null
        }
        // Local-activity guard, same clock on both sides: if this device had playback activity
        // within the last few minutes, do not switch what is playing, even when the server check
        // below would pass because this device's pushes failed to land. This covers the window
        // where an episode is loading, buffering, or errored (so not yet "playing") right after
        // real local use; without it a reconcile tick can yank the player mid-load. Device time is
        // compared to device time, so clock skew between devices cannot break it. A negative
        // elapsed value (the device clock moved backward) is treated as recent, which errs on the
        // side of not switching.
        val lastLocalActivityMs = prefs().getLong(PREF_LAST_LOCAL_ACTIVITY_MS, 0L)
        val sinceLocalActivityMs = System.currentTimeMillis() - lastLocalActivityMs
        if (lastLocalActivityMs > 0L && sinceLocalActivityMs < LOCAL_ACTIVITY_ADOPT_GRACE_MS) {
            Log.i(LOG_TAG, "adopt skipped: local playback activity ${sinceLocalActivityMs}ms ago is within grace")
            return null
        }
        // Core guard, in server time: only switch to another device's episode when that row is newer
        // than this device's own most recent write. Both timestamps are stamped by the database, so
        // this compares one clock to itself, never two device clocks. Without it a device gets yanked
        // back to another device's older episode, because the pull excludes this device's own writes
        // and so the page alone cannot tell that this device is the fresher one.
        val myLatest = latestServerTs(onlyThisDevice = true)
        if (candidate.updatedAtMs <= myLatest) {
            Log.i(LOG_TAG, "adopt skipped: candidate ${candidate.episodeKey} ts=${candidate.updatedAtMs} not newer than my latest write ts=$myLatest")
            return null
        }
        var episode = episodeManager.findByUuid(candidate.episodeKey)
        if (episode == null && !candidate.feedUrl.isNullOrBlank()) {
            podcastManager.addFeedUrlAsUnsubscribed(candidate.feedUrl)
            episode = episodeManager.findByUuid(candidate.episodeKey)
        }
        val target = episode ?: run {
            Log.i(LOG_TAG, "adopt skipped: episode ${candidate.episodeKey} not resolvable locally")
            return null
        }
        Log.i(LOG_TAG, "adopting episode ${target.uuid} ts=${candidate.updatedAtMs} (my latest write ts=$myLatest), loadIntoPlayer=$loadIntoPlayer")
        playbackManager.get().adoptCurrentEpisodeFromSync(target, loadIntoPlayer = loadIntoPlayer)
        return target
    }

    /**
     * Synchronously find the most recently played in-progress episode written by another device and,
     * when the auto-switch setting is on, make it the current player episode, returning it. The car's
     * one-shot auto-resume needs this inline, before it reads the current episode: the background
     * pull that adopts on the phone loses the race to the car's resume and is then blocked by the
     * "already playing" guard. Player loading is deliberately left to the resume itself
     * (loadIntoPlayer = false), so this only sets the current episode and never runs a second,
     * competing load. Returns null when the setting is off, nothing newer exists, or we are offline,
     * in which case the caller resumes this device's own current episode. Time-bounded.
     */
    suspend fun adoptLatestForResume(loadIntoPlayer: Boolean = false): BaseEpisode? {
        if (!supabaseClient.isLoggedIn()) {
            return null
        }
        if (!settings.autoSwitchPlayerToCurrentPodcast.value) {
            return null
        }
        return try {
            withTimeoutOrNull(PLAY_PULL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val installId = getOrCreateInstallId()
                    val query = "select=episode_key,feed_url,position_sec,total_sec,completed,updated_at_ms" +
                        "&device_id=neq.$installId" +
                        "&order=updated_at_ms.desc" +
                        "&limit=$ADOPT_SCAN_LIMIT"
                    val rows = supabaseClient.select(TABLE_PLAYBACK_STATE, query)
                    val candidate = latestInProgressFrom(rows)
                    if (candidate == null) {
                        Log.i(LOG_TAG, "adopt-before-resume: no in-progress episode from another device")
                        return@withContext null
                    }
                    maybeAdoptLatest(candidate, loadIntoPlayer = loadIntoPlayer)
                }
            }
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper adopt-before-resume failed: ${e.message}")
            null
        }
    }

    /**
     * Picks the most recently played episode from a desc-ordered page of rows that is still in
     * progress (not completed and not at or past its end). Mirrors the in-progress rule used when
     * applying a full page, so completions never become a now-playing.
     */
    private fun latestInProgressFrom(rows: JSONArray): AdoptCandidate? {
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val episodeKey = row.optString("episode_key")
            if (episodeKey.isEmpty()) {
                continue
            }
            val positionSec = row.optInt("position_sec", -1)
            val totalSec = row.optInt("total_sec", 0)
            val completed = row.optBoolean("completed", false)
            val isCompletion = completed || (totalSec > 0 && positionSec >= totalSec)
            if (isCompletion) {
                continue
            }
            val feedUrl = row.optString("feed_url").takeIf { it.isNotEmpty() }
            return AdoptCandidate(episodeKey, feedUrl, row.optLong("updated_at_ms", 0L))
        }
        return null
    }

    /**
     * Pulls this episode's latest cross-device position and applies it BEFORE playback reads the
     * resume point, so a play (fresh or resume) starts from the synced position. Time-bounded by
     * [PLAY_PULL_TIMEOUT_MS] so a slow or offline network cannot hang playback. The device_id
     * filter means a row only comes back when another device wrote it most recently, so this never
     * fights this device's own latest position.
     */
    suspend fun applyRemotePositionBeforePlay(episode: BaseEpisode): PlayPullResult {
        if (!supabaseClient.isLoggedIn()) {
            return PlayPullResult.NONE
        }
        return try {
            val outcome = withTimeoutOrNull(PLAY_PULL_TIMEOUT_MS) {
                withContext(Dispatchers.IO) {
                    val installId = getOrCreateInstallId()
                    // Newest other-device row for this episode. Ordered so "limit 1" is deterministic.
                    val query = "select=position_sec,total_sec,updated_at_ms,device_id" +
                        "&episode_key=eq.${episode.uuid}" +
                        "&device_id=neq.$installId" +
                        "&order=updated_at_ms.desc" +
                        "&limit=1"
                    val rows = supabaseClient.select(TABLE_PLAYBACK_STATE, query)
                    if (rows.length() == 0) {
                        Log.i(LOG_TAG, "play-pull ${episode.uuid}: no other-device row, keeping local")
                        PlayPullResult.NONE
                    } else {
                        val row = rows.getJSONObject(0)
                        val positionSec = row.optInt("position_sec", -1)
                        val remoteTs = row.optLong("updated_at_ms", 0L)
                        if (positionSec < 0) {
                            PlayPullResult.NONE
                        } else {
                            // Freshest writer wins. There is one playback_state row per episode,
                            // overwritten by whoever played it last, and its updated_at_ms is stamped by
                            // the database, not by any device's clock. A row that belongs to another
                            // device is therefore the freshest write for this episode, so adopt it, even
                            // if it moves the position backward (a deliberate rewind on another device
                            // should follow you here). When the latest write is ours, the device_id filter
                            // returns no row and we keep our own local position. No clock comparison: the
                            // old localModified vs remoteTs guard compared a local time that merely pressing
                            // play refreshed against another device's time, so it wrongly kept local almost
                            // every time.
                            Log.i(LOG_TAG, "play-pull ${episode.uuid}: applying freshest remote pos=${positionSec}s remoteTs=$remoteTs")
                            episodeManager.updatePlayedUpToBlocking(episode, positionSec.toDouble(), forceUpdate = true)
                            PlayPullResult.APPLIED
                        }
                    }
                }
            }
            if (outcome == null) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper play-pull timed out, playing from local position")
                PlayPullResult.FAILED
            } else {
                outcome
            }
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper play-pull failed: ${e.message}")
            PlayPullResult.FAILED
        }
    }

    enum class PlayPullResult {
        APPLIED,
        NONE,
        FAILED,
    }

    /** Applies every row in a page, parking those whose episode is not local yet. Returns the
     *  highest updated_at_ms seen, so the caller can advance the cursor past the whole page. */
    private suspend fun applyRows(rows: JSONArray): ApplyResult {
        var maxTs = 0L
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val updatedAtMs = row.optLong("updated_at_ms", 0L)
            if (updatedAtMs > maxTs) {
                maxTs = updatedAtMs
            }
            val episodeKey = row.optString("episode_key")
            if (episodeKey.isEmpty()) {
                continue
            }
            val positionSec = row.optInt("position_sec", -1)
            val totalSec = row.optInt("total_sec", 0)
            val completed = row.optBoolean("completed", false)
            applyOrPark(episodeKey, positionSec, totalSec, completed, updatedAtMs)
        }
        return ApplyResult(maxTs)
    }

    private suspend fun applyOrPark(episodeKey: String, positionSec: Int, totalSec: Int, completed: Boolean, remoteTs: Long) {
        val episode = episodeManager.findByUuid(episodeKey)
        if (episode == null) {
            // Not in the local database yet (feed not refreshed). Park it; retried on the next pull
            // or once a refresh brings the episode in, so the position is never silently dropped.
            parkRow(episodeKey, positionSec, totalSec, completed, remoteTs)
            return
        }
        applyOne(episode, positionSec, totalSec, completed)
    }

    private fun applyOne(episode: BaseEpisode, positionSec: Int, totalSec: Int, completed: Boolean) {
        // Freshest writer wins: this row is another device's write to the episode's single shared row,
        // so it is the latest state by the database's own clock. The one thing we will not stomp is the
        // episode this device is actively playing right now; its position reconciles on the next play.
        val manager = playbackManager.get()
        if (episode.uuid == manager.getCurrentEpisode()?.uuid && manager.isPlaying()) {
            return
        }

        val isCompletion = completed || (totalSec > 0 && positionSec >= totalSec)
        if (isCompletion) {
            // A real local completion: removes from Up Next and auto-archives per the podcast's
            // settings, so finishing elsewhere removes it here. Guarded so this apply is not echoed
            // back to the backend as a fresh completion push.
            applyingUuids.add(episode.uuid)
            try {
                episodeManager.markAsPlayedBlocking(episode, manager, podcastManager)
            } finally {
                applyingUuids.remove(episode.uuid)
            }
        } else if (positionSec >= 0) {
            episodeManager.updatePlayedUpToBlocking(episode, positionSec.toDouble(), forceUpdate = true)
            if (episode.playingStatus == EpisodePlayingStatus.NOT_PLAYED) {
                // Keep status and position consistent: a synced mid-episode position is in progress,
                // not "not played".
                episodeManager.updatePlayingStatusBlocking(episode, EpisodePlayingStatus.IN_PROGRESS)
            }
        }
    }

    private fun parkRow(episodeKey: String, positionSec: Int, totalSec: Int, completed: Boolean, remoteTs: Long) {
        try {
            val parked = readParked()
            val entry = JSONObject()
            entry.put("p", positionSec)
            entry.put("t", totalSec)
            entry.put("c", completed)
            entry.put("u", remoteTs)
            parked.put(episodeKey, entry)

            // Bound the list: if it grows past the cap, drop the oldest entries by remote timestamp.
            if (parked.length() > MAX_PARKED) {
                val keys = parked.keys().asSequence().toList()
                val oldestFirst = keys.sortedBy { parked.getJSONObject(it).optLong("u", 0L) }
                val toDrop = parked.length() - MAX_PARKED
                oldestFirst.take(toDrop).forEach { parked.remove(it) }
            }
            writeParked(parked)
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper park failed: ${e.message}")
        }
    }

    private suspend fun retryParkedRows() {
        val parked = try {
            readParked()
        } catch (e: Exception) {
            return
        }
        if (parked.length() == 0) {
            return
        }
        var changed = false
        val keys = parked.keys().asSequence().toList()
        for (episodeKey in keys) {
            val episode = episodeManager.findByUuid(episodeKey) ?: continue
            val entry = parked.getJSONObject(episodeKey)
            applyOne(
                episode = episode,
                positionSec = entry.optInt("p", -1),
                totalSec = entry.optInt("t", 0),
                completed = entry.optBoolean("c", false),
            )
            parked.remove(episodeKey)
            changed = true
        }
        if (changed) {
            writeParked(parked)
        }
    }

    private fun readParked(): JSONObject {
        val raw = prefs().getString(PREF_PARKED, null) ?: return JSONObject()
        return try {
            JSONObject(raw)
        } catch (e: Exception) {
            JSONObject()
        }
    }

    private fun writeParked(parked: JSONObject) {
        prefs().edit().putString(PREF_PARKED, parked.toString()).apply()
    }

    private fun getOrCreateInstallId(): String {
        val prefs = prefs()
        val existing = prefs.getString(PREF_INSTALL_ID, null)
        if (existing != null) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_INSTALL_ID, generated).apply()
        return generated
    }

    private fun prefs(): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /** Records the time of this device's most recent local playback activity (monotonic; never moves back). */
    private fun stampLocalActivity(ms: Long) {
        val current = prefs().getLong(PREF_LAST_LOCAL_ACTIVITY_MS, 0L)
        if (ms > current) {
            prefs().edit().putLong(PREF_LAST_LOCAL_ACTIVITY_MS, ms).apply()
        }
    }

    /**
     * The newest updated_at_ms the database holds for this user, optionally limited to this device's
     * own writes. These timestamps are stamped by Supabase, so they are the one clock every device
     * shares. Returns 0 when there is no matching row. Blocking; call from the IO dispatcher.
     */
    private fun latestServerTs(onlyThisDevice: Boolean): Long {
        val deviceClause = if (onlyThisDevice) "&device_id=eq.${getOrCreateInstallId()}" else ""
        val query = "select=updated_at_ms$deviceClause&order=updated_at_ms.desc&limit=1"
        val rows = supabaseClient.select(TABLE_PLAYBACK_STATE, query)
        if (rows.length() == 0) {
            return 0L
        }
        return rows.getJSONObject(0).optLong("updated_at_ms", 0L)
    }

    /**
     * Resets this device's position sync bookkeeping so a future account starts clean. Clears the
     * pull cursor, any parked rows, and the local-activity clock, which sends the cursor back to the
     * first-sync sentinel. The install id is kept, since it identifies the device, not the account.
     * Does not touch any episode, podcast, or playback data on the device.
     */
    fun clearLocalSyncState() {
        prefs().edit()
            .remove(PREF_LAST_PULL_MS)
            .remove(PREF_PARKED)
            .remove(PREF_LAST_LOCAL_ACTIVITY_MS)
            .remove(PREF_COMPLETIONS_CURSOR)
            .remove(PREF_LAST_FULL_SYNC_MS)
            .apply()
    }

    companion object {
        private const val LOG_TAG = "PodHopperSync"
        private const val TABLE_PLAYBACK_STATE = "playback_state"
        private const val PREF_NAME = "podhopper_position_sync"
        private const val PREF_INSTALL_ID = "install_id"
        private const val PREF_LAST_PULL_MS = "last_pull_ms"
        private const val PREF_PARKED = "parked_rows"
        private const val PREF_LAST_LOCAL_ACTIVITY_MS = "last_local_activity_ms"
        private const val PREF_COMPLETIONS_CURSOR = "completions_cursor"
        private const val PREF_LAST_FULL_SYNC_MS = "last_full_sync_ms"
        private const val FULL_SYNC_MIN_INTERVAL_MS = 15 * 60 * 1000L
        private const val MIN_PUSH_INTERVAL_MS = 4000L
        private const val PLAY_PULL_TIMEOUT_MS = 5000L
        private const val BROWSE_PULL_MIN_INTERVAL_MS = 10000L
        private const val RECONCILE_MIN_INTERVAL_MS = 5000L
        private const val LOCAL_ACTIVITY_ADOPT_GRACE_MS = 5 * 60 * 1000L
        private const val ADOPT_SCAN_LIMIT = 10
        private const val FIRST_SYNC_SENTINEL = -1L
        private const val MAX_PARKED = 500
    }
}
