package com.mammonrn.phoneaikiosk.calc

import java.math.BigDecimal
import java.math.RoundingMode
import kotlin.math.ceil
import kotlin.math.floor

/**
 * The solar mode of the calculator (0.62.0, Poom: "ห้ามให้ผลคำนวณผิด"). Plain
 * Kotlin, no Android: every formula is here and SolarTest works each one by
 * hand. SolarPages only reads the fields and draws what comes back.
 *
 * EVERY INPUT IS CHECKED HERE, not on the page: a missing field, a word where
 * a number goes, zero, a negative, a value past its [Range] all come back as
 * a [Problem] naming the field and the rule, which the page says in Thai.
 * The ranges also keep every product far from overflow, and each answer is
 * checked finite before it leaves ([finite]), so NaN or Infinity can never
 * reach the screen.
 *
 * ASSUMPTIONS, each said on the screen under its answer (DESIGN.md 5จ):
 *  * panels: array W = daily Wh ÷ (peak sun hours × (1 − losses));
 *  * battery: Wh = daily Wh × days ÷ (DoD × efficiency), efficiency being the
 *    inverter and battery together (default 90 %);
 *  * inverter: continuous and surge both × 1.25 ([INVERTER_MARGIN]);
 *  * charge controller: × 1.25 ([CONTROLLER_SAFETY]) for MPPT and PWM alike;
 *  * strings: Voc at the coldest cell, Vmp at the hottest, linear in the
 *    temperature coefficients from the panel's datasheet (25 °C STC);
 *  * wire: DC copper, ρ = 0.0175 Ω·mm²/m, a round trip of 2 × L;
 *  * payback: 365 days a year, a month a twelfth of it, today's price, no
 *    panel ageing, no maintenance.
 */
object Solar {

    // ------------------------------------------------------------ inputs and their checks

    /** Every number a page asks for. The page has a Thai name for each. */
    enum class Field {
        DAILY_KWH, SUN_HOURS, LOSS, PANEL_W,
        DAYS, DOD, EFFICIENCY, BATTERY_V,
        RUNNING_W, PEAK_W,
        ARRAY_W, ISC, PARALLEL,
        VOC, VMP, COEF_VOC, COEF_VMP, MIN_TEMP, CELL_TEMP, MAX_V, MPPT_MIN,
        CURRENT, LENGTH, VOLTAGE, DROP,
        COST, PRICE, PRODUCED_KWH,
    }

    /** Why an input cannot be used. [limit] is the bound broken, for the message. */
    enum class Rule {
        MISSING,          // กรุณากรอก…
        NOT_NUMBER,       // …ต้องเป็นตัวเลข
        NOT_POSITIVE,     // …ต้องมากกว่า 0
        NEGATIVE,         // …ต้องไม่ติดลบ
        BELOW,            // …ต้องไม่ต่ำกว่า [limit]
        ABOVE,            // …ต้องไม่เกิน [limit]
        NOT_WHOLE,        // …ต้องเป็นจำนวนเต็ม
        NOT_NEGATIVE,     // …ต้องติดลบ (a temperature coefficient of voltage)
        VMP_NOT_BELOW_VOC,
        MPPT_NOT_BELOW_MAX,
        CELL_NOT_ABOVE_MIN,
        PEAK_BELOW_RUNNING,
    }

    data class Problem(val field: Field, val rule: Rule, val limit: Double? = null)

    sealed interface Result<out T> {
        data class Ok<T>(val value: T) : Result<T>
        data class Bad(val problem: Problem) : Result<Nothing>
    }

    /**
     * What a field may hold: above (or from) [min], at most [max]. [whole]:
     * only whole numbers. [below]: strictly under this (the coefficients, < 0).
     */
    data class Range(val min: Double, val minIncluded: Boolean, val max: Double,
                     val whole: Boolean = false, val below: Double? = null)

