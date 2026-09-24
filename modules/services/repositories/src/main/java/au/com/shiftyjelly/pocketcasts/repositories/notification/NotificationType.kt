package au.com.shiftyjelly.pocketcasts.repositories.notification

import android.content.Context
import android.content.Intent
import androidx.annotation.PluralsRes
import androidx.annotation.StringRes
import au.com.shiftyjelly.pocketcasts.deeplink.AppOpenDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.CreateAccountDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.DownloadsDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.ImportDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.ShowFiltersDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.ShowUpNextTabDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.SmartFoldersDeepLink
import au.com.shiftyjelly.pocketcasts.deeplink.ThemesDeepLink
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.preferences.Settings.NotificationId
import au.com.shiftyjelly.pocketcasts.localization.R as LR

sealed interface NotificationType {
    val notificationId: Int
    val subcategory: String
    val analyticsType: String

    @get:StringRes
    val titleRes: Int

    @get:StringRes
    val messageRes: Int?

    @get:PluralsRes
    val messagePluralRes: Int?

    fun formattedMessage(context: Context, count: Int = 0): String {
        messagePluralRes?.takeIf { count > 0 }?.let { pluralRes ->
            return context.resources.getQuantityString(pluralRes, count, count)
        }
        return messageRes?.let { context.getString(it) }.orEmpty()
    }

    fun toIntent(context: Context): Intent
    fun isSettingsToggleOn(settings: Settings): Boolean

    companion object {
        fun fromSubCategory(subCategory: String): NotificationType? {
            val allSupportedNotifications = buildList {
                addAll(OnboardingNotificationType.values)
                addAll(ReEngagementNotificationType.values)
                addAll(NewFeaturesAndTipsNotificationType.values)
            }
            return allSupportedNotifications.find { it.subcategory == subCategory }
        }
    }
}

sealed class OnboardingNotificationType(
    override val notificationId: Int,
    override val subcategory: String,
    override val analyticsType: String,
    @StringRes override val titleRes: Int,
    val dayOffset: Int,
) : NotificationType {
    abstract override val messageRes: Int

    override fun isSettingsToggleOn(settings: Settings): Boolean {
        return settings.dailyRemindersNotification.value
    }

    override val messagePluralRes: Int? get() = null

    data object Sync : OnboardingNotificationType(
        notificationId = NotificationId.ONBOARDING_SYNC.value,
        subcategory = SUBCATEGORY_SYNC,
        titleRes = LR.string.notification_sync_title,
        dayOffset = 0,
        analyticsType = "onboardingSignUp",
    ) {
        override val messageRes get() = LR.string.notification_sync_message

        override fun toIntent(context: Context) = CreateAccountDeepLink.toIntent(context)
    }

    data object Import : OnboardingNotificationType(
        notificationId = NotificationId.ONBOARDING_IMPORT.value,
        subcategory = SUBCATEGORY_IMPORT,
        titleRes = LR.string.notification_import_title,
        dayOffset = 1,
        analyticsType = "onboardingImport",
    ) {
        override val messageRes get() = LR.string.notification_import_message

        override fun toIntent(context: Context) = ImportDeepLink.toIntent(context)
    }

    data object UpNext : OnboardingNotificationType(
        notificationId = NotificationId.ONBOARDING_UPNEXT.value,
        subcategory = SUBCATEGORY_UP_NEXT,
        titleRes = LR.string.notification_up_next_title,
        dayOffset = 2,
        analyticsType = "onboardingUpNext",
    ) {
        override val messageRes get() = LR.string.notification_up_next_message

        override fun toIntent(context: Context) = ShowUpNextTabDeepLink.toIntent(context)
    }

    data object Filters : OnboardingNotificationType(
        notificationId = NotificationId.ONBOARDING_FILTERS.value,
        subcategory = SUBCATEGORY_FILTERS,
        titleRes = LR.string.notification_filters_title,
        dayOffset = 3,
        analyticsType = "onboardingFilters",
    ) {
        override val messageRes get() = LR.string.notification_filters_message_2

        override fun toIntent(context: Context) = ShowFiltersDeepLink.toIntent(context)
    }

    data object Themes : OnboardingNotificationType(
        notificationId = NotificationId.ONBOARDING_THEMES.value,
        subcategory = SUBCATEGORY_THEMES,
        titleRes = LR.string.notification_themes_title,
        dayOffset = 4,
        analyticsType = "onboardingThemes",
    ) {
        override val messageRes get() = LR.string.notification_themes_message

        override fun toIntent(context: Context) = ThemesDeepLink.toIntent(context)
    }

    companion object {
        const val SUBCATEGORY_SYNC = "sync"
        const val SUBCATEGORY_IMPORT = "import"
        const val SUBCATEGORY_UP_NEXT = "up_next"
        const val SUBCATEGORY_FILTERS = "filters"
        const val SUBCATEGORY_THEMES = "themes"
        const val SUBCATEGORY_STAFF_PICKS = "staff_picks"

        // PodHopper: the StaffPicks notification is gone. Its text was PodHopper's, but tapping it
        // opened Pocket Casts' own staff picks list, fetched from their servers. The subcategory
        // above is kept only so an install that queued one before this change can cancel it, and so
        // that a queued copy resolves to nothing and is quietly dropped.
        val values: List<OnboardingNotificationType>
            get() = listOf(
                Sync,
                Import,
                UpNext,
                Filters,
                Themes,
            )
    }
}

