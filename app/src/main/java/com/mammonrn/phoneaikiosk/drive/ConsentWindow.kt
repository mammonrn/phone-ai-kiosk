package com.mammonrn.phoneaikiosk.drive

/**
 * How long Play services may stay in the locked task for Google's consent
 * screen (Poom, 2026-09-25): until it answers, is cancelled, or [maxMs] has
 * passed — whichever comes first. Pure, with the clock passed in, so the
 * five-minute case is tested without waiting five minutes (DriveTest) and
 * without taking Poom's Drive grant away to get the screen back.
 */
class ConsentWindow(private val maxMs: Long) {
    /** When the package was added (elapsedRealtime), or null when it is not in the list. */
    var openedAt: Long? = null
        private set

    val isOpen: Boolean get() = openedAt != null

    fun open(now: Long) { openedAt = now }

    /** Past its time at [now]: withdraw. A clock that went backwards counts as past (never left open). */
    fun expired(now: Long): Boolean {
        val at = openedAt ?: return false
        return now - at >= maxMs || now < at
    }

    /** How long until it must close (for the timer); 0 when already due. */
    fun remaining(now: Long): Long {
        val at = openedAt ?: return 0L
        return (maxMs - (now - at)).coerceAtLeast(0L)
    }

    /** Closed — answered, cancelled, timed out. Returns whether it was open. */
    fun close(): Boolean = (openedAt != null).also { openedAt = null }
}
