package com.mammonrn.phoneaikiosk.ui

import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.TextPaint
import android.text.style.ForegroundColorSpan
import android.text.style.MetricAffectingSpan
import android.text.style.RelativeSizeSpan
import android.text.style.ScaleXSpan

/**
 * Two typefaces on one line, without either of them looking like an accident.
 *
 * WHY THIS EXISTS. The screen is meant to look like a pixel-art desktop, and
 * the face that carries that is Press Start 2P — which has no Thai. Every
 * interesting line here is mixed: "27.4°C แดดรำไร", "ทองแท่ง 68,000 บ.".
 * Letting Android fall back per character would work, but it would pick the
 * device's own Thai font and set it at the pixel font's size, and the two
 * disagree about everything: where the baseline is, how tall a capital is,
 * how much air a line needs.
 *
 * So the app owns the split instead. Thai is the TextView's own face (IBM Plex
 * Sans Thai Looped) and the runs of digits and Latin are lifted into the pixel
 * face, resized and dropped onto the Thai baseline by spans.
 *
 * THE TWO NUMBERS BELOW ARE MEASURED, NOT GUESSED. Both fonts are 1000 units
 * per em. Press Start 2P draws a digit from y=125 to y=1000 — 0.875 em tall,
 * floating an eighth of an em above the baseline. Plex's capitals run 0 to
 * 698. Set the pixel runs at [PIXEL_SCALE] of the surrounding size and they
 * match Plex's capitals; shift them down by [PIXEL_DROP] of their own size and
 * they sit on the same baseline instead of hovering over it.
 *
 * Pure enough to test: [pixelRuns] is the whole decision and takes no Android
 * with it. See RetroTypeTest.
 */
object RetroType {

    /** 0.698 / 0.875 — Plex's cap height over Press Start 2P's digit height. */
    const val PIXEL_SCALE = 0.8f

    /** Press Start 2P's digits start 0.125 em above the baseline. Put them back. */
    const val PIXEL_DROP = 0.125f

    /**
     * The runs of text the pixel face should take.
     *
     * A run has to START with something that has a shape of its own — a digit,
     * a letter or a "$" — so that the lone full stop in "68,000 บ." stays with
     * the Thai it belongs to rather than becoming a one-character island in
     * another font. After that it may carry the punctuation that lives inside
     * numbers: "27.4°C", "+0.97%", "68,000", "19:10".
     */
    private val PIXEL_RUN = Regex("""[+\-]?[0-9A-Za-z$][0-9A-Za-z$,.:%+\-/°]*""")

    /** Ranges of [text] that belong to the pixel face, in order. */
    fun pixelRuns(text: CharSequence): List<IntRange> =
        PIXEL_RUN.findAll(text)
            .map { it.range }
            // A run may end on punctuation it only borrowed: "บ." has no digits
            // to hold the stop, and "(4 นาทีก่อน)" leaves the 4 with nothing.
            .map { trimTrailingPunctuation(text, it) }
            .filter { !it.isEmpty() }
            .toList()

    private fun trimTrailingPunctuation(text: CharSequence, range: IntRange): IntRange {
        var last = range.last
        while (last >= range.first && text[last] in ",.:+-/") last--
        return range.first..last
    }

    /**
     * The spaces that have to work harder than the rest.
     *
     * On the phone, "ความชื้น 83%" read as one word. Plex's word space is
     * 0.236 em, which is right between two Thai words and not nearly enough
     * between a Thai word and a slab of 8-bit digits — the pixel face is so
     * much blacker that the gap disappears under it.
     *
     * Only the spaces on the SEAM get widened: a space with a pixel run on one
     * side and Thai on the other. The two spaces inside "BTC  $85,965" have
     * pixel on both sides and are already generous, and the ones in
     * "27.4°C  แดดจัด" come in pairs and need no help either. Widening those
     * too was the first attempt and it blew the crypto window apart.
     */
    fun gapSpaces(text: CharSequence, runs: List<IntRange>): List<Int> {
        if (runs.isEmpty()) return emptyList()
        val startsRun = runs.map { it.first }.toSet()
        val endsRun = runs.map { it.last }.toSet()
        val out = ArrayList<Int>()
        for (i in text.indices) {
            if (text[i] != ' ') continue
            val leftIsRun = i > 0 && (i - 1) in endsRun
            val rightIsRun = i + 1 < text.length && (i + 1) in startsRun
            val leftIsWord = i > 0 && text[i - 1] != ' ' && (i - 1) !in endsRun
            val rightIsWord = i + 1 < text.length && text[i + 1] != ' ' && (i + 1) !in startsRun
            if ((leftIsRun && rightIsWord) || (rightIsRun && leftIsWord)) out.add(i)
        }
        return out
    }