sealed class ReEngagementNotificationType(
    override val subcategory: String,
    @StringRes override val titleRes: Int,
    @StringRes override val messageRes: Int? = null,
    @PluralsRes override val messagePluralRes: Int? = null,
) : NotificationType {

    override val notificationId: Int
        get() = ReEngagementNotificationType.notificationId

    override fun isSettingsToggleOn(settings: Settings): Boolean {
        return settings.newFeaturesNotification.value
    }

    override val analyticsType: String
        get() = "reengagementWeekly"

    data object WeMissYou : ReEngagementNotificationType(
        subcategory = SUBCATEGORY_REENGAGE_WE_MISS_YOU,
        titleRes = LR.string.notification_reengage_we_miss_you_title,
        messageRes = LR.string.notification_reengage_we_miss_you_message,
    ) {
        // PodHopper: opens the app, as the message invites. Upstream this opened Pocket Casts'
        // staff picks list, which PodHopper has no equivalent of.
        override fun toIntent(context: Context): Intent = AppOpenDeepLink.toIntent(context)
    }

    data object CatchUpOffline : ReEngagementNotificationType(
        subcategory = SUBCATEGORY_REENGAGE_CATCH_UP_OFFLINE,
        titleRes = LR.string.notification_reengage_catch_up_offline_title,
        messagePluralRes = LR.plurals.notification_reengage_catch_up_offline_message,
    ) {
        override fun toIntent(context: Context): Intent = DownloadsDeepLink.toIntent(context)
    }

    companion object {
        const val SUBCATEGORY_REENGAGE_WE_MISS_YOU = "we_miss_you"
        const val SUBCATEGORY_REENGAGE_CATCH_UP_OFFLINE = "catch_up_offline"

        val notificationId: Int
            get() = NotificationId.RE_ENGAGEMENT.value

        val values: List<ReEngagementNotificationType>
            get() = listOf(
                WeMissYou,
                CatchUpOffline,
            )
    }
}

sealed class NewFeaturesAndTipsNotificationType(
    override val notificationId: Int,
    override val subcategory: String,
    override val analyticsType: String,
    @StringRes override val titleRes: Int,
    @StringRes override val messageRes: Int? = null,
    @PluralsRes override val messagePluralRes: Int? = null,
) : NotificationType {

    override fun isSettingsToggleOn(settings: Settings) = settings.newFeaturesNotification.value

    data object SmartFolders : NewFeaturesAndTipsNotificationType(
        notificationId = NotificationId.FEATURES_AND_TIPS.value,
        subcategory = SUBCATEGORY_SMART_FOLDERS,
        titleRes = LR.string.notification_new_features_smart_folders_title,
        messageRes = LR.string.notification_new_features_smart_folders_message,
        analyticsType = "newFeatureSuggestedFolders",
    ) {
        override fun toIntent(context: Context) = SmartFoldersDeepLink.toIntent(context)
    }

    companion object {
        const val SUBCATEGORY_SMART_FOLDERS = "smart_folders"

        val values: List<NotificationType> get() = listOf(
            SmartFolders,
        )
    }
}
