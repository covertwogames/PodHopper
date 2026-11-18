package au.com.shiftyjelly.pocketcasts.settings

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.tooling.preview.PreviewParameter
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.fragment.app.Fragment
import au.com.shiftyjelly.pocketcasts.compose.AppThemeWithBackground
import au.com.shiftyjelly.pocketcasts.compose.bars.ThemedTopAppBar
import au.com.shiftyjelly.pocketcasts.compose.components.SettingRow
import au.com.shiftyjelly.pocketcasts.compose.preview.ThemePreviewParameterProvider
import au.com.shiftyjelly.pocketcasts.compose.theme
import au.com.shiftyjelly.pocketcasts.settings.about.AboutFragment
import au.com.shiftyjelly.pocketcasts.settings.carsync.PodHopperCarSyncFragment
import au.com.shiftyjelly.pocketcasts.settings.deleteaccount.PodHopperDeleteAccountFragment
import au.com.shiftyjelly.pocketcasts.settings.devices.PodHopperDevicesFragment
import au.com.shiftyjelly.pocketcasts.settings.developer.DeveloperFragment
import au.com.shiftyjelly.pocketcasts.settings.history.HistoryFragment
import au.com.shiftyjelly.pocketcasts.settings.notifications.NotificationsSettingsFragment
import au.com.shiftyjelly.pocketcasts.settings.privacy.PrivacyFragment
import au.com.shiftyjelly.pocketcasts.ui.theme.Theme
import au.com.shiftyjelly.pocketcasts.views.fragments.BatteryRestrictionsSettingsFragment
import au.com.shiftyjelly.pocketcasts.images.R as IR
import au.com.shiftyjelly.pocketcasts.localization.R as LR
import au.com.shiftyjelly.pocketcasts.settings.R as SR

@Composable
fun SettingsFragmentPage(
    isDebug: Boolean,
    isUnrestrictedBattery: Boolean,
    bottomInset: Dp,
    onBackPress: () -> Unit,
    openFragment: (Fragment) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(
        modifier = modifier,
    ) {
        ThemedTopAppBar(
            title = stringResource(LR.string.settings),
            bottomShadow = true,
            onNavigationClick = onBackPress,
        )
        LazyColumn(
            contentPadding = PaddingValues(top = 8.dp, bottom = bottomInset + 8.dp),
            modifier = Modifier.background(MaterialTheme.theme.colors.primaryUi02),
        ) {
            if (isDebug) {
                item {
                    DeveloperRow(onClick = { openFragment(DeveloperFragment()) })
                }
            }

            if (isDebug) {
                item {
                    BetaFeatures(onClick = { openFragment(BetaFeaturesFragment()) })
                }
            }

            if (!isUnrestrictedBattery && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                item {
                    BatteryOptimizationRow(onClick = { openFragment(BatteryRestrictionsSettingsFragment.newInstance(closeButton = false)) })
                }
            }

            item {
                GeneralRow(onClick = { openFragment(PlaybackSettingsFragment()) })
            }
            item {
                NotificationRow(onClick = { openFragment(NotificationsSettingsFragment()) })
            }
            item {
                AppearanceRow(
                    onClick = { openFragment(AppearanceSettingsFragment.newInstance()) },
                )
            }
            item {
                StorageAndDataUseRow(onClick = { openFragment(StorageSettingsFragment()) })
            }
            item {
                AutoArchiveRow(onClick = { openFragment(AutoArchiveFragment()) })
            }
            item {
                AutoDownloadRow(onClick = { openFragment(AutoDownloadSettingsFragment()) })
            }
            item {
                AutoAddToUpNextRow(onClick = { openFragment(AutoAddSettingsFragment()) })
            }
            item {
                HeadphoneControlsRow(onClick = { openFragment(HeadphoneControlsSettingsFragment()) })
            }
            item {
                ImportAndExportOpmlRow(onClick = { openFragment(ExportSettingsFragment()) })
            }
            item {
                RestoreFromLocalHistoryRow(onClick = { openFragment(HistoryFragment()) })
            }
            item {
                AdvancedRow(onClick = { openFragment(AdvancedSettingsFragment()) })
            }
            item {
                SyncAccountToCarRow(onClick = { openFragment(PodHopperCarSyncFragment()) })
                SignedInDevicesRow(onClick = { openFragment(PodHopperDevicesFragment()) })
            }
            item {
                PrivacyRow(onClick = { openFragment(PrivacyFragment()) })
            }
            item {
                AboutRow(onClick = { openFragment(AboutFragment()) })
            }
            item {
                DeleteAccountRow(onClick = { openFragment(PodHopperDeleteAccountFragment()) })
            }
        }
    }
}

