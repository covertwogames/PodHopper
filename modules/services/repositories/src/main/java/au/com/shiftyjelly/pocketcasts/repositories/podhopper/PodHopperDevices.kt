package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.content.SharedPreferences
import android.os.Build
import au.com.shiftyjelly.pocketcasts.coroutines.di.ApplicationScope
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.utils.AppPlatform
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.Lazy
import dagger.hilt.android.qualifiers.ApplicationContext
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * PodHopper: this install's place on the account's list of signed-in devices, and what happens to
 * the device when its sign-in ends.
 *
 * While signed in, the install keeps itself on the list (what kind of device it is, its model and
 * app version), which is what the phone's "Signed-in devices" screen shows.
 *
 * When the device ends up signed out, the rule depends on what it is:
 *
 *  - A car or a watch clears its library, however the sign-out happened: Sign Out was tapped, it
 *    was removed from the list on another device, the account was deleted, or the sign-in expired.
 *    Neither can be used signed out, and a signed-out car or watch should never be holding
 *    someone's podcasts for the next driver or wearer to inherit. Signing in again brings the
 *    library back from the account. The same check runs at every start, so a car or watch that is
 *    found signed out with a library still on it is cleared then.
 *
 *  - A phone or tablet clears its library only when it was removed from the list on another
 *    device. It learns that by asking the server, after the server has ended its sign-in. Tapping
 *    Log out on the phone itself, or a sign-in that simply expires, leaves the library alone: the
 *    phone works without an account, and it may hold things that were never synced.
 *
 * [start] is called once from each app's Application class.
 */
