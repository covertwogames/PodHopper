package au.com.shiftyjelly.pocketcasts.repositories.podhopper

import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.inject.Inject
import javax.inject.Singleton

/**
 * PodHopper: keeps "clear this device's library" and "sign in" from overlapping.
 *
 * Clearing a signed-out device takes a moment. If a sign-in landed in the middle of it, the first
 * sync for the new account could run while the previous library was only half removed. Clearing
 * holds this gate for as long as it runs, and every way of signing in waits at the gate first.
 *
 * It has no dependencies of its own on purpose, so both the sign-in client and the code that does
 * the clearing can use it without depending on each other.
 */
@Singleton
class PodHopperWipeGate @Inject constructor() {
    private val lock = ReentrantLock()

    /** Runs [block] with the gate held. Call from a background thread. */
    fun <T> whileClearing(block: () -> T): T {
        lock.lock()
        try {
            return block()
        } finally {
            lock.unlock()
        }
    }

    /**
     * Waits for any clearing in progress to finish, then returns. The wait is bounded so a sign-in
     * can never be held up indefinitely. Call from a background thread.
     */
    fun awaitIdle() {
        try {
            if (lock.tryLock(MAX_WAIT_SECONDS, TimeUnit.SECONDS)) {
                lock.unlock()
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
        }
    }

    private companion object {
        const val MAX_WAIT_SECONDS = 20L
    }
}
