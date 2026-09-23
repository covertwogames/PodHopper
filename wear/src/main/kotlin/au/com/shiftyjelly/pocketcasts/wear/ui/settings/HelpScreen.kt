package au.com.shiftyjelly.pocketcasts.wear.ui.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.res.stringResource
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import au.com.shiftyjelly.pocketcasts.wear.ui.component.ScreenHeaderChip
import au.com.shiftyjelly.pocketcasts.wear.ui.component.WatchListChip
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScreenScaffold
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import au.com.shiftyjelly.pocketcasts.localization.R as LR

object HelpScreen {
    const val ROUTE = "help_screen"
}

@Composable
fun HelpScreen(viewModel: HelpScreenViewModel = hiltViewModel()) {
    val shareLogsLabel by viewModel.shareLogsLabel.collectAsState()
    val columnState = rememberResponsiveColumnState()

    ScreenScaffold(
        scrollState = columnState,
    ) {
        ScalingLazyColumn(columnState = columnState) {
            item {
                ScreenHeaderChip(text = LR.string.settings_title_help)
            }

            // PodHopper: uploads this watch's log directly, so it works with or without the phone.
            item {
                WatchListChip(
                    title = stringResource(shareLogsLabel),
                    onClick = { viewModel.shareLogs() },
                )
            }
        }
    }
}
