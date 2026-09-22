package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.util.concurrent.TimeUnit

/**
 * PodHopper: retries podcasts that failed to add during a subscription sync pass, for example
 * because the network dropped part way through. It runs only once the device has a network
 * connection, and while it runs the system keeps the device awake. If feeds are still waiting
 * afterwards it asks to run again later, spaced out further each time.
 */
@HiltWorker
class PodHopperSubscriptionRetryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val subscriptionSync: PodHopperSubscriptionSync,
) : CoroutineWorker(context, parameters) {

    override suspend fun doWork(): Result {
        val stillWaiting = subscriptionSync.syncNowForRetry()
        return if (stillWaiting) Result.retry() else Result.success()
    }

    companion object {
        private const val UNIQUE_WORK_NAME = "podhopper_subscription_retry"
        private const val BACKOFF_MINUTES = 1L

        /**
         * Schedules a retry after [delayMs], once there is a network connection. Keeps an already
         * scheduled or running retry rather than replacing it, so this never cancels one mid-run.
         */
        fun enqueue(context: Context, delayMs: Long) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()
            val request = OneTimeWorkRequestBuilder<PodHopperSubscriptionRetryWorker>()
                .setConstraints(constraints)
                .setInitialDelay(delayMs.coerceAtLeast(0L), TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_MINUTES, TimeUnit.MINUTES)
                .build()
            WorkManager.getInstance(context)
                .enqueueUniqueWork(UNIQUE_WORK_NAME, ExistingWorkPolicy.KEEP, request)
        }

        /** Cancels any scheduled retry, used when signing out. */
        fun cancel(context: Context) {
            WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_WORK_NAME)
        }
    }
}
