package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calc.Solar
import com.mammonrn.phoneaikiosk.calc.Solar.Field
import com.mammonrn.phoneaikiosk.calc.Solar.Result
import com.mammonrn.phoneaikiosk.calc.Solar.Rule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/**
 * The solar calculator (0.62.0, Poom: "ห้ามให้ผลคำนวณผิด"). Every formula has
 * at least two examples worked by hand — the working is in the comment above
 * each — and each was worked again, separately, in Python before it went in.
 * Then the edges: empty, not a number, zero, negative, too large; never NaN
 * or Infinity.
 */
class SolarTest {

    private val tol = 1e-6

    private fun <T> ok(r: Result<T>): T = when (r) {
        is Result.Ok -> r.value
        is Result.Bad -> { fail("expected an answer, got ${r.problem}"); throw IllegalStateException() }
    }

    private fun bad(r: Result<*>, field: Field, rule: Rule) {
        assertTrue("expected $field $rule, got $r", r is Result.Bad)
        val p = (r as Result.Bad).problem
        assertEquals(field, p.field)
        assertEquals(rule, p.rule)
    }

    // ------------------------------------------------------------ 1. panels

    @Test
    fun `panels - hand examples`() {
        // 10 kWh a day, 4.5 h of sun, 20 % lost:
        //   10 × 1000 ÷ (4.5 × 0.8) = 10000 ÷ 3.6 = 2777.78 W
        //   550 W panels: 2777.78 ÷ 550 = 5.05 → 6 panels = 3300 W
        //   those make 3300 × 4.5 × 0.8 ÷ 1000 = 11.88 kWh a day
        val a = ok(Solar.panels(10.0, 4.5, 20.0, 550.0))
        assertEquals(2777.7777778, a.arrayW, tol)
        assertEquals(6, a.panels)
        assertEquals(3300.0, a.installedW, tol)
        assertEquals(11.88, a.installedKwhPerDay, tol)

        // 3 kWh, 5 h, no loss: 3000 ÷ 5 = 600 W; 400 W panels: 1.5 → 2 = 800 W, 800 × 5 = 4 kWh
        val b = ok(Solar.panels(3.0, 5.0, 0.0, 400.0))
        assertEquals(600.0, b.arrayW, tol)
        assertEquals(2, b.panels)
        assertEquals(800.0, b.installedW, tol)
        assertEquals(4.0, b.installedKwhPerDay, tol)

        // 1.2 kWh, 4 h, 25 %: 1200 ÷ (4 × 0.75) = 400 W; 300 W panels: 1.33 → 2 = 600 W
        val c = ok(Solar.panels(1.2, 4.0, 25.0, 300.0))
        assertEquals(400.0, c.arrayW, tol)
        assertEquals(2, c.panels)
    }

    @Test
    fun `panels - an exact fit is not rounded up by a binary fraction`() {
        // 2.2 kWh, 5.5 h, 20 %: 2200 ÷ 4.4 = 500 W, which the double makes 499.99999999999994;
        // 250 W panels: exactly 2, not 3 (and 1.8 kWh, 4.5 h gives 500.0 → 2 as well).
        assertEquals(2, ok(Solar.panels(2.2, 5.5, 20.0, 250.0)).panels)
        assertEquals(2, ok(Solar.panels(1.8, 4.5, 20.0, 250.0)).panels)
        // 0.9 kWh → 250 W: one 250 W panel, two 125 W panels
        assertEquals(1, ok(Solar.panels(0.9, 4.5, 20.0, 250.0)).panels)
        assertEquals(2, ok(Solar.panels(0.9, 4.5, 20.0, 125.0)).panels)
    }

