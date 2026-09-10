package au.com.shiftyjelly.pocketcasts.repositories.user

import android.content.Context
import au.com.shiftyjelly.pocketcasts.analytics.AccountStatusInfo
import au.com.shiftyjelly.pocketcasts.analytics.AnalyticsController
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.analytics.experiments.ExperimentProvider
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.models.db.dao.PlaylistDao
import au.com.shiftyjelly.pocketcasts.models.type.SignInState
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.endofyear.EndOfYearSync
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationScheduler
import au.com.shiftyjelly.pocketcasts.repositories.notification.NotificationSchedulerImpl.Companion.TAG_TRENDING_RECOMMENDATIONS
import au.com.shiftyjelly.pocketcasts.repositories.notification.TrendingAndRecommendationsNotificationType
import au.com.shiftyjelly.pocketcasts.repositories.playback.PlaybackManager
import au.com.shiftyjelly.pocketcasts.repositories.playback.UpNextQueue
import au.com.shiftyjelly.pocketcasts.repositories.playlist.DefaultPlaylistsInitializer
import au.com.shiftyjelly.pocketcasts.repositories.podcast.EpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.FolderManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podcast.UserEpisodeManager
import au.com.shiftyjelly.pocketcasts.repositories.searchhistory.SearchHistoryManager
import au.com.shiftyjelly.pocketcasts.repositories.subscription.SubscriptionManager
import au.com.shiftyjelly.pocketcasts.repositories.sync.SyncManager
import au.com.shiftyjelly.pocketcasts.utils.Optional
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.UserSignedOutEvent
import dagger.hilt.android.qualifiers.ApplicationContext
import io.reactivex.BackpressureStrategy
import io.reactivex.Flowable
import io.reactivex.Single
import io.reactivex.rxkotlin.combineLatest
import javax.inject.Inject
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.rx2.asFlowable
import kotlinx.coroutines.rx2.rxSingle
import timber.log.Timber

interface UserManager {

    fun getSignInState(): Flowable<SignInState>
    fun signOut(playbackManager: PlaybackManager, wasInitiatedByUser: Boolean)
    fun signOutAndClearData(playbackManager: PlaybackManager, upNextQueue: UpNextQueue, folderManager: FolderManager, searchHistoryManager: SearchHistoryManager, episodeManager: EpisodeManager, wasInitiatedByUser: Boolean)
}

