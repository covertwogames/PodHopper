package au.com.shiftyjelly.pocketcasts.profile

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.models.type.SignInState
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperPositionSync
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperSubscriptionSync
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import au.com.shiftyjelly.pocketcasts.repositories.user.StatsManager
import au.com.shiftyjelly.pocketcasts.repositories.user.UserManager
import au.com.shiftyjelly.pocketcasts.utils.Gravatar
import au.com.shiftyjelly.pocketcasts.utils.featureflag.Feature
import au.com.shiftyjelly.pocketcasts.utils.featureflag.FeatureFlag
import au.com.shiftyjelly.pocketcasts.utils.toDurationFromNow
import com.automattic.eventhorizon.DownloadsShownEvent
import com.automattic.eventhorizon.EventHorizon
import com.automattic.eventhorizon.ListeningHistoryShownEvent
import com.automattic.eventhorizon.ProfileAccountButtonTappedEvent
import com.automattic.eventhorizon.ProfileBookmarksShowEvent
import com.automattic.eventhorizon.ProfileRefreshButtonTappedEvent
import com.automattic.eventhorizon.ProfileSettingsButtonTappedEvent
import com.automattic.eventhorizon.ProfileShownEvent
import com.automattic.eventhorizon.SettingsHelpShownEvent
import com.automattic.eventhorizon.StarredShownEvent
import com.automattic.eventhorizon.StatsShownEvent
import com.automattic.eventhorizon.UploadedFilesShownEvent
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.reactive.asFlow

@HiltViewModel
class ProfileViewModel @Inject constructor(
    private val settings: Settings,
    private val podcastManager: PodcastManager,
    private val statsManager: StatsManager,
    private val userManager: UserManager,
    private val eventHorizon: EventHorizon,
    private val supabaseClient: SupabaseClient,
    private val positionSync: PodHopperPositionSync,
    private val subscriptionSync: PodHopperSubscriptionSync,
) : ViewModel() {
    private val refreshStatsTrigger = MutableSharedFlow<Unit>(extraBufferCapacity = 1)

    private val signInState = userManager.getSignInState().asFlow().stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = SignInState.SignedOut,
    )
    private val sharingFeatureFlag = FeatureFlag.isEnabledFlow(Feature.PROFILE_SHARING)

    internal val isSignedIn get() = signInState.value.isSignedIn

    internal val profileHeaderState = combine(
        signInState,
        sharingFeatureFlag,
    ) { state, isProfileSharingEnabled ->
        when (state) {
            is SignInState.SignedIn -> ProfileHeaderState(
                imageUrl = Gravatar.getUrl(state.email),
                subscriptionTier = state.subscription?.tier,
                email = state.email,
                expiresIn = state.subscription?.expiryDate?.toDurationFromNow(),
                isShareVisible = isProfileSharingEnabled,
            )

            is SignInState.SignedOut -> ProfileHeaderState(
                imageUrl = null,
                subscriptionTier = null,
                email = null,
                expiresIn = null,
                isShareVisible = false,
            )
        }
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ProfileHeaderState(
            imageUrl = null,
            subscriptionTier = null,
            email = null,
            expiresIn = null,
            isShareVisible = false,
        ),
    )

    internal val profileStatsState = combine(
        refreshStatsTrigger.onStart { emit(Unit) },
        podcastManager.countSubscribedFlow(),
    ) { _, count ->
        ProfileStatsState(
            podcastsCount = count,
            listenedDuration = statsManager.mergedTotalListeningTimeSec.seconds,
            savedDuration = statsManager.mergedTotalTimeSaved.seconds,
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = ProfileStatsState(
            podcastsCount = 0,
            listenedDuration = Duration.ZERO,
            savedDuration = Duration.ZERO,
        ),
    )

    internal val podHopperAccountState = combine(
        settings.podhopperRefreshToken.flow,
        settings.podhopperEmail.flow,
    ) { refreshToken, email ->
        PodHopperAccountState(
            isSignedIn = refreshToken.isNotEmpty(),
            email = email.ifEmpty { null },
        )
    }.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = PodHopperAccountState(isSignedIn = false, email = null),
    )

    internal val refreshState = settings.refreshStateFlow

    internal val miniPlayerInset = settings.bottomInset.stateIn(
        scope = viewModelScope,
        started = SharingStarted.Eagerly,
        initialValue = 0,
    )

    internal fun onScreenShown() {
        eventHorizon.track(ProfileShownEvent)
    }

    internal fun onSettingsClick() {
        eventHorizon.track(ProfileSettingsButtonTappedEvent)
    }

    internal fun onHeaderClick() {
        eventHorizon.track(ProfileAccountButtonTappedEvent)
    }

    internal fun logoutPodHopper() {
        supabaseClient.logout()
        // Forget this device's sync bookkeeping so signing into a different account next starts
        // clean. No on-device podcast, episode, or download data is touched.
        positionSync.clearLocalSyncState()
        subscriptionSync.clearLocalSyncState()
    }

    internal fun onShareClick() {
        // Placeholder for share action
    }

    internal fun refreshStats() {
        refreshStatsTrigger.tryEmit(Unit)
    }

    internal fun onSectionClick(section: ProfileSection) {
        val event = when (section) {
            ProfileSection.Stats -> StatsShownEvent
            ProfileSection.Downloads -> DownloadsShownEvent
            ProfileSection.CloudFiles -> UploadedFilesShownEvent
            ProfileSection.Starred -> StarredShownEvent
            ProfileSection.Bookmarks -> ProfileBookmarksShowEvent
            ProfileSection.ListeningHistory -> ListeningHistoryShownEvent
            ProfileSection.Help -> SettingsHelpShownEvent
        }
        eventHorizon.track(event)
    }

    internal fun refreshProfile() {
        eventHorizon.track(ProfileRefreshButtonTappedEvent)
        podcastManager.refreshPodcasts("profile")
    }

    internal fun clearFailedRefresh() {
        val lastSuccess = settings.getLastSuccessRefreshState()
        if (settings.getRefreshState() is RefreshState.Failed && lastSuccess != null) {
            settings.setRefreshState(lastSuccess)
        }
    }
}
