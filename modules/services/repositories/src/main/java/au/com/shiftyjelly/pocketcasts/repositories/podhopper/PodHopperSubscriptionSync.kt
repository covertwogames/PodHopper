package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.annotation.SuppressLint
import android.content.Context
import android.content.SharedPreferences
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.entity.Podcast
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FeedParser
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.SubscribeManager
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Syncs podcast subscriptions across devices through Supabase, so a podcast added on one device
 * shows up on the others.
 *
 * This is a direct port of AntennaPod's syncSubscriptions, which is the version that worked. Local
 * subscribe and unsubscribe actions are written into a persisted queue (sync_added / sync_removed).
 * One sync pass then runs: download remote changes newer than a saved timestamp cursor, apply them
 * locally, upload whatever is in the local queue (minus anything that just arrived), clear the queue
 * and advance the cursor. The cursor is what prevents echoes: a device only ever downloads rows
 * newer than the last row it saw, so its own writes come back at most once, no-op, and the cursor
 * moves past them. There is no reliance on a volatile flag for correctness.
 *
 * Subscriptions are keyed by feed url, which both devices agree on. Because an unsubscribe deletes
 * the local podcast row before we can read its feed url, each subscribe also remembers the feed url
 * keyed by the podcast uuid, so a later unsubscribe can still be pushed.
 */
