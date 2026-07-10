package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.os.Build
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackState
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.ArrayDeque
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.rx2.asFlow
import kotlinx.coroutines.withContext

/**
 * PodHopper: car-only playback diagnostics. The head unit has no logcat, so the only way to see
 * what playback did on a real drive is for the app to ship its own log file out. Two paths:
 *
 *  - Automatic: watches the playback state for a burst of STOPPED or ERROR transitions inside a
 *    short window, the signature of the teardown loop, and uploads a LogBuffer snapshot to the
 *    private Supabase Storage "diagnostics" bucket. Throttled so one bad drive produces a couple
 *    of snapshots, not hundreds.
 *  - Manual: the car settings screen exposes an "Upload diagnostic logs" row that calls
 *    [uploadNow] directly.
 *
 * Started only from AutomotiveApplication, and double-gated on [Util.isAutomotive], so the phone
 * build never runs any of this. Signed-out devices skip uploads silently: the storage policy
 * requires an authenticated user, and there is nothing account-scoped to attach the logs to.
 */
@Singleton
class PodHopperCarDiagnostics @Inject constructor(
    private val playbackManager: Lazy<PlaybackManager>,
    private val supabaseClient: SupabaseClient,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private var started = false

    // Timestamps of recent STOPPED/ERROR transitions, pruned to the detection window.
    private val recentFailures = ArrayDeque<Long>()

    @Volatile
    private var lastAutoUploadMs = 0L

    @Volatile
    private var lastSeenState: PlaybackState.State? = null

    fun start() {
        if (started || !Util.isAutomotive(context)) {
            return
        }
        started = true
        playbackManager.get().playbackStateRelay
            .asFlow()
            .onEach { state -> onPlaybackState(state) }
            .launchIn(applicationScope)
        LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Car diagnostics watcher started")
    }

    private fun onPlaybackState(state: PlaybackState) {
        val previous = lastSeenState
        lastSeenState = state.state
        // Only transitions INTO a failure state count. A stream sitting in ERROR emits the same
        // state repeatedly through unrelated updates; counting those would false-trigger.
        val isFailureTransition = state.state != previous &&
            (state.state == PlaybackState.State.STOPPED || state.state == PlaybackState.State.ERROR)
        if (!isFailureTransition) {
            return
        }
        val now = System.currentTimeMillis()
        synchronized(recentFailures) {
            recentFailures.addLast(now)
            while (recentFailures.isNotEmpty() && now - recentFailures.peekFirst() > BURST_WINDOW_MS) {
                recentFailures.removeFirst()
            }
            if (recentFailures.size < BURST_THRESHOLD) {
                return
            }
            recentFailures.clear()
        }
        if (now - lastAutoUploadMs < AUTO_UPLOAD_MIN_INTERVAL_MS) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Playback failure burst detected, upload throttled")
            return
        }
        lastAutoUploadMs = now
        LogBuffer.e(LogBuffer.TAG_PLAYBACK, "Playback failure burst detected ($BURST_THRESHOLD stops/errors within ${BURST_WINDOW_MS / 1000}s), uploading diagnostics")
        applicationScope.launch(Dispatchers.IO) {
            runCatching { uploadNow(reason = "burst") }
        }
    }

    /**
     * Snapshots the LogBuffer and uploads it. Returns true on success. Blocking network inside;
     * runs itself on the IO dispatcher. Signed-out devices return false without attempting.
     */
    suspend fun uploadNow(reason: String): Boolean {
        if (!supabaseClient.isLoggedIn()) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Diagnostics upload skipped: not signed in")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = ByteArrayOutputStream()
                LogBuffer.output(snapshot)
                val bytes = snapshot.toByteArray()
                if (bytes.isEmpty()) {
                    LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Diagnostics upload skipped: log buffer empty")
                    return@withContext false
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
                val device = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val objectPath = "$device/$stamp-$reason.log"
                supabaseClient.uploadDiagnostics(objectPath, bytes)
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Diagnostics uploaded: $objectPath (${bytes.size} bytes)")
                true
            } catch (e: Exception) {
                LogBuffer.e(LogBuffer.TAG_PLAYBACK, "Diagnostics upload failed: ${e.message}")
                false
            }
        }
    }

    private companion object {
        const val BURST_WINDOW_MS = 10_000L
        const val BURST_THRESHOLD = 3
        const val AUTO_UPLOAD_MIN_INTERVAL_MS = 10 * 60 * 1000L
    }
}
