package com.mammonrn.phoneaikiosk.ui

/**
 * Keeping the line being spoken in view while a long answer is read out.
 *
 * WHY BOTH, AND WHY THIS WAY ROUND (2026-09-23). A 161-character answer runs
 * past the bottom of the Jarvis window and Poom could not read the end of it.
 * The window now scrolls with a finger, AND it follows the voice on its own
 * while the answer is being said: the person is listening, not reaching for
 * the screen, and the words should arrive where their eyes already are. The
 * moment somebody touches the window, following stops for that answer — a
 * screen that scrolls away from the line you are reading is worse than one
 * that does not move at all. Once it has been said, the answer stays up (see
 * FadingLine), and can be scrolled back through.
 *
 * The pace is assumed even across the answer: where the voice is, in
 * characters, is the share of the audio played so far. Thai TTS reads at a
 * steady rate, so over a few lines that lands on the right line — which is all
 * a scroll position needs to be right about.
 *
 * Pure, so it is tested without Android. See SpeechFollowTest.
 */
object SpeechFollow {

    /** How the transcript line marks the answer. See MainActivity.transcriptLine. */
    const val REPLY_MARK = "ตอบ: "

    /** Where the spoken line sits in the window: a third of the way down. */
    const val LINE_AT = 1f / 3f

    /**
     * The character being said now, as an index into [text], or null when
     * nothing is being said or there is no answer in [text].
     */
    fun spokenOffset(text: CharSequence, sinceMs: Long, durationMs: Long, nowMs: Long): Int? {
        if (sinceMs <= 0 || durationMs <= 0) return null
        val mark = text.lastIndexOf(REPLY_MARK)
        if (mark < 0) return null
        val start = mark + REPLY_MARK.length
        val length = text.length - start
        if (length <= 0) return null
        val share = ((nowMs - sinceMs).toFloat() / durationMs).coerceIn(0f, 1f)
        return (start + share * (length - 1)).toInt()
    }

    /** The scroll position that puts a line starting at [lineTop] at [LINE_AT]. */
    fun scrollTarget(lineTop: Int, viewportHeight: Int, contentHeight: Int): Int {
        val max = (contentHeight - viewportHeight).coerceAtLeast(0)
        return (lineTop - (viewportHeight * LINE_AT).toInt()).coerceIn(0, max)
    }
}
