package au.com.shiftyjelly.pocketcasts.profile

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper.PodHopperOnboardingActivity
import au.com.shiftyjelly.pocketcasts.analytics.SourceView
import au.com.shiftyjelly.pocketcasts.compose.CallOnce
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.player.view.bookmark.BookmarksContainerFragment
import au.com.shiftyjelly.pocketcasts.podcasts.view.ProfileEpisodeListFragment
import au.com.shiftyjelly.pocketcasts.settings.HelpFeedbackFragment
import au.com.shiftyjelly.pocketcasts.profile.cloud.CloudFilesFragment
import au.com.shiftyjelly.pocketcasts.settings.SettingsFragment
import au.com.shiftyjelly.pocketcasts.settings.stats.StatsFragment
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.views.fragments.TopScrollable
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch

@AndroidEntryPoint
class ProfileFragment :
    BaseFragment(),
    TopScrollable {
    private val profileViewModel by viewModels<ProfileViewModel>()

    private val scrollToTopSignal = MutableSharedFlow<Unit>()

    private var getCanScrollBackward: () -> Boolean = { false }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        CallOnce {
            profileViewModel.onScreenShown()
        }
        val state = ProfilePageState(
            sections = ProfileSection.visibleEntries(),
            miniPlayerPadding = profileViewModel.miniPlayerInset.collectAsState().value.pxToDp(requireContext()).dp,
            podHopperAccount = profileViewModel.podHopperAccountState.collectAsState().value,
            statsState = profileViewModel.profileStatsState.collectAsState().value,
            refreshState = profileViewModel.refreshState.collectAsState().value,
        )

        val listState = rememberLazyListState()

        getCanScrollBackward = { listState.canScrollBackward }

        LaunchedEffect(listState) {
            scrollToTopSignal.collectLatest {
                listState.animateScrollToItem(0)
            }
        }

        ProfilePage(
            state = state,
            themeType = theme.activeTheme,
            listState = listState,
            onSettingsClick = {
                profileViewModel.onSettingsClick()
                fragmentHostListener.addFragment(SettingsFragment())
            },
            onLoginClick = {
                startActivity(PodHopperOnboardingActivity.newInstance(requireContext(), loginOnly = true))
            },
            onLogoutClick = {
                profileViewModel.logoutPodHopper()
            },
            onSectionClick = { section ->
                goToSection(section)
            },
            onRefreshClick = {
                profileViewModel.refreshProfile()
            },
            modifier = Modifier.fillMaxSize(),
        )
    }

    override fun onDestroyView() {
        getCanScrollBackward = { false }
        super.onDestroyView()
    }

    override fun onResume() {
        super.onResume()
        profileViewModel.clearFailedRefresh()
    }

    private val fragmentHostListener get() = requireActivity() as FragmentHostListener

    private fun goToSection(section: ProfileSection) {
        profileViewModel.onSectionClick(section)
        val fragment = when (section) {
            ProfileSection.Stats -> StatsFragment()
            ProfileSection.Downloads -> ProfileEpisodeListFragment.newInstance(ProfileEpisodeListFragment.Mode.Downloaded)
            ProfileSection.CloudFiles -> CloudFilesFragment()
            ProfileSection.Starred -> ProfileEpisodeListFragment.newInstance(ProfileEpisodeListFragment.Mode.Starred)
            ProfileSection.Bookmarks -> BookmarksContainerFragment.newInstance(sourceView = SourceView.PROFILE)
            ProfileSection.ListeningHistory -> ProfileEpisodeListFragment.newInstance(ProfileEpisodeListFragment.Mode.History)
            ProfileSection.Help -> HelpFeedbackFragment()
        }
        fragmentHostListener.addFragment(fragment)
    }

    override fun onBackPressed(): Boolean {
        profileViewModel.refreshStats()
        return super.onBackPressed()
    }

    override fun scrollToTop(): Boolean {
        val canScroll = getCanScrollBackward()
        if (canScroll) {
            lifecycleScope.launch {
                scrollToTopSignal.emit(Unit)
            }
        }

        return canScroll
    }
}
