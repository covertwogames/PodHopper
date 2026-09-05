package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.AppDatabase
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.UpNextChange
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * PodHopper: cross-device Up Next queue, synced as ACTIONS rather than as a list.
 *
 * Position sync answers "what is playing and where". This answers "what plays after it". The
 * queue is an ordered list the user arranges by hand, and the first version of this class synced
 * it by having each device publish its whole list, with device clocks deciding whose list won.
 * That produced a run of bugs with one root: every device was allowed to assert what the queue
 * should be, including devices that had not looked at it in weeks and devices whose queue was
 * empty only because they were not in use. A phone in a drawer erased a queue the car had just
 * built, and nothing in the design could tell that apart from the user clearing it.
 *
 * This version does what the upstream app does. Each device records what the user DID: played
 * this now, queued this next or last, removed this, cleared everything. On sync it sends those
 * actions to the backend, which applies them, in the order it received them, to the one true
 * queue, and returns the result with a version number. Every device then applies that result.
 *
 * The properties that fall out of this, none of which needed a special case:
 *  - A device that did nothing sends nothing. It cannot publish an empty queue, because empty is
 *    not an action; only clearing is, and clearing is recorded as such.
 *  - A device acting on a stale queue has its action applied ON TOP of the current queue rather
 *    than replacing it. Tapping play in a car that sat for a week puts that episode at the head of
 *    the queue the phone arranged, instead of wiping the arrangement.
 *  - Clocks are irrelevant. Ordering across devices is the backend's arrival order, and the
 *    backend is the single source of truth, so a device with a wrong clock cannot win or lose.
 *  - Entries this device cannot resolve are simply kept by the backend. The device shows what it
 *    can and never publishes a shortened list, because it never publishes a list at all.
 *
 * The action log itself is the up_next_changes table the upstream app already maintained, written
 * from the single point every queue mutation passes through. It is compact by construction: a new
 * action for an episode replaces any earlier action for that episode, and a clear discards
 * everything before it.
 */
