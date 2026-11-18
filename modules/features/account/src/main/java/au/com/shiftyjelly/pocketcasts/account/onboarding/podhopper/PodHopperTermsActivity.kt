package au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper

import android.content.Context
import android.content.Intent
import android.os.Bundle
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.MaterialTheme
import androidx.compose.material.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.core.view.WindowCompat
import au.com.shiftyjelly.pocketcasts.account.R
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.buttons.RowButton
import au.com.shiftyjelly.pocketcasts.compose.components.TextH30
import au.com.shiftyjelly.pocketcasts.compose.components.TextP40
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperTerms
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * PodHopper: the one-time Terms of Service prompt for people who were already using the app
 * before the Terms existed, so they never passed the sign-up box or the first-run notices.
 *
 * The main screen opens this whenever first-run setup is finished but the current Terms have not
 * been agreed to on this device. There is one way forward, "Agree and continue", which records the
 * agreement on the device and, when signed in, on the account. Back leaves the app instead of
 * returning to it, so the app cannot be used without agreeing; opening it again shows this again.
 * Nothing is deleted or signed out by leaving.
 */
@AndroidEntryPoint
class PodHopperTermsActivity : AppCompatActivity() {

    @Inject
    lateinit var theme: Theme

    @Inject
    lateinit var terms: PodHopperTerms

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        theme.setupThemeForConfig(this, resources.configuration)
        enableEdgeToEdge()

        setContent {
            AppThemeWithBackground(theme.activeTheme) {
                BackHandler { finishAffinity() }
                TermsPrompt(
                    onAgree = {
                        terms.recordAgreement(PodHopperTerms.METHOD_PROMPT)
                        terms.syncToAccountAsync()
                        finish()
                    },
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Already agreed (for example a second copy of this screen was opened): nothing to ask.
        if (!terms.needsAgreement()) {
            finish()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        theme.setupThemeForConfig(this, resources.configuration)
    }

    companion object {
        fun newInstance(context: Context): Intent = Intent(context, PodHopperTermsActivity::class.java)
    }
}

@Composable
private fun TermsPrompt(onAgree: () -> Unit) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxSize()
            .windowInsetsPadding(WindowInsets.safeDrawing)
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 32.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Image(
            painter = painterResource(R.drawable.welcome_lockup),
            contentDescription = null,
            modifier = Modifier.size(160.dp),
        )
        Spacer(modifier = Modifier.height(24.dp))
        TextH30(
            text = stringResource(LR.string.podhopper_terms_prompt_title),
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(12.dp))
        TextP40(
            text = stringResource(LR.string.podhopper_terms_prompt_message),
            textAlign = TextAlign.Center,
            color = MaterialTheme.theme.colors.primaryText02,
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(modifier = Modifier.height(16.dp))
        TextButton(onClick = { openWebPage(context, PodHopperTerms.TERMS_URL) }) {
            TextP40(
                text = stringResource(LR.string.podhopper_terms_prompt_read_terms),
                color = MaterialTheme.theme.colors.primaryInteractive01,
            )
        }
        TextButton(onClick = { openWebPage(context, PodHopperTerms.PRIVACY_URL) }) {
            TextP40(
                text = stringResource(LR.string.podhopper_terms_prompt_read_privacy),
                color = MaterialTheme.theme.colors.primaryInteractive01,
            )
        }
        Spacer(modifier = Modifier.height(24.dp))
        RowButton(
            text = stringResource(LR.string.podhopper_terms_prompt_agree),
            onClick = onAgree,
            includePadding = false,
        )
        Spacer(modifier = Modifier.height(12.dp))
        TermsLinksText(
            text = stringResource(LR.string.podhopper_terms_prompt_footer),
            modifier = Modifier.fillMaxWidth(),
        )
    }
}