class UserManagerImpl @Inject constructor(
    @ApplicationContext val application: Context,
    val settings: Settings,
    val syncManager: SyncManager,
    val subscriptionManager: SubscriptionManager,
    val podcastManager: PodcastManager,
    val userEpisodeManager: UserEpisodeManager,
    private val playlistDao: PlaylistDao,
    private val playlistsInitializer: DefaultPlaylistsInitializer,
    private val analyticsController: AnalyticsController,
    private val eventHorizon: EventHorizon,
    private val accountStatusInfo: AccountStatusInfo,
    @ApplicationScope private val applicationScope: CoroutineScope,
    private val experimentProvider: ExperimentProvider,
    private val endOfYearSync: EndOfYearSync,
    private val notificationScheduler: NotificationScheduler,
) : UserManager,
    CoroutineScope {

    companion object {
        private const val KEY_USER_INITIATED = "user_initiated"
    }

    override val coroutineContext: CoroutineContext
        get() = Dispatchers.Default

    // PodHopper: account monitoring removed. Upstream watched Android's AccountManager so that
    // removing the Pocket Casts account from system settings signed the user out of the app. The
    // de-branding deleted that account authenticator, so no account can ever exist, and the check
    // fired on every launch and signed the user out unconditionally. On one phone it ran 42 times
    // in a single log. It never touched PodHopper's own sign-in, the Up Next queue or playback
    // positions, so the damage was limited, but it did clear Plus preferences, cloud file status
    // and marketing opt-in on the first run and left the UI mid-transition. Nothing replaces it:
    // PodHopper accounts are signed out from the app's own profile screen, which calls signOut
    // directly.

    override fun getSignInState(): Flowable<SignInState> {
        return syncManager.isLoggedInObservable.toFlowable(BackpressureStrategy.LATEST)
            .switchMap { isLoggedIn ->
                if (isLoggedIn) {
                    launch(coroutineContext) {
                        notificationScheduler.setupTrendingAndRecommendationsNotifications()
                    }

                    settings.cachedSubscription.flow
                        .map { Optional.of(it) }
                        .asFlowable()
                        .flatMapSingle { maybeSubscription ->
                            if (maybeSubscription.isPresent()) {
                                Single.just(maybeSubscription)
                            } else {
                                rxSingle { Optional.of(subscriptionManager.fetchFreshSubscription()) }
                            }
                        }
                        .combineLatest(syncManager.emailFlowable())
                        .map { (maybeSubscription, maybeEmail) ->
                            analyticsController.refreshMetadata()
                            SignInState.SignedIn(email = maybeEmail.get() ?: "", subscription = maybeSubscription.get())
                        }
                        .onErrorReturn {
                            Timber.e(it, "Error getting subscription state")
                            SignInState.SignedIn(syncManager.getEmail() ?: "", subscription = null)
                        }
                } else {
                    launch(coroutineContext) {
                        notificationScheduler.cancelScheduledWorksByTag(listOf("$TAG_TRENDING_RECOMMENDATIONS-${TrendingAndRecommendationsNotificationType.Recommendations.subcategory}"))
                    }
                    Flowable.just(SignInState.SignedOut)
                }
            }
    }

    override fun signOut(playbackManager: PlaybackManager, wasInitiatedByUser: Boolean) {
        if (wasInitiatedByUser || !settings.getFullySignedOut()) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Signing out")
            applicationScope.launch {
                syncManager.signOut {
                    settings.clearPlusPreferences()

                    userEpisodeManager.removeCloudStatusFromFiles(playbackManager)

                    settings.marketingOptIn.set(false, updateModifiedAt = false)

                    eventHorizon.track(
                        UserSignedOutEvent(
                            userInitiated = wasInitiatedByUser,
                        ),
                    )
                    analyticsController.flush()
                    analyticsController.clearAllData()
                    analyticsController.refreshMetadata()

                    val anonId = accountStatusInfo.recreateAnonId()
                    experimentProvider.refreshExperiments(anonId)

                    settings.setEndOfYearShowModal(true)
                    endOfYearSync.reset()
                }
                subscriptionManager.clearCachedMembership()
            }
        }
        settings.setFullySignedOut(true)
    }

    override fun signOutAndClearData(
        playbackManager: PlaybackManager,
        upNextQueue: UpNextQueue,
        folderManager: FolderManager,
        searchHistoryManager: SearchHistoryManager,
        episodeManager: EpisodeManager,
        wasInitiatedByUser: Boolean,
    ) {
        // Sign out first to make sure no data changes get synced
        signOut(playbackManager = playbackManager, wasInitiatedByUser = wasInitiatedByUser)

        // Need to stop playback before we start clearing data
        playbackManager.removeEpisode(
            episodeToRemove = playbackManager.getCurrentEpisode(),
            // Unknown is fine here because we don't send analytics when the user did not initiate the action
            source = SourceView.UNKNOWN,
            userInitiated = false,
        )

        // Block while clearing data so that users cannot interact with the app until we're done clearing data
        runBlocking(Dispatchers.IO) {
            upNextQueue.removeAllIncludingChanges()

            playlistDao.deleteAllPlaylists()
            playlistsInitializer.initialize(force = true)
            folderManager.deleteAll()
            searchHistoryManager.clearAll()

            podcastManager.deleteAllPodcasts()

            userEpisodeManager.findUserEpisodes().forEach {
                userEpisodeManager.delete(episode = it, playbackManager = playbackManager)
            }
            episodeManager.deleteAll(SourceView.UNKNOWN)
        }
    }
}
