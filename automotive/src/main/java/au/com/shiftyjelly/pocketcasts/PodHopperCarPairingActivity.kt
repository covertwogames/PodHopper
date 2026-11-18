package au.com.shiftyjelly.pocketcasts

import android.os.Bundle
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.Button
import androidx.compose.material.CircularProgressIndicator
import androidx.compose.material.MaterialTheme
import androidx.compose.material.OutlinedTextField
import androidx.compose.material.TextFieldDefaults
import androidx.compose.material.Text
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.ViewModelProvider
import au.com.shiftyjelly.pocketcasts.compose.AutomotiveTheme
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.ui.extensions.getThemeDrawable
import au.com.shiftyjelly.pocketcasts.PodHopperCarPairingViewModel.UiState
import dagger.hilt.android.AndroidEntryPoint
import au.com.shiftyjelly.pocketcasts.localization.R as LR
import au.com.shiftyjelly.pocketcasts.ui.R as UR

/**
 * PodHopper: the car (Android Automotive) sign-in screen. Reached while parked, either from the
 * media browser's "Sign In" prompt or from car Settings. Shows a pairing code the phone approves,
 * with a direct email and password fallback. Closes itself once signed in.
 *
 * Both ways of signing in show a line saying that signing in means agreeing to the Terms of
 * Service and Privacy Policy, with a "Read the Terms" button. The car has no web browser, so that
 * button swaps this screen for the built-in Terms page and Back returns to sign-in. Pairing keeps
 * running underneath, so approving the code on the phone while reading still signs the car in.
 */
@AndroidEntryPoint
class PodHopperCarPairingActivity : AppCompatActivity() {

    private lateinit var viewModel: PodHopperCarPairingViewModel

    private val showTerms = mutableStateOf(false)

    // Enabled only while the Terms page is showing, so Back closes that page first and otherwise
    // leaves the sign-in screen exactly as before.
    private val closeTermsOnBack = object : OnBackPressedCallback(false) {
        override fun handleOnBackPressed() {
            setTermsShown(false)
        }
    }

    private fun setTermsShown(shown: Boolean) {
        showTerms.value = shown
        closeTermsOnBack.isEnabled = shown
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        // The car recreates this screen when it switches between day and night. Stay on the Terms.
        outState.putBoolean(STATE_SHOW_TERMS, showTerms.value)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        viewModel = ViewModelProvider(this)[PodHopperCarPairingViewModel::class.java]
        onBackPressedDispatcher.addCallback(this, closeTermsOnBack)
        setTermsShown(savedInstanceState?.getBoolean(STATE_SHOW_TERMS) == true)

        val composeView = ComposeView(this).apply {
            setContent {
                AutomotiveTheme {
                    val state by viewModel.uiState.collectAsState()
                    LaunchedEffect(state) {
                        if (state is UiState.Success) {
                            finish()
                        }
                    }
                    if (showTerms.value) {
                        TermsReader(onBack = { setTermsShown(false) })
                    } else {
                        PairingScreen(
                            state = state,
                            onRetry = { viewModel.startPairing() },
                            onShowEmail = { viewModel.showEmailEntry() },
                            onSubmitEmail = { email, password -> viewModel.submitEmail(email, password) },
                            onBackToPairing = { viewModel.backToPairing() },
                            onReadTerms = { setTermsShown(true) },
                        )
                    }
                }
            }
        }
        setContentView(composeView)
    }

    private companion object {
        const val STATE_SHOW_TERMS = "show_terms"
    }
}

/**
 * PodHopper: the built-in Terms page with a way back to sign-in pinned above it, so the driver
 * never has to rely on a system Back button that some head units hide.
 */
@Composable
private fun TermsReader(onBack: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        TextButton(
            onClick = onBack,
            modifier = Modifier.padding(start = 32.dp, top = 12.dp),
        ) {
            Text(
                text = stringResource(LR.string.podhopper_car_terms_back),
                fontSize = 24.sp,
                color = MaterialTheme.theme.colors.primaryInteractive01,
            )
        }
        PodHopperTermsPage(modifier = Modifier.weight(1f))
    }
}

/**
 * PodHopper: the line that says signing in means agreeing to the Terms and Privacy Policy, with
 * the button that opens the built-in Terms. Shown wherever the car can actually sign in.
 */
@Composable
private fun TermsNotice(onReadTerms: () -> Unit) {
    Text(
        text = stringResource(LR.string.podhopper_car_terms_notice),
        fontSize = 20.sp,
        textAlign = TextAlign.Center,
        color = MaterialTheme.theme.colors.primaryText02,
    )
    TextButton(onClick = onReadTerms) {
        Text(
            text = stringResource(LR.string.podhopper_car_terms_read),
            fontSize = 22.sp,
            color = MaterialTheme.theme.colors.primaryInteractive01,
        )
    }
}

