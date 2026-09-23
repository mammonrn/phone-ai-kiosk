package com.mammonrn.phoneaikiosk.auth

import java.security.MessageDigest
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.PBEKeySpec

/**
 * The fallback when the camera cannot recognise Poom: a pattern dragged across
 * a 3×3 grid of dots (Poom, 2026-09-23). Pure — hashing, checking and the
 * wrong-attempt lockout — so PatternLockTest covers all of it on the JVM.
 *
 * NEVER STORED AS WRITTEN. What is kept is a PBKDF2-HMAC-SHA256 of the dots
 * with a random salt, and that record itself is then encrypted with a key in
 * the Android Keystore (SecretBox) in no-backup storage. A 3×3 pattern has
 * only ~390,000 possibilities, so the hash alone would not survive an offline
 * search; the Keystore encryption and the lockout are what protect it.
 *
 * THE LOCKOUT: [FREE_TRIES] wrong patterns in a row, then a wait of
 * [FIRST_WAIT_MS], doubling with every further wrong one up to [MAX_WAIT_MS].
 * A right pattern clears the count. The count survives a restart (it is saved
 * with the attempts, see AuthStore), so turning the app off and on is not a
 * way round it.
 */
object PatternLock {

    const val DOTS = 9
    const val MIN_DOTS = 4
    const val FREE_TRIES = 5
    const val FIRST_WAIT_MS = 30_000L
    const val MAX_WAIT_MS = 15 * 60_000L
    const val ITERATIONS = 120_000

    /** A usable pattern: at least [MIN_DOTS], each dot once, all on the grid. */
    fun isValid(dots: List<Int>): Boolean =
        dots.size >= MIN_DOTS && dots.size <= DOTS &&
            dots.all { it in 0 until DOTS } && dots.toSet().size == dots.size

    fun hash(dots: List<Int>, salt: ByteArray, iterations: Int = ITERATIONS): ByteArray {
        require(isValid(dots)) { "not a usable pattern" }
        val chars = dots.joinToString("-").toCharArray()
        val spec = PBEKeySpec(chars, salt, iterations, 256)
        try {
            return SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).encoded
        } finally {
            spec.clearPassword()
        }
    }

    /** Constant-time: how long the comparison takes says nothing about the pattern. */
    fun matches(dots: List<Int>, salt: ByteArray, expected: ByteArray,
                iterations: Int = ITERATIONS): Boolean =
        isValid(dots) && MessageDigest.isEqual(hash(dots, salt, iterations), expected)

    /** The wrong-attempt count, and when trying is allowed again. */
    data class Attempts(val failures: Int = 0, val lockedUntilMs: Long = 0L)

    /** Milliseconds still to wait, 0 when a pattern may be tried now. */
    fun waitMs(attempts: Attempts, nowMs: Long): Long =
        (attempts.lockedUntilMs - nowMs).coerceAtLeast(0L)

    /** The attempts after one try: a right pattern clears, a wrong one counts. */
    fun afterTry(attempts: Attempts, right: Boolean, nowMs: Long): Attempts {
        if (right) return Attempts()
        val failures = attempts.failures + 1
        if (failures < FREE_TRIES) return Attempts(failures, 0L)
        val doublings = (failures - FREE_TRIES).coerceAtMost(10)
        val wait = (FIRST_WAIT_MS shl doublings).coerceAtMost(MAX_WAIT_MS)
        return Attempts(failures, nowMs + wait)
    }
}
