package au.com.shiftyjelly.pocketcasts.account.onboarding.podhopper

import android.app.Activity
import android.content.Context
import android.content.Intent
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import androidx.core.net.toUri
import au.com.shiftyjelly.pocketcasts.compose.components.Clickable
import au.com.shiftyjelly.pocketcasts.compose.components.ClickableTextHelper
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperTerms
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * PodHopper: a sentence in which the words "Terms of Service" and "Privacy Policy" are links that
 * open those pages in the browser. [text] must contain both phrases exactly as they are spelled in
 * podhopper_terms_link_terms and podhopper_terms_link_privacy, Terms first.
 */
@Composable
internal fun TermsLinksText(
    text: String,
    modifier: Modifier = Modifier,
    textAlign: TextAlign = TextAlign.Center,
) {
    val context = LocalContext.current
    ClickableTextHelper(
        text = text,
        clickables = listOf(
            Clickable(
                text = stringResource(LR.string.podhopper_terms_link_terms),
                data = PodHopperTerms.TERMS_URL,
                onClick = { url -> openWebPage(context, url) },
            ),
            Clickable(
                text = stringResource(LR.string.podhopper_terms_link_privacy),
                data = PodHopperTerms.PRIVACY_URL,
                onClick = { url -> openWebPage(context, url) },
            ),
        ),
        lineHeight = 20.sp,
        modifier = modifier,
        textAlign = textAlign,
        color = MaterialTheme.theme.colors.primaryText02,
        fontSize = 14.sp,
    )
}

/** Opens [url] in the browser. Quietly does nothing on a device that has no browser at all. */
internal fun openWebPage(context: Context, url: String) {
    try {
        val intent = Intent(Intent.ACTION_VIEW, url.toUri())
        if (context !is Activity) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    } catch (e: Exception) {
        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Could not open $url: ${e.message}")
    }
}
