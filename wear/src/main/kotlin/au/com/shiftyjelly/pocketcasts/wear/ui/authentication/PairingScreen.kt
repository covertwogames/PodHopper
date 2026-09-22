package au.com.shiftyjelly.pocketcasts.wear.ui.authentication

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.wear.compose.material.CircularProgressIndicator
import androidx.wear.compose.material.Icon
import androidx.wear.compose.material.MaterialTheme
import androidx.wear.compose.material.Text
import androidx.wear.tooling.preview.devices.WearDevices
import au.com.shiftyjelly.pocketcasts.wear.theme.WearAppTheme
import au.com.shiftyjelly.pocketcasts.wear.theme.WearColors
import au.com.shiftyjelly.pocketcasts.wear.ui.authentication.PairingViewModel.UiState
import au.com.shiftyjelly.pocketcasts.wear.ui.component.WatchListChip
import com.google.android.horologist.compose.layout.ScalingLazyColumn
import com.google.android.horologist.compose.layout.ScreenScaffold
import com.google.android.horologist.compose.layout.rememberResponsiveColumnState
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR

object PairingScreen {
    const val ROUTE = "pairing_screen"
}

/**
 * PodHopper: the watch's only sign-in route. Shown whenever the watch has no PodHopper account.
 * Nothing here navigates on success: the stored session flips the app to signed in, and the main
 * activity then swaps the watch to its main screen.
 */
@Composable
fun PairingScreen(
    modifier: Modifier = Modifier,
    viewModel: PairingViewModel = hiltViewModel(),
) {
    val state by viewModel.uiState.collectAsState()
    PairingContent(
        state = state,
        onRetry = viewModel::startPairing,
        modifier = modifier,
    )
}

@Composable
private fun PairingContent(
    state: UiState,
    onRetry: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val columnState = rememberResponsiveColumnState()

    ScreenScaffold(
        scrollState = columnState,
        modifier = modifier,
    ) {
        ScalingLazyColumn(
            columnState = columnState,
        ) {
            when (state) {
                is UiState.Starting -> {
                    item {
                        CircularProgressIndicator(
                            indicatorColor = MaterialTheme.colors.onPrimary,
                            trackColor = MaterialTheme.colors.onBackground.copy(alpha = 0.2f),
                            strokeWidth = 3.dp,
                        )
                    }
                }

                is UiState.ShowingCode -> {
                    item {
                        Text(
                            text = stringResource(LR.string.podhopper_watch_pairing_title),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.title3,
                        )
                    }
                    item {
                        Text(
                            text = state.code,
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.title1.copy(fontWeight = FontWeight.Bold),
                            color = MaterialTheme.colors.primary,
                            modifier = Modifier.padding(vertical = 8.dp),
                        )
                    }
                    item {
                        Text(
                            text = stringResource(LR.string.podhopper_watch_pairing_instructions),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    item {
                        Text(
                            text = stringResource(LR.string.podhopper_watch_pairing_waiting),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.caption3,
                            color = MaterialTheme.colors.onSecondary,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }

                is UiState.Expired -> {
                    item {
                        Text(
                            text = stringResource(LR.string.podhopper_watch_pairing_expired),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { RetryChip(onRetry = onRetry) }
                }

                is UiState.NetworkError -> {
                    item {
                        Text(
                            text = stringResource(LR.string.podhopper_watch_pairing_error),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.body2,
                        )
                    }
                    item { Spacer(Modifier.height(8.dp)) }
                    item { RetryChip(onRetry = onRetry) }
                }

                is UiState.Success -> {
                    item {
                        Icon(
                            imageVector = Icons.Default.CheckCircle,
                            tint = WearColors.success,
                            contentDescription = null,
                            modifier = Modifier.size(52.dp),
                        )
                    }
                    item {
                        Text(
                            text = stringResource(LR.string.podhopper_watch_pairing_success),
                            textAlign = TextAlign.Center,
                            style = MaterialTheme.typography.title3,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun RetryChip(onRetry: () -> Unit) {
    WatchListChip(
        title = stringResource(LR.string.podhopper_watch_pairing_retry),
        iconRes = IR.drawable.ic_retry,
        onClick = onRetry,
    )
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PairingCodePreview() {
    WearAppTheme {
        PairingContent(
            state = UiState.ShowingCode(code = "482913"),
            onRetry = {},
        )
    }
}

@Preview(device = WearDevices.SMALL_ROUND, showSystemUi = true)
@Composable
private fun PairingExpiredPreview() {
    WearAppTheme {
        PairingContent(
            state = UiState.Expired,
            onRetry = {},
        )
    }
}