@Composable
private fun PairingScreen(
    state: UiState,
    onRetry: () -> Unit,
    onShowEmail: () -> Unit,
    onSubmitEmail: (String, String) -> Unit,
    onBackToPairing: () -> Unit,
    onReadTerms: () -> Unit,
) {
    val context = LocalContext.current
    val logo = @Composable {
        Image(
            painter = painterResource(context.getThemeDrawable(UR.attr.logo_title_vertical)),
            contentDescription = stringResource(LR.string.podhopper_car_pairing_title),
            modifier = Modifier.size(width = 200.dp, height = 120.dp),
        )
    }

    when (state) {
        is UiState.Starting -> CenteredScroll {
            logo()
            Spacer(Modifier.height(32.dp))
            CircularProgressIndicator(color = MaterialTheme.theme.colors.primaryInteractive01)
        }

        is UiState.ShowingCode -> AdaptivePairing(
            scrollable = {
                logo()
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(LR.string.podhopper_car_pairing_instructions),
                    fontSize = 26.sp,
                    color = MaterialTheme.theme.colors.primaryText01,
                )
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(LR.string.podhopper_car_pairing_email_prompt),
                    fontSize = 20.sp,
                    color = MaterialTheme.theme.colors.primaryText02,
                )
                TextButton(onClick = onShowEmail) {
                    Text(
                        text = stringResource(LR.string.podhopper_car_pairing_email_button),
                        fontSize = 24.sp,
                        color = MaterialTheme.theme.colors.primaryInteractive01,
                    )
                }
            },
            pinned = {
                Text(
                    text = stringResource(LR.string.podhopper_car_pairing_code_label),
                    fontSize = 22.sp,
                    color = MaterialTheme.theme.colors.primaryText02,
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.code,
                    fontSize = 64.sp,
                    fontWeight = FontWeight(700),
                    letterSpacing = 12.sp,
                    color = MaterialTheme.theme.colors.primaryInteractive01,
                )
                Spacer(Modifier.height(16.dp))
                Text(
                    text = stringResource(LR.string.podhopper_car_pairing_waiting),
                    fontSize = 20.sp,
                    color = MaterialTheme.theme.colors.primaryText02,
                )
                // Kept in the pinned area with the code so it is on screen without scrolling.
                Spacer(Modifier.height(20.dp))
                TermsNotice(onReadTerms = onReadTerms)
            },
        )

        is UiState.Expired -> AdaptivePairing(
            scrollable = {
                logo()
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(LR.string.podhopper_car_pairing_expired),
                    fontSize = 26.sp,
                    color = MaterialTheme.theme.colors.primaryText01,
                )
            },
            pinned = { RetryActions(onRetry = onRetry, onShowEmail = onShowEmail) },
        )

        is UiState.NetworkError -> AdaptivePairing(
            scrollable = {
                logo()
                Spacer(Modifier.height(24.dp))
                Text(
                    text = stringResource(LR.string.podhopper_car_pairing_error),
                    fontSize = 26.sp,
                    color = MaterialTheme.theme.colors.primaryText01,
                )
            },
            pinned = { RetryActions(onRetry = onRetry, onShowEmail = onShowEmail) },
        )

        is UiState.EmailEntry -> CenteredScroll {
            logo()
            Spacer(Modifier.height(24.dp))
            EmailContent(isError = false, isSubmitting = false, onSubmit = onSubmitEmail, onBack = onBackToPairing, onReadTerms = onReadTerms)
        }

        is UiState.EmailSubmitting -> CenteredScroll {
            logo()
            Spacer(Modifier.height(24.dp))
            EmailContent(isError = false, isSubmitting = true, onSubmit = onSubmitEmail, onBack = onBackToPairing, onReadTerms = onReadTerms)
        }

        is UiState.EmailError -> CenteredScroll {
            logo()
            Spacer(Modifier.height(24.dp))
            EmailContent(isError = true, isSubmitting = false, onSubmit = onSubmitEmail, onBack = onBackToPairing, onReadTerms = onReadTerms)
        }

        is UiState.Success -> CenteredScroll {
            logo()
            Spacer(Modifier.height(24.dp))
            Text(
                text = stringResource(LR.string.podhopper_car_pairing_success),
                fontSize = 28.sp,
                color = MaterialTheme.theme.colors.primaryText01,
            )
        }
    }
}

/**
 * PodHopper: a layout that keeps [pinned] (the pairing code, or the primary action) always on
 * screen while [scrollable] (logo and instructions) scrolls if there is not enough room. It adapts
 * to whatever the head unit hands us: side by side when the screen is wider than it is tall, stacked
 * with the pinned area below when it is taller than it is wide. Measured at runtime, never hardcoded.
 */