    @Test
    fun `panels - edges`() {
        bad(Solar.panels(null, 4.5, 20.0, 550.0), Field.DAILY_KWH, Rule.MISSING)
        bad(Solar.panels(10.0, 0.0, 20.0, 550.0), Field.SUN_HOURS, Rule.NOT_POSITIVE)   // "ชั่วโมงแดด ต้องมากกว่า 0"
        bad(Solar.panels(10.0, -3.0, 20.0, 550.0), Field.SUN_HOURS, Rule.NOT_POSITIVE)
        bad(Solar.panels(10.0, 25.0, 20.0, 550.0), Field.SUN_HOURS, Rule.ABOVE)
        bad(Solar.panels(10.0, 4.5, 100.0, 550.0), Field.LOSS, Rule.ABOVE)            // would divide by 0
        bad(Solar.panels(10.0, 4.5, -5.0, 550.0), Field.LOSS, Rule.NEGATIVE)
        bad(Solar.panels(10.0, 4.5, 20.0, 0.0), Field.PANEL_W, Rule.NOT_POSITIVE)
        bad(Solar.panels(0.0, 4.5, 20.0, 550.0), Field.DAILY_KWH, Rule.NOT_POSITIVE)
        bad(Solar.panels(Double.NaN, 4.5, 20.0, 550.0), Field.DAILY_KWH, Rule.NOT_NUMBER)
        bad(Solar.panels(Double.POSITIVE_INFINITY, 4.5, 20.0, 550.0), Field.DAILY_KWH, Rule.NOT_NUMBER)
        bad(Solar.panels(1e300, 4.5, 20.0, 550.0), Field.DAILY_KWH, Rule.ABOVE)
        // The largest allowed of everything still gives finite numbers.
        val big = ok(Solar.panels(10_000.0, 0.001, 50.0, 0.001))
        assertTrue(big.arrayW.isFinite() && big.installedW.isFinite())
    }

    // ------------------------------------------------------------ 2. battery

    @Test
    fun `battery - hand examples`() {
        // Lithium, 5 kWh a day, 1 day, DoD 90 %, efficiency 90 %, 48 V:
        //   5000 × 1 ÷ (0.9 × 0.9) = 5000 ÷ 0.81 = 6172.84 Wh; ÷ 48 = 128.60 Ah
        val a = ok(Solar.battery(5.0, 1.0, 90.0, 90.0, 48.0))
        assertEquals(6172.839506, a.wh, tol)
        assertEquals(128.600823, a.ah, tol)

        // Lead-acid, 5 kWh, 2 days, DoD 50 %, 90 %, 24 V:
        //   10000 ÷ (0.5 × 0.9) = 10000 ÷ 0.45 = 22222.22 Wh; ÷ 24 = 925.93 Ah
        val b = ok(Solar.battery(5.0, 2.0, 50.0, 90.0, 24.0))
        assertEquals(22222.222222, b.wh, tol)
        assertEquals(925.925926, b.ah, tol)

        // No losses at all: 2.4 kWh, 1 day, 100 %, 100 %, 12 V → 2400 Wh, 200 Ah
        val c = ok(Solar.battery(2.4, 1.0, 100.0, 100.0, 12.0))
        assertEquals(2400.0, c.wh, tol)
        assertEquals(200.0, c.ah, tol)
    }

    @Test
    fun `battery - the chemistries' depth of discharge`() {
        assertEquals(90.0, Solar.Chemistry.LITHIUM.defaultDod, 0.0)
        assertEquals(50.0, Solar.Chemistry.LEAD_ACID.defaultDod, 0.0)
        assertEquals(90.0, Solar.DEFAULT_EFFICIENCY, 0.0)
    }

