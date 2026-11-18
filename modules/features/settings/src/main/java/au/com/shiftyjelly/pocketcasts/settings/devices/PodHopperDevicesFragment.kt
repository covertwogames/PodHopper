package au.com.shiftyjelly.pocketcasts.settings.devices

import android.os.Bundle
import android.text.format.DateUtils
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.AlertDialog
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.viewModels
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowButton
import au.com.shiftyjelly.pocketcasts.compose.components.HorizontalDivider
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperDevices
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import au.com.shiftyjelly.pocketcasts.settings.R
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PodHopper: Settings > Signed-in devices. Lists every place the account is signed in (phones,
 * tablets, cars, watches) and lets the person sign any other device out, or all of them at once.
 * This is how someone signs out of a car they have already handed back.
 */
@HiltViewModel
class PodHopperDevicesViewModel @Inject constructor(
    private val devices: PodHopperDevices,
    private val supabaseClient: SupabaseClient,
) : ViewModel() {

    enum class Status {
        Loading,
        LoggedOut,
        LoadFailed,
        Ready,
    }

    enum class Notice {
        None,
        Working,
        DoneOne,
        DoneOthers,
        Failed,
    }

    data class UiState(
        val status: Status = Status.Loading,
        val devices: List<PodHopperDevices.Device> = emptyList(),
        val notice: Notice = Notice.None,
    )

    private val _state = MutableStateFlow(UiState())
    val state: StateFlow<UiState> = _state.asStateFlow()

    /** Loads the list. Called when the screen opens and after every change. */
    fun load(notice: Notice = Notice.None) {
        if (!supabaseClient.isLoggedIn()) {
            _state.value = UiState(status = Status.LoggedOut)
            return
        }
        if (_state.value.status != Status.Ready) {
            _state.value = _state.value.copy(status = Status.Loading)
        }
        viewModelScope.launch {
            val loaded = withContext(Dispatchers.IO) {
                try {
                    devices.loadDevices()
                } catch (e: Exception) {
                    null
                }
            }
            _state.value = if (loaded != null) {
                UiState(status = Status.Ready, devices = loaded, notice = notice)
            } else if (!supabaseClient.isLoggedIn()) {
                UiState(status = Status.LoggedOut)
            } else {
                UiState(status = Status.LoadFailed)
            }
        }
    }

    fun signOut(device: PodHopperDevices.Device) {
        runChange(doneNotice = Notice.DoneOne) { devices.signOutDevice(device.sessionId) }
    }

    fun signOutOthers() {
        runChange(doneNotice = Notice.DoneOthers) { devices.signOutOtherDevices() }
    }

    private fun runChange(doneNotice: Notice, change: () -> Unit) {
        if (_state.value.notice == Notice.Working) {
            return
        }
        _state.value = _state.value.copy(notice = Notice.Working)
        viewModelScope.launch {
            val ok = withContext(Dispatchers.IO) {
                try {
                    change()
                    true
                } catch (e: Exception) {
                    false
                }
            }
            // Reload either way, so the list always shows what the server now has.
            load(notice = if (ok) doneNotice else Notice.Failed)
        }
    }
}

@AndroidEntryPoint
class PodHopperDevicesFragment : BaseFragment() {

    @Inject
    lateinit var settings: Settings

    private val viewModel: PodHopperDevicesViewModel by viewModels()

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        val bottomInset = settings.bottomInset.collectAsStateWithLifecycle(initialValue = 0)

        AppThemeWithBackground(theme.activeTheme) {
            PodHopperDevicesScreen(
                viewModel = viewModel,
                bottomInset = bottomInset.value.pxToDp(LocalContext.current).dp,
                onBackPress = {
                    activity?.onBackPressedDispatcher?.onBackPressed()
                },
            )
        }
    }
}

