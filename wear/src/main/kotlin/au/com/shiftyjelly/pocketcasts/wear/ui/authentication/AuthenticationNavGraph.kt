@file:Suppress("DEPRECATION")

package au.com.shiftyjelly.pocketcasts.wear.ui.authentication

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.State
import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.navigation.compose.navigation
import androidx.wear.compose.navigation.composable

const val AUTHENTICATION_SUB_GRAPH = "authentication_graph"

private object AuthenticationNavRoutes {
    const val LOGIN_SCREEN = "login_screen"
    const val LOGIN_WITH_PHONE = "login_with_phone"
    const val LOGIN_WITH_EMAIL = "login_with_email"
}

fun NavGraphBuilder.authenticationNavGraph(
    navController: NavController,
    onEmailSignInSuccess: () -> Unit,
    syncState: State<WatchSyncState>,
    onRetrySync: () -> Unit = {},
    onSyncScreenVisible: () -> Unit = {},
) {
    navigation(
        startDestination = AuthenticationNavRoutes.LOGIN_SCREEN,
        route = AUTHENTICATION_SUB_GRAPH,
    ) {
        composable(
            route = AuthenticationNavRoutes.LOGIN_SCREEN,
        ) {
            LoginScreen(
                onLoginWithPhoneClick = {
                    navController.navigate(AuthenticationNavRoutes.LOGIN_WITH_PHONE)
                },
                onLoginWithEmailClick = {
                    navController.navigate(AuthenticationNavRoutes.LOGIN_WITH_EMAIL)
                },
            )
        }

        composable(
            route = AuthenticationNavRoutes.LOGIN_WITH_EMAIL,
        ) {
            LoginWithEmailScreen(
                onSignInSuccess = onEmailSignInSuccess,
            )
        }

        composable(
            route = AuthenticationNavRoutes.LOGIN_WITH_PHONE,
        ) {
            LaunchedEffect(Unit) { onSyncScreenVisible() }
            LoginWithPhoneScreen(
                onLoginClick = { navController.popBackStack() },
                syncState = syncState.value,
                onRetry = onRetrySync,
            )
        }
    }
}
