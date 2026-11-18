package au.com.shiftyjelly.pocketcasts

import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.fragment.app.Fragment
import au.com.shiftyjelly.pocketcasts.compose.AutomotiveTheme
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.localization.R as LR

/**
 * PodHopper: the car Terms of Service screen, reached while parked from the About page. The car
 * has no web browser, so the full text is built in (see [PodHopperTermsText]). The same page is
 * shown from the car sign-in screen's "Read the Terms" button.
 */
class AutomotiveTermsFragment : Fragment() {

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        AutomotiveTheme {
            PodHopperTermsPage()
        }
    }
}

/**
 * The full Terms, laid out for the car with the same type sizes as the car Privacy Policy page.
 * A lazy list, because the text is long and a head unit should only lay out what is on screen.
 */
@Composable
internal fun PodHopperTermsPage(modifier: Modifier = Modifier) {
    val primary = MaterialTheme.theme.colors.primaryText01
    val secondary = MaterialTheme.theme.colors.primaryText02

    LazyColumn(
        modifier = modifier.fillMaxWidth(),
        contentPadding = PaddingValues(horizontal = 48.dp, vertical = 40.dp),
    ) {
        item {
            Text(
                text = PodHopperTermsText.TITLE,
                fontSize = 34.sp,
                fontWeight = FontWeight.Bold,
                color = primary,
            )
        }
        item {
            Text(
                text = stringResource(LR.string.podhopper_car_terms_effective, PodHopperTermsText.EFFECTIVE_DATE),
                fontSize = 20.sp,
                color = secondary,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
        item {
            Text(
                text = stringResource(LR.string.podhopper_car_terms_current),
                fontSize = 20.sp,
                color = secondary,
                modifier = Modifier.padding(top = 2.dp),
            )
        }
        PodHopperTermsText.sections.forEach { section ->
            item {
                Text(
                    text = section.heading,
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = primary,
                    modifier = Modifier.padding(top = 28.dp, bottom = 6.dp),
                )
            }
            items(section.blocks) { block ->
                when (block) {
                    is TermsBlock.Paragraph -> {
                        val lead = block.lead
                        Text(
                            text = buildAnnotatedString {
                                if (lead != null) {
                                    withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primary)) {
                                        append(lead)
                                    }
                                    append(" ")
                                }
                                append(block.text)
                            },
                            fontSize = 24.sp,
                            lineHeight = 34.sp,
                            fontWeight = if (block.strong) FontWeight.Bold else FontWeight.Normal,
                            color = if (block.strong) primary else secondary,
                            modifier = Modifier.padding(bottom = 14.dp),
                        )
                    }

                    is TermsBlock.Bullet -> {
                        Row(modifier = Modifier.padding(start = 12.dp, bottom = 10.dp)) {
                            Text(
                                text = "•",
                                fontSize = 24.sp,
                                lineHeight = 34.sp,
                                color = if (block.strong) primary else secondary,
                                modifier = Modifier.padding(end = 14.dp),
                            )
                            Text(
                                text = block.text,
                                fontSize = 24.sp,
                                lineHeight = 34.sp,
                                fontWeight = if (block.strong) FontWeight.Bold else FontWeight.Normal,
                                color = if (block.strong) primary else secondary,
                            )
                        }
                    }
                }
            }
        }
    }
}
