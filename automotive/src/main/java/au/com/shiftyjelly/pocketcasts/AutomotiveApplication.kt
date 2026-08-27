package au.com.shiftyjelly.pocketcasts

import android.annotation.SuppressLint
import android.app.Application
import android.app.UiModeManager
import android.util.Log
import androidx.core.content.getSystemService
import androidx.hilt.work.HiltWorkerFactory
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.work.Configuration
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsController
import au.com.shiftyjelly.pocketcasts.analytics.experiments.ExperimentProvider
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.crashlogging.InitializeRemoteLogging
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.download.DownloadStatusObserver
import au.com.shiftyjelly.pocketcasts.repositories.jobs.VersionMigrationsWorker
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.UserEpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperPositionSync
import au.com.shiftyjelly.pocketcasts.repositories.refresh.RefreshPodcastsTask
import au.com.shiftyjelly.pocketcasts.repositories.stats.PlaybackStatsSyncWorker
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationHelper
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperCarDiagnostics
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperSyncWorker
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import au.com.shiftyjelly.pocketcasts.utils.TimberDebugTree
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.DefaultReleaseFeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.FirebaseRemoteFeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.PreferencesFeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.utils.log.RxJavaUncaughtExceptionHandling
import coil3.ImageLoader
import coil3.SingletonImageLoader
import com.google.android.gms.common.ConnectionResult
import com.google.android.gms.common.GoogleApiAvailability
import com.squareup.moshi.Moshi
import dagger.hilt.android.HiltAndroidApp
import java.io.File
import java.util.concurrent.Executors
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import timber.log.Timber

