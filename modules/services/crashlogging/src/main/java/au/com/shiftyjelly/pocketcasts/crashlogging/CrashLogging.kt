package au.com.shiftyjelly.pocketcasts.crashlogging

/**
 * PodHopper: where the apps report errors they have handled but want a record of, such as a playback
 * failure or a feed refresh that threw.
 *
 * Upstream this was Sentry, which PodHopper does not use: no key was ever configured, so nothing was
 * ever sent, while the library still started itself, opened its own database and added a breadcrumb
 * to every network request. Reports now go to the app's own log buffer instead, which "Share logs"
 * uploads from the phone, the car and the watch. If a real crash reporter is ever wanted, it only
 * needs a new implementation of this interface; the reporting sites around the apps stay as they are.
 */
interface CrashLogging {
    fun sendReport(exception: Throwable? = null, tags: Map<String, String> = emptyMap(), message: String? = null)

    fun recordEvent(message: String, category: String? = null)

    fun recordException(exception: Throwable, category: String? = null)
}
