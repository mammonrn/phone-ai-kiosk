package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calc.CalcEngine
import com.mammonrn.phoneaikiosk.calc.CalcEngine.Reason
import com.mammonrn.phoneaikiosk.calc.CalcEngine.Result
import com.mammonrn.phoneaikiosk.calc.Electrical
import com.mammonrn.phoneaikiosk.calc.Electrical.Band
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The calculator (0.52.0): answers that can be checked by hand. */
class CalculatorTest {

    private fun shown(input: String, degrees: Boolean = true, ans: Double = 0.0): String =
        when (val r = CalcEngine.evaluate(input, degrees, ans)) {
            is Result.Value -> CalcEngine.format(r.value)
            is Result.Error -> "error:${r.reason}"
        }

    @Test
    fun `arithmetic with the usual precedence`() {
        assertEquals("2", shown("1+1"))
        assertEquals("60", shown("5×12"))
        assertEquals("14", shown("2+3×4"))
        assertEquals("20", shown("(2+3)×4"))
        assertEquals("2.5", shown("5÷2"))
        assertEquals("0.3333333333", shown("1÷3"))
        assertEquals("0.3", shown("0.1+0.2"))                    // not 0.30000000000000004
        assertEquals("-4", shown("-2^2"))
        assertEquals("512", shown("2^3^2"))
        assertEquals("0.5", shown("2^-1"))
        assertEquals("7", shown("10-3"))
        assertEquals("-7", shown("3-10"))
        assertEquals("6", shown("2*3"))
        assertEquals("3", shown("9/3"))
    }

    @Test
    fun `roots powers factorials and implicit multiplication`() {
        assertEquals("16", shown("√256"))
        assertEquals("16", shown("√(256)"))
        assertEquals("1.414213562", shown("√2"))
        assertEquals("25", shown("5²"))
        assertEquals("120", shown("5!"))
        assertEquals("1", shown("0!"))
        assertEquals("6.283185307", shown("2π"))
        assertEquals("12", shown("3(4)"))
        assertEquals("6", shown("2√9"))
        assertEquals("2.718281828", shown("e"))
        assertEquals("20", shown("(2+3)(4"))                    // open parenthesis closed at the end
        assertEquals("42", shown("Ans", ans = 42.0))
        assertEquals("84", shown("2Ans", ans = 42.0))
    }

    @Test
    fun `trigonometry in degrees is exact where it should be`() {
        assertEquals("0", shown("sin(180)"))
        assertEquals("0.5", shown("sin(30)"))
        assertEquals("0.5", shown("cos(60)"))
        assertEquals("1", shown("tan(45)"))
        assertEquals("-1", shown("cos(180)"))
        assertEquals("30", shown("asin(0.5)"))
        assertEquals("45", shown("atan(1)"))
        assertEquals("60", shown("acos(0.5)"))
        assertEquals("0.8660254038", shown("sin(60)"))
        assertEquals("error:DOMAIN", shown("tan(90)"))
        assertEquals("1", shown("sin(π÷2)", degrees = false))
        assertEquals("0.5235987756", shown("asin(0.5)", degrees = false))
    }

    @Test
    fun `logarithms`() {
        assertEquals("2", shown("log(100)"))
        assertEquals("1", shown("ln(e)"))
        assertEquals("-3", shown("log(0.001)"))
        assertEquals("error:DOMAIN", shown("log(0)"))
        assertEquals("error:DOMAIN", shown("ln(-1)"))
    }

    @Test
    fun `powers of ten in and out`() {
        assertEquals("1.5E-7", shown("1.5E-7"))
        assertEquals("3000", shown("3E3"))
        assertEquals("6.02214076E23", shown("6.02214076E23"))
        assertEquals("1E10", shown("10^10"))
        assertEquals("9999999999", shown("9999999999"))
        assertEquals("0.000001", shown("1E-6"))
        assertEquals("1E-7", shown("1E-7"))
        assertEquals("1.23456789E-12", shown("1.23456789E-12"))
        assertEquals("error:INCOMPLETE", shown("2E"))
        // The widest result still fits 16 characters.
        assertEquals("-1.23456789E-100", CalcEngine.format(-1.234567891e-100))
        assertTrue(CalcEngine.format(-1.234567891e-100).length <= CalcEngine.MAX_CHARS)
    }

