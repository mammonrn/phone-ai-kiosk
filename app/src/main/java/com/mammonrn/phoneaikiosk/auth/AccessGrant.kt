package com.mammonrn.phoneaikiosk.auth

import android.os.SystemClock

/**
 * The two minutes of access a passed verification opens (Poom, 2026-09-23).
 *
 * In memory only and on the monotonic clock: a restart closes it, and changing
 * the wall clock cannot stretch it. Nothing grants it but VerifyActivity after
 * a face or pattern passed, and anyone can close it early ([close]).
 *
 * PART 2 (email, calendar) enforces this at the broker too: the phone never
 * holds a Google token, so the broker is where "no private data without a
 * pass" has to be true. See DESIGN.md, "ยืนยันตัวตน".
 */
object AccessGrant {

    const val DURATION_MS = 2 * 60_000L

    /** How the window was opened, for the log and the screen. Never who. */
    enum class Method { FACE, PATTERN }

    @Volatile private var until = 0L
    @Volatile var method: Method? = null
        private set

    /** Injectable for tests. */
    @Volatile var clock: () -> Long = { SystemClock.elapsedRealtime() }

    fun open(how: Method) {
        method = how
        until = clock() + DURATION_MS
    }

    fun close() {
        until = 0L
        method = null
    }

    fun isOpen(): Boolean = clock() < until

    /** Milliseconds left, 0 when closed. */
    fun remainingMs(): Long = (until - clock()).coerceAtLeast(0L)
}