@Composable
private fun DeveloperRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_developer),
        icon = painterResource(SR.drawable.ic_developer_mode),
        iconGradientColors = listOf(
            MaterialTheme.theme.colors.gradient03A,
            MaterialTheme.theme.colors.gradient03E,
        ),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun BetaFeatures(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_beta_features),
        icon = painterResource(IR.drawable.ic_science),
        iconGradientColors = listOf(
            MaterialTheme.theme.colors.gradient03A,
            MaterialTheme.theme.colors.gradient03E,
        ),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun BatteryOptimizationRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_battery_settings),
        icon = painterResource(SR.drawable.ic_baseline_warning_amber_24),
        iconGradientColors = listOf(
            MaterialTheme.theme.colors.gradient03A,
            MaterialTheme.theme.colors.gradient03E,
        ),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun GeneralRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_playback),
        icon = painterResource(IR.drawable.ic_profile_settings),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun NotificationRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_notifications),
        icon = painterResource(SR.drawable.settings_notifications),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun AppearanceRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_appearance),
        icon = painterResource(SR.drawable.settings_appearance),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun StorageAndDataUseRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_storage),
        icon = painterResource(SR.drawable.settings_storage),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun AutoArchiveRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_auto_archive),
        icon = painterResource(SR.drawable.settings_auto_archive),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun AutoDownloadRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_auto_download),
        icon = painterResource(SR.drawable.settings_auto_download),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun AutoAddToUpNextRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_auto_add_to_up_next),
        icon = painterResource(IR.drawable.ic_upnext_playlast),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun HeadphoneControlsRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_headphone_controls),
        icon = painterResource(IR.drawable.ic_headphone),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun ImportAndExportOpmlRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_import_export),
        icon = painterResource(SR.drawable.settings_import_export),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun PrivacyRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_privacy),
        icon = painterResource(SR.drawable.whatsnew_privacy),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun AboutRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_about),
        icon = painterResource(SR.drawable.settings_about),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun DeleteAccountRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(SR.string.podhopper_delete_account_row),
        icon = painterResource(SR.drawable.ic_baseline_warning_amber_24),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun RestoreFromLocalHistoryRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.restore_from_local_history),
        icon = painterResource(IR.drawable.ic_history),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun AdvancedRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(LR.string.settings_title_advanced),
        icon = painterResource(SR.drawable.settings_advanced),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun SyncAccountToCarRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(SR.string.podhopper_car_sync_title),
        icon = painterResource(SR.drawable.podhopper_settings_car),
        modifier = Modifier.rowModifier(onClick),
    )
}

@Composable
private fun SignedInDevicesRow(onClick: () -> Unit) {
    SettingRow(
        primaryText = stringResource(SR.string.podhopper_devices_title),
        icon = painterResource(IR.drawable.ic_profile_settings),
        modifier = Modifier.rowModifier(onClick),
    )
}

fun Modifier.rowModifier(onClick: () -> Unit): Modifier = this
    .clickable { onClick() }
    .padding(vertical = 6.dp)

@Preview
@Composable
private fun SettingsPagePreview(@PreviewParameter(ThemePreviewParameterProvider::class) themeType: Theme.ThemeType) {
    AppThemeWithBackground(themeType) {
        SettingsFragmentPage(
            isDebug = true,
            isUnrestrictedBattery = false,
            onBackPress = {},
            openFragment = {},
            bottomInset = 0.dp,
        )
    }
}