    val RANGES: Map<Field, Range> = mapOf(
        Field.DAILY_KWH to Range(0.0, false, 10_000.0),
        Field.SUN_HOURS to Range(0.0, false, 12.0),
        Field.LOSS to Range(0.0, true, 50.0),
        Field.PANEL_W to Range(0.0, false, 1_000.0),
        Field.DAYS to Range(0.0, false, 30.0),
        Field.DOD to Range(0.0, false, 100.0),
        Field.EFFICIENCY to Range(50.0, true, 100.0),
        Field.BATTERY_V to Range(0.0, false, 1_000.0),
        Field.RUNNING_W to Range(0.0, false, 1_000_000.0),
        Field.PEAK_W to Range(0.0, false, 10_000_000.0),
        Field.ARRAY_W to Range(0.0, false, 1_000_000.0),
        Field.ISC to Range(0.0, false, 100.0),
        Field.PARALLEL to Range(1.0, true, 100.0, whole = true),
        Field.VOC to Range(0.0, false, 1_000.0),
        Field.VMP to Range(0.0, false, 1_000.0),
        Field.COEF_VOC to Range(-1.0, true, 0.0, below = 0.0),
        Field.COEF_VMP to Range(-1.0, true, 0.0, below = 0.0),
        Field.MIN_TEMP to Range(-50.0, true, 50.0),
        Field.CELL_TEMP to Range(25.0, true, 100.0),
        Field.MAX_V to Range(0.0, false, 2_000.0),
        Field.MPPT_MIN to Range(0.0, false, 2_000.0),
        Field.CURRENT to Range(0.0, false, 1_000.0),
        Field.LENGTH to Range(0.0, false, 1_000.0),
        Field.VOLTAGE to Range(0.0, false, 1_500.0),
        Field.DROP to Range(0.0, false, 20.0),
        Field.COST to Range(0.0, false, 1_000_000_000.0),
        Field.PRICE to Range(0.0, false, 100.0),
        Field.PRODUCED_KWH to Range(0.0, false, 10_000.0),
    )

    /**
     * What was typed, as a number. Null: the field is empty. NaN: it is not a
     * number ("abc", "1.2.3", "NaN", "Infinity" — Kotlin's own parser would
     * take the last two). "150,000" is a hundred and fifty thousand (a Thai
     * thousands comma); a lone comma with other than three digits after it is
     * a decimal point ("4,5" = 4.5). "−" (the minus sign) is a minus.
     */
    fun parse(text: String?): Double? {
        val t = text?.trim()?.replace('−', '-')?.replace(" ", "") ?: return null
        if (t.isEmpty()) return null
        val plain = when {
            THOUSANDS.matches(t) -> t.replace(",", "")
            t.count { it == ',' } == 1 && '.' !in t -> t.replace(',', '.')
            else -> t
        }
        if (!NUMBER.matches(plain)) return Double.NaN
        return plain.toDoubleOrNull()?.takeIf { it.isFinite() } ?: Double.NaN
    }

    private val THOUSANDS = Regex("""[+-]?\d{1,3}(,\d{3})+(\.\d+)?""")
    private val NUMBER = Regex("""[+-]?(\d+\.?\d*|\.\d+)([eE][+-]?\d+)?""")

    /** The value of [field] if it may be used, else why not. */
    fun check(field: Field, value: Double?): Result<Double> {
        val r = RANGES.getValue(field)
        fun bad(rule: Rule, limit: Double? = null) = Result.Bad(Problem(field, rule, limit))
        return when {
            value == null -> bad(Rule.MISSING)
            !value.isFinite() -> bad(Rule.NOT_NUMBER)
            r.below != null && value >= r.below -> bad(Rule.NOT_NEGATIVE)
            r.min == 0.0 && !r.minIncluded && value <= 0.0 -> bad(Rule.NOT_POSITIVE)
            r.min == 0.0 && r.minIncluded && value < 0.0 -> bad(Rule.NEGATIVE)
            r.minIncluded && value < r.min -> bad(Rule.BELOW, r.min)
            !r.minIncluded && value <= r.min -> bad(Rule.BELOW, r.min)
            value > r.max -> bad(Rule.ABOVE, r.max)
            r.whole && value != floor(value) -> bad(Rule.NOT_WHOLE)
            else -> Result.Ok(value)
        }
    }