@Singleton
class PodHopperSubscriptionSync @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val feedParser: FeedParser,
    private val subscribeManager: SubscribeManager,
    private val podcastManager: Lazy<PodcastManager>,
    private val settings: Settings,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    // True only while a pull is applying remote changes. This is a cheap first guard against
    // re-enqueuing a change we are in the middle of applying; the real echo prevention is the
    // cursor plus removing downloaded changes from the upload set below.
    @Volatile
    private var applyingRemote = false

    // Throttle clock shared by every pull path. Frequent triggers (navigation, the foreground
    // timer) skip if a pull already ran within the throttle window.
    @Volatile
    private var lastPollMs = 0L

    // The foreground poll loop, started in onStart and stopped in onStop.
    @Volatile
    private var periodicJob: Job? = null

    // Guards against two sync passes running at once. A pull requested while one is already in
    // flight is skipped; its work is covered by the running pass or the next trigger, and any
    // queued local change persists until then. This matters most right after sign-in, when the
    // login pull and MainActivity.onStart can both fire while the full library is downloading.
    private val syncInFlight = AtomicBoolean(false)

    init {
        observeSubscriptionChanges()
        observeSignIn()
    }

    /**
     * Every local subscribe and unsubscribe path converges on this relay, so listening here records
     * the change into the queue without having to hook each screen. It enqueues only; the sync pass
     * does the network work.
     */
    @SuppressLint("CheckResult")
    private fun observeSubscriptionChanges() {
        subscribeManager.subscriptionChangedRelay.subscribe { uuid ->
            // Checked synchronously on the emitting thread so a change applied by our own pull is
            // not queued back up.
            if (applyingRemote) {
                return@subscribe
            }
            applicationScope.launch(Dispatchers.IO) {
                enqueueLocalChange(uuid)
                pullSubscriptions()
            }
        }
    }

    /**
     * Pulls subscriptions the instant sign-in completes, so a freshly signed-in user lands on a
     * populated Podcasts screen instead of waiting for the next navigation or the periodic loop.
     * Watching login state here (rather than hooking each sign-in screen) covers every entry path:
     * email login, account creation, and any future provider. The startup value is dropped, so only
     * a real transition into the signed-in state triggers a pull; sign-out does not.
     */
    private fun observeSignIn() {
        supabaseClient.loginState
            .distinctUntilChanged()
            .drop(1)
            .onEach { loggedIn ->
                if (loggedIn) {
                    pullSubscriptions()
                }
            }
            .launchIn(applicationScope)
    }

    private suspend fun enqueueLocalChange(uuid: String) {
        val podcast = podcastManager.get().findPodcastByUuid(uuid)
        if (podcast != null && podcast.isSubscribed) {
            val feedUrl = podcast.podcastUrl
            if (!feedUrl.isNullOrBlank()) {
                rememberFeedUrl(uuid, feedUrl)
                queueAdd(feedUrl)
            }
        } else {
            // The podcast row is already gone, so recover its feed url from the remembered mapping.
            val feedUrl = recallFeedUrl(uuid)
            if (!feedUrl.isNullOrBlank()) {
                queueRemove(feedUrl)
                forgetFeedUrl(uuid)
            }
        }
    }

    /**
     * Enqueues an explicit change and runs a sync pass. Kept so existing callers (for example an
     * unsubscribe pushed from PodcastManagerImpl) keep working.
     */
    fun pushSubscription(feedUrl: String, subscribed: Boolean) {
        if (feedUrl.isBlank()) {
            return
        }
        applicationScope.launch(Dispatchers.IO) {
            if (subscribed) {
                queueAdd(feedUrl)
            } else {
                queueRemove(feedUrl)
            }
            pullSubscriptions()
        }
    }

    /**
     * Runs one full sync pass on the background scope. Safe to call on foreground, on sign-in, and
     * after a local change.
     */
    fun pullSubscriptions() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        if (!syncInFlight.compareAndSet(false, true)) {
            return
        }
        lastPollMs = System.currentTimeMillis()
        applicationScope.launch(Dispatchers.IO) {
            try {
                runSync()
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "SUBSYNC sync FAILED: ${e.message}")
            } finally {
                syncInFlight.set(false)
            }
        }
    }

    /**
     * Throttled pull for frequent triggers like navigation and the foreground timer. Skips if a
     * pull already ran within the throttle window, so rapid navigation does not fire a burst of
     * redundant requests. Picks up both new subscribes and unsubscribes, same as any pull.
     */
    fun pollSubscriptions() {
        if (System.currentTimeMillis() - lastPollMs < MIN_POLL_INTERVAL_MS) {
            return
        }
        pullSubscriptions()
    }

    /**
     * Starts a foreground poll loop so an already open device notices changes from other devices
     * without being reopened. The first poll runs immediately, then once every
     * [PERIODIC_SYNC_INTERVAL_MS] after. Call from onStart. Safe to call repeatedly; a second call
     * while a loop is already running is ignored.
     */
    fun startPeriodicSync() {
        if (periodicJob?.isActive == true) {
            return
        }
        periodicJob = applicationScope.launch(Dispatchers.IO) {
            while (isActive) {
                pollSubscriptions()
                delay(PERIODIC_SYNC_INTERVAL_MS)
            }
        }
    }

    /** Stops the foreground poll loop. Call from onStop. */
    fun stopPeriodicSync() {
        periodicJob?.cancel()
        periodicJob = null
    }

    private suspend fun runSync() {
        val manager = podcastManager.get()
        val lastSync = prefs().getLong(PREF_LAST_PULL_MS, 0L)
        val localSubscriptions = manager.findSubscribedBlocking()
            .mapNotNull { it.podcastUrl }
            .filter { it.isNotBlank() }

        val query = "select=feed_url,subscribed,updated_at_ms" +
            "&updated_at_ms=gt.$lastSync" +
            "&order=updated_at_ms.asc" +
            "&limit=${PodHopperConfig.PULL_PAGE_LIMIT}"
        val rows = supabaseClient.select(TABLE_SUBSCRIPTIONS, query)

        val remoteAdded = mutableListOf<String>()
        val remoteRemoved = mutableListOf<String>()
        var newest = lastSync
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            val feedUrl = row.optString("feed_url")
            if (feedUrl.isEmpty()) {
                continue
            }
            if (row.optBoolean("subscribed", false)) {
                remoteAdded.add(feedUrl)
            } else {
                remoteRemoved.add(feedUrl)
            }
            val updatedAtMs = row.optLong("updated_at_ms", 0L)
            if (updatedAtMs > newest) {
                newest = updatedAtMs
            }
        }

        val queuedAdded = readQueue(PREF_QUEUE_ADDED).toMutableList()
        val queuedRemoved = readQueue(PREF_QUEUE_REMOVED).toMutableList()

        applyingRemote = true
        try {
            // Apply remote adds, skipping feeds we already have or just removed locally.
            for (feedUrl in remoteAdded) {
                if (localSubscriptions.contains(feedUrl) || queuedRemoved.contains(feedUrl)) {
                    continue
                }
                manager.subscribeToFeedUrl(feedUrl)
                // PodHopper: car only. When the car's auto-download toggle is on, a podcast
                // subscribed on the phone must download on the car too, so flag it here the
                // moment the inbound sync adds it. Gated on automotive so the phone's inbound
                // sync behavior is unchanged.
                if (Util.isAutomotive(context) &&
                    settings.autoDownloadNewEpisodes.value == Podcast.AUTO_DOWNLOAD_NEW_EPISODES
                ) {
                    val uuid = feedParser.podcastUuidForFeed(feedUrl)
                    manager.findPodcastByUuid(uuid)?.let { podcast ->
                        if (!podcast.isAutoDownloadNewEpisodes) {
                            manager.updateAutoDownloadStatusBlocking(podcast, Podcast.AUTO_DOWNLOAD_NEW_EPISODES)
                        }
                    }
                }
            }
            // Apply remote removes, skipping feeds we just re-subscribed to locally.
            for (feedUrl in remoteRemoved) {
                if (queuedAdded.contains(feedUrl)) {
                    continue
                }
                val uuid = feedParser.podcastUuidForFeed(feedUrl)
                if (manager.findPodcastByUuid(uuid)?.isSubscribed == true) {
                    manager.unsubscribe(uuid, SourceView.UNKNOWN)
                }
            }
        } finally {
            applyingRemote = false
        }

        // On the first sync, push the whole local library up so the cloud starts consistent.
        val addsToUpload = if (lastSync == 0L) localSubscriptions.toMutableList() else queuedAdded
        // Do not re-upload anything that just came down in this same pass.
        addsToUpload.removeAll(remoteAdded)
        queuedRemoved.removeAll(remoteRemoved)

        if (addsToUpload.isEmpty() && queuedRemoved.isEmpty()) {
            clearQueues()
        } else {
            uploadChanges(addsToUpload, queuedRemoved)
            clearQueues()
        }

        if (newest > lastSync) {
            prefs().edit().putLong(PREF_LAST_PULL_MS, newest).apply()
        }
    }

    private fun uploadChanges(added: List<String>, removed: List<String>) {
        val userId = supabaseClient.getUserId() ?: return
        val now = System.currentTimeMillis()
        val rows = JSONArray()
        for (feedUrl in added) {
            rows.put(subscriptionRow(userId, feedUrl, true, now))
        }
        for (feedUrl in removed) {
            rows.put(subscriptionRow(userId, feedUrl, false, now))
        }
        if (rows.length() > 0) {
            supabaseClient.upsert(TABLE_SUBSCRIPTIONS, "user_id,feed_url", rows)
        }
    }

    private fun subscriptionRow(userId: String, feedUrl: String, subscribed: Boolean, timestampMs: Long): JSONObject {
        val row = JSONObject()
        row.put("user_id", userId)
        row.put("feed_url", feedUrl)
        row.put("subscribed", subscribed)
        row.put("updated_at_ms", timestampMs)
        return row
    }

    private fun queueAdd(feedUrl: String) {
        addToQueue(PREF_QUEUE_ADDED, feedUrl)
        removeFromQueue(PREF_QUEUE_REMOVED, feedUrl)
    }

    private fun queueRemove(feedUrl: String) {
        addToQueue(PREF_QUEUE_REMOVED, feedUrl)
        removeFromQueue(PREF_QUEUE_ADDED, feedUrl)
    }

    private fun readQueue(key: String): List<String> {
        val json = prefs().getString(key, "[]") ?: "[]"
        val array = JSONArray(json)
        val list = mutableListOf<String>()
        for (i in 0 until array.length()) {
            val value = array.optString(i)
            if (value.isNotEmpty()) {
                list.add(value)
            }
        }
        return list
    }

    private fun writeQueue(key: String, values: List<String>) {
        val array = JSONArray()
        for (value in values) {
            array.put(value)
        }
        prefs().edit().putString(key, array.toString()).apply()
    }

    private fun addToQueue(key: String, feedUrl: String) {
        val values = readQueue(key).toMutableList()
        if (!values.contains(feedUrl)) {
            values.add(feedUrl)
            writeQueue(key, values)
        }
    }

    private fun removeFromQueue(key: String, feedUrl: String) {
        val values = readQueue(key).toMutableList()
        if (values.remove(feedUrl)) {
            writeQueue(key, values)
        }
    }

    private fun clearQueues() {
        prefs().edit()
            .putString(PREF_QUEUE_ADDED, "[]")
            .putString(PREF_QUEUE_REMOVED, "[]")
            .apply()
    }

    private fun rememberFeedUrl(uuid: String, feedUrl: String) {
        prefs().edit().putString(PREF_FEED_PREFIX + uuid, feedUrl).apply()
    }

    private fun recallFeedUrl(uuid: String): String? {
        return prefs().getString(PREF_FEED_PREFIX + uuid, null)
    }

    private fun forgetFeedUrl(uuid: String) {
        prefs().edit().remove(PREF_FEED_PREFIX + uuid).apply()
    }

    private fun prefs(): SharedPreferences {
        return context.getSharedPreferences(PREF_NAME, Context.MODE_PRIVATE)
    }

    /**
     * Resets this device's subscription sync bookkeeping so a future account starts clean. Clears
     * the pull cursor, the pending add and remove queues, and the remembered feed url mapping. With
     * the cursor back at zero, the next sign-in does a full first-sync upload of the local library.
     * Does not unsubscribe anything or remove any podcast from the device.
     */
    fun clearLocalSyncState() {
        prefs().edit().clear().apply()
    }

    companion object {
        private const val PERIODIC_SYNC_INTERVAL_MS = 30_000L
        private const val MIN_POLL_INTERVAL_MS = 1_000L
        private const val TABLE_SUBSCRIPTIONS = "subscriptions"
        private const val PREF_NAME = "podhopper_subscription_sync"
        private const val PREF_LAST_PULL_MS = "last_subs_pull_ms"
        private const val PREF_QUEUE_ADDED = "sync_added"
        private const val PREF_QUEUE_REMOVED = "sync_removed"
        private const val PREF_FEED_PREFIX = "feedfor_"
    }
}
