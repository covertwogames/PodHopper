package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import au.com.shiftyjelly.pocketcasts.models.entity.BaseEpisode
import au.com.shiftyjelly.pocketcasts.models.entity.PodcastEpisode
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject

/**
 * PodHopper: cross-device Up Next queue.
 *
 * Position sync answers "what is playing and where". This answers "what plays after it", which is
 * the part a queue-driven listener actually arranges by hand and the part that previously did not
 * leave the device it was built on.
 *
 * The queue is an ordered list, so it syncs as a whole rather than per entry: two different
 * orderings cannot be meaningfully merged, and the freshest write simply wins, the same rule
 * position sync already uses. The backend holds one row per user.
 */
@Singleton
class PodHopperUpNextSync @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val episodeManager: EpisodeManager,
    private val playbackManager: Lazy<PlaybackManager>,
    @ApplicationContext private val context: Context,
    @Suppress("unused") @ApplicationScope private val applicationScope: CoroutineScope,
) {

    private fun prefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * One entry of the synced queue, carrying enough to describe an episode this device may not
     * have yet. Bare uuids would not be enough: a device that cannot resolve an entry could neither
     * show it nor hand it back intact, and would silently drop it from the user's queue.
     */
    private data class Entry(
        val uuid: String,
        val title: String?,
        val podcastUuid: String?,
        val feedUrl: String?,
        val mediaUrl: String?,
        val publishedMs: Long?,
    ) {
        fun toJson(): JSONObject = JSONObject().apply {
            put("u", uuid)
            put("t", title ?: JSONObject.NULL)
            put("p", podcastUuid ?: JSONObject.NULL)
            put("f", feedUrl ?: JSONObject.NULL)
            put("m", mediaUrl ?: JSONObject.NULL)
            put("d", publishedMs ?: JSONObject.NULL)
        }

        companion object {
            fun fromJson(json: JSONObject): Entry? {
                val uuid = json.optString("u").takeIf { it.isNotEmpty() } ?: return null
                return Entry(
                    uuid = uuid,
                    title = json.optString("t").takeIf { it.isNotEmpty() },
                    podcastUuid = json.optString("p").takeIf { it.isNotEmpty() },
                    feedUrl = json.optString("f").takeIf { it.isNotEmpty() },
                    mediaUrl = json.optString("m").takeIf { it.isNotEmpty() },
                    publishedMs = json.optLong("d", 0L).takeIf { it > 0L },
                )
            }
        }
    }

    private fun BaseEpisode.toEntry(): Entry {
        val podcastUuid = (this as? PodcastEpisode)?.podcastUuid
        return Entry(
            uuid = uuid,
            title = title,
            podcastUuid = podcastUuid,
            feedUrl = null,
            mediaUrl = downloadUrl,
            publishedMs = publishedDate.time,
        )
    }

    /**
     * Pull the backend's queue, then publish ours if it has moved on. Pull first, always: a device
     * that has just started has not reconciled yet, and publishing before reading is how an empty
     * local queue overwrites a real one.
     */
    suspend fun syncBlocking() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        try {
            pullBlocking()
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next pull failed: ${e.message}")
        }
        try {
            pushIfChangedBlocking()
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next push failed: ${e.message}")
        }
    }

    private suspend fun pullBlocking() {
        val remote = withContext(Dispatchers.IO) {
            val rows = supabaseClient.select(TABLE_UP_NEXT_QUEUE, "select=episodes,updated_at_ms,device_id&limit=1")
            if (rows.length() == 0) null else rows.getJSONObject(0)
        }

        if (remote == null) {
            // No queue on the backend yet. This still counts as a reconcile: the device asked and
            // was told there is nothing, which is exactly the state it is safe to publish from.
            // Recording an empty signature is what lets the first device create the row at all;
            // returning without recording deadlocked the feature, because the push waits for a
            // reconcile and no reconcile could complete against a table with no row in it.
            if (prefs().getString(PREF_SIGNATURE, null) == null) {
                prefs().edit().putString(PREF_SIGNATURE, "").apply()
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next: no queue on the backend yet, this device may publish its own")
            }
            return
        }

        // Freshest change wins, and the pull runs first, so without this an unpushed local edit
        // would be silently replaced by an older remote queue purely because of call order. The
        // stamp is set when a local change is found to be unpushed and cleared once it lands, so
        // it is only ever set while this device is genuinely ahead of the backend.
        val remoteTs = remote.optLong("updated_at_ms", 0L)
        val localChangeMs = prefs().getLong(PREF_LOCAL_CHANGE_MS, 0L)
        if (localChangeMs > 0L && remoteTs > 0L && localChangeMs > remoteTs) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next: keeping this device's newer queue, the backend copy is older")
            return
        }

        val remoteEntries = parseEntries(remote.optJSONArray("episodes"))
        // Two different facts, tracked separately. PREF_REMOTE_SIGNATURE is the queue this device
        // last received, and answers "is there anything new to apply". PREF_SIGNATURE is what this
        // device's own queue settled to, and answers "is there anything of ours to publish". They
        // legitimately differ whenever the apply preserved a different playing episode or held an
        // entry, so one value cannot serve both questions.
        val remoteSignature = remoteEntries.joinToString(",") { it.uuid }
        if (remoteSignature == prefs().getString(PREF_REMOTE_SIGNATURE, null)) {
            // Already applied this exact queue; re-applying would rewrite the database and re-emit
            // a queue change for no reason.
            return
        }

        // Resolve what this device knows. Anything it does not know is held verbatim at its
        // position rather than dropped: the device cannot play it, but it must not delete it from
        // the user's queue when it later publishes its own. Held entries resolve on their own once
        // a feed refresh brings the episode in.
        val resolved = mutableListOf<BaseEpisode>()
        val held = JSONArray()
        for ((index, entry) in remoteEntries.withIndex()) {
            val episode = episodeManager.findEpisodeByUuid(entry.uuid)
            if (episode != null) {
                resolved.add(episode)
            } else {
                held.put(entry.toJson().put("i", index))
            }
        }

        val manager = playbackManager.get()
        withContext(Dispatchers.IO) {
            // importServerChangesBlocking is upstream's own apply path and already refuses to
            // displace an episode that is playing right now, keeping it at the head and applying
            // the rest behind it.
            manager.upNextQueue.importServerChangesBlocking(resolved, manager)
        }

        // Record what this device's queue actually became, not what arrived. The apply path keeps
        // whatever is playing here at the head, so a device playing a different episode ends up
        // with a legitimately different order, and held entries mean the local list can differ
        // again. Storing the received order instead would leave the signature permanently at odds
        // with reality: the next push would publish this device's reordering, the other device
        // would move its own playing episode back to the top and publish that, and the two would
        // rewrite the queue past each other on every cycle without either being wrong. Recording
        // the local result keeps that reordering local, which is what it is.
        prefs().edit()
            .putString(PREF_HELD, if (held.length() == 0) null else held.toString())
            .putString(PREF_REMOTE_SIGNATURE, remoteSignature)
            .apply()
        val settled = mergedQueueForPush().joinToString(",") { it.uuid }
        prefs().edit().putString(PREF_SIGNATURE, settled).apply()

        val heldNote = if (held.length() > 0) ", ${held.length()} entry(s) held until their episodes arrive" else ""
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next pulled: ${resolved.size} episode(s) applied$heldNote")
    }

    /**
     * Records that this device's queue just changed locally.
     *
     * Called from the queue's own mutation point rather than from the push, because the stamp means
     * "when this device's queue last changed" and the push may not run for seconds, or at all if
     * the process is backgrounded first. Reordering a queue and pocketing the phone is ordinary
     * behaviour, and stamping at push time left exactly that case unprotected: no stamp was written,
     * so the next pull discarded the edit.
     *
     * Applying a remote queue must never come through here. It is not a local change, and treating
     * it as one would make the device claim to be ahead of the backend and start refusing the very
     * updates it just accepted.
     */
    fun noteLocalChange() {
        prefs().edit().putLong(PREF_LOCAL_CHANGE_MS, System.currentTimeMillis()).apply()
    }

    /**
     * Push only, for the queue's own change trigger, so an edit reaches the other devices in
     * seconds rather than waiting for the next sync cycle. Deliberately never pulls: a pull during
     * an edit could apply a remote queue on top of what the user is in the middle of arranging.
     * Cheap when nothing changed, because the signature check short circuits before any network.
     */
    fun pushIfChanged() {
        if (!supabaseClient.isLoggedIn()) {
            return
        }
        applicationScope.launch(Dispatchers.IO) {
            try {
                pushIfChangedBlocking()
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next change push failed, the next sync retries: ${e.message}")
            }
        }
    }

    private suspend fun pushIfChangedBlocking() {
        val storedSignature = prefs().getString(PREF_SIGNATURE, null)
        if (storedSignature == null) {
            // Never reconciled with the backend on this install. Publishing now would push whatever
            // this device happens to hold, which right after a fresh install is nothing at all.
            return
        }

        val merged = mergedQueueForPush()
        val signature = merged.joinToString(",") { it.uuid }
        if (signature == storedSignature) {
            // Nothing of ours to publish, so this device is not ahead of the backend and must not
            // keep claiming to be. Clearing here as well as on success matters: a stamp left behind
            // by a failed push would otherwise survive forever once the queue was edited back to
            // match, and this device would silently refuse every remote queue from then on.
            prefs().edit().remove(PREF_LOCAL_CHANGE_MS).apply()
            return
        }

        val payload = JSONArray()
        merged.forEach { payload.put(it.toJson()) }

        withContext(Dispatchers.IO) {
            val userId = supabaseClient.getUserId() ?: return@withContext
            val row = JSONObject()
                .put("user_id", userId)
                .put("episodes", payload)
                .put("device_id", getOrCreateInstallId())
                .put("device_name", Build.MODEL)
                .put("updated_at_ms", System.currentTimeMillis())
            supabaseClient.upsert(TABLE_UP_NEXT_QUEUE, "user_id", JSONArray().put(row))
            // Published, so this device is no longer ahead of the backend.
            // The backend now holds exactly this list, so it is both what we published and what we
            // would next receive. Recording both stops the next pull from treating our own write as
            // new and re-applying it.
            prefs().edit()
                .putString(PREF_SIGNATURE, signature)
                .putString(PREF_REMOTE_SIGNATURE, signature)
                .remove(PREF_LOCAL_CHANGE_MS)
                .apply()
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "PodHopper Up Next pushed ${merged.size} entry(s)")
        }
    }

    /**
     * This device's queue, with any held entries put back where they came from, which is what makes
     * publishing safe on a device that could not resolve everything it was sent.
     */
    private fun mergedQueueForPush(): List<Entry> {
        val local = playbackManager.get().upNextQueue.allEpisodes.map { it.toEntry() }
        val held = parseHeld()
        if (held.isEmpty()) {
            return local
        }
        val result = local.toMutableList()
        // Ascending, so each insertion lands at the index it was recorded at.
        held.sortedBy { it.second }.forEach { (entry, index) ->
            if (result.none { it.uuid == entry.uuid }) {
                result.add(index.coerceIn(0, result.size), entry)
            }
        }
        return result
    }

    private fun parseEntries(array: JSONArray?): List<Entry> {
        if (array == null) {
            return emptyList()
        }
        val entries = mutableListOf<Entry>()
        for (i in 0 until array.length()) {
            array.optJSONObject(i)?.let { Entry.fromJson(it)?.let(entries::add) }
        }
        return entries
    }

    private fun parseHeld(): List<Pair<Entry, Int>> {
        val raw = prefs().getString(PREF_HELD, null) ?: return emptyList()
        return try {
            val array = JSONArray(raw)
            val out = mutableListOf<Pair<Entry, Int>>()
            for (i in 0 until array.length()) {
                val json = array.optJSONObject(i) ?: continue
                Entry.fromJson(json)?.let { out.add(it to json.optInt("i", out.size)) }
            }
            out
        } catch (e: Exception) {
            emptyList()
        }
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

    /** Called on sign out: the next sign in must reconcile from the backend, not publish over it. */
    fun clearLocalState() {
        prefs().edit()
            .remove(PREF_SIGNATURE)
            .remove(PREF_HELD)
            .remove(PREF_REMOTE_SIGNATURE)
            .remove(PREF_LOCAL_CHANGE_MS)
            .apply()
    }

    companion object {
        private const val PREFS_NAME = "podhopper_upnext_sync"
        private const val PREF_SIGNATURE = "queue_signature"
        private const val PREF_REMOTE_SIGNATURE = "queue_remote_signature"
        private const val PREF_HELD = "queue_held"
        private const val PREF_LOCAL_CHANGE_MS = "queue_local_change_ms"
        private const val PREF_INSTALL_ID = "install_id"
        private const val TABLE_UP_NEXT_QUEUE = "up_next_queue"
    }
}
