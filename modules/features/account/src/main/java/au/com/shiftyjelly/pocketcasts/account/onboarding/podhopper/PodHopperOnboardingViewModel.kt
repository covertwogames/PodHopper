package au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper

import android.util.Patterns
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.AuthRejectedException
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperTerms
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.SupabaseClient
import dagger.hilt.android.lifecycle.HiltViewModel
import javax.inject.Inject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject

/**
 * Drives the PodHopper first-run login, signup, and password-recovery screens. It is a thin
 * wrapper over the same Supabase client the rest of the app uses, so a session created here is
 * the session sync relies on. Field validation lives in the UI; this view model only performs the
 * network calls and maps the outcome to a state the screens render.
 *
 * Every way through these screens ends with the Terms of Service agreed to and recorded: creating
 * an account (the box on that screen must be ticked), logging in (the screen says logging in means
 * agreeing), and continuing without an account (the confirmation says the same).
 */
@HiltViewModel
class PodHopperOnboardingViewModel @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val terms: PodHopperTerms,
) : ViewModel() {

    enum class ErrorKind {
        InvalidCredentials,
        InvalidEmail,
        LoginFailed,
        SignupFailed,
        RecoverFailed,
        MissingFields,
    }

    sealed interface AuthState {
        data object Idle : AuthState
        data object Busy : AuthState

        // A live session now exists, so the flow can advance to the notifications step.
        data object Authenticated : AuthState

        // Signup succeeded but the project requires email confirmation before a session is issued.
        data object ConfirmEmail : AuthState

        // A password reset email was sent.
        data object RecoverSent : AuthState

        data class Error(val kind: ErrorKind, val detail: String?) : AuthState
    }

    private val _authState = MutableStateFlow<AuthState>(AuthState.Idle)
    val authState: StateFlow<AuthState> = _authState

    fun isSignedIn(): Boolean = supabaseClient.isLoggedIn()

    fun resetState() {
        _authState.value = AuthState.Idle
    }

    /** The person confirmed "Continue without account", whose dialog carries the Terms notice. */
    fun agreeWithoutAccount() {
        terms.recordAgreement(PodHopperTerms.METHOD_NOTICE)
    }

    fun signIn(email: String, password: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error(ErrorKind.MissingFields, null)
            return
        }
        _authState.value = AuthState.Busy
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    supabaseClient.login(trimmedEmail, password)
                    // Logged in on a screen that says logging in means agreeing to the Terms.
                    // Saving it to the account happens in the background, so it can never slow
                    // down or fail the login.
                    terms.recordAgreement(PodHopperTerms.METHOD_SIGN_IN)
                    terms.syncToAccountAsync()
                }
                _authState.value = AuthState.Authenticated
            } catch (e: AuthRejectedException) {
                _authState.value = AuthState.Error(ErrorKind.InvalidCredentials, null)
            } catch (e: Exception) {
                _authState.value = AuthState.Error(ErrorKind.LoginFailed, serverMessage(e))
            }
        }
    }

    fun signUp(email: String, password: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || password.isEmpty()) {
            _authState.value = AuthState.Error(ErrorKind.MissingFields, null)
            return
        }
        _authState.value = AuthState.Busy
        viewModelScope.launch {
            try {
                val sessionActive = withContext(Dispatchers.IO) {
                    // The screen only lets this run with the Terms box ticked. Record that now, so
                    // it is kept even when the account has to be confirmed by email before the
                    // first login, and copy it to the account as soon as there is a session.
                    terms.recordAgreement(PodHopperTerms.METHOD_CHECKBOX)
                    val active = supabaseClient.signUp(trimmedEmail, password)
                    if (active) {
                        terms.syncToAccountAsync()
                    }
                    active
                }
                _authState.value = if (sessionActive) AuthState.Authenticated else AuthState.ConfirmEmail
            } catch (e: Exception) {
                _authState.value = AuthState.Error(ErrorKind.SignupFailed, serverMessage(e))
            }
        }
    }

    fun recoverPassword(email: String) {
        val trimmedEmail = email.trim()
        if (trimmedEmail.isEmpty() || !Patterns.EMAIL_ADDRESS.matcher(trimmedEmail).matches()) {
            _authState.value = AuthState.Error(ErrorKind.InvalidEmail, null)
            return
        }
        _authState.value = AuthState.Busy
        viewModelScope.launch {
            try {
                withContext(Dispatchers.IO) {
                    supabaseClient.recoverPassword(trimmedEmail)
                }
                _authState.value = AuthState.RecoverSent
            } catch (e: Exception) {
                _authState.value = AuthState.Error(ErrorKind.RecoverFailed, serverMessage(e))
            }
        }
    }

    /**
     * Pulls the human-readable "msg" that Supabase returns inside its JSON error body, so the UI can
     * show that one clean sentence instead of the raw exception and HTTP dump. Returns null when no
     * such message is present (for example a plain network failure), in which case the caller falls
     * back to a generic message.
     */
    private fun serverMessage(e: Throwable): String? {
        var current: Throwable? = e
        var depth = 0
        while (current != null && depth < 4) {
            val message = current.message
            if (message != null) {
                val start = message.indexOf('{')
                val end = message.lastIndexOf('}')
                if (start != -1 && end > start) {
                    try {
                        val parsed = JSONObject(message.substring(start, end + 1))
                        val msg = parsed.optString("msg")
                        if (msg.isNotBlank()) {
                            return msg
                        }
                    } catch (_: Exception) {
                        // Not JSON; keep walking the cause chain.
                    }
                }
            }
            current = current.cause
            depth++
        }
        return null
    }
}