@Singleton
class PodHopperUpNextSync @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val episodeManager: EpisodeManager,
    private val podcastManager: PodcastManager,
    private val playbackManager: Lazy<PlaybackManager>,
    private val appDatabase: AppDatabase,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private val upNextChangeDao by lazy { appDatabase.upNextChangeDao() }
    private val syncInFlight = AtomicBoolean(false)

    private fun prefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isSignedIn(): Boolean = supabaseClient.isLoggedIn()

    /** Wire form of one queue entry, carrying enough for a device that has not seen the episode. */
    /**
     * The podcast's feed url, which is what lets a device that has never seen this podcast fetch it
     * and resolve the episode. Position sync has carried this from the start; the queue did not,
     * so a queued episode from an unsubscribed podcast could never appear on another device.
     */
    private fun feedUrlFor(episode: BaseEpisode): String? {
        val podcastEpisode = episode as? PodcastEpisode ?: return null
        return podcastManager.findPodcastByUuidBlocking(podcastEpisode.podcastUuid)?.podcastUrl
    }

    private fun BaseEpisode.toEntryJson(): JSONObject {
        val podcastUuid = (this as? PodcastEpisode)?.podcastUuid
        return JSONObject().apply {
            put("u", uuid)
            put("t", title.ifEmpty { JSONObject.NULL })
            put("p", podcastUuid ?: JSONObject.NULL)
            put("f", feedUrlFor(this@toEntryJson) ?: JSONObject.NULL)
            put("m", downloadUrl ?: JSONObject.NULL)
            put("d", publishedDate.time)
        }
    }

    /**
     * Send this device's pending actions and apply the backend's resulting queue. Safe to call
     * from anywhere and as often as wanted: with nothing pending and nothing new on the backend it
     * costs one small request. Overlapping calls collapse into one, since the second would only
     * repeat the first.
     */
    suspend fun syncBlocking() {
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next sync entered")
        if (!supabaseClient.isLoggedIn()) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next sync stopped: not signed in")
            return
        }
        if (!syncInFlight.compareAndSet(false, true)) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next sync already running, skipping")
            return
        }
        try {
            syncOnce()
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next sync failed, pending actions are kept for the next attempt: ${e.message}")
        } finally {
            syncInFlight.set(false)
        }
    }

    /**
     * For the queue's own change trigger, so an edit reaches the other devices within seconds
     * rather than on the next sync cycle. Identical to [syncBlocking]; the name is kept for the
     * call site.
     */
    fun pushIfChanged() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        applicationScope.launch(Dispatchers.IO) {
            syncBlocking()
        }
    }

    private suspend fun syncOnce() {
        // Snapshot the pending actions and remember the highest row id, so anything the user does
        // while the request is in flight is logged after that id and survives the cleanup below.
        val pending = withContext(Dispatchers.IO) {
            upNextChangeDao.findAllBlocking().sortedWith(compareBy({ it.modified }, { it.id ?: 0L }))
        }
        val maxSentId = pending.mapNotNull { it.id }.maxOrNull()
        val actions = buildActions(pending)

        val response = withContext(Dispatchers.IO) {
            val params = JSONObject()
                .put("p_actions", actions)
                .put("p_device_id", getOrCreateInstallId())
                .put("p_device_name", Build.MODEL)
            JSONObject(supabaseClient.rpc(RPC_APPLY_ACTIONS, params))
        }

        // The backend accepted them: they are part of the canonical queue now and must not be
        // sent again. Delete by id so an action logged during the request is not swept up.
        if (maxSentId != null) {
            withContext(Dispatchers.IO) { upNextChangeDao.deleteUpToIdBlocking(maxSentId) }
        }
        if (actions.length() > 0) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next sent ${actions.length()} action(s)")
        }

        var version = response.optLong("version", -1L)
        var episodesJson = response.optJSONArray("episodes") ?: JSONArray()
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next backend queue is version $version with ${episodesJson.length()} entry(s)")

        // Seeding. Actions are only recorded while signed in, so a queue built before the user
        // signed in (or signed up) has no actions behind it. On the very first sync, if the account
        // has never had a queue and this device has one, that queue becomes the account's. If the
        // account already has a queue, it wins and the local one is replaced, which is the right
        // answer for a device joining an existing account. This is the one place a whole list is
        // ever sent, and it is guarded so it can only ever fill an empty account, never overwrite.
        val firstSync = !prefs().contains(PREF_APPLIED_VERSION)
        if (firstSync && version == 0L && episodesJson.length() == 0) {
            val local = playbackManager.get().upNextQueue.allEpisodes
            if (local.isNotEmpty()) {
                val entries = JSONArray()
                local.forEach { entries.put(it.toEntryJson()) }
                val seed = JSONArray().put(JSONObject().put("type", "replace").put("entries", entries))
                val seeded = withContext(Dispatchers.IO) {
                    val params = JSONObject()
                        .put("p_actions", seed)
                        .put("p_device_id", getOrCreateInstallId())
                        .put("p_device_name", Build.MODEL)
                    JSONObject(supabaseClient.rpc(RPC_APPLY_ACTIONS, params))
                }
                version = seeded.optLong("version", version)
                episodesJson = seeded.optJSONArray("episodes") ?: episodesJson
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next seeded the account's queue from this device: ${local.size} episode(s)")
            }
        }

        // Nothing new since this device last applied the backend's queue, and nothing was sent
        // that would have changed it. Applying again would only rewrite the database and re-emit
        // a queue change for no reason.
        if (actions.length() == 0 && version >= 0L && version == prefs().getLong(PREF_APPLIED_VERSION, -1L)) {
            // The backend has not moved. Re-apply only if an entry this device could not build
            // before has since arrived locally, which is what makes a device recover once a feed
            // refresh brings the podcast in. The check is local lookups only: no network, and no
            // database write when nothing has changed, so this cannot feed itself.
            val previouslyUnresolved = prefs().getString(PREF_UNRESOLVED, null)
                ?.split(",")
                ?.filter { it.isNotEmpty() }
                .orEmpty()
            val recovered = previouslyUnresolved.any { episodeManager.findEpisodeByUuid(it) != null }
            if (!recovered) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next pull: already at version $version" + if (previouslyUnresolved.isEmpty()) "" else ", ${previouslyUnresolved.size} entry(s) still missing locally")
                return
            }
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next: a previously missing episode has arrived, re-applying version $version")
        }

        // The version records what the backend was; the unresolved list records what this device
        // could not build from it. Keeping them separate is what lets a device retry the missing
        // entries without re-applying a queue that has not changed. Simply not banking the version
        // would loop: applying emits a queue change, which triggers a push, which syncs, which
        // applies again, every five seconds forever on a device holding an entry that can never
        // resolve.
        val stillUnresolved = applyCanonical(episodesJson)
        prefs().edit()
            .putLong(PREF_APPLIED_VERSION, version)
            .putString(PREF_UNRESOLVED, stillUnresolved.joinToString(",").takeIf { it.isNotEmpty() })
            .apply()
    }

    /** Turns the change log into the backend's action format, resolving episodes for adds. */
    private suspend fun buildActions(pending: List<UpNextChange>): JSONArray {
        val actions = JSONArray()
        for (change in pending) {
            when (change.type) {
                UpNextChange.ACTION_PLAY_NOW, UpNextChange.ACTION_PLAY_NEXT, UpNextChange.ACTION_PLAY_LAST -> {
                    val uuid = change.uuid ?: continue
                    // If the episode has since vanished locally there is nothing to describe to the
                    // backend; the action is dropped rather than sent half-formed.
                    val episode = episodeManager.findEpisodeByUuid(uuid) ?: continue
                    val type = when (change.type) {
                        UpNextChange.ACTION_PLAY_NOW -> "play_now"
                        UpNextChange.ACTION_PLAY_NEXT -> "play_next"
                        else -> "play_last"
                    }
                    actions.put(JSONObject().put("type", type).put("entry", episode.toEntryJson()))
                }
                UpNextChange.ACTION_REMOVE -> {
                    val uuid = change.uuid ?: continue
                    actions.put(JSONObject().put("type", "remove").put("uuid", uuid))
                }
                UpNextChange.ACTION_REPLACE -> {
                    val uuids = change.uuids.orEmpty().split(",").map { it.trim() }.filter { it.isNotEmpty() }
                    val entries = JSONArray()
                    for (uuid in uuids) {
                        val episode = episodeManager.findEpisodeByUuid(uuid) ?: continue
                        entries.put(episode.toEntryJson())
                    }
                    actions.put(JSONObject().put("type", "replace").put("entries", entries))
                }
            }
        }
        return actions
    }

    /**
     * Applies the backend's queue locally through the upstream import path, which already refuses
     * to displace an episode that is playing right now, keeping it at the head and applying the
     * rest behind it. Entries this device cannot resolve are skipped here and kept by the backend;
     * they appear once a feed refresh brings the episode in.
     */
    /** Applies the backend's queue and returns the uuids this device could not build. */
    private suspend fun applyCanonical(episodesJson: JSONArray): List<String> {
        val resolved = mutableListOf<BaseEpisode>()
        val unresolved = mutableListOf<String>()
        for (i in 0 until episodesJson.length()) {
            val entry = episodesJson.optJSONObject(i) ?: continue
            val uuid = entry.optString("u").orEmpty()
            if (uuid.isEmpty()) continue
            var episode = episodeManager.findEpisodeByUuid(uuid)
            if (episode == null) {
                // Not known here, so fetch the podcast it belongs to and look again. Without this a
                // queued episode from a podcast this device has never seen could never be shown,
                // however many times it synced. Position sync has always done exactly this.
                val feedUrl = entry.optString("f").takeIf { it.isNotEmpty() }
                if (feedUrl != null) {
                    try {
                        podcastManager.addFeedUrlAsUnsubscribed(feedUrl)
                        episode = episodeManager.findEpisodeByUuid(uuid)
                    } catch (e: Exception) {
                        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next could not fetch $feedUrl to resolve $uuid: ${e.message}")
                    }
                }
            }
            if (episode != null) resolved.add(episode) else unresolved.add(uuid)
        }
        val manager = playbackManager.get()
        withContext(Dispatchers.IO) {
            manager.upNextQueue.importServerChangesBlocking(resolved, manager)
        }
        val note = if (unresolved.isNotEmpty()) ", ${unresolved.size} could not be built and will be retried when their podcasts arrive" else ""
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next applied ${resolved.size} episode(s)$note")
        return unresolved
    }

    private fun getOrCreateInstallId(): String {
        val existing = prefs().getString(PREF_INSTALL_ID, null)
        if (existing != null) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs().edit().putString(PREF_INSTALL_ID, generated).apply()
        return generated
    }

    /**
     * Called on sign out. The next account must not inherit this one's pending actions or think it
     * has already applied a queue it has never seen.
     */
    fun clearLocalState() {
        prefs().edit().remove(PREF_APPLIED_VERSION).remove(PREF_UNRESOLVED).apply()
        applicationScope.launch(Dispatchers.IO) {
            try {
                upNextChangeDao.deleteAllBlocking()
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next could not clear pending actions on sign out: ${e.message}")
            }
        }
    }

    companion object {
        private const val PREFS_NAME = "podhopper_upnext_sync"
        private const val PREF_APPLIED_VERSION = "applied_version"
        private const val PREF_UNRESOLVED = "unresolved_entries"
        private const val PREF_INSTALL_ID = "install_id"
        private const val RPC_APPLY_ACTIONS = "apply_up_next_actions"
    }
}
