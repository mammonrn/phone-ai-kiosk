package com.mammonrn.phoneaikiosk.media

import kotlin.math.PI
import kotlin.math.atan2

/**
 * The video player's decisions, in plain Kotlin (0.56.0). VideoRulesTest.
 */
object VideoRules {

    /** Poom: "วิดีโอจำกัดที่ 720p ตามจอของเครื่อง ไม่ต้องรองรับ 4K และ AV1". */
    const val MAX_W = 1280
    const val MAX_H = 720

    /** Played by the phone now (0.59.0: decided in [PlayerChoice], the one place). */
    fun playable(name: String): Boolean = PlayerChoice.playsVideo(name)

    /**
     * Why a video is not played, in words, or null when it may be. [width] ×
     * [height] as the file says (either way round: a phone video stands up).
     */
    fun refusal(width: Int, height: Int, mime: String?): String? {
        if (mime != null && mime.contains("av01", ignoreCase = true)) return "AV1"
        val long = maxOf(width, height); val short = minOf(width, height)
        if (long > MAX_W || short > MAX_H) return "${width}×$height"
        return null
    }

    /** The speeds the button goes round, and the next one after [now]. */
    val SPEEDS = listOf(0.5f, 0.75f, 1f, 1.25f, 1.5f, 2f)

    fun nextSpeed(now: Float): Float = SPEEDS.firstOrNull { it > now + 0.01f } ?: SPEEDS.first()

    /** "0.5×", "0.75×", "1.0×", "2.0×". */
    fun speedWord(speed: Float): String {
        val two = "%.2f".format(java.util.Locale.US, speed)
        return (if (two.endsWith("0")) two.dropLast(1) else two) + "×"
    }

    /** A skip on the ring: this far back or forward. */
    const val SKIP_MS = 10_000L

    // ------------------------------------------------------------ the ring

    /** Angle of ([x], [y]) around ([cx], [cy]), clockwise from twelve o'clock, 0..1. */
    fun turn(x: Float, y: Float, cx: Float, cy: Float): Float {
        val a = atan2((x - cx).toDouble(), -(y - cy).toDouble())   // 0 at the top, clockwise positive
        val t = (a / (2 * PI)).toFloat()
        return if (t < 0) t + 1 else t
    }

    enum class RingZone { CENTRE, BACK, FORWARD, RING, OUTSIDE }

    /**
     * What a touch at distance [r] (of a ring of [inner]..[outer] radius) and
     * turn [t] means: the play button in the middle, the skips at nine and
     * three o'clock, or the ring to drag. The skips and the ring reach past
     * the drawn ring to the view's edge, so a finger a little outside still counts.
     */
    fun zone(r: Float, t: Float, inner: Float, outer: Float, reach: Float): RingZone = when {
        r <= inner -> RingZone.CENTRE
        r > outer + reach -> RingZone.OUTSIDE
        t in 0.625f..0.875f -> RingZone.BACK        // around nine o'clock
        t in 0.125f..0.375f -> RingZone.FORWARD     // around three o'clock
        else -> RingZone.RING
    }

    // ------------------------------------------------------------ where it was left

    /**
     * The place to start a video from, given where it was left: the start when
     * it was barely begun or nearly done (then it was watched), else the place.
     */
    fun resumeAt(savedMs: Long, durationMs: Long): Long = when {
        savedMs < 5_000 -> 0
        durationMs > 0 && savedMs > durationMs - 15_000 -> 0
        else -> savedMs
    }

    /** "1:02:03" or "0:02:03" — the read-out always shows hours, like the reference. */
    fun hms(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60)
    }
}

/**
 * THE HEAT LADDER (0.56.0; Poom asked for it at the start: "ผูกตัวฟังสถานะ
 * ความร้อนตั้งแต่แรก … ลดภาระตามลำดับที่กำหนดไว้ล่วงหน้า"). Plain Kotlin;
 * VideoRulesTest walks it.
 *
 * Android's thermal status (PowerManager, 0 none … 6 shutdown) picks a step.
 * Each step keeps what the one before it did and adds one thing:
 *
 *   0 NORMAL   (none, light)  — nothing held back.
 *   1 COOLER   (moderate)     — the video screen at 70% brightness; the music's bars stop.
 *   2 COOLEST  (severe)       — the video screen at 40%.
 *   3 PAUSE    (critical)     — playback pauses, play is refused, and the screen says why.
 *   4 STOP     (emergency, shutdown) — playback stops.
 *
 * WHAT IS NOT ON IT, AND WHY: a smaller picture. A file on the phone or the
 * NAS has one size, and the player cannot decode it smaller (seen on the A07:
 * the cap was set and a 720p file stayed 720p). [maxHeight] still asks for a
 * smaller version when a file carries more than one, but the screen never
 * claims the size was lowered — it says what was really done: dimmed.
 *
 * Going up is at once. Coming down waits [COOL_DOWN_MS] at the lower status
 * and then goes one step at a time, so a phone at the edge does not flicker.
 * A pause for heat is not resumed by itself: a person presses play.
 */
class HeatLadder(private val coolDownMs: Long = COOL_DOWN_MS) {

    enum class Step(val maxHeight: Int?, val barsOff: Boolean, val brightness: Float?, val pause: Boolean, val stop: Boolean) {
        NORMAL(VideoRules.MAX_H, false, null, false, false),
        COOLER(480, true, 0.7f, false, false),
        COOLEST(360, true, 0.4f, false, false),
        PAUSE(360, true, 0.4f, true, false),
        STOP(360, true, 0.4f, true, true),
    }

    var step = Step.NORMAL
        private set
    private var lowerSince = Long.MIN_VALUE

    /** The step for a thermal status reading. */
    fun stepFor(status: Int): Step = when {
        status <= 1 -> Step.NORMAL
        status == 2 -> Step.COOLER
        status == 3 -> Step.COOLEST
        status == 4 -> Step.PAUSE
        else -> Step.STOP
    }

    /** A new reading at [nowMs]; returns the step to be at. */
    fun update(status: Int, nowMs: Long): Step {
        val wanted = stepFor(status)
        when {
            wanted.ordinal > step.ordinal -> { step = wanted; lowerSince = Long.MIN_VALUE }
            wanted.ordinal < step.ordinal -> {
                if (lowerSince == Long.MIN_VALUE) lowerSince = nowMs
                if (nowMs - lowerSince >= coolDownMs) {
                    step = Step.entries[step.ordinal - 1]
                    lowerSince = if (wanted.ordinal < step.ordinal) nowMs else Long.MIN_VALUE
                }
            }
            else -> lowerSince = Long.MIN_VALUE
        }
        return step
    }

    companion object {
        const val COOL_DOWN_MS = 60_000L
    }
}