    /** Checks the fields in order and hands back their values, or the first problem. */
    private inline fun <T> checked(vararg inputs: Pair<Field, Double?>, block: (List<Double>) -> Result<T>): Result<T> {
        val values = ArrayList<Double>(inputs.size)
        for ((field, value) in inputs) {
            when (val c = check(field, value)) {
                is Result.Ok -> values.add(c.value)
                is Result.Bad -> return c
            }
        }
        return block(values)
    }

    /** Guards the way out: an answer that is not a finite number is a problem with [blame]. */
    private fun <T> finite(value: T, blame: Field, vararg numbers: Double): Result<T> =
        if (numbers.all { it.isFinite() }) Result.Ok(value) else Result.Bad(Problem(blame, Rule.ABOVE, RANGES.getValue(blame).max))

    // Ceil and floor that do not trip on binary fractions: 3.0000000000000004 is 3.
    internal fun roundUp(x: Double): Int = ceil(x - EPS * maxOf(1.0, x)).toInt()
    internal fun roundDown(x: Double): Int = floor(x + EPS * maxOf(1.0, x)).toInt()
    private const val EPS = 1e-9

    // ------------------------------------------------------------ 1. panels

    data class Panels(
        val arrayW: Double,          // the array needed
        val panels: Int,             // panels of the given W, rounded up
        val installedW: Double,      // panels × W per panel
        val installedKwhPerDay: Double,
    )

    /**
     * Array W = daily kWh × 1000 ÷ (sun hours × (1 − losses ÷ 100));
     * panels = ⌈array W ÷ W per panel⌉. Also what those panels make a day.
     */
    fun panels(dailyKwh: Double?, sunHours: Double?, lossPct: Double?, panelW: Double?): Result<Panels> =
        checked(Field.DAILY_KWH to dailyKwh, Field.SUN_HOURS to sunHours, Field.LOSS to lossPct, Field.PANEL_W to panelW) { (e, h, l, w) ->
            val derate = 1 - l / 100
            val array = e * 1000 / (h * derate)
            val n = roundUp(array / w)
            val installed = n * w
            val made = installed * h * derate / 1000
            finite(Panels(array, n, installed, made), Field.DAILY_KWH, array, installed, made)
        }

    // ------------------------------------------------------------ 2. battery

    enum class Chemistry(val defaultDod: Double) { LITHIUM(90.0), LEAD_ACID(50.0) }

    /** The inverter's and battery's efficiency together, when nothing else is known. */
    const val DEFAULT_EFFICIENCY = 90.0

    data class Battery(val wh: Double, val ah: Double)

    /** Wh = daily kWh × 1000 × days ÷ (DoD × efficiency); Ah = Wh ÷ system V. */
    fun battery(dailyKwh: Double?, days: Double?, dodPct: Double?, efficiencyPct: Double?, volts: Double?): Result<Battery> =
        checked(Field.DAILY_KWH to dailyKwh, Field.DAYS to days, Field.DOD to dodPct,
                Field.EFFICIENCY to efficiencyPct, Field.BATTERY_V to volts) { (e, d, dod, eff, v) ->
            val wh = e * 1000 * d / ((dod / 100) * (eff / 100))
            val ah = wh / v
            finite(Battery(wh, ah), Field.DAILY_KWH, wh, ah)
        }

    // ------------------------------------------------------------ 3. inverter

    /** The headroom on both ratings: 25 %. */
    const val INVERTER_MARGIN = 1.25

    data class Inverter(val continuousW: Double, val surgeW: Double, val peakW: Double)

