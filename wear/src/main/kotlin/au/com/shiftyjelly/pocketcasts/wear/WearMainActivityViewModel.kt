package au.com.shiftyjelly.pocketcasts.wear

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.repositories.podcast.PodcastManager
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import au.com.shiftyjelly.pocketcasts.repositories.refresh.RefreshPodcastsTask
import au.com.shiftyjelly.pocketcasts.wear.networking.ConnectivityStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import timber.log.Timber

@OptIn(FlowPreview::class)
@HiltViewModel
class WearMainActivityViewModel @Inject constructor(
    private val podcastManager: PodcastManager,
    private val settings: Settings,
    @ApplicationContext private val context: Context,
    private val supabaseClient: SupabaseClient,
    private val connectivityStateManager: ConnectivityStateManager,
) : ViewModel() {

    data class State(
        val isSignedIn: Boolean,
        val isConnected: Boolean = true,
    )

    // PodHopper: start from the stored session so a signed-in watch opens straight to its main
    // screen instead of flashing the pairing screen while the login flow emits.
    private val _state = MutableStateFlow(State(isSignedIn = supabaseClient.isLoggedIn()))
    val state = _state.asStateFlow()

    init {
        // PodHopper: the watch is signed in exactly when a PodHopper session is stored. Pairing sets
        // it, and signing out from Settings clears it.
        viewModelScope.launch {
            supabaseClient.loginState
                .distinctUntilChanged()
                .collect { isSignedIn ->
                    _state.update { it.copy(isSignedIn = isSignedIn) }
                }
        }

        viewModelScope.launch {
            connectivityStateManager.isConnected
                .debounce(CONNECTIVITY_DEBOUNCE_MS)
                .collect { isConnected ->
                    _state.update { it.copy(isConnected = isConnected) }
                }
        }
    }

    fun refreshPodcasts() {
        viewModelScope.launch(Dispatchers.IO) {
            delay(REFRESH_START_DELAY) // delay the refresh to allow the UI to load
            try {
                podcastManager.refreshPodcastsIfRequired(fromLog = "watch - open app")
            } catch (e: Exception) {
                Timber.e(e)
            }
        }
        // Schedule next refresh in the background
        RefreshPodcastsTask.scheduleOrCancel(context, settings)
    }

    companion object {
        private const val REFRESH_START_DELAY = 1000L
        private const val CONNECTIVITY_DEBOUNCE_MS = 2_000L
    }
}