    @Test
    fun `battery - edges`() {
        bad(Solar.battery(5.0, 0.0, 90.0, 90.0, 48.0), Field.DAYS, Rule.NOT_POSITIVE)
        bad(Solar.battery(5.0, 1.0, 0.0, 90.0, 48.0), Field.DOD, Rule.NOT_POSITIVE)
        bad(Solar.battery(5.0, 1.0, 120.0, 90.0, 48.0), Field.DOD, Rule.ABOVE)
        bad(Solar.battery(5.0, 1.0, 90.0, 30.0, 48.0), Field.EFFICIENCY, Rule.BELOW)
        bad(Solar.battery(5.0, 1.0, 90.0, 101.0, 48.0), Field.EFFICIENCY, Rule.ABOVE)
        bad(Solar.battery(5.0, 1.0, 90.0, 90.0, 0.0), Field.BATTERY_V, Rule.NOT_POSITIVE)
        bad(Solar.battery(5.0, 1.0, 90.0, 90.0, -12.0), Field.BATTERY_V, Rule.NOT_POSITIVE)
        bad(Solar.battery(-5.0, 1.0, 90.0, 90.0, 48.0), Field.DAILY_KWH, Rule.NOT_POSITIVE)
        bad(Solar.battery(5.0, null, 90.0, 90.0, 48.0), Field.DAYS, Rule.MISSING)
        bad(Solar.battery(5.0, 1.0, Double.NaN, 90.0, 48.0), Field.DOD, Rule.NOT_NUMBER)
        val big = ok(Solar.battery(10_000.0, 30.0, 1e-9, 50.0, 1e-9))
        assertTrue(big.wh.isFinite() && big.ah.isFinite())
    }

    // ------------------------------------------------------------ 3. inverter

    @Test
    fun `inverter - hand examples`() {
        // 800 W running, a pump starting at 2000 W: 800 × 1.25 = 1000 W, 2000 × 1.25 = 2500 W
        val a = ok(Solar.inverter(800.0, 2000.0))
        assertEquals(1000.0, a.continuousW, tol)
        assertEquals(2500.0, a.surgeW, tol)

        // 1500 W, nothing starting hard (empty): 1875 W and 1875 W
        val b = ok(Solar.inverter(1500.0, null))
        assertEquals(1875.0, b.continuousW, tol)
        assertEquals(1875.0, b.surgeW, tol)
        assertEquals(1500.0, b.peakW, tol)

        // 350 W with a 1200 W start: 437.5 W and 1500 W
        val c = ok(Solar.inverter(350.0, 1200.0))
        assertEquals(437.5, c.continuousW, tol)
        assertEquals(1500.0, c.surgeW, tol)
    }

    @Test
    fun `inverter - edges`() {
        bad(Solar.inverter(null, 2000.0), Field.RUNNING_W, Rule.MISSING)
        bad(Solar.inverter(0.0, null), Field.RUNNING_W, Rule.NOT_POSITIVE)
        bad(Solar.inverter(-100.0, null), Field.RUNNING_W, Rule.NOT_POSITIVE)
        bad(Solar.inverter(800.0, 500.0), Field.PEAK_W, Rule.PEAK_BELOW_RUNNING)
        bad(Solar.inverter(800.0, Double.NaN), Field.PEAK_W, Rule.NOT_NUMBER)
        bad(Solar.inverter(2e6, null), Field.RUNNING_W, Rule.ABOVE)
        assertEquals(1.25, Solar.INVERTER_MARGIN, 0.0)
    }

    // ------------------------------------------------------------ 4. charge controller

    @Test
    fun `controller - hand examples`() {
        // MPPT: 1000 W ÷ 24 V = 41.667 A × 1.25 = 52.083 A
        assertEquals(52.0833333, ok(Solar.mppt(1000.0, 24.0)), tol)
        // MPPT: 3000 W ÷ 48 V = 62.5 A × 1.25 = 78.125 A
        assertEquals(78.125, ok(Solar.mppt(3000.0, 48.0)), tol)
        // PWM: Isc 9.5 A × 2 strings × 1.25 = 23.75 A
        assertEquals(23.75, ok(Solar.pwm(9.5, 2.0)), tol)
        // PWM: 5.2 A × 1 × 1.25 = 6.5 A
        assertEquals(6.5, ok(Solar.pwm(5.2, 1.0)), tol)
    }

