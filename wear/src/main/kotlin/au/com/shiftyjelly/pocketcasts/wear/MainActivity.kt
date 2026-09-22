package au.com.shiftyjelly.pocketcasts.wear

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import androidx.navigation.NavController
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.navArgument
import androidx.wear.compose.foundation.SwipeToDismissBoxState
import androidx.wear.compose.foundation.rememberSwipeToDismissBoxState
import androidx.wear.compose.navigation.SwipeDismissableNavHost
import androidx.wear.compose.navigation.composable
import androidx.wear.compose.navigation.rememberSwipeDismissableNavController
import androidx.wear.compose.navigation.rememberSwipeDismissableNavHostState
import androidx.wear.tooling.preview.devices.WearDevices
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperPositionSync
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperSubscriptionSync
import au.com.shiftyjelly.pocketcasts.wear.theme.WearAppTheme
import au.com.shiftyjelly.pocketcasts.wear.ui.FilesScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.WatchListScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.authentication.PairingScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.component.NowPlayingPager
import au.com.shiftyjelly.pocketcasts.wear.ui.component.TimeTextWithConnectivity
import au.com.shiftyjelly.pocketcasts.wear.ui.downloads.DownloadsScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.episode.EpisodeScreenFlow
import au.com.shiftyjelly.pocketcasts.wear.ui.episode.EpisodeScreenFlow.episodeGraph
import au.com.shiftyjelly.pocketcasts.wear.ui.player.EffectsScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.player.NowPlayingScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.player.PCVolumeScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.player.StreamingConfirmationScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.playlist.PlaylistScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.playlists.PlaylistsScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.podcast.PodcastScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.podcasts.PodcastsScreen
import au.com.shiftyjelly.pocketcasts.wear.ui.settings.settingsRoutes
import au.com.shiftyjelly.pocketcasts.wear.ui.starred.StarredScreen
import com.google.android.horologist.compose.layout.AppScaffold
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import kotlinx.coroutines.launch

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    private val viewModel: WearMainActivityViewModel by viewModels()

    @Inject lateinit var podHopperPositionSync: PodHopperPositionSync

    @Inject lateinit var podHopperSubscriptionSync: PodHopperSubscriptionSync

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setTheme(android.R.style.Theme_DeviceDefault)
        setContent {
            WearAppTheme {
                val state by viewModel.state.collectAsState()

                WearApp(
                    isSignedIn = state.isSignedIn,
                    isConnected = state.isConnected,
                    onNavigate = podHopperSubscriptionSync::pollSubscriptions,
                )
            }
        }
    }

    override fun onStart() {
        super.onStart()

        // PodHopper: pull the latest cross device positions when the app comes to the foreground.
        podHopperPositionSync.pullLatestPositions()

        // PodHopper: pull subscription changes too, so a podcast added on another device shows up
        // here without subscribing to anything on the watch.
        podHopperSubscriptionSync.pullSubscriptions()

        // PodHopper: while the app is in the foreground, poll every 30s so an open watch notices
        // subscription changes from other devices without needing to be reopened.
        podHopperSubscriptionSync.startPeriodicSync()
    }

    override fun onResume() {
        super.onResume()
        viewModel.refreshPodcasts()
    }

    override fun onStop() {
        super.onStop()

        // PodHopper: stop the foreground subscription poll loop while backgrounded.
        podHopperSubscriptionSync.stopPeriodicSync()
    }
}