    @Test
    fun `errors are reasons not NaN or Infinity`() {
        assertEquals("error:DIVIDE_BY_ZERO", shown("1÷0"))
        assertEquals("error:DIVIDE_BY_ZERO", shown("0^-1"))
        assertEquals("error:DOMAIN", shown("√-4"))
        assertEquals("error:DOMAIN", shown("asin(2)"))
        assertEquals("error:DOMAIN", shown("2.5!"))
        assertEquals("error:DOMAIN", shown("(-8)^0.5"))
        assertEquals("error:TOO_BIG", shown("171!"))
        assertEquals("error:TOO_BIG", shown("10^400"))
        assertEquals("error:INCOMPLETE", shown(""))
        assertEquals("error:INCOMPLETE", shown("5+"))
        assertEquals("error:INCOMPLETE", shown("1..2"))
        assertEquals("error:INCOMPLETE", shown("×3"))
        assertEquals("0", shown("-0"))                          // never "-0"
    }

    // ------------------------------------------------------------- electrical

    @Test
    fun `ohm's law from any two`() {
        val a = Electrical.ohm(12.0, null, 4.0, null)!!
        assertEquals(3.0, a.amps, 1e-12); assertEquals(36.0, a.watts, 1e-12)
        val b = Electrical.ohm(null, 2.0, null, 100.0)!!
        assertEquals(50.0, b.volts, 1e-12); assertEquals(25.0, b.ohms, 1e-12)
        val c = Electrical.ohm(null, null, 100.0, 1.0)!!
        assertEquals(10.0, c.volts, 1e-12); assertEquals(0.1, c.amps, 1e-12)
        val d = Electrical.ohm(230.0, null, null, 1000.0)!!
        assertEquals(52.9, d.ohms, 1e-9); assertEquals(4.347826087, d.amps, 1e-9)
        assertNull(Electrical.ohm(12.0, 1.0, 12.0, null))      // three given
        assertNull(Electrical.ohm(12.0, null, null, null))     // one given
        assertNull(Electrical.ohm(12.0, null, 0.0, null))      // R = 0
    }

    @Test
    fun `inputs are checked`() {
        assertTrue(Electrical.checkOhm('V', -5.0)!!.blocking)
        assertTrue(Electrical.checkOhm('R', 0.0)!!.blocking)
        assertFalse(Electrical.checkOhm('V', 500_000.0)!!.blocking)     // a warning
        assertNull(Electrical.checkOhm('V', 230.0))
    }

    @Test
    fun `units are shown with a prefix`() {
        assertEquals("4.7 kΩ", Electrical.withUnit(4700.0, "Ω"))
        assertEquals("230 V", Electrical.withUnit(230.0, "V"))
        assertEquals("12 mA", Electrical.withUnit(0.012, "A"))
        assertEquals("100 nF", Electrical.withUnit(1e-7, "F"))
        assertEquals("1 MΩ", Electrical.withUnit(1e6, "Ω"))
        assertEquals("0 W", Electrical.withUnit(0.0, "W"))
    }

    @Test
    fun `series and parallel`() {
        val (s, p) = Electrical.combine(Electrical.Part.RESISTOR, listOf(100.0, 100.0))!!
        assertEquals(200.0, s, 1e-12); assertEquals(50.0, p, 1e-12)
        val (sc, pc) = Electrical.combine(Electrical.Part.CAPACITOR, listOf(10e-6, 10e-6))!!
        assertEquals(5e-6, sc, 1e-18); assertEquals(20e-6, pc, 1e-18)
        val (_, p3) = Electrical.combine(Electrical.Part.RESISTOR, listOf(10.0, 20.0, 30.0))!!
        assertEquals(60.0 / 11.0, p3, 1e-12)
        assertNull(Electrical.combine(Electrical.Part.RESISTOR, listOf(10.0, 0.0)))
    }

