package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.content.SharedPreferences
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import org.json.JSONObject

/**
 * PodHopper: keeps track of whether the person using this phone has agreed to the current Terms
 * of Service, and copies that agreement onto their account when they have one.
 *
 * Two records exist on purpose:
 *
 *  - On this device: which version was agreed to, when, and how (the sign-up checkbox, the notice
 *    on the sign-in or no-account path, or the one-time prompt shown to people who were already
 *    using the app). This is what decides whether the prompt is shown, so it works with no
 *    account and with no connection.
 *
 *  - On the account: the same facts, sent once per account through the accept_terms function.
 *    The server stamps its own time on the row and keeps the first record for each version.
 *
 * Only the phone app records agreement. The car and the watch cannot create accounts; they show a
 * notice on their sign-in screens instead.
 */
@Singleton
class PodHopperTerms @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val settings: Settings,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    private fun prefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /** True until the current version of the Terms has been agreed to on this device. */
    fun needsAgreement(): Boolean = prefs().getString(PREF_VERSION, null) != TERMS_VERSION

    /**
     * Records that the current Terms were agreed to on this device just now. [method] is one of
     * the METHOD_ constants.
     *
     * An agreement that is still waiting to be copied to an account is left as it is. That keeps
     * the original moment and method when someone ticks the box to create an account, has to
     * confirm their email first, and only then signs in.
     */
    fun recordAgreement(method: String) {
        val prefs = prefs()
        val alreadyAgreed = prefs.getString(PREF_VERSION, null) == TERMS_VERSION
        val waitingForAccount = prefs.getString(PREF_UPLOADED_FOR, null) == null
        if (alreadyAgreed && waitingForAccount) {
            return
        }
        prefs.edit()
            .putString(PREF_VERSION, TERMS_VERSION)
            .putLong(PREF_AGREED_AT_MS, System.currentTimeMillis())
            .putString(PREF_METHOD, method)
            .remove(PREF_UPLOADED_FOR)
            .apply()
    }

    /** Runs [syncToAccount] in the background. Safe to call from the main thread, and often. */
    fun syncToAccountAsync() {
        applicationScope.launch(Dispatchers.IO) {
            syncToAccount()
        }
    }

    /**
     * Copies this device's agreement onto the signed-in account, once per account. Does nothing
     * when nobody is signed in, when nothing has been agreed to, or when it was already sent for
     * this account. Blocking network call; never throws. A failed attempt is simply retried the
     * next time this is called.
     */
    @Synchronized
    fun syncToAccount() {
        try {
            if (needsAgreement() || !supabaseClient.isLoggedIn()) {
                return
            }
            val prefs = prefs()
            val accountKey = TERMS_VERSION + "|" + settings.podhopperEmail.value
            if (prefs.getString(PREF_UPLOADED_FOR, null) == accountKey) {
                return
            }
            val params = JSONObject()
                .put("p_version", TERMS_VERSION)
                .put("p_platform", "android")
                .put("p_app_version", settings.getVersion())
                .put("p_method", prefs.getString(PREF_METHOD, METHOD_NOTICE))
            val agreedAtMs = prefs.getLong(PREF_AGREED_AT_MS, 0L)
            if (agreedAtMs > 0L) {
                params.put("p_accepted_at", isoUtc(agreedAtMs))
            }
            supabaseClient.rpc("accept_terms", params)
            prefs.edit().putString(PREF_UPLOADED_FOR, accountKey).apply()
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Terms agreement saved to the account (version $TERMS_VERSION)")
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Terms agreement not saved to the account yet: ${e.message}")
        }
    }

    private fun isoUtc(timeMs: Long): String {
        val format = SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US)
        format.timeZone = TimeZone.getTimeZone("UTC")
        return format.format(Date(timeMs))
    }

    companion object {
        /**
         * The effective date of the Terms this build was made for. Change it only when the Terms
         * themselves change: a new value shows the one-time prompt to everyone again.
         */
        const val TERMS_VERSION = "2026-07-01"

        const val TERMS_URL = Settings.INFO_TOS_URL
        const val PRIVACY_URL = Settings.INFO_PRIVACY_URL

        /** Ticked the box while creating an account. */
        const val METHOD_CHECKBOX = "checkbox"

        /** Tapped Agree on the one-time prompt shown to people already using the app. */
        const val METHOD_PROMPT = "prompt"

        /** Signed in on a screen that says signing in means agreeing. */
        const val METHOD_SIGN_IN = "signin"

        /** Chose to continue without an account on a screen that says continuing means agreeing. */
        const val METHOD_NOTICE = "notice"

        private const val PREFS_NAME = "podhopper_terms"
        private const val PREF_VERSION = "agreed_version"
        private const val PREF_AGREED_AT_MS = "agreed_at_ms"
        private const val PREF_METHOD = "agreed_method"
        private const val PREF_UPLOADED_FOR = "uploaded_for"
    }
}