@Composable
private fun WearApp(
    isSignedIn: Boolean,
    isConnected: Boolean,
    onNavigate: () -> Unit,
) {
    val navController = rememberSwipeDismissableNavController()
    val swipeToDismissState = rememberSwipeToDismissBoxState()
    val navState = rememberSwipeDismissableNavHostState(swipeToDismissState)

    // PodHopper: check for subscription changes from other devices whenever the user navigates, as
    // the phone does. Throttled, and a no-op while signed out, inside the sync class.
    val currentOnNavigate by rememberUpdatedState(onNavigate)
    DisposableEffect(navController) {
        val listener = NavController.OnDestinationChangedListener { _, _, _ -> currentOnNavigate() }
        navController.addOnDestinationChangedListener(listener)
        onDispose { navController.removeOnDestinationChangedListener(listener) }
    }

    // PodHopper: the watch needs a PodHopper account, so it shows the pairing screen until one is
    // signed in. Signing in or out changes the start destination, which resets the back stack, so
    // pairing lands on the main screen and signing out returns to pairing.
    val startDestination = if (isSignedIn) WatchListScreen.ROUTE else PairingScreen.ROUTE

    AppScaffold(
        timeText = {
            TimeTextWithConnectivity(isConnected = isConnected)
        },
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            SwipeDismissableNavHost(
                startDestination = startDestination,
                navController = navController,
                state = navState,
            ) {
                composable(
                    route = PairingScreen.ROUTE,
                ) {
                    PairingScreen()
                }

                composable(
                    route = WatchListScreen.ROUTE,
                ) {
                    NowPlayingPager(
                        allowSwipeToDismiss = false,
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        val scope = rememberCoroutineScope()
                        WatchListScreen(
                            columnState = columnState,
                            navigateToRoute = navController::navigate,
                            toNowPlaying = {
                                scope.launch {
                                    pagerState.animateScrollToPage(NowPlayingScreen.PAGER_INDEX)
                                }
                            },
                        )
                    }
                }

                composable(
                    route = PCVolumeScreen.ROUTE,
                ) {
                    PCVolumeScreen()
                }

                composable(
                    route = StreamingConfirmationScreen.ROUTE,
                ) {
                    StreamingConfirmationScreen(
                        onFinish = { result ->
                            navController.previousBackStackEntry?.savedStateHandle?.set(
                                StreamingConfirmationScreen.RESULT_KEY,
                                result,
                            )
                            navController.popBackStack()
                        },
                    )
                }

                composable(
                    route = PodcastsScreen.ROUTE_HOME_FOLDER,
                ) {
                    PodcastsScreenContent(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    )
                }

                composable(
                    route = PodcastsScreen.ROUTE_FOLDER,
                    arguments = listOf(
                        navArgument(PodcastsScreen.ARGUMENT_FOLDER_UUID) {
                            type = NavType.StringType
                        },
                    ),
                ) {
                    PodcastsScreenContent(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    )
                }

                composable(
                    route = PodcastScreen.ROUTE,
                    arguments = listOf(
                        navArgument(PodcastScreen.ARGUMENT) {
                            type = NavType.StringType
                        },
                    ),
                ) {
                    NowPlayingPager(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        PodcastScreen(
                            columnState = columnState,
                            onEpisodeTap = { episode ->
                                navController.navigate(EpisodeScreenFlow.navigateRoute(episodeUuid = episode.uuid))
                            },
                        )
                    }
                }

                episodeGraph(
                    navigateToPodcast = { podcastUuid ->
                        navController.navigate(PodcastScreen.navigateRoute(podcastUuid))
                    },
                    navController = navController,
                    swipeToDismissState = swipeToDismissState,
                )

                composable(PlaylistsScreen.ROUTE) {
                    NowPlayingPager(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        PlaylistsScreen(
                            onClickPlaylist = { playlist ->
                                navController.navigate(PlaylistScreen.navigateRoute(playlist.uuid, playlist.type))
                            },
                            columnState = columnState,
                        )
                    }
                }

                composable(
                    route = PlaylistScreen.ROUTE,
                    arguments = listOf(
                        navArgument(PlaylistScreen.ARGUMENT_PLAYLIST_UUID) {
                            type = NavType.StringType
                        },
                        navArgument(PlaylistScreen.ARGUMENT_PLAYLIST_TYPE) {
                            type = NavType.StringType
                        },
                    ),
                ) {
                    NowPlayingPager(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        PlaylistScreen(
                            onEpisodeTap = { episode ->
                                navController.navigate(EpisodeScreenFlow.navigateRoute(episodeUuid = episode.uuid))
                            },
                            columnState = columnState,
                        )
                    }
                }

                composable(DownloadsScreen.ROUTE) {
                    NowPlayingPager(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        DownloadsScreen(
                            columnState = columnState,
                            onItemClick = { episode ->
                                val route = EpisodeScreenFlow.navigateRoute(episodeUuid = episode.uuid)
                                navController.navigate(route)
                            },
                        )
                    }
                }

                composable(FilesScreen.ROUTE) {
                    NowPlayingPager(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        FilesScreen(
                            columnState = columnState,
                            navigateToEpisode = { episodeUuid ->
                                navController.navigate(EpisodeScreenFlow.navigateRoute(episodeUuid))
                            },
                        )
                    }
                }

                composable(StarredScreen.ROUTE) {
                    NowPlayingPager(
                        navController = navController,
                        swipeToDismissState = swipeToDismissState,
                    ) {
                        StarredScreen(
                            columnState = columnState,
                            onItemClick = { episode ->
                                val route = EpisodeScreenFlow.navigateRoute(episodeUuid = episode.uuid)
                                navController.navigate(route)
                            },
                        )
                    }
                }

                settingsRoutes(navController)

                composable(
                    route = EffectsScreen.ROUTE,
                ) {
                    EffectsScreen()
                }
            }
        }
    }
}

@Composable
fun PodcastsScreenContent(
    navController: NavHostController,
    swipeToDismissState: SwipeToDismissBoxState,
) {
    NowPlayingPager(
        navController = navController,
        swipeToDismissState = swipeToDismissState,
    ) {
        PodcastsScreen(
            columnState = columnState,
            navigateToPodcast = { podcastUuid ->
                navController.navigate(PodcastScreen.navigateRoute(podcastUuid))
            },
            navigateToFolder = { folderUuid ->
                navController.navigate(PodcastsScreen.navigateRoute(folderUuid))
            },
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun DefaultPreview() {
    WearApp(
        isSignedIn = false,
        isConnected = true,
        onNavigate = {},
    )
}
