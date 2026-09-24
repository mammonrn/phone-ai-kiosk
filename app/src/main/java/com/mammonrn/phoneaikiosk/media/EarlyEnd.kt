package com.mammonrn.phoneaikiosk.media

/**
 * An end that VLC reports too early (0.60.0): a damaged spot in a file — a VCD
 * copied from a scratched disc — makes its demuxer give up and say the file
 * ended. Poom's คู่โจร1 stopped at 4:07 of 1:05:07 this way, and คู่โจร2 has
 * a damaged stretch of 12 seconds near its start.
 *
 * An end well before the length is not the end: the file goes on a little past
 * the spot. Within one damaged stretch each step goes twice as far (2, 4, 8,
 * 16, 32 s), from the furthest point reached — a damaged stretch can make VLC's
 * clock say a time before it. A limited number of times per file, so a file
 * broken to the end still ends.
 */
object EarlyEnd {
    /** An end this far or more before the length is taken as a damaged spot. */
    const val EARLY_MS = 10_000L
    /** How far past the spot the file goes on, the first time. */
    const val SKIP_MS = 2_000L
    /** Played this long since the last step: a new spot, and the step starts small again. */
    const val NEW_SPOT_MS = 30_000L
    const val MAX_TIMES = 20

    /**
     * Where to go on from, or null when the end is the real end.
     * [lastFromMs]: where the last step went on from (0 for none); [streak]:
     * steps in a row within the same damaged stretch.
     */
    fun goOnAt(atMs: Long, lengthMs: Long, timesSoFar: Int, lastFromMs: Long = 0, streak: Int = 0): Long? {
        if (lengthMs <= 0 || timesSoFar >= MAX_TIMES) return null
        val from = maxOf(atMs, lastFromMs)
        if (from <= 0 || from >= lengthMs - EARLY_MS) return null
        val step = if (isNewSpot(atMs, lastFromMs)) SKIP_MS else SKIP_MS shl minOf(streak, 4)
        return (from + step).takeIf { it < lengthMs - EARLY_MS }
    }

    /** Whether this end is at a new spot rather than the stretch the last step was in. */
    fun isNewSpot(atMs: Long, lastFromMs: Long) = lastFromMs <= 0 || atMs - lastFromMs > NEW_SPOT_MS
}
