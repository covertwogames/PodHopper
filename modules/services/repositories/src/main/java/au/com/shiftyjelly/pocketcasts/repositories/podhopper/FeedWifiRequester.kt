package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeoutOrNull

/**
 * PodHopper watch: asks for a Wi-Fi network for the length of one piece of work. While the phone is
 * nearby, a Wear OS watch sends app traffic through the phone over Bluetooth, which is slow, and keeps
 * its own Wi-Fi off to save battery; an app that needs to move a lot of data asks for Wi-Fi explicitly
 * and releases it as soon as it is done. If no saved Wi-Fi network comes up in time the work runs on
 * the default network instead. Only the watch calls this.
 */
@Singleton
class FeedWifiRequester @Inject constructor(
    @ApplicationContext private val context: Context,
) {
    /**
     * Requests Wi-Fi, waits up to [waitMs] for it, runs [block] with the granted network (or null if
     * none came up), and releases the request whatever happens.
     *
     * Requesting a network needs CHANGE_NETWORK_STATE. This shared module deliberately does not
     * declare it, so the phone and car apps do not gain the permission: the watch app gets it from the
     * Horologist network library it already uses, and only the watch calls this. Should the permission
     * ever be missing, the request fails with a SecurityException, which is caught below and the work
     * runs on the default network.
     */
    @SuppressLint("MissingPermission")
    suspend fun <T> withWifi(waitMs: Long, block: suspend (Network?) -> T): T {
        val connectivityManager = context.getSystemService(ConnectivityManager::class.java) ?: return block(null)
        val granted = CompletableDeferred<Network>()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                granted.complete(network)
            }
        }
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        try {
            connectivityManager.requestNetwork(request, callback)
        } catch (e: RuntimeException) {
            // SecurityException without the permission, or too many outstanding requests.
            LogBuffer.e(LogBuffer.TAG_BACKGROUND_TASKS, e, "Wi-Fi request refused, using the default network")
            return block(null)
        }
        try {
            val network = withTimeoutOrNull(waitMs) { granted.await() }
            if (network == null) {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Wi-Fi not available within ${waitMs / MS_PER_SECOND}s, using the default network")
            } else {
                LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Wi-Fi granted for feed downloads")
            }
            return block(network)
        } finally {
            try {
                connectivityManager.unregisterNetworkCallback(callback)
            } catch (e: IllegalArgumentException) {
                // Already unregistered.
            }
            LogBuffer.i(LogBuffer.TAG_BACKGROUND_TASKS, "Wi-Fi request released")
        }
    }

    private companion object {
        const val MS_PER_SECOND = 1000L
    }
}
