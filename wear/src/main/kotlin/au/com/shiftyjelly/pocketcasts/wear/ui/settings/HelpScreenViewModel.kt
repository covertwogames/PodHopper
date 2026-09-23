package au.com.shiftyjelly.pocketcasts.wear.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperCarDiagnostics
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * PodHopper: the watch Help screen's "Share logs with developers" row. Uploads this watch's log with
 * the same uploader the phone's About screen and the car use, straight from the watch, so no phone
 * needs to be nearby. The row's label shows progress and the result, exactly as on the phone.
 */
@HiltViewModel
class HelpScreenViewModel @Inject constructor(
    private val diagnostics: PodHopperCarDiagnostics,
) : ViewModel() {

    private val _shareLogsLabel = MutableStateFlow(LR.string.podhopper_share_logs_title)
    val shareLogsLabel: StateFlow<Int> = _shareLogsLabel.asStateFlow()

    fun shareLogs() {
        if (_shareLogsLabel.value == LR.string.podhopper_share_logs_uploading) {
            return
        }
        _shareLogsLabel.value = LR.string.podhopper_share_logs_uploading
        viewModelScope.launch {
            val shared = diagnostics.uploadNow(reason = "manual")
            _shareLogsLabel.value = if (shared) LR.string.podhopper_share_logs_done else LR.string.podhopper_share_logs_failed
        }
    }
}
