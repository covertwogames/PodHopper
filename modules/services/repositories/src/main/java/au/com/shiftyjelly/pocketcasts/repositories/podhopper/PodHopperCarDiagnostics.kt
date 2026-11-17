package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import android.os.Build
import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import java.io.ByteArrayOutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * PodHopper: sends this device's app log to the private Supabase Storage "diagnostics" bucket so
 * a problem can be investigated. A car head unit and a watch have no other way to get a log off
 * the device.
 *
 * Nothing here runs on its own. A log leaves the device only when the person asks for it:
 *
 *  - Car: the "Upload diagnostic logs" row on the car settings screen.
 *  - Phone: the "Share logs with developers" row on the About screen.
 *  - Watch: the "Send logs" action on the help screen.
 *
 * Each of those calls [uploadNow]. Signed-out devices skip the upload: the storage rules require
 * a signed-in user, and each user can reach only their own files.
 */
@Singleton
class PodHopperCarDiagnostics @Inject constructor(
    private val supabaseClient: SupabaseClient,
) {
    /**
     * Snapshots the LogBuffer and uploads it. Returns true on success. Blocking network inside;
     * runs itself on the IO dispatcher. Signed-out devices return false without attempting.
     */
    suspend fun uploadNow(reason: String): Boolean {
        if (!supabaseClient.isLoggedIn()) {
            LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Diagnostics upload skipped: not signed in")
            return false
        }
        return withContext(Dispatchers.IO) {
            try {
                val snapshot = ByteArrayOutputStream()
                LogBuffer.output(snapshot)
                val bytes = snapshot.toByteArray()
                if (bytes.isEmpty()) {
                    LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Diagnostics upload skipped: log buffer empty")
                    return@withContext false
                }
                val stamp = SimpleDateFormat("yyyy-MM-dd'T'HH-mm-ss", Locale.US).format(Date())
                val device = Build.MODEL.replace(Regex("[^A-Za-z0-9._-]"), "_")
                val objectPath = "$device/$stamp-$reason.log"
                supabaseClient.uploadDiagnostics(objectPath, bytes)
                LogBuffer.i(LogBuffer.TAG_PLAYBACK, "Diagnostics uploaded: $objectPath (${bytes.size} bytes)")
                true
            } catch (e: Exception) {
                LogBuffer.e(LogBuffer.TAG_PLAYBACK, "Diagnostics upload failed: ${e.message}")
                false
            }
        }
    }
}
