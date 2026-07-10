package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.content.Context
import au.com.shiftyjelly.pocketcasts.preferences.Settings
import au.com.shiftyjelly.pocketcasts.servers.di.Raw
import au.com.shiftyjelly.pocketcasts.utils.Util
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
 */
@Singleton
class SupabaseClient @Inject constructor(
    @Raw private val httpClient: OkHttpClient,
    private val settings: Settings,
    @ApplicationContext private val context: Context,
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

    @Synchronized
    fun login(email: String, password: String) {
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)
        val json = authCall("/auth/v1/token?grant_type=password", body)
        applySession(json)
        settings.podhopperRefreshToken.set(json.optString("refresh_token", ""), updateModifiedAt = false)
        settings.podhopperEmail.set(email, updateModifiedAt = false)
    }

    /**
     * Creates a new account. When the project does not require email confirmation, Supabase returns
     * a session immediately: that session is applied and persisted exactly like [login], and this
     * returns true. When confirmation is required, no token comes back and this returns false, which
     * the caller surfaces as "check your email to confirm".
     */
    @Synchronized
    fun signUp(email: String, password: String): Boolean {
        val body = JSONObject()
        body.put("email", email)
        body.put("password", password)
        val json = authCall("/auth/v1/signup", body)
        if (json.has("access_token")) {
            applySession(json)
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

    @Synchronized
    fun logout() {
        clearSessionCache()
        settings.podhopperRefreshToken.set("", updateModifiedAt = false)
        settings.podhopperEmail.set("", updateModifiedAt = false)
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
    @Synchronized
    fun claimPairingSession(tokenHash: String) {
        clearSessionCache()
        val body = JSONObject()
        body.put("type", "magiclink")
        body.put("token_hash", tokenHash)
        val json = authCall("/auth/v1/verify", body)
        applySession(json)
        val refreshToken = json.optString("refresh_token", "")
        if (refreshToken.isNotEmpty()) {
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
                throw AuthRejectedException("PodHopper auth failed: HTTP $code $responseBody")
            }
            if (!response.isSuccessful) {
                throw SupabaseException("PodHopper auth failed: HTTP $code $responseBody")
            }
            return if (responseBody.isEmpty()) JSONObject() else JSONObject(responseBody)
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
    }
}

open class SupabaseException(message: String, cause: Throwable? = null) : Exception(message, cause)

class AuthRejectedException(message: String) : SupabaseException(message)
