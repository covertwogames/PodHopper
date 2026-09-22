package au.com.shiftyjelly.pocketcasts.wear.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.models.to.RefreshState
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperPositionSync
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperSubscriptionSync
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val podcastManager: PodcastManager,
    private val settings: Settings,
    private val supabaseClient: SupabaseClient,
    private val positionSync: PodHopperPositionSync,
    private val subscriptionSync: PodHopperSubscriptionSync,
) : ViewModel() {

    data class State(
        val refreshState: RefreshState?,
        val isSignedIn: Boolean,
        val email: String?,
        val showDataWarning: Boolean,
        val refreshInBackground: Boolean,
    )

    private val _state = MutableStateFlow(
        State(
            refreshState = null,
            isSignedIn = supabaseClient.isLoggedIn(),
            email = settings.podhopperEmail.value.ifBlank { null },
            showDataWarning = settings.warnOnMeteredNetwork.value,
            refreshInBackground = settings.backgroundRefreshPodcasts.value,
        ),
    )
    val state = _state.asStateFlow()

    init {
        // PodHopper: the account row reflects the PodHopper session and the email it was signed in
        // with (pairing records it), not a Pocket Casts account.
        viewModelScope.launch {
            combine(supabaseClient.loginState, settings.podhopperEmail.flow) { isSignedIn, email ->
                isSignedIn to email.ifBlank { null }
            }.collectLatest { (isSignedIn, email) ->
                _state.update { it.copy(isSignedIn = isSignedIn, email = email) }
            }
        }

        viewModelScope.launch {
            settings.refreshStateFlow
                .collectLatest { refreshState ->
                    _state.update { it.copy(refreshState = refreshState) }
                }
        }
        viewModelScope.launch {
            settings.warnOnMeteredNetwork.flow.collectLatest { warnOnMeteredNetwork ->
                _state.update { it.copy(showDataWarning = warnOnMeteredNetwork) }
            }
        }
        viewModelScope.launch {
            settings.backgroundRefreshPodcasts.flow.collectLatest { refreshInBackground ->
                _state.update { it.copy(refreshInBackground = refreshInBackground) }
            }
        }
    }

    fun setWarnOnMeteredNetwork(warnOnMeteredNetwork: Boolean) {
        settings.warnOnMeteredNetwork.set(warnOnMeteredNetwork, updateModifiedAt = true)
    }

    fun setRefreshPodcastsInBackground(isChecked: Boolean) {
        settings.backgroundRefreshPodcasts.set(isChecked, updateModifiedAt = true)
    }

    /**
     * PodHopper: signs the watch out of its PodHopper account, the same way the phone's profile
     * screen does. Forgets this device's sync bookkeeping so signing into a different account next
     * starts clean. No podcast, episode or download data is touched. Clearing the session returns
     * the watch to the pairing screen.
     */
    fun signOut() {
        supabaseClient.logout()
        positionSync.clearLocalSyncState()
        subscriptionSync.clearLocalSyncState()
    }

    fun refresh() {
        if (_state.value.refreshState is RefreshState.Refreshing) {
            return
        }
        podcastManager.refreshPodcasts("watch - settings")
    }
}