    @Test
    fun `controller - edges`() {
        bad(Solar.mppt(0.0, 24.0), Field.ARRAY_W, Rule.NOT_POSITIVE)
        bad(Solar.mppt(1000.0, 0.0), Field.BATTERY_V, Rule.NOT_POSITIVE)       // no division by zero
        bad(Solar.mppt(1000.0, null), Field.BATTERY_V, Rule.MISSING)
        bad(Solar.pwm(9.5, 0.0), Field.PARALLEL, Rule.BELOW)
        bad(Solar.pwm(9.5, 1.5), Field.PARALLEL, Rule.NOT_WHOLE)
        bad(Solar.pwm(9.5, -2.0), Field.PARALLEL, Rule.BELOW)
        bad(Solar.pwm(-9.5, 2.0), Field.ISC, Rule.NOT_POSITIVE)
        bad(Solar.pwm(Double.NaN, 2.0), Field.ISC, Rule.NOT_NUMBER)
        bad(Solar.mppt(1e12, 12.0), Field.ARRAY_W, Rule.ABOVE)
        assertTrue(ok(Solar.mppt(1_000_000.0, 1e-9)).isFinite())
    }

    // ------------------------------------------------------------ 5. strings

    @Test
    fun `strings - hand examples`() {
        // Voc 49.5, Vmp 41.5, −0.28 and −0.35 %/°C, 10 °C at the coldest, 70 °C cell, 500 V max, MPPT from 120 V:
        //   Voc cold = 49.5 × (1 + (−0.0028) × (10 − 25)) = 49.5 × 1.042 = 51.579 V → 500 ÷ 51.579 = 9.69 → 9 panels
        //   Vmp hot  = 41.5 × (1 + (−0.0035) × (70 − 25)) = 41.5 × 0.8425 = 34.964 V → 120 ÷ 34.964 = 3.43 → 4 panels
        val a = ok(Solar.strings(49.5, 41.5, -0.28, -0.35, 10.0, 70.0, 500.0, 120.0))
        assertEquals(51.579, a.vocCold, tol)
        assertEquals(34.96375, a.vmpHot, tol)
        assertEquals(9, a.maxSeries)
        assertEquals(4, a.minSeries)
        assertTrue(a.fits)

        // Voc 22, Vmp 18, −0.30 and −0.40, 0 °C, 75 °C, 150 V, 60 V:
        //   Voc cold = 22 × (1 + 0.003 × 25) = 22 × 1.075 = 23.65 V → 150 ÷ 23.65 = 6.34 → 6
        //   Vmp hot  = 18 × (1 − 0.004 × 50) = 18 × 0.8 = 14.4 V → 60 ÷ 14.4 = 4.17 → 5
        val b = ok(Solar.strings(22.0, 18.0, -0.30, -0.40, 0.0, 75.0, 150.0, 60.0))
        assertEquals(23.65, b.vocCold, tol)
        assertEquals(14.4, b.vmpHot, tol)
        assertEquals(6, b.maxSeries)
        assertEquals(5, b.minSeries)
        assertTrue(b.fits)
    }

    @Test
    fun `strings - when no count fits`() {
        // Max 50 V: 50 ÷ 51.579 = 0.97 → 0; not even one panel. MPPT 30 V: 30 ÷ 34.964 = 0.86 → 1.
        val a = ok(Solar.strings(49.5, 41.5, -0.28, -0.35, 10.0, 70.0, 50.0, 30.0))
        assertEquals(0, a.maxSeries)
        assertEquals(1, a.minSeries)
        assertFalse(a.fits)
        // Max 100 V → 1 (100 ÷ 51.579 = 1.94); MPPT 90 V → 3 (90 ÷ 34.964 = 2.57): 3 > 1, nothing fits.
        val b = ok(Solar.strings(49.5, 41.5, -0.28, -0.35, 10.0, 70.0, 100.0, 90.0))
        assertEquals(1, b.maxSeries)
        assertEquals(3, b.minSeries)
        assertFalse(b.fits)
    }

