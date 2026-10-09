package au.com.shiftyjelly.pocketcasts.profile

import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.AppBarDefaults
import androidx.compose.material.Icon
import androidx.compose.material.IconButton
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.compose.AppTheme
import au.com.shiftyjelly.pocketcasts.compose.PreviewOrientation
import au.com.shiftyjelly.pocketcasts.compose.components.HorizontalDivider
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.images.R
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import java.util.Date
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.minutes

@Composable
internal fun ProfilePage(
    state: ProfilePageState,
    themeType: Theme.ThemeType,
    onSettingsClick: () -> Unit,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    onSectionClick: (ProfileSection) -> Unit,
    onRefreshClick: () -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
) {
    val isPortrait = LocalConfiguration.current.orientation != Configuration.ORIENTATION_LANDSCAPE
    AppTheme(themeType) {
        Column(
            modifier = modifier
                .fillMaxSize()
                .background(MaterialTheme.theme.colors.primaryUi02),
        ) {
            Toolbar(
                onSettingsClick = onSettingsClick,
            )
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
            ) {
                item {
                    VerticalSpacer()
                }
                headerWithStats(
                    podHopperAccount = state.podHopperAccount,
                    statsState = state.statsState,
                    onLoginClick = onLoginClick,
                    onLogoutClick = onLogoutClick,
                    isPortrait = isPortrait,
                )
                item {
                    VerticalSpacer()
                }
                item {
                    HorizontalDivider()
                }
                item {
                    ProfileSections(
                        sections = state.sections,
                        onClick = onSectionClick,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                item {
                    VerticalSpacer()
                }
                item {
                    var localRefreshState by remember(state.refreshState) { mutableStateOf(state.refreshState) }
                    RefreshSection(
                        refreshState = localRefreshState,
                        onClick = {
                            localRefreshState = RefreshState.Refreshing
                            onRefreshClick()
                        },
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = horizontalPadding),
                    )
                }
                item {
                    VerticalSpacer()
                }
                item {
                    MiniPlayerPadding(
                        padding = state.miniPlayerPadding,
                    )
                }
            }
        }
    }
}

internal data class ProfilePageState(
    val sections: List<ProfileSection>,
    val miniPlayerPadding: Dp,
    val podHopperAccount: PodHopperAccountState,
    val statsState: ProfileStatsState,
    val refreshState: RefreshState,
)

private val horizontalPadding = 16.dp
private val verticalSpacing = 16.dp

@Composable
private fun VerticalSpacer() {
    Spacer(
        modifier = Modifier.height(verticalSpacing),
    )
}

@Composable
private fun Toolbar(
    onSettingsClick: () -> Unit,
) {
    Row(
        horizontalArrangement = Arrangement.End,
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .background(MaterialTheme.theme.colors.secondaryUi01)
            .windowInsetsPadding(AppBarDefaults.topAppBarWindowInsets)
            .height(56.dp)
            .padding(horizontal = horizontalPadding),
    ) {
        IconButton(
            onClick = onSettingsClick,
        ) {
            Icon(
                painter = painterResource(R.drawable.ic_profile_settings),
                contentDescription = stringResource(au.com.shiftyjelly.pocketcasts.localization.R.string.settings),
                tint = MaterialTheme.theme.colors.secondaryIcon01,
            )
        }
    }
}

private fun LazyListScope.headerWithStats(
    podHopperAccount: PodHopperAccountState,
    statsState: ProfileStatsState,
    onLoginClick: () -> Unit,
    onLogoutClick: () -> Unit,
    isPortrait: Boolean,
) {
    if (isPortrait) {
        item {
            PodHopperProfileHeader(
                account = podHopperAccount,
                onLoginClick = onLoginClick,
                onLogoutClick = onLogoutClick,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
            )
        }
        item {
            VerticalSpacer()
        }
        item {
            ProfileStats(
                state = statsState,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
            )
        }
        item {
            VerticalSpacer()
        }
    } else {
        item {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = horizontalPadding),
            ) {
                PodHopperProfileHeader(
                    account = podHopperAccount,
                    onLoginClick = onLoginClick,
                    onLogoutClick = onLogoutClick,
                    modifier = Modifier.weight(1f),
                )
                ProfileStats(
                    state = statsState,
                    modifier = Modifier.weight(1f),
                )
            }
        }
        item {
            VerticalSpacer()
        }
    }
}

@Composable
private fun MiniPlayerPadding(
    padding: Dp,
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .height(padding),
    )
}

@PreviewOrientation
@Composable
private fun ProfilePagePreview() {
    ProfilePageStub(Theme.ThemeType.ROSE)
}

@Preview
@Composable
private fun ProfilePageThemePreview(
    @PreviewParameter(ThemePreviewParameterProvider::class) theme: Theme.ThemeType,
) {
    ProfilePageStub(theme)
}

@Composable
private fun ProfilePageStub(
    theme: Theme.ThemeType,
) {
    ProfilePage(
        state = ProfilePageState(
            sections = ProfileSection.entries,
            miniPlayerPadding = 64.dp,
            podHopperAccount = PodHopperAccountState(
                isSignedIn = true,
                email = "you@example.com",
            ),
            statsState = ProfileStatsState(
                podcastsCount = 50,
                listenedDuration = 75.hours,
                savedDuration = 35.minutes,
            ),
            refreshState = RefreshState.Success(Date()),
        ),
        themeType = theme,
        onSettingsClick = {},
        onLoginClick = {},
        onLogoutClick = {},
        onSectionClick = {},
        onRefreshClick = {},
    )
}
