package au.com.shiftyjelly.pocketcasts.shared

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import au.com.shiftyjelly.pocketcasts.analytics.AppLifecycleAnalytics
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.ads.BlazeAdsManager
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationScheduler
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperPositionSync
import au.com.shiftyjelly.pocketcasts.utils.AppPlatform
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.DefaultReleaseFeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.featureflag.providers.PreferencesFeatureProvider
import au.com.shiftyjelly.pocketcasts.utils.getVersionCode
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class AppLifecycleObserver(
    @ApplicationContext private val appContext: Context,
    private val appLifecycleAnalytics: AppLifecycleAnalytics,
    private val appLifecycleOwner: LifecycleOwner = ProcessLifecycleOwner.get(),
    private val appLifecycleProviderImpl: AppLifecycleProviderImpl,
    private val applicationScope: CoroutineScope,
    private val blazeAdsManager: BlazeAdsManager,
    private val defaultReleaseFeatureProvider: DefaultReleaseFeatureProvider,
    private val networkConnectionWatcher: NetworkConnectionWatcherImpl,
    private val versionCode: Int,
    private val preferencesFeatureProvider: PreferencesFeatureProvider,
    private val settings: Settings,
    private val notificationScheduler: NotificationScheduler,
    private val podHopperPositionSync: PodHopperPositionSync,
) : DefaultLifecycleObserver {

    @Inject
    constructor(
        @ApplicationContext appContext: Context,
        @ApplicationScope applicationScope: CoroutineScope,
        appLifecycleAnalytics: AppLifecycleAnalytics,
        appLifecycleProviderImpl: AppLifecycleProviderImpl,
        blazeAdsManager: BlazeAdsManager,
        defaultReleaseFeatureProvider: DefaultReleaseFeatureProvider,
        networkConnectionWatcher: NetworkConnectionWatcherImpl,
        preferencesFeatureProvider: PreferencesFeatureProvider,
        settings: Settings,
        notificationScheduler: NotificationScheduler,
        podHopperPositionSync: PodHopperPositionSync,
    ) : this(
        appContext = appContext,
        applicationScope = applicationScope,
        appLifecycleAnalytics = appLifecycleAnalytics,
        appLifecycleOwner = ProcessLifecycleOwner.get(),
        appLifecycleProviderImpl = appLifecycleProviderImpl,
        blazeAdsManager = blazeAdsManager,
        defaultReleaseFeatureProvider = defaultReleaseFeatureProvider,
        networkConnectionWatcher = networkConnectionWatcher,
        versionCode = appContext.getVersionCode(),
        preferencesFeatureProvider = preferencesFeatureProvider,
        settings = settings,
        notificationScheduler = notificationScheduler,
        podHopperPositionSync = podHopperPositionSync,
    )

    private var reconcileJob: Job? = null

    fun setup() {
        appLifecycleOwner.lifecycle.addObserver(this)
        appLifecycleOwner.lifecycle.addObserver(appLifecycleProviderImpl)
        handleNewInstallOrUpgrade()
        setupFeatureFlags()
        networkConnectionWatcher.startWatching()
        applicationScope.launch {
            notificationScheduler.setupReEngagementNotification()
            // PodHopper: the promotional notification categories (Recommendations, New features,
            // Offers) were removed from settings. Never schedule them, and cancel any that an
            // earlier build may have scheduled so existing installs self heal on next launch.
            notificationScheduler.cancelScheduledTrendingAndRecommendationsNotifications()
            notificationScheduler.cancelScheduledNewFeaturesAndTipsNotifications()
            notificationScheduler.cancelScheduledOffersNotifications()
        }
    }

    override fun onStart(owner: LifecycleOwner) {
        super.onStart(owner)
        // PodHopper: reconcile the now-playing window when the app comes to the foreground, so
        // opening the app reflects progress made on other devices and switches the player to the
        // most recently played episode (when the setting is on and not actively playing).
        podHopperPositionSync.reconcileNowPlaying()
        // PodHopper: while the app stays in the foreground, keep the now-playing window in sync by
        // reconciling every RECONCILE_INTERVAL_MS. The reconcile is quiet (it updates the episode
        // and position only when not actively playing, and never forces the player open) and stops
        // when the app leaves the foreground (onStop cancels the job).
        reconcileJob?.cancel()
        reconcileJob = applicationScope.launch {
            while (isActive) {
                delay(RECONCILE_INTERVAL_MS)
                podHopperPositionSync.reconcileNowPlaying()
            }
        }
        applicationScope.launch {
            blazeAdsManager.updateAds()
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        super.onStop(owner)
        // PodHopper: stop the foreground reconcile timer when the app leaves the foreground.
        reconcileJob?.cancel()
        reconcileJob = null
    }

    override fun onResume(owner: LifecycleOwner) {
        super.onResume(owner)
        appLifecycleAnalytics.onApplicationEnterForeground()
    }

    override fun onPause(owner: LifecycleOwner) {
        super.onPause(owner)
        appLifecycleAnalytics.onApplicationEnterBackground()
    }

    override fun onDestroy(owner: LifecycleOwner) {
        applicationScope.cancel("Application onTerminate")
        networkConnectionWatcher.stopWatching()
        super.onDestroy(owner)
    }

    private fun setupFeatureFlags() {
        val providers: List<FeatureProvider> = if (BuildConfig.DEBUG || BuildConfig.IS_PROTOTYPE) {
            listOf(preferencesFeatureProvider)
        } else {
            listOf(defaultReleaseFeatureProvider)
        }
        FeatureFlag.initialize(providers)
    }

    private fun handleNewInstallOrUpgrade() {
        // Track app upgrade and install
        val previousVersionCode = settings.getMigratedVersionCode()

        val isNewInstall = previousVersionCode == 0
        if (isNewInstall) {
            appLifecycleAnalytics.onNewApplicationInstall()

            // new installs default to not forcing up next to use the dark theme
            settings.useDarkUpNextTheme.set(false, updateModifiedAt = false)

            // new installations default to not displaying the tooltip
            settings.showPodcastsRecentlyPlayedSortOrderTooltip.set(false, updateModifiedAt = false)

            settings.showFreeAccountEncouragement.set(false, updateModifiedAt = false)

            when (getAppPlatform()) {
                // do nothing because this already defaults to true for all users on automotive
                AppPlatform.Automotive -> {}

                // do nothing because feature has not been enabled on Wear OS yet
                AppPlatform.WearOs -> {}

                AppPlatform.Phone -> {
                    // For new users we want to auto play when the queue is empty by default
                    settings.autoPlayNextEpisodeOnEmpty.set(true, updateModifiedAt = false)

                    // For new users we want to auto download on follow podcast by default
                    // If condition is a solution for development initialization scripts. Internal-ref: p1764840798661999/1764122715.191139-slack-C028JAG44VD
                    if (!settings.contains(settings.autoDownloadOnFollowPodcast.sharedPrefKey)) {
                        settings.autoDownloadOnFollowPodcast.set(true, updateModifiedAt = false)
                    }

                    // For new users we enable new episode and daily reminder notifications by default.
                    // The promotional categories (Recommendations, New features, Offers) were removed
                    // from PodHopper, so they are intentionally left at their false default.
                    settings.notifyRefreshPodcast.set(true, updateModifiedAt = false)
                    settings.dailyRemindersNotification.set(true, updateModifiedAt = false)

                    notificationScheduler.setupOnboardingNotifications()
                }
            }
        } else if (previousVersionCode < versionCode) {
            appLifecycleAnalytics.onApplicationUpgrade(previousVersionCode)
        }
    }

    @VisibleForTesting
    fun getAppPlatform() = Util.getAppPlatform(appContext)

    companion object {
        private const val RECONCILE_INTERVAL_MS = 30_000L
    }
}