    @Test
    fun `strings - edges`() {
        bad(Solar.strings(41.5, 49.5, -0.28, -0.35, 10.0, 70.0, 500.0, 120.0), Field.VMP, Rule.VMP_NOT_BELOW_VOC)
        bad(Solar.strings(49.5, 41.5, 0.28, -0.35, 10.0, 70.0, 500.0, 120.0), Field.COEF_VOC, Rule.NOT_NEGATIVE)
        bad(Solar.strings(49.5, 41.5, 0.0, -0.35, 10.0, 70.0, 500.0, 120.0), Field.COEF_VOC, Rule.NOT_NEGATIVE)
        bad(Solar.strings(49.5, 41.5, -2.0, -0.35, 10.0, 70.0, 500.0, 120.0), Field.COEF_VOC, Rule.BELOW)
        bad(Solar.strings(49.5, 41.5, -0.28, 0.1, 10.0, 70.0, 500.0, 120.0), Field.COEF_VMP, Rule.NOT_NEGATIVE)
        bad(Solar.strings(49.5, 41.5, -0.28, -0.35, 10.0, 70.0, 120.0, 500.0), Field.MPPT_MIN, Rule.MPPT_NOT_BELOW_MAX)
        bad(Solar.strings(49.5, 41.5, -0.28, -0.35, 40.0, 30.0, 500.0, 120.0), Field.CELL_TEMP, Rule.CELL_NOT_ABOVE_MIN)
        bad(Solar.strings(49.5, 41.5, -0.28, -0.35, -60.0, 70.0, 500.0, 120.0), Field.MIN_TEMP, Rule.BELOW)
        bad(Solar.strings(49.5, 41.5, -0.28, -0.35, 10.0, 150.0, 500.0, 120.0), Field.CELL_TEMP, Rule.ABOVE)
        bad(Solar.strings(0.0, 41.5, -0.28, -0.35, 10.0, 70.0, 500.0, 120.0), Field.VOC, Rule.NOT_POSITIVE)
        bad(Solar.strings(49.5, null, -0.28, -0.35, 10.0, 70.0, 500.0, 120.0), Field.VMP, Rule.MISSING)
        bad(Solar.strings(49.5, 41.5, -0.28, -0.35, 10.0, 70.0, Double.NaN, 120.0), Field.MAX_V, Rule.NOT_NUMBER)
        // The harshest allowed: −1 %/°C, −50 °C and 100 °C still leave positive voltages.
        val worst = ok(Solar.strings(1000.0, 999.0, -1.0, -1.0, -50.0, 100.0, 2000.0, 1.0))
        assertTrue(worst.vocCold > 0 && worst.vmpHot > 0 && worst.vocCold.isFinite())
        assertEquals(1750.0, worst.vocCold, tol)    // 1000 × (1 + 0.01 × 75)
        assertEquals(249.75, worst.vmpHot, tol)      // 999 × (1 − 0.01 × 75)
    }

    // ------------------------------------------------------------ 6. wire

    @Test
    fun `wire - hand examples`() {
        // 20 A, 10 m one way, 24 V, 3 %:
        //   section = 2 × 10 × 20 × 0.0175 ÷ (24 × 0.03) = 7 ÷ 0.72 = 9.722 mm² → 10 mm²
        //   drop in 10 mm² = 7 ÷ 10 = 0.7 V = 0.7 ÷ 24 = 2.917 %
        val a = ok(Solar.wire(20.0, 10.0, 24.0, 3.0))
        assertEquals(9.7222222, a.neededMm2, tol)
        assertEquals(10.0, a.chosenMm2!!, 0.0)
        assertEquals(0.7, a.dropV, tol)
        assertEquals(2.9166667, a.dropPct, tol)

        // 10 A, 5 m, 48 V, 3 %: 1.75 ÷ 1.44 = 1.215 mm² → 1.5 mm²; 1.75 ÷ 1.5 = 1.1667 V = 2.431 %
        val b = ok(Solar.wire(10.0, 5.0, 48.0, 3.0))
        assertEquals(1.2152778, b.neededMm2, tol)
        assertEquals(1.5, b.chosenMm2!!, 0.0)
        assertEquals(1.1666667, b.dropV, tol)
        assertEquals(2.4305556, b.dropPct, tol)

        // 8 A, 15 m, 230 V, 3 %: 4.2 ÷ 6.9 = 0.609 mm² → the smallest, 1.5 mm²; 4.2 ÷ 1.5 = 2.8 V = 1.217 %
        val c = ok(Solar.wire(8.0, 15.0, 230.0, 3.0))
        assertEquals(1.5, c.chosenMm2!!, 0.0)
        assertEquals(1.2173913, c.dropPct, tol)
    }