    /**
     * Continuous = running W × 1.25; surge = the highest starting W × 1.25.
     * [peakW] null: nothing starts hard, the peak is the running load.
     */
    fun inverter(runningW: Double?, peakW: Double?): Result<Inverter> {
        val run = when (val c = check(Field.RUNNING_W, runningW)) { is Result.Ok -> c.value; is Result.Bad -> return c }
        val peak = if (peakW == null) run else when (val c = check(Field.PEAK_W, peakW)) { is Result.Ok -> c.value; is Result.Bad -> return c }
        if (peak < run) return Result.Bad(Problem(Field.PEAK_W, Rule.PEAK_BELOW_RUNNING))
        val cont = run * INVERTER_MARGIN
        val surge = peak * INVERTER_MARGIN
        return finite(Inverter(cont, surge, peak), Field.RUNNING_W, cont, surge)
    }

    // ------------------------------------------------------------ 4. charge controller

    /** The NEC-style 1.25 on a controller's current, for MPPT and PWM alike. */
    const val CONTROLLER_SAFETY = 1.25

    /** MPPT: I = array W ÷ battery V × 1.25. */
    fun mppt(arrayW: Double?, batteryV: Double?): Result<Double> =
        checked(Field.ARRAY_W to arrayW, Field.BATTERY_V to batteryV) { (w, v) ->
            val amps = w / v * CONTROLLER_SAFETY
            finite(amps, Field.ARRAY_W, amps)
        }

    /** PWM: I = Isc × strings in parallel × 1.25. */
    fun pwm(iscA: Double?, parallel: Double?): Result<Double> =
        checked(Field.ISC to iscA, Field.PARALLEL to parallel) { (isc, n) ->
            val amps = isc * n * CONTROLLER_SAFETY
            finite(amps, Field.ISC, amps)
        }

    // ------------------------------------------------------------ 5. strings

    /** The datasheet's temperature, and Thailand's defaults (editable on the page). */
    const val STC_C = 25.0
    const val DEFAULT_COEF_VOC = -0.28
    const val DEFAULT_COEF_VMP = -0.35
    const val DEFAULT_MIN_C = 10.0
    const val DEFAULT_CELL_C = 70.0

    data class Strings(
        val vocCold: Double,         // one panel's Voc at the coldest
        val vmpHot: Double,          // one panel's Vmp at the hottest cell
        val maxSeries: Int,          // most in series: Voc cold × n ≤ max V (0: not even one)
        val minSeries: Int,          // fewest in series: Vmp hot × n ≥ MPPT min
    ) {
        /** Some count fits between the two. */
        val fits: Boolean get() = maxSeries >= 1 && minSeries <= maxSeries
    }

    /**
     * Voc cold = Voc × (1 + coefVoc ÷ 100 × (Tmin − 25));
     * Vmp hot = Vmp × (1 + coefVmp ÷ 100 × (Tcell − 25));
     * most in series = ⌊max V ÷ Voc cold⌋; fewest = ⌈MPPT min ÷ Vmp hot⌉.
     */
    fun strings(voc: Double?, vmp: Double?, coefVocPct: Double?, coefVmpPct: Double?,
                minC: Double?, cellC: Double?, maxV: Double?, mpptMinV: Double?): Result<Strings> =
        checked(Field.VOC to voc, Field.VMP to vmp, Field.COEF_VOC to coefVocPct, Field.COEF_VMP to coefVmpPct,
                Field.MIN_TEMP to minC, Field.CELL_TEMP to cellC, Field.MAX_V to maxV, Field.MPPT_MIN to mpptMinV) { v ->
            val oc = v[0]; val mp = v[1]; val cv = v[2]; val cm = v[3]
            val tMin = v[4]; val tCell = v[5]; val vMax = v[6]; val vMin = v[7]
            when {
                mp >= oc -> Result.Bad(Problem(Field.VMP, Rule.VMP_NOT_BELOW_VOC))
                tCell <= tMin -> Result.Bad(Problem(Field.CELL_TEMP, Rule.CELL_NOT_ABOVE_MIN))
                vMin >= vMax -> Result.Bad(Problem(Field.MPPT_MIN, Rule.MPPT_NOT_BELOW_MAX))
                else -> {
                    val cold = oc * (1 + cv / 100 * (tMin - STC_C))
                    val hot = mp * (1 + cm / 100 * (tCell - STC_C))
                    // With the ranges above both factors stay above 0.25; kept safe anyway.
                    if (cold <= 0 || hot <= 0) Result.Bad(Problem(Field.COEF_VMP, Rule.BELOW, -1.0))
                    else finite(Strings(cold, hot, roundDown(vMax / cold), roundUp(vMin / hot)), Field.VOC, cold, hot)
                }
            }
        }