@SuppressLint("LogNotTimber")
@HiltAndroidApp
class AutomotiveApplication :
    Application(),
    Configuration.Provider {

    @Inject lateinit var moshi: Moshi

    @Inject lateinit var coilImageLoader: ImageLoader

    @Inject lateinit var playbackManager: PlaybackManager

    @Inject lateinit var podHopperPositionSync: PodHopperPositionSync

    @Inject lateinit var podHopperCarDiagnostics: PodHopperCarDiagnostics

    @Inject lateinit var notificationHelper: NotificationHelper

    @Inject lateinit var downloadStatusObserver: DownloadStatusObserver

    @Inject lateinit var userEpisodeManager: UserEpisodeManager

    @Inject lateinit var settings: Settings

    @Inject lateinit var userManager: UserManager

    @Inject lateinit var workerFactory: HiltWorkerFactory

    @Inject lateinit var initializeRemoteLogging: InitializeRemoteLogging

    @Inject lateinit var analyticsController: AnalyticsController

    @Inject lateinit var experimentProvider: ExperimentProvider

    @Inject lateinit var defaultReleaseFeatureProvider: DefaultReleaseFeatureProvider

    @Inject lateinit var firebaseRemoteFeatureProvider: FirebaseRemoteFeatureProvider

    @Inject lateinit var preferencesFeatureProvider: PreferencesFeatureProvider

    @Inject @ApplicationScope
    lateinit var applicationScope: CoroutineScope

    override fun onCreate() {
        super.onCreate()

        // PodHopper: register the DI ImageLoader (which carries FeedArtworkInterceptor) as the
        // singleton the loadInto extension and compose surfaces resolve, so uuid-addressed
        // artwork resolves to feed thumbnails on the car exactly as it does on the phone.
        SingletonImageLoader.setSafe { coilImageLoader }

        // PodHopper: create the app's notification channels on the car, the same call the phone
        // application has always made and this application class never did. Without it, the
        // download worker's startForeground notification references a channel that does not
        // exist, and Android kills the process with CannotPostForegroundServiceNotificationException
        // ("Bad notification for startForeground"), which surfaced as playback stopping every
        // ~25 seconds while Up Next auto-download retried. Verified from the crash stacks in the
        // 2026-07-10 car diagnostics upload. Channel creation is idempotent.
        notificationHelper.setupNotificationChannels()

        setupFeatureFlags()

        RxJavaUncaughtExceptionHandling.setUp()
        setupRemoteLogging()
        setupLogging()
        setupAnalytics()
        setupApp()
        setupForegroundSync()
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(workerFactory)
            .setExecutor(Executors.newFixedThreadPool(3))
            .setJobSchedulerJobIdRange(1000, 20000)
            .build()

    private fun setupApp() {
        Log.i(Settings.LOG_TAG_AUTO, "App started. ${settings.getVersion()} (${settings.getVersionCode()})")

        runBlocking {
            withContext(Dispatchers.Default) {
                playbackManager.setup()
                RefreshPodcastsTask.runNow(this@AutomotiveApplication, applicationScope)
                // PodHopper: schedule the PERIODIC feed refresh on the car. Only the phone ever
                // called scheduleOrCancel, so the car refreshed solely when its process or media
                // service was created, and AAOS keeps processes warm across drives, so feeds went
                // days without refreshing (observed: "last refresh 2 days ago", 2026-07-23). The
                // schedule honors the same refresh-frequency setting as the phone (default 1h).
                RefreshPodcastsTask.scheduleOrCancel(this@AutomotiveApplication, settings)
            }

            VersionMigrationsWorker.performMigrations(
                context = this@AutomotiveApplication,
                settings = settings,
                moshi = moshi,
            )
        }

        val playServices = GoogleApiAvailability.getInstance().isGooglePlayServicesAvailable(this) == ConnectionResult.SUCCESS
        Log.i(Settings.LOG_TAG_AUTO, "Play services $playServices")

        userEpisodeManager.monitorUploads(applicationContext)
        downloadStatusObserver.monitorDownloadStatus()

        // force the Automotive app into car mode as some car companies send the UI mode as normal, this makes sure the car resources such as layout-car are used.
        this.getSystemService<UiModeManager>()?.enableCarMode(0)

        PlaybackStatsSyncWorker.scheduleOneTimeWork(this)
        PlaybackStatsSyncWorker.schedulePeriodicWork(this)
        // PodHopper: periodic cross-device sync on the car too, so completions and positions from
        // the phone land between drives, not only at session connect.
        PodHopperSyncWorker.schedulePeriodicWork(this)

        // PodHopper: one-time car download defaults. The shared auto-download engine defaults to
        // unmetered-only and charge-only, both of which mean "never" on a head unit whose only
        // network is cellular and whose power state is not a phone battery. Applied once so a
        // user change in settings is never overwritten on a later launch.

        // PodHopper: playback diagnostics watcher. The car has no logcat, so bursts of playback
        // failures upload a log snapshot to Supabase Storage for offline analysis.
        podHopperCarDiagnostics.start()

        // PodHopper: the car has no logcat, so a fatal crash normally dies without a trace and
        // can only be inferred from timing patterns in the log. This wrapper writes the crash
        // stack into LogBuffer (a synchronous, flushed file write) in the process's final
        // moment, then delegates to the previously installed handler so crash reporting and the
        // system's normal death handling still run. Installed last in onCreate so it wraps every
        // handler registered above.
        val previousUncaughtHandler = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, throwable ->
            try {
                LogBuffer.e(LogBuffer.TAG_CRASH, throwable, "FATAL crash on thread ${thread.name}")
            } catch (ignored: Throwable) {
                // Never let diagnostics interfere with crash handling.
            }
            previousUncaughtHandler?.uncaughtException(thread, throwable)
        }
    }

    override fun onTerminate() {
        super.onTerminate()
        Log.d(Settings.LOG_TAG_AUTO, "Terminate")
    }

    /**
     * PodHopper: pull the latest cross-device positions when the car app comes to the foreground,
     * and switch to the most recently played cross-device episode when it is genuinely newer (the
     * adopt guard lives in the sync). The phone does this through AppLifecycleObserver, but the car
     * does not run that observer (it also drives ads and promo notifications), so register a minimal
     * process-lifecycle observer here that does only the pull.
     */
    private fun setupForegroundSync() {
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            object : DefaultLifecycleObserver {
                override fun onStart(owner: LifecycleOwner) {
                    podHopperPositionSync.pullLatestPositions(adoptCurrentEpisode = true)
                }
            },
        )
    }

    private fun setupRemoteLogging() {
        initializeRemoteLogging()
    }

    private fun setupLogging() {
        LogBuffer.setup(File(filesDir, "logs").absolutePath)
        if (BuildConfig.DEBUG) {
            Timber.plant(TimberDebugTree())
        }
    }

    private fun setupFeatureFlags() {
        val providers = if (BuildConfig.DEBUG || BuildConfig.IS_PROTOTYPE) {
            listOf(preferencesFeatureProvider)
        } else {
            listOf(
                firebaseRemoteFeatureProvider,
                defaultReleaseFeatureProvider,
            )
        }
        FeatureFlag.initialize(providers)
    }

    private fun setupAnalytics() {
        analyticsController.clearAllData()
        experimentProvider.initialize()
    }
}
