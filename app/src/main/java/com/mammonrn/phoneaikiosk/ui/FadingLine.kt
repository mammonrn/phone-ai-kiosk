package com.mammonrn.phoneaikiosk.ui

/**
 * Text that stays on the screen for a while after it last changed, then goes.
 *
 * The Jarvis window shows "ได้ยิน: …" — what the speech-to-text heard — and the
 * answer. That is the one way to see WHY a request did nothing ("ขอดูกล่อง"
 * rather than "ขอดูกล้อง"), so it has to be there after every turn. It used to
 * stay there until the next one, hours later, on a screen that faces a room:
 * what somebody asked at breakfast was still up at dinner. Now it shows for
 * [holdMs] after it last changed, then the standing invitation comes back.
 *
 * Pure and given the clock, like IdleScreen, so the rule is testable.
 */
class FadingLine(private val holdMs: Long = HOLD_MS) {

    private var shown = ""
    private var changedAt = 0L

    /** [text] while it is fresh, "" once it has been up for [holdMs] unchanged. */
    fun visible(text: String, nowMs: Long, busy: Boolean): String {
        if (text != shown) {
            shown = text
            changedAt = nowMs
        }
        // Never faded mid-turn: a long answer is still being spoken.
        if (busy) changedAt = nowMs
        return if (nowMs - changedAt < holdMs) text else ""
    }

    companion object {
        /** A minute: long enough to read both lines twice, short enough to clear. */
        const val HOLD_MS = 60_000L
    }
}
