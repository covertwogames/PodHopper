package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.Operation
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.models.type.EpisodePlayingStatus
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadQueue
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.rx2.await

/**
 * PodHopper: car-only downloaded-episode cleanup. Head unit storage is small and shared, so
 * downloads are a rotating buffer, not an archive. Once a day this deletes the downloaded FILE
 * (never the episode row, so everything stays browsable and streamable) for any episode that is
 * either completed or whose download is older than [MAX_DOWNLOAD_AGE_DAYS] days. Deletion goes
 * through the same DownloadQueue.cancelAll path the phone's own Manage Downloads screen uses, so
 * download status bookkeeping stays consistent. Scheduled only from AutomotiveApplication; the
 * phone never runs it. The played half also fires naturally for episodes finished on the phone,
 * because the cross-device completion sync marks them completed here first.
 */
@HiltWorker
class PodHopperCarDownloadCleanupWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val episodeManager: EpisodeManager,
    private val downloadQueue: DownloadQueue,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        try {
            val downloaded = episodeManager.findDownloadedEpisodesRxFlowable().firstOrError().await()
            if (downloaded.isEmpty()) {
                return Result.success()
            }
            val cutoffMs = System.currentTimeMillis() - TimeUnit.DAYS.toMillis(MAX_DOWNLOAD_AGE_DAYS)
            val toDelete = downloaded.filter { episode ->
                val isCompleted = episode.playingStatus == EpisodePlayingStatus.COMPLETED
                val downloadedAtMs = episode.lastDownloadAttemptDate?.time
                val isExpired = downloadedAtMs != null && downloadedAtMs < cutoffMs
                isCompleted || isExpired
            }
            if (toDelete.isEmpty()) {
                return Result.success()
            }
            LogBuffer.i(
                LogBuffer.TAG_BACKGROUND_TASKS,
                "Car download cleanup: removing ${toDelete.size} of ${downloaded.size} downloads (completed or older than $MAX_DOWNLOAD_AGE_DAYS days)",
            )
            downloadQueue.cancelAll(toDelete.map { it.uuid }, SourceView.DOWNLOADS).join()
        } catch (e: Exception) {
            // Cleanup is best-effort and self-heals on the next daily run; never trigger
            // WorkManager retry backoff for it.
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Car download cleanup failed, will retry next period: ${e.message}")
        }
        return Result.success()
    }

    companion object {
        private const val WORKER_TAG = "podhopper_car_download_cleanup_worker"
        private const val REPEAT_INTERVAL_HOURS = 24L
        const val MAX_DOWNLOAD_AGE_DAYS = 30L

        fun schedulePeriodicWork(context: Context): Operation {
            val request = PeriodicWorkRequestBuilder<PodHopperCarDownloadCleanupWorker>(REPEAT_INTERVAL_HOURS, TimeUnit.HOURS)
                .addTag(WORKER_TAG)
                .build()
            return WorkManager
                .getInstance(context)
                .enqueueUniquePeriodicWork("$WORKER_TAG-periodic", ExistingPeriodicWorkPolicy.KEEP, request)
        }
    }
}
