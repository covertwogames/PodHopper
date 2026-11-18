package au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper

import android.util.Patterns
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.AlertDialog
import androidx.compose.material.Checkbox
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import au.com.shiftyjelly.pocketcasts.account.R
import au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper.PodHopperOnboardingViewModel.AuthState
import au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper.PodHopperOnboardingViewModel.ErrorKind
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowButton
import au.com.shiftyjelly.pocketcasts.compose.components.EmailAndPasswordFields
import au.com.shiftyjelly.pocketcasts.compose.components.EmailField
import au.com.shiftyjelly.pocketcasts.compose.components.PasswordField
import au.com.shiftyjelly.pocketcasts.compose.components.TextH10
import au.com.shiftyjelly.pocketcasts.compose.components.TextH30
import au.com.shiftyjelly.pocketcasts.compose.components.TextP40
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.localization.R as LR

private enum class OnboardingStep {
    Welcome,
    Login,
    Signup,
    Notifications,
}

@Composable
fun PodHopperOnboardingFlow(
    viewModel: PodHopperOnboardingViewModel,
    startSignedIn: Boolean,
    onExitApp: () -> Unit,
    onGetStarted: (Boolean) -> Unit,
    loginOnly: Boolean = false,
    onLoginComplete: () -> Unit = {},
) {
    var step by remember {
        mutableStateOf(
            when {
                loginOnly -> OnboardingStep.Login
                startSignedIn -> OnboardingStep.Notifications
                else -> OnboardingStep.Welcome
            },
        )
    }
    val authState by viewModel.authState.collectAsState()

    LaunchedEffect(authState) {
        if (authState is AuthState.Authenticated) {
            if (loginOnly) {
                viewModel.resetState()
                onLoginComplete()
            } else {
                step = OnboardingStep.Notifications
                viewModel.resetState()
            }
        }
    }

    BackHandler {
        when (step) {
            OnboardingStep.Welcome -> onExitApp()
            OnboardingStep.Login -> {
                if (loginOnly) {
                    onExitApp()
                } else {
                    viewModel.resetState()
                    step = OnboardingStep.Welcome
                }
            }
            OnboardingStep.Signup -> {
                viewModel.resetState()
                step = OnboardingStep.Login
            }
            OnboardingStep.Notifications -> {
                if (startSignedIn) {
                    onExitApp()
                } else {
                    viewModel.resetState()
                    step = OnboardingStep.Welcome
                }
            }
        }
    }

    when (step) {
        OnboardingStep.Welcome -> WelcomeStep(
            onLogin = { step = OnboardingStep.Login },
            onSkipConfirmed = {
                // The dialog they just confirmed says continuing means agreeing to the Terms.
                viewModel.agreeWithoutAccount()
                step = OnboardingStep.Notifications
            },
        )

        OnboardingStep.Login -> LoginStep(
            authState = authState,
            onSignIn = { email, password -> viewModel.signIn(email, password) },
            onRecover = { email -> viewModel.recoverPassword(email) },
            onCreateAccount = {
                viewModel.resetState()
                step = OnboardingStep.Signup
            },
        )

        OnboardingStep.Signup -> SignupStep(
            authState = authState,
            onCreate = { email, password -> viewModel.signUp(email, password) },
        )

        OnboardingStep.Notifications -> NotificationsStep(
            onGetStarted = onGetStarted,
        )
    }
}

@Composable
private fun StepScaffold(
    content: @Composable () -> Unit,
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        content()
    }
}