@Composable
private fun AdaptivePairing(
    scrollable: @Composable ColumnScope.() -> Unit,
    pinned: @Composable ColumnScope.() -> Unit,
) {
    BoxWithConstraints(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .padding(horizontal = 48.dp, vertical = 24.dp),
    ) {
        if (maxWidth > maxHeight) {
            Row(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = scrollable,
                )
                Spacer(Modifier.width(32.dp))
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxHeight(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = pinned,
                )
            }
        } else {
            Column(modifier = Modifier.fillMaxSize()) {
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .fillMaxWidth()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = scrollable,
                )
                Spacer(Modifier.height(24.dp))
                Column(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    content = pinned,
                )
            }
        }
    }
}

/**
 * PodHopper: a simple centered, scrollable column for the shorter pairing states (loading, the
 * email form, success). Centers when it fits, scrolls when it does not, on any screen shape.
 */
@Composable
private fun CenteredScroll(content: @Composable ColumnScope.() -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState()),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier = Modifier
                .widthIn(max = 720.dp)
                .fillMaxWidth()
                .padding(horizontal = 48.dp, vertical = 24.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            content = content,
        )
    }
}

@Composable
private fun RetryActions(onRetry: () -> Unit, onShowEmail: () -> Unit) {
    Button(
        onClick = onRetry,
        modifier = Modifier.widthIn(min = 280.dp),
    ) {
        Text(text = stringResource(LR.string.podhopper_car_pairing_retry), fontSize = 24.sp)
    }
    Spacer(Modifier.height(16.dp))
    TextButton(onClick = onShowEmail) {
        Text(
            text = stringResource(LR.string.podhopper_car_pairing_email_button),
            fontSize = 22.sp,
            color = MaterialTheme.theme.colors.primaryInteractive01,
        )
    }
}

@Composable
private fun EmailContent(
    isError: Boolean,
    isSubmitting: Boolean,
    onSubmit: (String, String) -> Unit,
    onBack: () -> Unit,
    onReadTerms: () -> Unit,
) {
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    // PodHopper: the car theme is dark, so set explicit colors. Without this the typed text renders
    // in a near invisible default color and you cannot see what you are entering.
    val fieldColors = TextFieldDefaults.outlinedTextFieldColors(
        textColor = MaterialTheme.theme.colors.primaryText01,
        cursorColor = MaterialTheme.theme.colors.primaryInteractive01,
        focusedBorderColor = MaterialTheme.theme.colors.primaryInteractive01,
        unfocusedBorderColor = MaterialTheme.theme.colors.primaryText02,
        focusedLabelColor = MaterialTheme.theme.colors.primaryInteractive01,
        unfocusedLabelColor = MaterialTheme.theme.colors.primaryText02,
        backgroundColor = Color.Transparent,
    )
    val fieldTextStyle = TextStyle(fontSize = 24.sp)

    Text(
        text = stringResource(LR.string.podhopper_car_email_title),
        fontSize = 28.sp,
        fontWeight = FontWeight(600),
        color = MaterialTheme.theme.colors.primaryText01,
    )
    Spacer(Modifier.height(24.dp))
    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        singleLine = true,
        enabled = !isSubmitting,
        label = { Text(stringResource(LR.string.podhopper_car_email_address_hint)) },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        colors = fieldColors,
        textStyle = fieldTextStyle,
        modifier = Modifier.fillMaxWidth(),
    )
    Spacer(Modifier.height(16.dp))
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        singleLine = true,
        enabled = !isSubmitting,
        label = { Text(stringResource(LR.string.podhopper_car_email_password_hint)) },
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        colors = fieldColors,
        textStyle = fieldTextStyle,
        modifier = Modifier.fillMaxWidth(),
    )
    if (isError) {
        Spacer(Modifier.height(12.dp))
        Text(
            text = stringResource(LR.string.podhopper_car_email_error),
            fontSize = 20.sp,
            color = MaterialTheme.theme.colors.support05,
            modifier = Modifier.fillMaxWidth(),
        )
    }
    Spacer(Modifier.height(20.dp))
    TermsNotice(onReadTerms = onReadTerms)
    Spacer(Modifier.height(12.dp))
    if (isSubmitting) {
        CircularProgressIndicator(color = MaterialTheme.theme.colors.primaryInteractive01)
    } else {
        Button(
            onClick = { onSubmit(email, password) },
            enabled = email.isNotBlank() && password.isNotBlank(),
            modifier = Modifier.widthIn(min = 280.dp),
        ) {
            Text(text = stringResource(LR.string.podhopper_car_email_submit), fontSize = 24.sp)
        }
        Spacer(Modifier.height(16.dp))
        TextButton(onClick = onBack) {
            Text(
                text = stringResource(LR.string.podhopper_car_email_back),
                fontSize = 22.sp,
                color = MaterialTheme.theme.colors.primaryInteractive01,
            )
        }
    }
}
