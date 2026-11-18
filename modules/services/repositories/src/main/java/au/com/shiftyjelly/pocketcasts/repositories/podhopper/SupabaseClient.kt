package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import android.content.SharedPreferences
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.servers.di.Raw
import au.com.shiftyjelly.pocketcasts.utils.Util
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject

/**
 * Talks to PodHopper's Supabase backend: GoTrue auth plus PostgREST upsert and select.
 *
 * This is a faithful Kotlin port of the proven AntennaPod implementation. The session
 * (access token, user id, expiry) is cached in memory. The refresh token and signed-in email
 * are persisted in app settings, so the user stays signed in across restarts and the access
 * token is silently refreshed when it expires.
 *
 * The @Raw OkHttp client is used deliberately: it carries no Pocket Casts auth interceptor,
 * since Supabase supplies its own apikey and bearer token.
 *
 * Signing out tells the server, so the sign-in stops working everywhere it was copied to and
 * drops off the account's list of signed-in devices. When it is the server that ends a sign-in
 * (the device was removed from that list, the account was deleted, the sign-in expired), that is
 * remembered in [wasEndedByServer] so the rest of the app can tell it apart from the person
 * tapping Sign Out.
 */
@Singleton
class SupabaseClient @Inject constructor(
    @Raw private val httpClient: OkHttpClient,
    private val settings: Settings,
    @ApplicationContext private val context: Context,
    private val wipeGate: PodHopperWipeGate,
) {
    private val jsonType = "application/json; charset=utf-8".toMediaType()

    // PodHopper: on Android Automotive every Supabase call is hard time-bounded. Car cellular
    // stalls in ways that leave a socket open indefinitely, and these calls sit on paths that
    // hold playback locks, so an unbounded call froze the play button for minutes. The derived
    // client copies the @Raw client (newBuilder shares the connection pool but mutates nothing
    // on the original, so every other client in the app is untouched) and adds a 10 second
    // whole-call ceiling. Phone builds keep the @Raw client exactly as before, because the
    // coroutine-level timeouts around these calls behave acceptably on phone networks and the
    // phone has years of production behavior we are deliberately not changing.
    private val callClient: OkHttpClient = if (Util.isAutomotive(context)) {
        httpClient.newBuilder()
            .connectTimeout(5, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .writeTimeout(8, TimeUnit.SECONDS)
            .callTimeout(10, TimeUnit.SECONDS)
            .build()
    } else {
        httpClient
    }

    private var cachedAccessToken: String? = null
    private var cachedUserId: String? = null
    private var tokenExpiresAtMs: Long = 0

    fun isLoggedIn(): Boolean = settings.podhopperRefreshToken.value.isNotEmpty()

    /**
     * Emits the current sign-in state and every later change to it: true once a refresh token is
     * stored (sign-in or sign-up succeeded), false when it is cleared (sign-out or an expired
     * session). Lets sync components react the instant sign-in completes instead of polling
     * isLoggedIn() or racing an activity lifecycle callback.
     */
    val loginState: Flow<Boolean>
        get() = settings.podhopperRefreshToken.flow.map { it.isNotEmpty() }

    fun login(email: String, password: String) {
        // If this device is still clearing a previous library, let that finish first.
        wipeGate.awaitIdle()
        loginLocked(email, password)
    }

    @Synchronized
    private fun loginLocked(email: String, password: String) {
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)
        val json = authCall("/auth/v1/token?grant_type=password", body)
        applySession(json)
        clearEndedByServer()
        settings.podhopperRefreshToken.set(json.optString("refresh_token", ""), updateModifiedAt = false)
        settings.podhopperEmail.set(email, updateModifiedAt = false)
    }

    /**
     * Creates a new account. When the project does not require email confirmation, Supabase returns
     * a session immediately: that session is applied and persisted exactly like [login], and this
     * returns true. When confirmation is required, no token comes back and this returns false, which
     * the caller surfaces as "check your email to confirm".
     */
    fun signUp(email: String, password: String): Boolean {
        wipeGate.awaitIdle()
        return signUpLocked(email, password)
    }

    @Synchronized
    private fun signUpLocked(email: String, password: String): Boolean {
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)
        val json = authCall("/auth/v1/signup", body)
        if (json.has("access_token")) {
            applySession(json)
            clearEndedByServer()
            settings.podhopperRefreshToken.set(json.optString("refresh_token", ""), updateModifiedAt = false)
            settings.podhopperEmail.set(email, updateModifiedAt = false)
            return true
        }
        return false
    }

    /**
     * Asks Supabase to send a password reset email. Succeeds quietly; failures throw.
     */
    fun recoverPassword(email: String) {
        val body = JSONObject()
        body.put("email", email)
        authCall("/auth/v1/recover", body)
    }

    @Synchronized
    fun ensureSession(): String {
        val cached = cachedAccessToken
        if (cached != null && System.currentTimeMillis() < tokenExpiresAtMs - TOKEN_SAFETY_MARGIN_MS) {
            return cached
        }
        val refreshToken = settings.podhopperRefreshToken.value
        if (refreshToken.isNotEmpty()) {
            return refreshSession(refreshToken)
        }
        throw SupabaseException("PodHopper sync: not signed in")
    }

    @Synchronized
    fun getUserId(): String? {
        ensureSession()
        return cachedUserId
    }

    /**
     * Signs this device out. Local state is cleared right away, so this is safe to call from the
     * main thread and works with no connection. The server is then told in the background, which
     * ends the sign-in there and takes this device off the account's list of signed-in devices.
     * If the server cannot be reached the device is still signed out: the token it held is thrown
     * away here, so nothing can use that sign-in again, and it stays on the list only until it is
     * removed there or this device signs in again.
     */
    @Synchronized
    fun logout() {
        val accessToken = cachedAccessToken?.takeIf { System.currentTimeMillis() < tokenExpiresAtMs - 5_000L }
        val refreshToken = settings.podhopperRefreshToken.value
        clearSessionCache()
        // The person chose this, so it is not a sign-out the server forced.
        clearEndedByServer()
        settings.podhopperRefreshToken.set("", updateModifiedAt = false)
        settings.podhopperEmail.set("", updateModifiedAt = false)
        if (accessToken != null || refreshToken.isNotEmpty()) {
            endSessionOnServer(accessToken, refreshToken)
        }
    }

    /**
     * Best effort, on its own short-lived thread: ends the given sign-in on the server. Uses the
     * access token when one is still valid; otherwise trades the refresh token for one first
     * (the app may have only just started). Touches no local state, since the device is already
     * signed out by the time this runs. Never throws.
     */
    private fun endSessionOnServer(accessToken: String?, refreshToken: String) {
        val worker = Thread {
            try {
                var token = accessToken
                if (token == null && refreshToken.isNotEmpty()) {
                    val body = JSONObject()
                    body.put("refresh_token", refreshToken)
                    token = authCall("/auth/v1/token?grant_type=refresh_token", body)
                        .optString("access_token")
                        .ifEmpty { null }
                }
                if (token != null) {
                    val request = Request.Builder()
                        .url(PodHopperConfig.SUPABASE_URL + "/auth/v1/logout?scope=local")
                        .addHeader("apikey", PodHopperConfig.SUPABASE_ANON_KEY)
                        .addHeader("Authorization", "Bearer $token")
                        .post("".toRequestBody(jsonType))
                        .build()
                    callClient.newCall(request).execute().use { response ->
                        LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper sign-out sent to the server: HTTP ${response.code}")
                    }
                }
            } catch (e: Exception) {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "PodHopper sign-out could not reach the server: ${e.message}")
            }
        }
        worker.name = "podhopper-sign-out"
        worker.isDaemon = true
        worker.start()
    }

    /**
     * True when the last sign-in on this device was ended by the server rather than by the person
     * tapping Sign Out. Stays true until the next sign-in, a local sign-out, or
     * [clearEndedByServer]. Survives the app being closed.
     */
    fun wasEndedByServer(): Boolean = devicePrefs().getBoolean(PREF_ENDED_BY_SERVER, false)

    fun clearEndedByServer() {
        devicePrefs().edit().remove(PREF_ENDED_BY_SERVER).apply()
    }

    private fun devicePrefs(): SharedPreferences = context.getSharedPreferences(DEVICE_PREFS_NAME, Context.MODE_PRIVATE)

    /**
     * Adds or refreshes this install on the account's list of signed-in devices. Blocking; throws
     * on failure.
     */
    fun registerDevice(installId: String, deviceType: String, platform: String, model: String, appVersion: String) {
        val params = JSONObject()
            .put("p_install_id", installId)
            .put("p_device_type", deviceType)
            .put("p_platform", platform)
            .put("p_model", model)
            .put("p_app_version", appVersion)
        rpc("register_device", params)
    }

    /** Every sign-in on the account, this device first. Blocking; throws on failure. */
    fun listDevices(): JSONArray = JSONArray(rpc("list_my_devices", JSONObject()))

    /**
     * Ends one sign-in on the account, by the id [listDevices] gave for it. Returns false when it
     * was already gone. Blocking; throws on failure.
     */
    fun signOutDevice(sessionId: String): Boolean {
        return rpc("sign_out_device", JSONObject().put("p_session_id", sessionId)).trim() == "true"
    }

    /** Ends every sign-in on the account except this one. Returns how many. Blocking; throws on failure. */
    fun signOutOtherDevices(): Int {
        return rpc("sign_out_other_devices", JSONObject()).trim().toIntOrNull() ?: 0
    }

    /**
     * Asks the server whether this install was signed out on purpose from another device's list.
     * Called after the server has ended this device's sign-in, so it uses the anon key only.
     * Blocking; throws on failure.
     */
    fun wasSignedOutFromList(installId: String): Boolean {
        val request = Request.Builder()
            .url(PodHopperConfig.SUPABASE_URL + "/rest/v1/rpc/device_signed_out")
            .addHeader("apikey", PodHopperConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer " + PodHopperConfig.SUPABASE_ANON_KEY)
            .addHeader("Content-Type", "application/json")
            .post(JSONObject().put("p_install_id", installId).toString().toRequestBody(jsonType))
            .build()
        callClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw SupabaseException("PodHopper device check failed: HTTP ${response.code} $responseBody")
            }
            return responseBody.trim() == "true"
        }
    }

    fun upsert(table: String, onConflictColumns: String, rows: JSONArray) {
        if (rows.length() == 0) {
            return
        }
        val url = PodHopperConfig.SUPABASE_URL + "/rest/v1/" + table + "?on_conflict=" + onConflictColumns
        val request = restRequestBuilder(url)
            .addHeader("Prefer", "resolution=merge-duplicates,return=minimal")
            .post(rows.toString().toRequestBody(jsonType))
            .build()
        executeExpectingSuccess(request, retryOnAuthError = true)
    }

    /**
     * Calls a Postgres function through PostgREST. The function runs as the signed-in user, so
     * row level security applies to everything it touches. Returns the raw JSON body.
     */
    fun rpc(function: String, params: JSONObject): String {
        val url = PodHopperConfig.SUPABASE_URL + "/rest/v1/rpc/" + function
        val request = restRequestBuilder(url)
            .post(params.toString().toRequestBody(jsonType))
            .build()
        return executeExpectingSuccess(request, retryOnAuthError = true)
    }

    fun select(table: String, query: String): JSONArray {
        val url = PodHopperConfig.SUPABASE_URL + "/rest/v1/" + table + "?" + query
        val request = restRequestBuilder(url).get().build()
        val body = executeExpectingSuccess(request, retryOnAuthError = true)
        return JSONArray(body)
    }

    /**
     * Approves a car or TV pairing code on behalf of the signed-in user. The other device (the car)
     * generates the code and polls for approval; this call, authenticated as the user, tells the
     * pairing edge function to grant that code access to the user's account. Blocking; call off the
     * main thread. Throws on failure (invalid or expired code, or network error).
     */
    fun approveCarPairing(code: String) {
        val url = PodHopperConfig.SUPABASE_URL + "/functions/v1/pairing"
        val body = JSONObject()
        body.put("action", "approve")
        body.put("code", code)
        val request = restRequestBuilder(url)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        executeExpectingSuccess(request, retryOnAuthError = true)
    }

    /**
     * Deletes the signed-in user's PodHopper account. Calls the delete-account edge function, which
     * verifies the user from the bearer token, removes their playback_state and subscriptions rows,
     * and deletes the auth user itself. Authenticated as the user (restRequestBuilder supplies the
     * access token). Blocking; call off the main thread. Throws on failure. Does not touch any local
     * on-device data: the caller logs out and clears local sync bookkeeping after this succeeds.
     */
    fun deleteAccount() {
        val url = PodHopperConfig.SUPABASE_URL + "/functions/v1/delete-account"
        val request = restRequestBuilder(url)
            .post(JSONObject().toString().toRequestBody(jsonType))
            .build()
        executeExpectingSuccess(request, retryOnAuthError = true)
    }

    /**
     * Car side of pairing, step 1. Asks the pairing edge function for a fresh code that the car
     * displays. Authenticated with the anon key only (the car is not signed in yet). Blocking; call
     * off the main thread. Returns the code string.
     */
    fun startPairing(deviceName: String): String {
        val body = JSONObject()
        body.put("action", "start")
        body.put("device_name", deviceName)
        val json = pairingCall(body)
        return json.getString("code")
    }

    /**
     * Car side of pairing, step 2. Polls the pairing edge function for the status of the code the
     * car is showing. Returns the token_hash once the phone has approved, null while still pending,
     * and throws when the code has expired. Authenticated with the anon key only. Blocking; call off
     * the main thread.
     */
    fun pollPairing(code: String): String? {
        val body = JSONObject()
        body.put("action", "poll")
        body.put("code", code)
        val json = pairingCall(body)
        val status = json.optString("status", "pending")
        if (status == "approved") {
            return json.getString("token_hash")
        }
        if (status == "expired") {
            throw SupabaseException("PodHopper pairing code expired")
        }
        return null
    }

    /**
     * Car side of pairing, step 3. Exchanges the approved token_hash for a real session through
     * Supabase Auth (the magiclink verify endpoint, anon key, no bearer), then applies and persists
     * that session exactly like a normal sign-in. Storing the refresh token is what flips
     * [isLoggedIn] to true and turns sync on. Blocking; call off the main thread.
     */
    fun claimPairingSession(tokenHash: String) {
        // If this device is still clearing a previous library, let that finish first.
        wipeGate.awaitIdle()
        claimPairingSessionLocked(tokenHash)
    }

    @Synchronized
    private fun claimPairingSessionLocked(tokenHash: String) {
        clearSessionCache()
        val body = JSONObject()
        body.put("type", "magiclink")
        body.put("token_hash", tokenHash)
        val json = authCall("/auth/v1/verify", body)
        applySession(json)
        val refreshToken = json.optString("refresh_token", "")
        if (refreshToken.isNotEmpty()) {
            clearEndedByServer()
            settings.podhopperRefreshToken.set(refreshToken, updateModifiedAt = false)
        }
        val email = json.optJSONObject("user")?.optString("email").orEmpty()
        if (email.isNotEmpty()) {
            settings.podhopperEmail.set(email, updateModifiedAt = false)
        }
    }

    /**
     * Posts to the pairing edge function with the anon key as both apikey and bearer (the calls the
     * car makes before it has a user session). Throws on a non-success HTTP status.
     */
    private fun pairingCall(body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(PodHopperConfig.SUPABASE_URL + "/functions/v1/pairing")
            .addHeader("apikey", PodHopperConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer " + PodHopperConfig.SUPABASE_ANON_KEY)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        callClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (!response.isSuccessful) {
                throw SupabaseException("PodHopper pairing request failed: HTTP ${response.code} $responseBody")
            }
            return if (responseBody.isEmpty()) JSONObject() else JSONObject(responseBody)
        }
    }

    @Synchronized
    private fun refreshSession(refreshToken: String): String {
        val body = JSONObject()
        body.put("refresh_token", refreshToken)
        val json = try {
            authCall("/auth/v1/token?grant_type=refresh_token", body)
        } catch (e: AuthRejectedException) {
            if (!e.fromAuthServer) {
                // Something on the way answered, not the sign-in service itself (a network block
                // page, a gateway error). That says nothing about this sign-in, so keep it and let
                // the next attempt try again. Ending it here would sign the device out, and on a
                // car or watch clear its library, over a passing network problem.
                throw SupabaseException("PodHopper sync: session refresh was refused before reaching the sign-in service", e)
            }
            // The sign-in service itself refused the token: this sign-in is over. Remember that
            // the server ended it (written before the token so nothing sees one without the other).
            devicePrefs().edit().putBoolean(PREF_ENDED_BY_SERVER, true).commit()
            settings.podhopperRefreshToken.set("", updateModifiedAt = false)
            throw SupabaseException("PodHopper sign-in expired. Sign in again.", e)
        }
        applySession(json)
        val rotated = json.optString("refresh_token", "")
        if (rotated.isNotEmpty()) {
            settings.podhopperRefreshToken.set(rotated, updateModifiedAt = false)
        }
        return cachedAccessToken ?: throw SupabaseException("PodHopper sync: missing access token")
    }

    private fun applySession(json: JSONObject) {
        cachedAccessToken = json.getString("access_token")
        val expiresInSec = json.optLong("expires_in", 3600)
        tokenExpiresAtMs = System.currentTimeMillis() + expiresInSec * 1000
        val user = json.optJSONObject("user")
        val userId = user?.optString("id").orEmpty()
        cachedUserId = if (userId.isEmpty()) null else userId
    }

    private fun clearSessionCache() {
        cachedAccessToken = null
        cachedUserId = null
        tokenExpiresAtMs = 0
    }

    private fun authCall(path: String, body: JSONObject): JSONObject {
        val request = Request.Builder()
            .url(PodHopperConfig.SUPABASE_URL + path)
            .addHeader("apikey", PodHopperConfig.SUPABASE_ANON_KEY)
            .post(body.toString().toRequestBody(jsonType))
            .build()
        callClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            val code = response.code
            if (code == 400 || code == 401 || code == 403) {
                throw AuthRejectedException(
                    message = "PodHopper auth failed: HTTP $code $responseBody",
                    fromAuthServer = isAuthServerError(responseBody),
                )
            }
            if (!response.isSuccessful) {
                throw SupabaseException("PodHopper auth failed: HTTP $code $responseBody")
            }
            return if (responseBody.isEmpty()) JSONObject() else JSONObject(responseBody)
        }
    }

    /**
     * True when [responseBody] is an error written by the sign-in service itself. It always answers
     * in JSON carrying an error code, an error name or a message under "msg". Anything else (an
     * HTML block page, a gateway's own JSON, an empty body) came from something in between.
     */
    private fun isAuthServerError(responseBody: String): Boolean {
        return try {
            val json = JSONObject(responseBody)
            json.has("error_code") || json.has("error") || json.has("msg")
        } catch (e: Exception) {
            false
        }
    }

    private fun restRequestBuilder(url: String): Request.Builder {
        val token = ensureSession()
        return Request.Builder()
            .url(url)
            .addHeader("apikey", PodHopperConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("Content-Type", "application/json")
    }

    private fun executeExpectingSuccess(request: Request, retryOnAuthError: Boolean): String {
        callClient.newCall(request).execute().use { response ->
            val responseBody = response.body.string()
            if (response.code == 401 && retryOnAuthError) {
                clearSessionCache()
                val refreshed = ensureSession()
                val retried = request.newBuilder()
                    .removeHeader("Authorization")
                    .addHeader("Authorization", "Bearer $refreshed")
                    .build()
                return executeExpectingSuccess(retried, retryOnAuthError = false)
            }
            if (!response.isSuccessful) {
                throw SupabaseException("PodHopper sync request failed: HTTP ${response.code} $responseBody")
            }
            return responseBody
        }
    }

    /**
     * PodHopper: uploads a diagnostic log snapshot to the private "diagnostics" Storage bucket,
     * named by object path (e.g. "carDeviceName/2026-07-09T18-30-00.log"). The car has no logcat,
     * so this is how playback diagnostics get off the head unit. Authenticated as the signed-in
     * user; the bucket's insert policy requires an authenticated role. Uses a longer dedicated
     * timeout than the sync calls because a log file is bigger than a sync row, but stays bounded
     * so a dead connection cannot hold the caller. Blocking; call off the main thread. Throws on
     * failure so the caller can decide whether to retry later.
     */
    fun uploadDiagnostics(objectPath: String, bytes: ByteArray) {
        val token = ensureSession()
        val request = Request.Builder()
            .url(PodHopperConfig.SUPABASE_URL + "/storage/v1/object/" + DIAGNOSTICS_BUCKET + "/" + objectPath)
            .addHeader("apikey", PodHopperConfig.SUPABASE_ANON_KEY)
            .addHeader("Authorization", "Bearer $token")
            .addHeader("x-upsert", "true")
            .post(bytes.toRequestBody("text/plain; charset=utf-8".toMediaType()))
            .build()
        val uploadClient = callClient.newBuilder()
            .callTimeout(30, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .writeTimeout(30, TimeUnit.SECONDS)
            .build()
        uploadClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                val responseBody = response.body.string()
                throw SupabaseException("PodHopper diagnostics upload failed: HTTP ${response.code} $responseBody")
            }
        }
    }

    private companion object {
        const val TOKEN_SAFETY_MARGIN_MS = 60 * 1000L
        const val DIAGNOSTICS_BUCKET = "diagnostics"

        // Shared with PodHopperDevices, which keeps this install's id in the same file. Nothing in
        // it is cleared on sign-out: it describes the device, not the account.
        const val DEVICE_PREFS_NAME = "podhopper_device"
        const val PREF_ENDED_BY_SERVER = "ended_by_server"
    }
}

open class SupabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

/**
 * The server answered a sign-in request with a refusal. [fromAuthServer] is false when the answer
 * did not come from the sign-in service itself, in which case it says nothing about the account.
 */
class AuthRejectedException(message: String, val fromAuthServer: Boolean = true) : SupabaseException(message)