@Singleton
class PodHopperDevices @Inject constructor(
    private val supabaseClient: SupabaseClient,
    private val settings: Settings,
    private val localData: Lazy<PodHopperLocalData>,
    @ApplicationContext private val context: Context,
    @ApplicationScope private val applicationScope: CoroutineScope,
) {
    /** One sign-in on the account, as shown on the phone's "Signed-in devices" screen. */
    data class Device(
        val sessionId: String,
        /** "phone", "tablet", "car", "watch", "other", or "unknown" for an older app version. */
        val type: String,
        val model: String,
        val appVersion: String,
        val signedInAtMs: Long,
        val lastActiveAtMs: Long,
        val isThisDevice: Boolean,
    )

    private val started = AtomicBoolean(false)
    private val platform: AppPlatform by lazy { Util.getAppPlatform(context) }

    @Volatile
    private var registerJob: Job? = null

    private fun prefs(): SharedPreferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Starts following the sign-in state. The current state is handled straight away, so this
     * also covers "the app was just started". Each change is handled to completion before the
     * next one is looked at, so a sign-in that follows a sign-out waits for the clearing to end.
     */
    fun start() {
        if (!started.compareAndSet(false, true)) {
            return
        }
        supabaseClient.loginState
            .distinctUntilChanged()
            .onEach { signedIn ->
                if (signedIn) {
                    keepOnTheList()
                } else {
                    registerJob?.cancel()
                    withContext(Dispatchers.IO) {
                        try {
                            onSignedOut()
                        } catch (e: Exception) {
                            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper sign-out handling failed: ${e.message}")
                        }
                    }
                }
            }
            .launchIn(applicationScope)
    }

    private fun onSignedOut() {
        when (platform) {
            AppPlatform.Automotive, AppPlatform.WearOs -> {
                localData.get().clearIfSignedOut(reason = "this device is signed out", onlyIfSomethingStored = true)
                supabaseClient.clearEndedByServer()
            }

            AppPlatform.Phone -> {
                if (!supabaseClient.wasEndedByServer()) {
                    return
                }
                // The server ended this sign-in. Was it removed from the list on purpose? If the
                // server cannot be asked right now, the question is kept and asked at next start.
                val removedFromList = try {
                    supabaseClient.wasSignedOutFromList(installId())
                } catch (e: Exception) {
                    LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper could not check why this sign-in ended, will ask again: ${e.message}")
                    return
                }
                if (removedFromList) {
                    localData.get().clearIfSignedOut(reason = "removed from the signed-in devices list", onlyIfSomethingStored = false)
                }
                // Cleared last, so an interrupted clearing is repeated at the next start.
                supabaseClient.clearEndedByServer()
            }
        }
    }

    /**
     * Puts this install on the account's list, retrying a few times because a car often starts
     * before it has a connection. Runs on its own so a sign-out is never kept waiting behind it.
     */
    private fun keepOnTheList() {
        registerJob?.cancel()
        registerJob = applicationScope.launch(Dispatchers.IO) {
            for (waitMs in REGISTER_RETRY_WAITS_MS) {
                delay(waitMs)
                if (!supabaseClient.isLoggedIn()) {
                    return@launch
                }
                if (registerNow()) {
                    return@launch
                }
            }
        }
    }

    /** Adds or refreshes this install on the list. Blocking. Returns false when it did not go through. */
    fun registerNow(): Boolean {
        return try {
            supabaseClient.registerDevice(
                installId = installId(),
                deviceType = deviceType(),
                platform = platformName(),
                model = deviceModel(),
                appVersion = settings.getVersion(),
            )
            true
        } catch (e: Exception) {
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper could not update the signed-in devices list: ${e.message}")
            false
        }
    }

    /**
     * Every sign-in on the account, this device first. Refreshes this device's own entry first so
     * it shows with its details. Blocking; throws when the list cannot be loaded.
     */
    fun loadDevices(): List<Device> {
        registerNow()
        val rows = supabaseClient.listDevices()
        val devices = ArrayList<Device>(rows.length())
        for (i in 0 until rows.length()) {
            val row = rows.getJSONObject(i)
            devices.add(
                Device(
                    sessionId = row.getString("session_id"),
                    type = row.optString("device_type", "unknown"),
                    model = row.optString("model", ""),
                    appVersion = row.optString("app_version", ""),
                    signedInAtMs = parseServerTime(row.optString("signed_in_at", "")),
                    lastActiveAtMs = parseServerTime(row.optString("last_active_at", "")),
                    isThisDevice = row.optBoolean("is_current", false),
                ),
            )
        }
        return devices
    }

    /** Signs one other device out of the account. Blocking; throws on failure. */
    fun signOutDevice(sessionId: String): Boolean = supabaseClient.signOutDevice(sessionId)

    /** Signs every other device out of the account. Returns how many. Blocking; throws on failure. */
    fun signOutOtherDevices(): Int = supabaseClient.signOutOtherDevices()

    /**
     * A random id this install made for itself. It names the device, not the account, so it is
     * kept across sign-outs: that is how a phone that was removed from the list can find out.
     */
    private fun installId(): String {
        val prefs = prefs()
        val existing = prefs.getString(PREF_INSTALL_ID, null)
        if (existing != null) {
            return existing
        }
        val generated = UUID.randomUUID().toString()
        prefs.edit().putString(PREF_INSTALL_ID, generated).apply()
        return generated
    }

    private fun deviceType(): String = when (platform) {
        AppPlatform.Automotive -> "car"
        AppPlatform.WearOs -> "watch"
        AppPlatform.Phone -> if (Util.isTablet(context)) "tablet" else "phone"
    }

    private fun platformName(): String = when (platform) {
        AppPlatform.Automotive -> "automotive"
        AppPlatform.WearOs -> "wearos"
        AppPlatform.Phone -> "android"
    }

    /** For example "Google Pixel 9". The maker is left off when the model name already starts with it. */
    private fun deviceModel(): String {
        val maker = Build.MANUFACTURER.trim()
        val model = Build.MODEL.trim()
        val name = if (maker.isEmpty() || model.startsWith(maker, ignoreCase = true)) model else "$maker $model"
        return name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.US) else it.toString() }
    }

    /** Server times look like 2026-11-18T01:43:07.00457+00:00. Returns 0 when one cannot be read. */
    private fun parseServerTime(value: String): Long {
        if (value.isEmpty()) {
            return 0L
        }
        return try {
            val withoutFraction = value.replace(Regex("\\.\\d+"), "")
            SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ssXXX", Locale.US).parse(withoutFraction)?.time ?: 0L
        } catch (e: Exception) {
            0L
        }
    }

    private companion object {
        // The same file SupabaseClient keeps its "ended by the server" note in. Never cleared on
        // sign-out, and not included in any backup, so a restored phone gets an id of its own.
        const val PREFS_NAME = "podhopper_device"
        const val PREF_INSTALL_ID = "install_id"

        // Right away, then after 30 seconds, 2 minutes and 10 minutes.
        val REGISTER_RETRY_WAITS_MS = longArrayOf(0L, 30_000L, 120_000L, 600_000L)
    }
}
