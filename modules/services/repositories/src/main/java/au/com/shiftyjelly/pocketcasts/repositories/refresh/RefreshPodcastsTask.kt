package au.com.shiftyjelly.pocketcasts.repositories.refresh

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

@HiltWorker
class RefreshPodcastsTask @AssistedInject constructor(
    @Assisted val context: Context,
    @Assisted val params: WorkerParameters,
) : Worker(context, params) {
    private var refreshRunnable: RefreshPodcastsThread? = null

    override fun doWork(): Result {
        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - Start")
        val refresh = RefreshPodcastsThread(
            context = this.applicationContext,
            runNow = false,
        )
        this.refreshRunnable = refresh
        val result = refresh.run()
        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - Finished $result")
        return result
    }

    override fun onStopped() {
        super.onStopped()
        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - onStopped")
        this.refreshRunnable?.cancelExecution()
    }

    companion object {
        private const val TAG_REFRESH_TASK = "au.com.shiftyjelly.pocketcasts.repositories.refresh.RefreshPodcastsTask"
        fun scheduleOrCancel(context: Context, settings: Settings) {
            val workManager = WorkManager.getInstance(context)

            val frequency = settings.podcastRefreshFrequency.value
            if (!frequency.isOn) {
                workManager.cancelAllWorkByTag(TAG_REFRESH_TASK)
                return
            }

            val syncNetworkConstraint = settings.getWorkManagerNetworkTypeConstraint()

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(syncNetworkConstraint)
                .setRequiresBatteryNotLow(true)
                .build()

            val request = PeriodicWorkRequestBuilder<RefreshPodcastsTask>(frequency.hours, TimeUnit.HOURS)
                .addTag(TAG_REFRESH_TASK)
                .setConstraints(constraints)
                .setInitialDelay(frequency.hours, TimeUnit.HOURS)
                .build()

            workManager.enqueueUniquePeriodicWork(TAG_REFRESH_TASK, ExistingPeriodicWorkPolicy.CANCEL_AND_REENQUEUE, request)

            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Set up periodic refresh every ${frequency.hours}h")
        }

        fun runNow(context: Context, applicationScope: CoroutineScope) {
            applicationScope.launch {
                runNowSync(context, applicationScope)
            }
        }

        private val refreshMutex = Mutex()
        private var refreshJob: Deferred<Result>? = null
        suspend fun runNowSync(context: Context, applicationScope: CoroutineScope) = withContext(Dispatchers.Default) {
            // PodHopper: only the check-and-start happens under the lock. A request that finds a
            // refresh already running waits for that same refresh outside the lock and then returns.
            // Waiting while holding the lock made later requests queue behind it, and each one that
            // got the lock after the refresh ended found nothing running and started another full
            // refresh, so several requests during one slow refresh (the watch asks on every screen
            // wake) ran back to back instead of sharing it.
            var startedHere = false
            val job = refreshMutex.withLock {
                refreshJob ?: run {
                    LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - runNow - Start")
                    val refreshThread = RefreshPodcastsThread(
                        context = context.applicationContext,
                        runNow = true,
                    )
                    async { refreshThread.run() }.also {
                        refreshJob = it
                        startedHere = true
                    }
                }
            }

            if (!startedHere) {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - runNow - Already running, joining.")
                job.await()
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - runNow - Already running, join complete.")
                return@withContext
            }

            try {
                val result = job.await()
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "RefreshPodcastsTask - runNow - Finished $result")
            } catch (e: Exception) {
                LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "RefreshPodcastsTask - runNow - Exception")
            } finally {
                // Clear the marker even if this coroutine was cancelled, and only while it still
                // points at this refresh, so a finished or cancelled refresh can never be left in
                // place for later requests to join forever.
                withContext(NonCancellable) {
                    refreshMutex.withLock {
                        if (refreshJob === job) {
                            refreshJob = null
                        }
                    }
                }
            }
        }
    }
}
