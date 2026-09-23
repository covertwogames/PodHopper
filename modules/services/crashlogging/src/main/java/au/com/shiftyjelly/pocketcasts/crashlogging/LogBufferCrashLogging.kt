package au.com.shiftyjelly.pocketcasts.crashlogging

import au.com.shiftyjelly.pocketcasts.utils.log.LogBuffer
import javax.inject.Inject

/**
 * Writes reported errors to the app's log buffer, under the "Crash" tag. Known noisy exceptions are
 * dropped by [ExceptionsFilter], as they were before.
 */
internal class LogBufferCrashLogging @Inject constructor() : CrashLogging {
    override fun sendReport(exception: Throwable?, tags: Map<String, String>, message: String?) {
        if (exception != null && ExceptionsFilter.shouldIgnoreExceptions(exception)) {
            return
        }
        val text = describe(message = message, tags = tags)
        if (exception == null) {
            LogBuffer.e(LogBuffer.TAG_CRASH, text)
        } else {
            LogBuffer.e(LogBuffer.TAG_CRASH, exception, text)
        }
    }

    override fun recordEvent(message: String, category: String?) {
        LogBuffer.i(LogBuffer.TAG_CRASH, describe(message = message, category = category))
    }

    override fun recordException(exception: Throwable, category: String?) {
        if (ExceptionsFilter.shouldIgnoreExceptions(exception)) {
            return
        }
        LogBuffer.e(LogBuffer.TAG_CRASH, exception, describe(message = null, category = category))
    }

    private fun describe(message: String?, tags: Map<String, String> = emptyMap(), category: String? = null): String = buildString {
        append(message ?: "Reported error")
        if (category != null) {
            append(" [")
            append(category)
            append("]")
        }
        if (tags.isNotEmpty()) {
            append(" ")
            append(tags.entries.joinToString(prefix = "(", postfix = ")") { "${it.key}=${it.value}" })
        }
    }
}
