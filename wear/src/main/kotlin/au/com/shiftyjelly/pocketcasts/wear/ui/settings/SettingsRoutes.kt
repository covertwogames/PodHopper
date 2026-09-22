package au.com.shiftyjelly.pocketcasts.wear.ui.settings

import androidx.navigation.NavController
import androidx.navigation.NavGraphBuilder
import androidx.wear.compose.navigation.composable

fun NavGraphBuilder.settingsRoutes(navController: NavController) {
    settingsUrlScreens()

    composable(
        route = SettingsScreen.ROUTE,
    ) {
        SettingsScreen(
            navigateToPrivacySettings = { navController.navigate(PrivacySettingsScreen.ROUTE) },
            navigateToAbout = { navController.navigate(WearAboutScreen.ROUTE) },
            navigateToHelp = { navController.navigate(HelpScreen.ROUTE) },
        )
    }

    composable(
        route = PrivacySettingsScreen.ROUTE,
    ) {
        PrivacySettingsScreen()
    }

    composable(
        route = WearAboutScreen.ROUTE,
    ) {
        WearAboutScreen(
            onTermsOfServiceClick = { navController.navigate(UrlScreenRoutes.TERMS_OF_SERVICES) },
            onPrivacyClick = { navController.navigate(UrlScreenRoutes.PRIVACY) },
        )
    }

    composable(
        route = HelpScreen.ROUTE,
    ) {
        HelpScreen()
    }
}
