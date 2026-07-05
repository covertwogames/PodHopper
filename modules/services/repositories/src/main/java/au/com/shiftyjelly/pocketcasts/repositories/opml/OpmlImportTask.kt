package au.com.shiftyjelly.pocketcasts.repositories.opml

import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
import android.net.Uri
import android.os.Build
import android.widget.Toast
import androidx.core.net.toUri
import androidx.hilt.work.HiltWorker
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.ForegroundInfo
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationHelper
import au.com.shiftyjelly.pocketcasts.repositories.notification.OnboardingNotificationType
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.servers.di.Downloads
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.OpmlImportFailedEvent
import com.automattic.eventhorizon.OpmlImportFinishedEvent
import com.automattic.eventhorizon.OpmlImportStartedEvent
import dagger.Lazy
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrlOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okio.Source
import okio.source
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationManager as OnboardingNotificationManager

@HiltWorker
class OpmlImportTask @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted parameters: WorkerParameters,
    private val podcastManager: PodcastManager,
    @Downloads private val httpClient: Lazy<OkHttpClient>,
    private val notificationHelper: NotificationHelper,
    private val eventHorizon: EventHorizon,
    private val onboardingNotificationManager: OnboardingNotificationManager,
) : CoroutineWorker(context, parameters) {

    companion object {
        private const val INPUT_URI = "INPUT_URI"
        private const val INPUT_URL = "INPUT_URL"
        private const val WORKER_TAG = "OpmlImportTask.Tag"

        // PodHopper: how many feeds are fetched and parsed at once during an import. Matches the
        // bounded parallelism the local refresh engine uses.
        private const val MAX_CONCURRENT_IMPORTS = 6

        fun run(uri: Uri, context: Context) {
            val data = workDataOf(INPUT_URI to uri.toString())
            run(data, context)
        }

        fun run(url: HttpUrl, context: Context) {
            val data = workDataOf(INPUT_URL to url.toString())
            run(data, context)
        }

        fun workInfos(context: Context) = WorkManager.getInstance(context).getWorkInfosByTagFlow(WORKER_TAG)

        private fun run(data: Data, context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val task = OneTimeWorkRequestBuilder<OpmlImportTask>()
                .setInputData(data)
                .setConstraints(constraints)
                .addTag(WORKER_TAG)
                .build()

            WorkManager.getInstance(context).enqueue(task)

            Toast.makeText(context, context.getString(LR.string.settings_import_opml_toast), Toast.LENGTH_LONG).show()
        }
    }

    private val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    override suspend fun doWork(): Result {
        try {
            onboardingNotificationManager.updateUserFeatureInteraction(OnboardingNotificationType.Import)
            eventHorizon.track(OpmlImportStartedEvent)
            val url = inputData.getString(INPUT_URL)?.toHttpUrlOrNull()
            val uri = inputData.getString(INPUT_URI)?.toUri()
            val source = when {
                url != null -> createUrlOpmlSource(url)

                uri != null -> createUriOpmlSource(uri)

                else -> {
                    trackFailure(reason = "no_input_found")
                    null
                }
            }
            if (source == null) {
                return Result.failure()
            }

            val urls = OpmlUrlReader().readUrls(source)
            processUrls(urls)
            trackProcessed(urls.size)

            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(applicationContext, applicationContext.getString(LR.string.settings_import_opml_succeeded_message), Toast.LENGTH_LONG).show()
            }
            return Result.success()
        } catch (t: Throwable) {
            CoroutineScope(Dispatchers.Main).launch {
                Toast.makeText(applicationContext, applicationContext.getString(LR.string.settings_import_opml_import_failed_message), Toast.LENGTH_LONG).show()
            }
            LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, t, "OPML import failed.")
            trackFailure(reason = "unknown")
            return Result.failure()
        }
    }

    private suspend fun createUrlOpmlSource(url: HttpUrl): Source {
        val request = Request.Builder().url(url).build()
        val client = withContext(Dispatchers.Default) { httpClient.get() }
        return client.newCall(request).execute().body.source()
    }

    private fun createUriOpmlSource(uri: Uri): Source? {
        return applicationContext.contentResolver.openInputStream(uri)?.source()
    }

    private fun trackProcessed(numberParsed: Int) {
        eventHorizon.track(
            OpmlImportFinishedEvent(
                number = numberParsed.toLong(),
            ),
        )
    }

    private fun trackFailure(reason: String) {
        eventHorizon.track(
            OpmlImportFailedEvent(
                reason = reason,
            ),
        )
    }

    private suspend fun processUrls(urls: List<String>) {
        val podcastCount = urls.size
        val initialDatabaseCount = podcastManager.countPodcastsBlocking()

        // keep the job running the in foreground with a notification
        setForeground(createForegroundInfo(0, podcastCount))

        // PodHopper: this used to post the feed urls to the Pocket Casts refresh server to resolve
        // them into Pocket Casts podcast uuids and then subscribe by uuid, polling the server for
        // podcasts it had not indexed yet. Subscribe to each feed url directly through the
        // client-side feed engine instead: each feed is fetched and parsed on-device, a feed that
        // fails to parse is logged and skipped, and the rest of the import continues.
        val semaphore = Semaphore(MAX_CONCURRENT_IMPORTS)
        coroutineScope {
            urls.map { feedUrl ->
                async(Dispatchers.IO) {
                    semaphore.withPermit {
                        try {
                            podcastManager.subscribeToFeedUrl(feedUrl)
                        } catch (e: Exception) {
                            LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "OPML import: failed to subscribe to $feedUrl")
                        }
                        updateNotification(initialDatabaseCount, podcastCount)
                    }
                }
            }.awaitAll()
        }

        // keep the job running while still subscribing to the podcasts
        while (podcastManager.isSubscribingToPodcasts()) {
            updateNotification(initialDatabaseCount, podcastCount)
            delay(1000)
        }
    }

    /**
     * Keep the job in the foreground with a notification
     */
    private fun createForegroundInfo(progress: Int, total: Int): ForegroundInfo {
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(
                Settings.NotificationId.OPML.value,
                buildNotification(progress, total),
                FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            ForegroundInfo(Settings.NotificationId.OPML.value, buildNotification(progress, total))
        }
    }

    private fun updateNotification(initialDatabaseCount: Int, podcastCount: Int) {
        val databaseCount = podcastManager.countPodcastsBlocking()
        val progress = (databaseCount - initialDatabaseCount).coerceIn(0, podcastCount)
        notificationManager.notify(Settings.NotificationId.OPML.value, buildNotification(progress, podcastCount))
    }

    private fun buildNotification(progress: Int, total: Int): Notification {
        val cancelIntent = WorkManager.getInstance(applicationContext).createCancelPendingIntent(id)
        return notificationHelper.podcastImportChannelBuilder()
            .setContentTitle(applicationContext.getString(LR.string.settings_import_opml_title))
            .setContentText(applicationContext.getString(LR.string.settings_import_opml_progress, progress, total))
            .setProgress(total, progress, false)
            .setSmallIcon(IR.drawable.notification_download)
            .setOngoing(true)
            .addAction(IR.drawable.ic_cancel, applicationContext.getString(LR.string.settings_import_opml_stop), cancelIntent)
            .build()
    }
}
