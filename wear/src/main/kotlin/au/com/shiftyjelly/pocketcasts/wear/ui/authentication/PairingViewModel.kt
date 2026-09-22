package au.com.shiftyjelly.pocketcasts.wear.ui.authentication

import android.os.Build
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PodHopper: drives the watch sign-in screen. The watch shows a pairing code, the user approves it
 * on their phone (Settings, Sync Account to Car or Watch), and the watch then claims a PodHopper
 * session. Storing that session flips the app to signed in, which swaps the watch to its main
 * screen and starts sync. Mirrors the car's pairing flow, without the email fallback.
 */
@HiltViewModel
class PairingViewModel @Inject constructor(
    private val supabaseClient: SupabaseClient,
) : ViewModel() {

    sealed interface UiState {
        data object Starting : UiState
        data class ShowingCode(val code: String) : UiState
        data object Expired : UiState
        data object NetworkError : UiState
        data object Success : UiState
    }

    private val _uiState = MutableStateFlow<UiState>(UiState.Starting)
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private var pollJob: Job? = null

    init {
        startPairing()
    }

    /** Requests a fresh pairing code and begins polling for the phone's approval. */
    fun startPairing() {
        pollJob?.cancel()
        _uiState.value = UiState.Starting
        pollJob = viewModelScope.launch {
            val code = try {
                withContext(Dispatchers.IO) { supabaseClient.startPairing(deviceName()) }
            } catch (e: Exception) {
                LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "PodHopper watch pairing: could not start")
                _uiState.value = UiState.NetworkError
                return@launch
            }
            _uiState.value = UiState.ShowingCode(code)

            var consecutiveFailures = 0
            while (isActive) {
                delay(POLL_INTERVAL_MS)
                val tokenHash = try {
                    val result = withContext(Dispatchers.IO) { supabaseClient.pollPairing(code) }
                    consecutiveFailures = 0
                    result
                } catch (e: Exception) {
                    if (e.message?.contains("expired", ignoreCase = true) == true) {
                        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper watch pairing: code expired")
                        _uiState.value = UiState.Expired
                        return@launch
                    }
                    LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper watch pairing: poll failed: ${e.message}")
                    consecutiveFailures++
                    if (consecutiveFailures >= MAX_POLL_FAILURES) {
                        _uiState.value = UiState.NetworkError
                        return@launch
                    }
                    null
                }
                if (tokenHash != null) {
                    val claimed = try {
                        withContext(Dispatchers.IO) { supabaseClient.claimPairingSession(tokenHash) }
                        true
                    } catch (e: Exception) {
                        LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "PodHopper watch pairing: claim failed")
                        _uiState.value = UiState.NetworkError
                        false
                    }
                    if (claimed) {
                        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper watch pairing: signed in")
                        _uiState.value = UiState.Success
                    }
                    return@launch
                }
            }
        }
    }

    private fun deviceName(): String {
        val manufacturer = Build.MANUFACTURER.orEmpty().trim()
        val model = Build.MODEL.orEmpty().trim()
        val combined = listOf(manufacturer, model).filter { it.isNotEmpty() }.joinToString(" ")
        return combined.ifBlank { "Watch" }
    }

    override fun onCleared() {
        super.onCleared()
        pollJob?.cancel()
    }

    private companion object {
        const val POLL_INTERVAL_MS = 3000L
        const val MAX_POLL_FAILURES = 4
    }
}