    @Test
    fun `wire - an exact standard size is taken, not the next one`() {
        // 2 × 10 × 30 × 0.0175 = 10.5; ÷ (35 × 0.03 = 1.05) = 10 mm² exactly → 10, drop exactly 3 %
        val w = ok(Solar.wire(30.0, 10.0, 35.0, 3.0))
        assertEquals(10.0, w.chosenMm2!!, 0.0)
        assertEquals(3.0, w.dropPct, tol)
    }

    @Test
    fun `wire - when even 120 mm² is not enough`() {
        // 100 A, 100 m, 12 V: 350 ÷ 0.36 = 972 mm², none; in 120 mm²: 350 ÷ 120 = 2.917 V = 24.3 %
        val w = ok(Solar.wire(100.0, 100.0, 12.0, 3.0))
        assertNull(w.chosenMm2)
        assertEquals(972.2222222, w.neededMm2, tol)
        assertEquals(2.9166667, w.dropV, tol)
        assertEquals(24.3055556, w.dropPct, tol)
    }

    @Test
    fun `wire - edges`() {
        bad(Solar.wire(0.0, 10.0, 24.0, 3.0), Field.CURRENT, Rule.NOT_POSITIVE)
        bad(Solar.wire(20.0, -10.0, 24.0, 3.0), Field.LENGTH, Rule.NOT_POSITIVE)
        bad(Solar.wire(20.0, 10.0, 0.0, 3.0), Field.VOLTAGE, Rule.NOT_POSITIVE)
        bad(Solar.wire(20.0, 10.0, 24.0, 0.0), Field.DROP, Rule.NOT_POSITIVE)
        bad(Solar.wire(20.0, 10.0, 24.0, 50.0), Field.DROP, Rule.ABOVE)
        bad(Solar.wire(20.0, 1e9, 24.0, 3.0), Field.LENGTH, Rule.ABOVE)
        bad(Solar.wire(null, 10.0, 24.0, 3.0), Field.CURRENT, Rule.MISSING)
        val big = ok(Solar.wire(1000.0, 1000.0, 1e-9, 1e-9))
        assertTrue(big.neededMm2.isFinite() && big.dropPct.isFinite())
        assertEquals(listOf(1.5, 2.5, 4.0, 6.0, 10.0, 16.0, 25.0, 35.0, 50.0, 70.0, 95.0, 120.0), Solar.STANDARD_MM2)
        assertEquals(0.0175, Solar.RHO_COPPER, 0.0)
    }

    // ------------------------------------------------------------ 7. payback

    @Test
    fun `payback - hand examples`() {
        // 150,000 baht, 4.2 baht a unit, 10 kWh a day:
        //   a year: 10 × 365 × 4.2 = 15,330 baht; a month: 15,330 ÷ 12 = 1,277.50; 150,000 ÷ 15,330 = 9.785 years
        val a = ok(Solar.payback(150_000.0, 4.2, 10.0))
        assertEquals(15_330.0, a.perYear, tol)
        assertEquals(1_277.5, a.perMonth, tol)
        assertEquals(9.7847358, a.years, tol)

        // 60,000 baht, 5 baht, 4 kWh: 4 × 365 × 5 = 7,300 a year; 608.33 a month; 60,000 ÷ 7,300 = 8.219 years
        val b = ok(Solar.payback(60_000.0, 5.0, 4.0))
        assertEquals(7_300.0, b.perYear, tol)
        assertEquals(608.3333333, b.perMonth, tol)
        assertEquals(8.2191781, b.years, tol)
        assertEquals(4.2, Solar.DEFAULT_PRICE, 0.0)
    }