@Composable
private fun WelcomeStep(
    onLogin: () -> Unit,
    onSkipConfirmed: () -> Unit,
) {
    var showSkipWarning by remember { mutableStateOf(false) }

    StepScaffold {
        Image(
            painter = painterResource(R.drawable.welcome_lockup),
            contentDescription = null,
            modifier = Modifier.size(220.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextH10(
            text = stringResource(LR.string.podhopper_welcome_title),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextP40(
            text = stringResource(LR.string.podhopper_welcome_message),
            textAlign = TextAlign.Center,
            color = MaterialTheme.theme.colors.primaryText02,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        RowButton(
            text = stringResource(LR.string.podhopper_welcome_login),
            onClick = onLogin,
            includePadding = false,
        )
        TextButton(onClick = { showSkipWarning = true }) {
            TextP40(
                text = stringResource(LR.string.podhopper_welcome_skip),
                color = MaterialTheme.theme.colors.primaryInteractive01,
            )
        }
    }

    if (showSkipWarning) {
        AlertDialog(
            onDismissRequest = { showSkipWarning = false },
            text = {
                Column {
                    TextP40(text = stringResource(LR.string.podhopper_skip_warning))
                    Spacer(modifier = Modifier.height(16.dp))
                    TermsLinksText(
                        text = stringResource(LR.string.podhopper_terms_skip_notice),
                        textAlign = TextAlign.Start,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    showSkipWarning = false
                    onSkipConfirmed()
                }) {
                    TextP40(
                        text = stringResource(LR.string.podhopper_skip_continue),
                        color = MaterialTheme.theme.colors.primaryInteractive01,
                    )
                }
            },
            dismissButton = {
                TextButton(onClick = { showSkipWarning = false }) {
                    TextP40(
                        text = stringResource(LR.string.podhopper_skip_back),
                        color = MaterialTheme.theme.colors.primaryInteractive01,
                    )
                }
            },
            backgroundColor = MaterialTheme.theme.colors.primaryUi01,
        )
    }
}

@Composable
private fun LoginStep(
    authState: AuthState,
    onSignIn: (String, String) -> Unit,
    onRecover: (String) -> Unit,
    onCreateAccount: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    val busy = authState is AuthState.Busy

    StepScaffold {
        TextH30(
            text = stringResource(LR.string.podhopper_auth_login),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextP40(
            text = stringResource(LR.string.podhopper_auth_explanation),
            textAlign = TextAlign.Center,
            color = MaterialTheme.theme.colors.primaryText02,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        EmailAndPasswordFields(
            email = email,
            password = password,
            enabled = !busy,
            isCreatingAccount = false,
            showEmailError = false,
            showPasswordError = false,
            onConfirm = { onSignIn(email, password) },
            onUpdateEmail = { email = it },
            onUpdatePassword = { password = it },
        )
        StatusMessage(authState)
        Spacer(modifier = Modifier.height(24.dp))
        TermsLinksText(
            text = stringResource(LR.string.podhopper_terms_login_notice),
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        if (busy) {
            CircularProgressIndicator(color = MaterialTheme.theme.colors.primaryInteractive01)
        } else {
            RowButton(
                text = stringResource(LR.string.podhopper_auth_login),
                onClick = { onSignIn(email, password) },
                includePadding = false,
            )
            TextButton(onClick = onCreateAccount) {
                TextP40(
                    text = stringResource(LR.string.podhopper_auth_signup),
                    color = MaterialTheme.theme.colors.primaryInteractive01,
                )
            }
            TextButton(onClick = { onRecover(email) }) {
                TextP40(
                    text = stringResource(LR.string.podhopper_auth_forgot),
                    color = MaterialTheme.theme.colors.primaryInteractive01,
                )
            }
        }
    }
}

@Composable
private fun SignupStep(
    authState: AuthState,
    onCreate: (String, String) -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var emailError by remember { mutableStateOf(false) }
    var passwordError by remember { mutableStateOf(false) }
    var confirmError by remember { mutableStateOf(false) }
    // PodHopper: an account cannot be created until the box agreeing to the Terms of Service and
    // Privacy Policy is ticked. It always starts empty.
    var agreed by remember { mutableStateOf(false) }
    var agreedError by remember { mutableStateOf(false) }
    val busy = authState is AuthState.Busy

    fun submit() {
        val validEmail = email.isNotBlank() && Patterns.EMAIL_ADDRESS.matcher(email.trim()).matches()
        val validPassword = password.length >= MIN_PASSWORD_LENGTH
        val validConfirm = password == confirm
        emailError = !validEmail
        passwordError = !validPassword
        confirmError = !validConfirm
        agreedError = !agreed
        if (validEmail && validPassword && validConfirm && agreed) {
            onCreate(email, password)
        }
    }

    StepScaffold {
        TextH30(
            text = stringResource(LR.string.podhopper_auth_signup),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextP40(
            text = stringResource(LR.string.podhopper_signup_explanation),
            textAlign = TextAlign.Center,
            color = MaterialTheme.theme.colors.primaryText02,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(24.dp))
        EmailField(
            email = email,
            enabled = !busy,
            isError = emailError,
            isCreatingAccount = true,
            imeAction = ImeAction.Next,
            onImeAction = {},
            onUpdateEmail = {
                email = it
                emailError = false
            },
        )
        if (emailError) {
            FieldError(stringResource(LR.string.podhopper_signup_invalid_email))
        }
        Spacer(modifier = Modifier.height(12.dp))
        PasswordField(
            password = password,
            enabled = !busy,
            isError = passwordError,
            isCreatingAccount = true,
            imeAction = ImeAction.Next,
            onImeAction = {},
            onUpdatePassword = {
                password = it
                passwordError = false
            },
        )
        if (passwordError) {
            FieldError(stringResource(LR.string.podhopper_signup_password_too_short))
        }
        Spacer(modifier = Modifier.height(12.dp))
        PasswordField(
            password = confirm,
            enabled = !busy,
            isError = confirmError,
            isCreatingAccount = true,
            imeAction = ImeAction.Done,
            placeholder = stringResource(LR.string.podhopper_signup_confirm_password),
            onImeAction = { submit() },
            onUpdatePassword = {
                confirm = it
                confirmError = false
            },
        )
        if (confirmError) {
            FieldError(stringResource(LR.string.podhopper_signup_passwords_mismatch))
        }
        Spacer(modifier = Modifier.height(16.dp))
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth(),
        ) {
            Checkbox(
                checked = agreed,
                enabled = !busy,
                onCheckedChange = {
                    agreed = it
                    agreedError = false
                },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TermsLinksText(
                text = stringResource(LR.string.podhopper_terms_checkbox),
                textAlign = TextAlign.Start,
                modifier = Modifier.weight(1f),
            )
        }
        if (agreedError) {
            FieldError(stringResource(LR.string.podhopper_terms_checkbox_required))
        }
        StatusMessage(authState)
        Spacer(modifier = Modifier.height(24.dp))
        if (busy) {
            CircularProgressIndicator(color = MaterialTheme.theme.colors.primaryInteractive01)
        } else {
            RowButton(
                text = stringResource(LR.string.podhopper_auth_signup),
                onClick = { submit() },
                includePadding = false,
            )
        }
    }
}

@Composable
private fun NotificationsStep(
    onGetStarted: (Boolean) -> Unit,
) {
    var receiveNotifications by remember { mutableStateOf(true) }

    StepScaffold {
        Image(
            painter = painterResource(R.drawable.welcome_lockup),
            contentDescription = null,
            modifier = Modifier.size(180.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextH10(
            text = stringResource(LR.string.podhopper_notifications_title),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextP40(
            text = stringResource(LR.string.podhopper_notifications_message),
            textAlign = TextAlign.Center,
            color = MaterialTheme.theme.colors.primaryText02,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(32.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Checkbox(
                checked = receiveNotifications,
                onCheckedChange = { receiveNotifications = it },
            )
            Spacer(modifier = Modifier.width(8.dp))
            TextP40(text = stringResource(LR.string.podhopper_notifications_checkbox))
        }
        Spacer(modifier = Modifier.height(24.dp))
        RowButton(
            text = stringResource(LR.string.podhopper_get_started),
            onClick = { onGetStarted(receiveNotifications) },
            includePadding = false,
        )
    }
}

@Composable
private fun StatusMessage(authState: AuthState) {
    val errorColor = MaterialTheme.theme.colors.support05
    val infoColor = MaterialTheme.theme.colors.primaryText02
    when (authState) {
        is AuthState.Error -> {
            val base = when (authState.kind) {
                ErrorKind.InvalidCredentials -> stringResource(LR.string.podhopper_auth_invalid_credentials)
                ErrorKind.InvalidEmail -> stringResource(LR.string.podhopper_signup_invalid_email)
                ErrorKind.LoginFailed -> stringResource(LR.string.podhopper_auth_login_error)
                ErrorKind.SignupFailed -> stringResource(LR.string.podhopper_signup_failed)
                ErrorKind.RecoverFailed -> stringResource(LR.string.podhopper_auth_recover_error)
                ErrorKind.MissingFields -> stringResource(LR.string.podhopper_auth_error)
            }
            val detail = authState.detail
            // When the server returned a clean message, show only that. Otherwise show the generic.
            val text = if (detail.isNullOrBlank()) base else detail
            Spacer(modifier = Modifier.height(12.dp))
            TextP40(text = text, color = errorColor, modifier = Modifier.fillMaxWidth())
        }
        is AuthState.ConfirmEmail -> {
            Spacer(modifier = Modifier.height(12.dp))
            TextP40(
                text = stringResource(LR.string.podhopper_auth_confirm_email),
                color = infoColor,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        is AuthState.RecoverSent -> {
            Spacer(modifier = Modifier.height(12.dp))
            TextP40(
                text = stringResource(LR.string.podhopper_auth_recover_sent),
                color = infoColor,
                modifier = Modifier.fillMaxWidth(),
            )
        }
        else -> Unit
    }
}

@Composable
private fun FieldError(message: String) {
    Spacer(modifier = Modifier.height(4.dp))
    TextP40(
        text = message,
        color = MaterialTheme.theme.colors.support05,
        modifier = Modifier.fillMaxWidth(),
    )
}

private const val MIN_PASSWORD_LENGTH = 6