@Composable
private fun PodHopperDevicesScreen(
    viewModel: PodHopperDevicesViewModel,
    bottomInset: Dp,
    onBackPress: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    // The device waiting for a yes or no, or all the others at once.
    var confirmDevice by remember { mutableStateOf<PodHopperDevices.Device?>(null) }
    var confirmOthers by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        viewModel.load()
    }

    Column(
        modifier = modifier
            .background(MaterialTheme.theme.colors.primaryUi02)
            .fillMaxHeight(),
    ) {
        ThemedTopAppBar(
            title = stringResource(R.string.podhopper_devices_title),
            onNavigationClick = onBackPress,
        )
        when (state.status) {
            PodHopperDevicesViewModel.Status.LoggedOut -> Message(stringResource(R.string.podhopper_devices_logged_out))

            PodHopperDevicesViewModel.Status.Loading -> Column(modifier = Modifier.padding(16.dp)) {
                CircularProgressIndicator(color = MaterialTheme.theme.colors.primaryInteractive01)
                Spacer(modifier = Modifier.height(12.dp))
                Text(
                    text = stringResource(R.string.podhopper_devices_loading),
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.theme.colors.primaryText02,
                )
            }

            PodHopperDevicesViewModel.Status.LoadFailed -> Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = stringResource(R.string.podhopper_devices_error),
                    style = MaterialTheme.typography.body1,
                    color = MaterialTheme.theme.colors.primaryText01,
                )
                Spacer(modifier = Modifier.height(16.dp))
                RowButton(
                    text = stringResource(R.string.podhopper_devices_retry),
                    includePadding = false,
                    onClick = { viewModel.load() },
                )
            }

            PodHopperDevicesViewModel.Status.Ready -> DeviceList(
                state = state,
                bottomInset = bottomInset,
                onSignOut = { confirmDevice = it },
                onSignOutOthers = { confirmOthers = true },
            )
        }
    }

    val deviceToConfirm = confirmDevice
    if (deviceToConfirm != null) {
        ConfirmDialog(
            message = stringResource(R.string.podhopper_devices_confirm_one),
            confirmLabel = stringResource(R.string.podhopper_devices_sign_out),
            onConfirm = {
                confirmDevice = null
                viewModel.signOut(deviceToConfirm)
            },
            onDismiss = { confirmDevice = null },
        )
    }
    if (confirmOthers) {
        ConfirmDialog(
            message = stringResource(R.string.podhopper_devices_confirm_others),
            confirmLabel = stringResource(R.string.podhopper_devices_sign_out_others),
            onConfirm = {
                confirmOthers = false
                viewModel.signOutOthers()
            },
            onDismiss = { confirmOthers = false },
        )
    }
}

@Composable
private fun Message(text: String) {
    Text(
        text = text,
        style = MaterialTheme.typography.body1,
        color = MaterialTheme.theme.colors.primaryText01,
        modifier = Modifier.padding(16.dp),
    )
}