    @Test
    fun `payback - edges`() {
        bad(Solar.payback(0.0, 4.2, 10.0), Field.COST, Rule.NOT_POSITIVE)
        bad(Solar.payback(150_000.0, 0.0, 10.0), Field.PRICE, Rule.NOT_POSITIVE)     // no division by zero
        bad(Solar.payback(150_000.0, 4.2, 0.0), Field.PRODUCED_KWH, Rule.NOT_POSITIVE)
        bad(Solar.payback(150_000.0, 4.2, -1.0), Field.PRODUCED_KWH, Rule.NOT_POSITIVE)
        bad(Solar.payback(150_000.0, 500.0, 10.0), Field.PRICE, Rule.ABOVE)
        bad(Solar.payback(Double.NaN, 4.2, 10.0), Field.COST, Rule.NOT_NUMBER)
        bad(Solar.payback(null, 4.2, 10.0), Field.COST, Rule.MISSING)
        val slow = ok(Solar.payback(1e9, 1e-9, 1e-9))
        assertTrue(slow.years.isFinite())
    }

    // ------------------------------------------------------------ what is typed, and what is shown

    @Test
    fun `typing - numbers, thousands, and what is not a number`() {
        assertNull(Solar.parse(null))
        assertNull(Solar.parse(""))
        assertNull(Solar.parse("   "))
        assertEquals(4.5, Solar.parse("4.5")!!, 0.0)
        assertEquals(4.5, Solar.parse(" 4,5 ")!!, 0.0)                // a decimal comma
        assertEquals(150_000.0, Solar.parse("150,000")!!, 0.0)        // a thousands comma, not 150
        assertEquals(1_234_567.89, Solar.parse("1,234,567.89")!!, 1e-9)
        assertEquals(-0.28, Solar.parse("-0.28")!!, 0.0)
        assertEquals(-0.28, Solar.parse("−0.28")!!, 0.0)              // the minus sign
        assertEquals(0.5, Solar.parse(".5")!!, 0.0)
        assertEquals(1500.0, Solar.parse("1.5e3")!!, 0.0)
        for (junk in listOf("abc", "1.2.3", "NaN", "Infinity", "-Infinity", "1e999", "--1", "1,2,3", "๕", "12a", "0x10", ".", "-")) {
            assertTrue("\"$junk\" should not be a number", Solar.parse(junk)!!.isNaN())
        }
        // …and not a number is said as such, never worked out.
        bad(Solar.panels(Solar.parse("abc"), 4.5, 20.0, 550.0), Field.DAILY_KWH, Rule.NOT_NUMBER)
    }

    @Test
    fun `every field has a range and every check names its field`() {
        assertEquals(Field.entries.toSet(), Solar.RANGES.keys)
        for (f in Field.entries) {
            bad(Solar.check(f, null), f, Rule.MISSING)
            bad(Solar.check(f, Double.NaN), f, Rule.NOT_NUMBER)
            bad(Solar.check(f, Double.NEGATIVE_INFINITY), f, Rule.NOT_NUMBER)
            val r = Solar.RANGES.getValue(f)
            assertTrue(Solar.check(f, r.max * 10 + 1) is Result.Bad)
            assertTrue(Solar.check(f, r.max) is Result.Ok || r.below != null)
        }
    }

    @Test
    fun `shown numbers - grouped, trimmed, never NaN`() {
        assertEquals("2,777.78", Solar.format(2777.7777778))
        assertEquals("3,300", Solar.format(3300.0))
        assertEquals("0.7", Solar.format(0.7000000000000001))
        assertEquals("6", Solar.format(6.0))
        assertEquals("0", Solar.format(-0.001))
        assertEquals("-0.28", Solar.format(-0.28))
        assertEquals("1,277.5", Solar.format(1277.5))
        assertEquals("9.8", Solar.format(9.7847358, 1))
        assertEquals("1,000,000,000", Solar.format(1e9))
        assertEquals("-", Solar.format(Double.NaN))
        assertEquals("-", Solar.format(Double.POSITIVE_INFINITY))
    }
}
