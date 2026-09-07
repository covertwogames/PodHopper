package au.com.shiftyjelly.pocketcasts.settings.about

import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.MaterialTheme
import androidx.compose.material.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import kotlinx.coroutines.launch
import au.com.shiftyjelly.pocketcasts.repositories.podhopper.PodHopperCarDiagnostics
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.core.net.toUri
import androidx.fragment.app.Fragment
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.components.HorizontalDivider
import au.com.shiftyjelly.pocketcasts.compose.extensions.contentWithoutConsumedInsets
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.localization.BuildConfig
import au.com.shiftyjelly.pocketcasts.settings.LicensesFragment
import au.com.shiftyjelly.pocketcasts.settings.R
import au.com.shiftyjelly.pocketcasts.settings.components.RowTextButton
import au.com.shiftyjelly.pocketcasts.ui.helper.FragmentHostListener
import au.com.shiftyjelly.pocketcasts.utils.extensions.pxToDp
import au.com.shiftyjelly.pocketcasts.utils.rateUs
import au.com.shiftyjelly.pocketcasts.views.fragments.BaseFragment
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject
import timber.log.Timber
import au.com.shiftyjelly.pocketcasts.localization.R as LR

@AndroidEntryPoint
class AboutFragment : BaseFragment() {

    @Inject lateinit var settings: Settings

    // PodHopper: the car's one-tap diagnostics upload, wired into the phone's About screen so a
    // phone log can be captured the same way. The phone previously had no way to get its log out
    // at all: upstream's Logs screen only opened from the Pocket Casts support page, which the
    // de-branding removed.
    @Inject lateinit var podHopperCarDiagnostics: PodHopperCarDiagnostics

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?,
    ) = contentWithoutConsumedInsets {
        val bottomInset = settings.bottomInset.collectAsStateWithLifecycle(initialValue = 0)

        AppThemeWithBackground(theme.activeTheme) {
            AboutPage(
                onShareLogs = { podHopperCarDiagnostics.uploadNow(reason = "manual") },
                bottomInset = bottomInset.value.pxToDp(LocalContext.current).dp,
                onBackPress = { closeFragment() },
                openFragment = { fragment ->
                    (activity as? FragmentHostListener)?.addFragment(fragment)
                },
            )
        }
    }

    private fun closeFragment() {
        (activity as? FragmentHostListener)?.closeModal(this)
    }
}

@Composable
private fun AboutPage(
    onShareLogs: suspend () -> Boolean,
    bottomInset: Dp,
    onBackPress: () -> Unit,
    openFragment: (Fragment) -> Unit,
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .background(MaterialTheme.theme.colors.primaryUi02),
    ) {
        ThemedTopAppBar(
            title = stringResource(LR.string.settings_title_about),
            onNavigationClick = onBackPress,
        )
        LazyColumn(
            horizontalAlignment = Alignment.CenterHorizontally,
            contentPadding = PaddingValues(bottom = bottomInset),
        ) {
            item {
                Image(
                    painter = painterResource(
                        if (MaterialTheme.theme.isDark) {
                            R.drawable.podhopper_lockup_stacked_ondark
                        } else {
                            R.drawable.podhopper_lockup_stacked_onlight
                        },
                    ),
                    contentDescription = stringResource(LR.string.settings_app_icon),
                    modifier = Modifier
                        .padding(top = 56.dp)
                        .fillMaxWidth(0.6f)
                        .aspectRatio(900f / 590f),
                )
            }
            item {
                Text(
                    text = stringResource(LR.string.settings_version, BuildConfig.VERSION_NAME, BuildConfig.VERSION_CODE.toString()),
                    style = MaterialTheme.typography.body1,
                    modifier = Modifier.padding(top = 8.dp),
                    textAlign = TextAlign.Center,
                    color = MaterialTheme.theme.colors.primaryText02,
                )
            }
            item {
                HorizontalDivider(
                    modifier = Modifier.padding(top = 56.dp, bottom = 8.dp),
                )
            }
            item {
                RowTextButton(
                    text = stringResource(LR.string.settings_about_rate_us),
                    onClick = { rateUs(context) },
                )
            }
            item {
                RowTextButton(
                    text = stringResource(LR.string.settings_about_share_with_friends),
                    onClick = { shareWithFriends(context) },
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                RowTextButton(
                    text = stringResource(LR.string.settings_about_website),
                    secondaryText = "podhopper.app",
                    onClick = { openUrl("https://podhopper.app", context) },
                )
            }
            item {
                HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
            }
            item {
                RowTextButton(
                    text = stringResource(LR.string.settings_about_acknowledgements),
                    onClick = { openFragment(LicensesFragment()) },
                )
            }
            item {
                val scope = rememberCoroutineScope()
                var status by remember { mutableStateOf<Int?>(null) }
                RowTextButton(
                    text = stringResource(status ?: LR.string.podhopper_share_logs_title),
                    onClick = {
                        if (status != LR.string.podhopper_share_logs_uploading) {
                            status = LR.string.podhopper_share_logs_uploading
                            scope.launch {
                                val ok = onShareLogs()
                                status = if (ok) LR.string.podhopper_share_logs_done else LR.string.podhopper_share_logs_failed
                            }
                        }
                    },
                )
            }
        }
    }
}

private fun shareWithFriends(context: Context) {
    val intent = Intent(Intent.ACTION_SEND)
    intent.type = "text/plain"
    intent.putExtra(Intent.EXTRA_TEXT, context.getString(LR.string.settings_about_share_with_friends_message))
    try {
        context.startActivity(Intent.createChooser(intent, context.getString(LR.string.share)))
    } catch (e: IllegalStateException) {
        // Not attached to activity anymore
    }
}

private fun openUrl(url: String, context: Context) {
    try {
        context.startActivity(Intent(Intent.ACTION_VIEW, url.toUri()))
    } catch (e: Exception) {
        Timber.i("Failed to open url $url")
    }
}

@Preview
@Composable
private fun AboutPagePreview() {
    AboutPage(
        onShareLogs = { true },
        bottomInset = 0.dp,
        onBackPress = {},
        openFragment = {},
    )
}