    @Test
    fun `colour code read`() {
        // yellow violet red gold = 4.7 kΩ ±5 %
        assertEquals(Electrical.Reading(4700.0, 5.0), Electrical.read(listOf(Band.YELLOW, Band.VIOLET, Band.RED, Band.GOLD)))
        // brown black black brown brown = 1 kΩ ±1 %
        assertEquals(Electrical.Reading(1000.0, 1.0),
                     Electrical.read(listOf(Band.BROWN, Band.BLACK, Band.BLACK, Band.BROWN, Band.BROWN)))
        // red red gold gold = 2.2 Ω
        assertEquals(2.2, Electrical.read(listOf(Band.RED, Band.RED, Band.GOLD, Band.GOLD))!!.ohms, 1e-12)
        assertEquals(0.01, Band.GREY.tolerance!!, 0.0)          // IEC 60062:2016
        assertNull(Electrical.read(listOf(Band.GOLD, Band.RED, Band.RED, Band.GOLD)))   // gold is no digit
        assertNull(Electrical.read(listOf(Band.RED, Band.RED, Band.RED, Band.WHITE)))   // white is no tolerance
    }

    @Test
    fun `colour code from a value`() {
        val c = Electrical.encode(4700.0, 2, 5.0)!!
        assertEquals(listOf(Band.YELLOW, Band.VIOLET, Band.RED, Band.GOLD), c.bands); assertTrue(c.exact)
        val d = Electrical.encode(1000.0, 3, 1.0)!!
        assertEquals(listOf(Band.BROWN, Band.BLACK, Band.BLACK, Band.BROWN, Band.BROWN), d.bands)
        val e = Electrical.encode(4.7, 2, 5.0)!!
        assertEquals(listOf(Band.YELLOW, Band.VIOLET, Band.GOLD, Band.GOLD), e.bands)
        val rounded = Electrical.encode(4732.0, 2, 5.0)!!
        assertFalse(rounded.exact); assertEquals(4700.0, rounded.shown, 1e-9)
        val up = Electrical.encode(996.0, 2, 5.0)!!                   // 99.6 → 100 ×10
        assertEquals(1000.0, up.shown, 1e-9)
        assertNull(Electrical.encode(1e12, 2, 5.0))                   // past white
        assertNull(Electrical.encode(100.0, 2, 3.0))                  // no such tolerance colour
        // Read back what was encoded.
        for (ohms in listOf(0.1, 1.0, 10.0, 47.0, 220.0, 3300.0, 68000.0, 1e6, 1e9)) {
            val code = Electrical.encode(ohms, 2, 5.0)!!
            assertEquals(ohms, Electrical.read(code.bands)!!.ohms, ohms * 1e-9)
        }
    }

    @Test
    fun `ac power and reactance`() {
        val one = Electrical.acPower(230.0, 10.0, 0.8, threePhase = false)!!
        assertEquals(1840.0, one.watts, 1e-9); assertEquals(2300.0, one.va, 1e-9); assertEquals(1380.0, one.vars, 1e-9)
        val three = Electrical.acPower(400.0, 10.0, 1.0, threePhase = true)!!
        assertEquals(6928.203230, three.watts, 1e-6)
        assertNull(Electrical.acPower(230.0, 10.0, 1.2, false))
        assertEquals(31.41592654, Electrical.inductiveReactance(50.0, 0.1)!!, 1e-8)
        assertEquals(31.83098862, Electrical.capacitiveReactance(50.0, 100e-6)!!, 1e-8)
        assertNull(Electrical.capacitiveReactance(0.0, 1e-6))
    }

    @Test
    fun `voltage drop uses IEC 60228 copper at 70 degrees`() {
        // 2.5 mm², 20 m, 16 A single phase: 2·20·16·7.41·1.1965/1000 = 5.674 V
        val drops = Electrical.drops(230.0, 16.0, 20.0, threePhase = false)!!
        val d25 = drops.first { it.mm2 == 2.5 }
        assertEquals(5.6743, d25.volts, 1e-3)
        assertEquals(2.4671, d25.percent, 1e-3)
        assertEquals(2.5, Electrical.smallestFor(drops, 3.0)!!.mm2, 0.0)
        assertEquals(4.0, Electrical.smallestFor(drops, 2.0)!!.mm2, 0.0)
        assertNull(Electrical.smallestFor(Electrical.drops(230.0, 400.0, 500.0, false)!!, 3.0))
        assertEquals(12.1, Electrical.COPPER_OHM_PER_KM.first().second, 0.0)
        assertNotNull(Electrical.drops(400.0, 10.0, 50.0, threePhase = true))
    }
}
