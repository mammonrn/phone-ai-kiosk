package com.mammonrn.phoneaikiosk.calc

import java.math.BigDecimal
import java.math.MathContext
import java.math.RoundingMode
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * The electrical and electronics mode (0.52.0, Poom). Plain Kotlin; the page
 * that draws it is CalculatorActivity. Every value that comes out carries its
 * unit ([withUnit]); every value that goes in is checked ([Check]).
 *
 * SOURCES, checked 2026-09-24 and quoted on the screen where they apply:
 *  * Resistor colour code: IEC 60062:2016 table, as reproduced on
 *    en.wikipedia.org/wiki/Electronic_color_code (digits, multipliers
 *    silver 10^-2 … white 10^9, tolerances incl. orange ±0.05, yellow ±0.02,
 *    grey ±0.01).
 *  * Conductor resistance: IEC 60228, class 2 stranded plain copper, maximum
 *    DC resistance at 20 °C (Nexans "Classification of conductors according
 *    to IEC 60228", Feb 2021, page 2).
 *  * NOT found in a readable, checked form: the ampacity table of the Thai
 *    installation standard (วสท., table 5-20 by the chapter-5 slides). So the
 *    wire page sizes by VOLTAGE DROP ONLY and says, on the screen, that the
 *    current rating must be checked against that table.
 */
object Electrical {

    // ------------------------------------------------------------- units

    /** "4.7 kΩ", "230 V", "12 mA", "0.5 µF": 4 significant digits, SI prefix. */
    fun withUnit(value: Double, unit: String): String {
        if (value == 0.0) return "0 $unit"
        val prefixes = listOf(1e9 to "G", 1e6 to "M", 1e3 to "k", 1.0 to "", 1e-3 to "m", 1e-6 to "µ", 1e-9 to "n", 1e-12 to "p")
        val a = abs(value)
        val (scale, prefix) = prefixes.firstOrNull { a >= it.first * 0.9999999 } ?: prefixes.last()
        return "${plain(value / scale)} $prefix$unit"
    }

    /** 4 significant digits, no trailing zeros: 4.7, 1000, 0.3333. */
    fun plain(value: Double, digits: Int = 4): String =
        BigDecimal(value).round(MathContext(digits, RoundingMode.HALF_EVEN)).stripTrailingZeros().toPlainString()

    /** What is wrong with an input, or a warning that it is unusual. Null: fine. */
    data class Check(val message: String, val blocking: Boolean)

    // ------------------------------------------------------------- Ohm's law

    data class Ohm(val volts: Double, val amps: Double, val ohms: Double, val watts: Double)

    /**
     * Any two of V, I, R, P (the others null) give all four. Null when not
     * exactly two are given or they cannot go together (R = 0 with V ≠ 0).
     */
    fun ohm(v: Double?, i: Double?, r: Double?, p: Double?): Ohm? {
        if (listOf(v, i, r, p).count { it != null } != 2) return null
        return when {
            v != null && i != null -> Ohm(v, i, if (i == 0.0) return null else v / i, v * i)
            v != null && r != null -> if (r == 0.0) null else Ohm(v, v / r, r, v * v / r)
            v != null && p != null -> if (v == 0.0) null else Ohm(v, p / v, v * v / p, p)
            i != null && r != null -> Ohm(i * r, i, r, i * i * r)
            i != null && p != null -> if (i == 0.0) null else Ohm(p / i, i, p / (i * i), p)
            r != null && p != null -> if (r == 0.0) null else Ohm(sqrt(p * r), sqrt(p / r), r, p)
            else -> null
        }
    }

    /** Checks one of Ohm's-law inputs: negatives and zeros block, the implausible warns. */
    fun checkOhm(name: Char, value: Double): Check? = when {
        value.isNaN() || value.isInfinite() -> Check("ค่า $name ไม่ใช่ตัวเลข", true)
        value < 0 -> Check("ค่า $name ต้องไม่ติดลบ", true)
        value == 0.0 && name in "RP" -> Check("ค่า $name ต้องมากกว่า 0", true)
        name == 'V' && value > 100_000 -> Check("แรงดันเกิน 100 kV ผิดปกติ กรุณาตรวจหน่วย", false)
        name == 'I' && value > 10_000 -> Check("กระแสเกิน 10 kA ผิดปกติ กรุณาตรวจหน่วย", false)
        name == 'R' && value > 1e12 -> Check("ความต้านทานเกิน 1 TΩ ผิดปกติ กรุณาตรวจหน่วย", false)
        name == 'P' && value > 1e8 -> Check("กำลังเกิน 100 MW ผิดปกติ กรุณาตรวจหน่วย", false)
        else -> null
    }

    // ------------------------------------------------------------- series / parallel

    enum class Part(val unit: String, val thai: String) {
        RESISTOR("Ω", "ตัวต้านทาน"), CAPACITOR("F", "ตัวเก็บประจุ"), INDUCTOR("H", "ตัวเหนี่ยวนำ")
    }

    /** (series, parallel). Resistors and inductors add in series; capacitors in parallel. */
    fun combine(part: Part, values: List<Double>): Pair<Double, Double>? {
        if (values.isEmpty() || values.any { it <= 0 || it.isNaN() || it.isInfinite() }) return null
        val sum = values.sum()
        val reciprocal = 1.0 / values.sumOf { 1.0 / it }
        return if (part == Part.CAPACITOR) reciprocal to sum else sum to reciprocal
    }

    // ------------------------------------------------------------- colour code

    /** IEC 60062:2016. [digit] null: not a digit band; [tolerance] null: not a tolerance band. */
    enum class Band(val thai: String, val rgb: Int, val digit: Int?, val exponent: Int?, val tolerance: Double?) {
        BLACK("ดำ", 0x000000, 0, 0, null),
        BROWN("น้ำตาล", 0x8B4513, 1, 1, 1.0),
        RED("แดง", 0xCC0000, 2, 2, 2.0),
        ORANGE("ส้ม", 0xFF8C00, 3, 3, 0.05),
        YELLOW("เหลือง", 0xFFD700, 4, 4, 0.02),
        GREEN("เขียว", 0x008000, 5, 5, 0.5),
        BLUE("น้ำเงิน", 0x0000CC, 6, 6, 0.25),
        VIOLET("ม่วง", 0x8A2BE2, 7, 7, 0.1),
        GREY("เทา", 0x808080, 8, 8, 0.01),
        WHITE("ขาว", 0xFFFFFF, 9, 9, null),
        GOLD("ทอง", 0xC9A227, null, -1, 5.0),
        SILVER("เงิน", 0xC0C0C0, null, -2, 10.0),
        PINK("ชมพู", 0xFF69B4, null, -3, null),
    }

    data class Reading(val ohms: Double, val tolerance: Double)

    /** 4 bands (2 digits) or 5 (3 digits): digits, multiplier, tolerance. Null if a band is not allowed there. */
    fun read(bands: List<Band>): Reading? {
        val digits = bands.size - 2
        if (digits !in 2..3) return null
        var value = 0
        for (b in bands.take(digits)) value = value * 10 + (b.digit ?: return null)
        val exp = bands[digits].exponent ?: return null
        val tol = bands[digits + 1].tolerance ?: return null
        return Reading(value * 10.0.pow(exp), tol)
    }

    data class Coding(val bands: List<Band>, val shown: Double, val exact: Boolean)

    /**
     * The bands for [ohms] with [digits] significant digits (2 for 4 bands,
     * 3 for 5) and [tolerance]. When the value needs more digits it is rounded
     * and [Coding.exact] is false — the page says so. Null when out of range
     * (below 0.01 Ω on 4 bands, above white's multiplier) or the tolerance has
     * no colour.
     */
    fun encode(ohms: Double, digits: Int, tolerance: Double): Coding? {
        if (ohms <= 0 || ohms.isNaN() || ohms.isInfinite() || digits !in 2..3) return null
        val tolBand = Band.entries.firstOrNull { it.tolerance == tolerance } ?: return null
        var exp = floor(log10(ohms)).toInt() - (digits - 1)
        var mantissa = Math.round(ohms / 10.0.pow(exp))
        if (mantissa >= 10.0.pow(digits).toLong()) { mantissa /= 10; exp += 1 }       // 99.96 → 100
        val multiplier = Band.entries.firstOrNull { it.exponent == exp && (it.digit == null || it.digit == exp) }
            ?: return null
        val digitBands = mantissa.toString().padStart(digits, '0').map { ch ->
            Band.entries.first { it.digit == ch - '0' }
        }
        val shown = mantissa * 10.0.pow(exp)
        val exact = abs(shown - ohms) <= ohms * 1e-9
        return Coding(digitBands + multiplier + tolBand, shown, exact)
    }

    // ------------------------------------------------------------- AC

    data class Power(val watts: Double, val va: Double, val vars: Double)

    /** Real, apparent and reactive power. [threePhase]: V is line-to-line. */
    fun acPower(volts: Double, amps: Double, pf: Double, threePhase: Boolean): Power? {
        if (volts <= 0 || amps < 0 || pf < 0 || pf > 1) return null
        val s = (if (threePhase) sqrt(3.0) else 1.0) * volts * amps
        val p = s * pf
        return Power(p, s, sqrt((s * s - p * p).coerceAtLeast(0.0)))
    }

    /** X_L = 2πfL. */
    fun inductiveReactance(hz: Double, henry: Double): Double? =
        if (hz <= 0 || henry <= 0) null else 2 * PI * hz * henry

    /** X_C = 1 / (2πfC). */
    fun capacitiveReactance(hz: Double, farad: Double): Double? =
        if (hz <= 0 || farad <= 0) null else 1 / (2 * PI * hz * farad)

    // ------------------------------------------------------------- wire size

    /** IEC 60228 class 2, plain copper, max Ω/km at 20 °C (see the notes above). */
    val COPPER_OHM_PER_KM: List<Pair<Double, Double>> = listOf(
        1.5 to 12.1, 2.5 to 7.41, 4.0 to 4.61, 6.0 to 3.08, 10.0 to 1.83, 16.0 to 1.15,
        25.0 to 0.727, 35.0 to 0.524, 50.0 to 0.387, 70.0 to 0.268, 95.0 to 0.193, 120.0 to 0.153,
    )

    /** Copper's temperature coefficient at 20 °C, per K (annealed copper, 0.00393). */
    const val COPPER_ALPHA = 0.00393

    /** The conductor temperature the drop is worked at: PVC's 70 °C rating, the worst case. */
    const val CONDUCTOR_C = 70.0

    data class Drop(val mm2: Double, val volts: Double, val percent: Double)

    /**
     * Voltage drop in each standard size for [amps] over [metres] one way:
     * single phase 2·L·I·R, three phase √3·L·I·R (V line-to-line), R at 70 °C.
     * Reactance is left out, which is small for these sizes; the page says so.
     */
    fun drops(volts: Double, amps: Double, metres: Double, threePhase: Boolean): List<Drop>? {
        if (volts <= 0 || amps <= 0 || metres <= 0) return null
        val k = if (threePhase) sqrt(3.0) else 2.0
        val hot = 1 + COPPER_ALPHA * (CONDUCTOR_C - 20.0)
        return COPPER_OHM_PER_KM.map { (mm2, ohmPerKm) ->
            val vd = k * metres * amps * ohmPerKm * hot / 1000.0
            Drop(mm2, vd, vd / volts * 100)
        }
    }

    /** The smallest size whose drop is within [limitPercent], or null if none of them is. */
    fun smallestFor(drops: List<Drop>, limitPercent: Double): Drop? =
        drops.firstOrNull { it.percent <= limitPercent }
}
