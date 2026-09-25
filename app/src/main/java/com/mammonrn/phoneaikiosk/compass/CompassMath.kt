package com.mammonrn.phoneaikiosk.compass

import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.floor
import kotlin.math.sqrt

/**
 * The compass's and the level's sums (0.62.0, DESIGN.md 5ด). No Android in
 * here: CompassTest drives them with plain numbers.
 */
object Heading {

    /** The eight directions in Thai, clockwise from north, each 45° wide and centred on its point. */
    val WORDS = listOf(
        "เหนือ", "ตะวันออกเฉียงเหนือ", "ตะวันออก", "ตะวันออกเฉียงใต้",
        "ใต้", "ตะวันตกเฉียงใต้", "ตะวันตก", "ตะวันตกเฉียงเหนือ",
    )

    /** The same eight as letters, for the Press Start 2P read-out ("NE"). */
    val LETTERS = listOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")

    /** Any angle into 0 ≤ a < 360. */
    fun normalize(degrees: Double): Double {
        val a = degrees % 360.0
        val n = if (a < 0) a + 360.0 else a
        return if (n >= 360.0) 0.0 else n
    }

    /** Whole degrees as shown: 0..359, where 359.6 is 0, never 360. */
    fun shown(degrees: Double): Int = Math.round(normalize(degrees)).toInt() % 360

    /** Which of the eight: north is 337.5 up to (not including) 22.5. */
    fun sector(degrees: Double): Int = floor((normalize(degrees) + 22.5) / 45.0).toInt() % 8

    fun thai(degrees: Double): String = WORDS[sector(degrees)]

    fun letters(degrees: Double): String = LETTERS[sector(degrees)]

    /**
     * Moves [previous] a share [alpha] of the way to [next] by THE SHORT WAY
     * ROUND: from 359° to 1° is +2°, not −358° — a plain average would swing
     * the needle through south every time it passes north.
     */
    fun smooth(previous: Double?, next: Double, alpha: Double): Double {
        if (previous == null) return normalize(next)
        val diff = ((next - previous) % 360.0 + 540.0) % 360.0 - 180.0
        return normalize(previous + alpha * diff)
    }

    /**
     * The Earth's field is about 25 to 65 µT everywhere (about 42 µT in
     * Thailand). Outside a wider 20..70 the needle is reading a magnet, a
     * speaker or a steel shelf, not north.
     */
    fun fieldOk(microTesla: Double): Boolean = microTesla in 20.0..70.0

    fun strength(x: Float, y: Float, z: Float): Double = sqrt((x * x + y * y + z * z).toDouble())

    /** How sure the magnetometer says it is (SensorManager.SENSOR_STATUS_*). */
    enum class Accuracy { HIGH, MEDIUM, LOW, UNRELIABLE, UNKNOWN }

    fun accuracy(status: Int?): Accuracy = when (status) {
        3 -> Accuracy.HIGH
        2 -> Accuracy.MEDIUM
        1 -> Accuracy.LOW
        0 -> Accuracy.UNRELIABLE
        else -> Accuracy.UNKNOWN   // -1 (no contact) or not heard yet
    }

    /** "โบกมือถือเป็นรูปเลข 8": low or unreliable, or a field that is not the Earth's. */
    fun needsFigureEight(accuracy: Accuracy, fieldOk: Boolean): Boolean =
        accuracy == Accuracy.LOW || accuracy == Accuracy.UNRELIABLE || !fieldOk

    /**
     * Held up (screen towards the person, the back camera towards the view)
     * rather than lying flat: from [tiltDegrees], the screen's angle from
     * facing the sky. With a margin both ways, so a hand at 45° does not make
     * the read-out flick between the two.
     */
    fun upright(wasUpright: Boolean, tiltDegrees: Double): Boolean =
        if (wasUpright) tiltDegrees > UPRIGHT_LEAVE else tiltDegrees > UPRIGHT_ENTER

    const val UPRIGHT_ENTER = 55.0
    const val UPRIGHT_LEAVE = 35.0
}

/**
 * The spirit level, from gravity as the accelerometer reports it (m/s², the
 * phone's axes: x to the right, y to the top, z out of the screen).
 *
 * A side that is HIGHER reads positive: the accelerometer reports "up", so
 * lifting the right edge puts some of it on +x. The bubble goes to the high
 * side, as a real bubble does.
 */
object Level {

    /** "ได้ระดับ" within this many degrees each way, inclusive (Poom: ±1°). */
    const val TOLERANCE = 1.0

    /** The bubble reaches the edge of its window at this tilt. */
    const val FULL_SCALE = 10.0

    sealed class Reading {
        /** Lying flat (on a table, a shelf): two axes. [right] > 0: the right edge is higher; [top] > 0: the top edge. */
        data class Flat(val right: Double, val top: Double) : Reading() {
            val level: Boolean get() = abs(right) <= TOLERANCE && abs(top) <= TOLERANCE
        }

        /** Standing on its bottom edge (against a picture frame): one axis. [right] > 0: the right end of the bottom edge is higher. */
        data class Upright(val right: Double) : Reading() {
            val level: Boolean get() = abs(right) <= TOLERANCE
        }

        /** On its side or upside down: no reading, only a hint. */
        object Sideways : Reading()
    }

    /** Null when there is too little gravity to read (the phone falling, or no data). */
    fun read(x: Double, y: Double, z: Double): Reading? {
        val g = sqrt(x * x + y * y + z * z)
        if (g < MIN_G) return null
        val ax = abs(x)
        val ay = abs(y)
        val az = abs(z)
        return when {
            az >= ax && az >= ay -> Reading.Flat(degrees(atan2(x, az)), degrees(atan2(y, az)))
            y > 0 && ay >= ax -> Reading.Upright(degrees(atan2(x, y)))
            else -> Reading.Sideways
        }
    }

    /** −1 (the minus side is higher), 0 (within [TOLERANCE]), +1 (the plus side is higher). */
    fun side(angle: Double): Int = when {
        angle > TOLERANCE -> 1
        angle < -TOLERANCE -> -1
        else -> 0
    }

    /** Where the bubble sits, −1..+1 of the way to its window's edge. */
    fun bubble(angle: Double): Double = (angle / FULL_SCALE).coerceIn(-1.0, 1.0)

    /** A light low-pass on gravity: the hand's shake out, a real tilt in within a quarter second. */
    fun lowPass(previous: DoubleArray?, next: DoubleArray, alpha: Double): DoubleArray =
        if (previous == null) next.copyOf()
        else DoubleArray(next.size) { i -> previous[i] + alpha * (next[i] - previous[i]) }

    private fun degrees(radians: Double) = Math.toDegrees(radians)

    private const val MIN_G = 3.0
}