    // ------------------------------------------------------------ 6. wire

    /** Copper at room temperature, Ω·mm²/m. */
    const val RHO_COPPER = 0.0175

    /** The sizes sold, mm². */
    val STANDARD_MM2 = listOf(1.5, 2.5, 4.0, 6.0, 10.0, 16.0, 25.0, 35.0, 50.0, 70.0, 95.0, 120.0)

    data class Wire(
        val neededMm2: Double,       // the exact section for the allowed drop
        val chosenMm2: Double?,      // the smallest standard size ≥ that; null: none is big enough
        val dropV: Double,           // the drop in the chosen size, or in the largest if none
        val dropPct: Double,
    )

    /**
     * Section = 2 × L × I × ρ ÷ (V × drop ÷ 100), then the next standard size;
     * the drop in that size = 2 × L × I × ρ ÷ section, as a share of V.
     */
    fun wire(amps: Double?, metresOneWay: Double?, volts: Double?, dropPct: Double?): Result<Wire> =
        checked(Field.CURRENT to amps, Field.LENGTH to metresOneWay, Field.VOLTAGE to volts, Field.DROP to dropPct) { (i, l, v, d) ->
            val loop = 2 * l * i * RHO_COPPER               // V·mm²: the drop in a 1 mm² wire
            val needed = loop / (v * d / 100)
            val chosen = STANDARD_MM2.firstOrNull { it >= needed * (1 - EPS) }
            val size = chosen ?: STANDARD_MM2.last()
            val dropV = loop / size
            val pct = dropV / v * 100
            finite(Wire(needed, chosen, dropV, pct), Field.CURRENT, needed, dropV, pct)
        }

    // ------------------------------------------------------------ 7. payback

    /** Thailand's household rate, all in, roughly (baht per unit); editable on the page. */
    const val DEFAULT_PRICE = 4.2

    data class Payback(val perYear: Double, val perMonth: Double, val years: Double)

    /** A year's saving = kWh a day × 365 × price; a month's = that ÷ 12; years = cost ÷ a year's saving. */
    fun payback(costBaht: Double?, pricePerKwh: Double?, kwhPerDay: Double?): Result<Payback> =
        checked(Field.COST to costBaht, Field.PRICE to pricePerKwh, Field.PRODUCED_KWH to kwhPerDay) { (cost, price, kwh) ->
            val year = kwh * 365 * price
            val years = cost / year
            finite(Payback(year, year / 12, years), Field.COST, year, years)
        }

    // ------------------------------------------------------------ showing numbers

    /**
     * "2,777.78", "6", "0.7": at most [decimals] places, no trailing zeros,
     * thousands with commas. Never "NaN" or "∞": those are "-" (and cannot
     * come out of the functions above).
     */
    fun format(value: Double, decimals: Int = 2): String {
        if (!value.isFinite()) return "-"
        val s = BigDecimal(value).setScale(decimals, RoundingMode.HALF_UP).stripTrailingZeros()
        val plain = if (s.signum() == 0) BigDecimal.ZERO else s
        return String.format(java.util.Locale.US, "%,." + maxOf(0, plain.scale()) + "f", plain)
    }
}
