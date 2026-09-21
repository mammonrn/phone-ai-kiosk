package com.mammonrn.phoneaikiosk

/**
 * Counts the rapid taps that open the kiosk's escape hatch.
 *
 * Split out from the activity and given the clock as an argument so the rule
 * can be tested without a device — which matters here, because getting it
 * wrong means either a kiosk nobody can leave or one that leaves itself.
 *
 * A tap continues the current run only if it lands within [maxGapMs] of the
 * previous one; otherwise it starts a new run of 1. The counter resets on
 * success, so ten more taps are needed for the next trigger.
 */
class TapGate(
    private val tapsRequired: Int = TAPS_REQUIRED,
    private val maxGapMs: Long = MAX_GAP_MS,
) {
    private var count = 0
    private var lastTapAt = 0L

    /** Taps counted so far in the current run. */
    val progress: Int get() = count

    /** Records a tap at [nowMs]; returns true when the run completes. */
    fun onTap(nowMs: Long): Boolean {
        count = if (count > 0 && nowMs - lastTapAt <= maxGapMs) count + 1 else 1
        lastTapAt = nowMs

        if (count >= tapsRequired) {
            count = 0
            return true
        }
        return false
    }

    fun reset() {
        count = 0
        lastTapAt = 0L
    }

    companion object {
        const val TAPS_REQUIRED = 10

        /**
         * A tap more than this long after the previous one starts over. 1.5s is
         * slow enough to be tappable by hand and fast enough that a pocket or a
         * curious passer-by does not accumulate ten taps over a minute.
         */
        const val MAX_GAP_MS = 1_500L
    }
}