    /** How much wider a seam space gets. 0.236 em becomes about 0.5. */
    private const val GAP_STRETCH = 2.2f

    /**
     * [text] with its numbers and Latin in [pixel], ready for setText.
     *
     * The TextView keeps its own face for everything else, so callers set the
     * Thai font in the layout once and never think about it again.
     */
    fun pixelify(text: CharSequence, pixel: Typeface): CharSequence {
        val out = SpannableStringBuilder(text)
        val runs = pixelRuns(text)
        for (run in runs) {
            out.setSpan(
                PixelSpan(pixel),
                run.first,
                run.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        for (gap in gapSpaces(text, runs)) {
            out.setSpan(
                ScaleXSpan(GAP_STRETCH),
                gap,
                gap + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }

    /**
     * The same, plus the freshness note at the end turned down.
     *
     * DashboardState ends a stale panel with "  (9 นาทีก่อน)". That has to stay
     * on the screen — a price with no age on it is the one genuinely dishonest
     * way to show this — but at full size and full black it was reading as
     * loudly as the price, and on the gold window it was wrapping onto a line
     * of its own. Smaller and grey says "this is a footnote" without hiding it.
     */
    fun pixelifyWithAge(text: CharSequence, pixel: Typeface, dim: Int): CharSequence {
        val out = SpannableStringBuilder(pixelify(text, pixel))
        val note = AGE_NOTE.find(text)
        if (note != null) {
            out.setSpan(
                RelativeSizeSpan(0.75f),
                note.range.first,
                note.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
            out.setSpan(
                ForegroundColorSpan(dim),
                note.range.first,
                note.range.last + 1,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }

    /** The "  (9 นาทีก่อน)" DashboardState.age() puts on the end, if it is there. */
    private val AGE_NOTE = Regex("""\s*\([^)]*\)\s*$""")

    /**
     * The same again, with everything after the first line turned down a step.
     *
     * The weather panel is two lines that are not equals: the first is the
     * temperature and the word for the sky, which is the whole reason anyone
     * looks; the second is humidity and the day's high and low, which is the
     * sort of thing you read only once you have read the first. At one size
     * they competed. This is the only panel it applies to — gold's two lines
     * are two prices and crypto's are two coins, and neither has a junior.
     */
    fun pixelifyHeadline(text: CharSequence, pixel: Typeface, dim: Int): CharSequence {
        val out = SpannableStringBuilder(pixelifyWithAge(text, pixel, dim))
        val breakAt = text.indexOf('\n')
        if (breakAt >= 0 && breakAt + 1 < text.length) {
            out.setSpan(
                RelativeSizeSpan(0.85f),
                breakAt + 1,
                text.length,
                Spanned.SPAN_EXCLUSIVE_EXCLUSIVE,
            )
        }
        return out
    }

    /**
     * The line breaks that make up an empty line: each "\n" straight after
     * another one. Pure, so the choice of WHICH characters shrink is testable.
     */
    fun blankLineBreaks(text: CharSequence): List<Int> =
        (1 until text.length).filter { text[it] == '\n' && text[it - 1] == '\n' }

    /**
     * [text] with every empty line shrunk to [factor] of its height.
     *
     * The crypto columns separate two coins with an empty line, and at full
     * height that empty line was as tall as a price — four coins spread over
     * the whole window and read as loose. A line's height comes from the text
     * on it, and the only thing on an empty line is its own break, so a size
     * span on that one character is what sets it.
     */
    fun tightenBlankLines(text: CharSequence, factor: Float): CharSequence {
        val out = SpannableStringBuilder(text)
        for (index in blankLineBreaks(text)) {
            out.setSpan(RelativeSizeSpan(factor), index, index + 1,
                        Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    /**
     * One span, three jobs: swap the face, shrink it to match, put it back on
     * the baseline. They have to travel together — change the size without the
     * shift and the digits float; do either in a plain CharacterStyle and the
     * line is laid out to the wrong width.
     */
    private class PixelSpan(private val pixel: Typeface) : MetricAffectingSpan() {

        override fun updateDrawState(tp: TextPaint) = apply(tp)

        override fun updateMeasureState(tp: TextPaint) = apply(tp)

        private fun apply(tp: TextPaint) {
            tp.typeface = pixel
            tp.textSize = tp.textSize * PIXEL_SCALE
            // Positive shifts down, which is the direction Press Start 2P's
            // digits need: SuperscriptSpan lifts text with a negative one.
            tp.baselineShift += (tp.textSize * PIXEL_DROP).toInt()
        }
    }
}