@Composable
private fun DeviceList(
    state: PodHopperDevicesViewModel.UiState,
    bottomInset: Dp,
    onSignOut: (PodHopperDevices.Device) -> Unit,
    onSignOutOthers: () -> Unit,
) {
    val working = state.notice == PodHopperDevicesViewModel.Notice.Working
    val hasOthers = state.devices.any { !it.isThisDevice }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = bottomInset + 16.dp),
    ) {
        item {
            Text(
                text = stringResource(R.string.podhopper_devices_intro),
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.theme.colors.primaryText01,
            )
        }
        item {
            val noticeText = when (state.notice) {
                PodHopperDevicesViewModel.Notice.Working -> stringResource(R.string.podhopper_devices_working)
                PodHopperDevicesViewModel.Notice.DoneOne -> stringResource(R.string.podhopper_devices_done_one)
                PodHopperDevicesViewModel.Notice.DoneOthers -> stringResource(R.string.podhopper_devices_done_others)
                PodHopperDevicesViewModel.Notice.Failed -> stringResource(R.string.podhopper_devices_failed)
                PodHopperDevicesViewModel.Notice.None -> null
            }
            if (noticeText != null) {
                Text(
                    text = noticeText,
                    style = MaterialTheme.typography.body2,
                    color = if (state.notice == PodHopperDevicesViewModel.Notice.Failed) {
                        MaterialTheme.theme.colors.support05
                    } else {
                        MaterialTheme.theme.colors.primaryText02
                    },
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
        items(state.devices, key = { it.sessionId }) { device ->
            DeviceRow(device = device, enabled = !working, onSignOut = { onSignOut(device) })
            HorizontalDivider()
        }
        if (hasOthers) {
            item {
                Spacer(modifier = Modifier.height(20.dp))
                RowButton(
                    text = stringResource(R.string.podhopper_devices_sign_out_others),
                    includePadding = false,
                    enabled = !working,
                    onClick = onSignOutOthers,
                )
            }
        }
        item {
            Text(
                text = stringResource(R.string.podhopper_devices_footer),
                style = MaterialTheme.typography.body2,
                color = MaterialTheme.theme.colors.primaryText02,
                modifier = Modifier.padding(top = 20.dp),
            )
        }
    }
}

@Composable
private fun DeviceRow(
    device: PodHopperDevices.Device,
    enabled: Boolean,
    onSignOut: () -> Unit,
) {
    val typeLabel = stringResource(
        when (device.type) {
            "phone" -> R.string.podhopper_devices_type_phone
            "tablet" -> R.string.podhopper_devices_type_tablet
            "car" -> R.string.podhopper_devices_type_car
            "watch" -> R.string.podhopper_devices_type_watch
            "unknown" -> R.string.podhopper_devices_type_unknown
            else -> R.string.podhopper_devices_type_other
        },
    )
    val name = if (device.model.isBlank()) {
        typeLabel
    } else {
        stringResource(R.string.podhopper_devices_name_with_model, typeLabel, device.model)
    }
    val detail = when {
        device.isThisDevice -> stringResource(R.string.podhopper_devices_this_device)
        device.lastActiveAtMs > 0L -> stringResource(R.string.podhopper_devices_last_active, relativeTime(device.lastActiveAtMs))
        device.signedInAtMs > 0L -> stringResource(R.string.podhopper_devices_signed_in_on, relativeTime(device.signedInAtMs))
        else -> ""
    }

    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 12.dp),
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = name,
                style = MaterialTheme.typography.body1,
                fontWeight = FontWeight.Medium,
                color = MaterialTheme.theme.colors.primaryText01,
            )
            if (detail.isNotEmpty()) {
                Text(
                    text = detail,
                    style = MaterialTheme.typography.body2,
                    color = MaterialTheme.theme.colors.primaryText02,
                    modifier = Modifier.padding(top = 2.dp),
                )
            }
        }
        if (!device.isThisDevice) {
            TextButton(onClick = onSignOut, enabled = enabled) {
                Text(
                    text = stringResource(R.string.podhopper_devices_sign_out),
                    color = MaterialTheme.theme.colors.primaryInteractive01,
                )
            }
        }
    }
}

/** "5 minutes ago", "Yesterday", or a date once it is more than a week back. */
private fun relativeTime(timeMs: Long): String {
    return DateUtils.getRelativeTimeSpanString(
        timeMs,
        System.currentTimeMillis(),
        DateUtils.MINUTE_IN_MILLIS,
        DateUtils.FORMAT_ABBREV_RELATIVE,
    ).toString()
}

@Composable
private fun ConfirmDialog(
    message: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        text = {
            Text(
                text = message,
                style = MaterialTheme.typography.body1,
                color = MaterialTheme.theme.colors.primaryText01,
            )
        },
        confirmButton = {
            TextButton(onClick = onConfirm) {
                Text(text = confirmLabel, color = MaterialTheme.theme.colors.primaryInteractive01)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(
                    text = stringResource(R.string.podhopper_devices_cancel),
                    color = MaterialTheme.theme.colors.primaryInteractive01,
                )
            }
        },
        backgroundColor = MaterialTheme.theme.colors.primaryUi01,
    )
}
