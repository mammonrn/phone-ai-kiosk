package com.mammonrn.phoneaikiosk.media

/**
 * An end that VLC reports too early (0.60.0): a damaged spot in a file — a VCD
 * copied from a scratched disc — makes its demuxer give up and say the file
 * ended. Poom's คู่โจร1 stopped at 4:07 of 1:05:07 this way.
 *
 * An end well before the length is not the end: the file goes on a little past
 * the spot. A few times per file at most, so a file broken to the end still ends.
 */
object EarlyEnd {
    /** An end this far or more before the length is taken as a damaged spot. */
    const val EARLY_MS = 10_000L
    /** How far past the spot the file is opened again. */
    const val SKIP_MS = 2_000L
    const val MAX_TIMES = 10

    /**
     * Where to go on from, or null when the end is the real end. [lastFromMs]:
     * where the last time went on from — an end not past it made no progress.
     */
    fun goOnAt(atMs: Long, lengthMs: Long, timesSoFar: Int, lastFromMs: Long = 0): Long? = when {
        lengthMs <= 0 || atMs <= 0 -> null
        atMs <= lastFromMs -> null
        atMs >= lengthMs - EARLY_MS -> null
        timesSoFar >= MAX_TIMES -> null
        else -> atMs + SKIP_MS
    }
}
