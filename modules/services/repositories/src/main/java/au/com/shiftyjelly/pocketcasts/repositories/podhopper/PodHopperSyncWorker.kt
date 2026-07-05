package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * PodHopper periodic background sync. Every few hours, even with the app closed, this pulls
 * cross-device playback positions and runs the completions reconcile, so playlists, badge counts,
 * and auto-download decisions stay correct against episodes played or finished on other devices.
 * The foreground reconcile handles the app-open moments; this worker covers everything in between.
 * Requires a network connection; skips silently when signed out.
 */
@HiltWorker
class PodHopperSyncWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val positionSync: PodHopperPositionSync,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        try {
            positionSync.fullSyncBlocking(force = true)
        } catch (e: Exception) {
            // A failed background sync self-heals on the next run (the completions reconcile is
            // cursor-based and idempotent), so never fail the job and trigger retry backoff storms.
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper periodic sync failed, will retry next period: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        private const val WORKER_TAG = "podhopper_sync_worker"
        private const val REPEAT_INTERVAL_HOURS = 4L

        fun schedulePeriodicWork(context: Context): Operation {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = PeriodicWorkRequestBuilder<PodHopperSyncWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
                .setConstraints(constraints)
                .addTag(WORKER_TAG)
                .build()
            return WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork("$WORKER_TAG-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
