package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calc.Money
import com.mammonrn.phoneaikiosk.calc.Money.Metal
import com.mammonrn.phoneaikiosk.calc.Money.Purity
import com.mammonrn.phoneaikiosk.calc.Money.WeightUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The "ราคา" tab's formulas (0.61.0, Poom: "ห้ามให้ผลคำนวณผิด"). Every expected
 * value below was worked by hand and again in Python; the working is beside it.
 */
class MoneyTest {

    /** A day's rates as fawazahmed0 gives them: how many of each one US dollar buys. */
    private val json = """{"date":"2026-09-23","usd":{"thb":33.20,"jpy":147.50,"eur":0.9250,
        "xau":0.000233417,"xag":0.0156,"xpt":0.0005693607,"xpd":0.0007889329,"btc":0.0000119,"bad":0}}"""
    private val t = Money.parseFawaz(json, Money.Source.JSDELIVR, 1_000L)!!

    private fun near(expected: Double, actual: Double?) {
        assertNotNull(actual)
        assertEquals(expected, actual!!, expected * 1e-9)
    }

    // ------------------------------------------------------------ currencies

    @Test
    fun `usd to baht`() {
        // 100 ÷ 1 × 33.20 = 3,320
        near(3320.0, Money.convert(100.0, "USD", "THB", t))
    }

    @Test
    fun `between two currencies that are not the dollar`() {
        // 1,000 THB → JPY: 1,000 ÷ 33.20 × 147.50 = 4,442.771084…
        near(4442.771084337349, Money.convert(1000.0, "thb", "jpy", t))
        // 50 EUR → THB: 50 ÷ 0.9250 × 33.20 = 1,794.594594…
        near(1794.5945945945946, Money.convert(50.0, "EUR", "THB", t))
    }

    @Test
    fun `there and back is the same amount, and a currency to itself is itself`() {
        val jpy = Money.convert(1234.5, "THB", "JPY", t)!!
        near(1234.5, Money.convert(jpy, "JPY", "THB", t))
        near(77.0, Money.convert(77.0, "EUR", "EUR", t))
    }

    @Test
    fun `nothing is guessed - unknown codes, bad amounts and a zero rate give no answer`() {
        assertNull(Money.convert(1.0, "THB", "ABC", t))
        assertNull(Money.convert(-1.0, "THB", "USD", t))
        assertNull(Money.convert(Double.NaN, "THB", "USD", t))
        assertNull(Money.convert(Double.POSITIVE_INFINITY, "THB", "USD", t))
        assertNull(Money.convert(1.0, "BAD", "USD", t))            // a 0 rate was dropped when read
        assertEquals(0.0, Money.convert(0.0, "THB", "USD", t)!!, 0.0)
    }

    // ------------------------------------------------------------ metals

    @Test
    fun `an ounce of gold in dollars, pure and 96_5`() {
        // 1 ÷ 0.000233417 = 4,284.178… per ounce; × 0.9999 = 4,283.749684…
        near(4283.749684041865, Money.metalPrice(Metal.GOLD, 1.0, WeightUnit.OUNCE, "USD", t, Purity.PURE))
        // × 0.965 = 4,134.231868…
        near(4134.231868287228, Money.metalPrice(Metal.GOLD, 1.0, WeightUnit.OUNCE, "USD", t, Purity.THAI))
    }

    @Test
    fun `one baht of gold in baht`() {
        // per gram 4,284.178 ÷ 31.1034768 = 137.739…; × 15.244 g × 0.965 × 33.20 = 67,270.230572…
        near(67270.23057196169, Money.metalPrice(Metal.GOLD, 1.0, WeightUnit.BAHT, "THB", t, Purity.THAI))
        // the same at 99.99%: … × 0.9999 × 33.20 = 69,703.112486…
        near(69703.1124859114, Money.metalPrice(Metal.GOLD, 1.0, WeightUnit.BAHT, "THB", t, Purity.PURE))
    }

    @Test
    fun `silver by the kilogram and by the gram, purity only for gold`() {
        // 1 ÷ 0.0156 ÷ 31.1034768 × 1,000 × 33.20 = 68,423.383723…
        near(68423.38372297751, Money.metalPrice(Metal.SILVER, 1.0, WeightUnit.KILOGRAM, "THB", t, Purity.THAI))
        // 10 g in yen: 1 ÷ 0.0156 ÷ 31.1034768 × 10 × 147.50 = 3,039.894307…
        near(3039.894306969633, Money.metalPrice(Metal.SILVER, 10.0, WeightUnit.GRAM, "JPY", t))
    }

    @Test
    fun `a metal with no free price gets no number, and neither does a bad quantity`() {
        for (m in Metal.entries.filter { it.code == null }) {
            assertNull(m.name, Money.metalPrice(m, 1.0, WeightUnit.KILOGRAM, "THB", t))
        }
        assertNull(Money.metalPrice(Metal.GOLD, -1.0, WeightUnit.OUNCE, "THB", t))
        assertNull(Money.metalPrice(Metal.GOLD, 1.0, WeightUnit.OUNCE, "ABC", t))
        assertEquals(Metal.GOLD, Metal.entries.first())
        assertEquals(10, Metal.entries.size)
    }

    @Test
    fun `the units - a troy ounce and a baht of gold`() {
        assertEquals(31.1034768, WeightUnit.OUNCE.grams, 0.0)
        assertEquals(15.244, WeightUnit.BAHT.grams, 0.0)
        assertEquals(1000.0, WeightUnit.KILOGRAM.grams, 0.0)
    }

    // ------------------------------------------------------------ the sources

    @Test
    fun `frankfurter's answer reads the same way, with no metals`() {
        val f = Money.parseFrankfurter("""{"amount":1.0,"base":"USD","date":"2026-09-24","rates":{"THB":33.40,"JPY":148.0}}""", 5L)!!
        assertEquals(Money.Source.FRANKFURTER, f.source)
        assertEquals("2026-09-24", f.date)
        // 1,000 THB → JPY: 1,000 ÷ 33.40 × 148.0 = 4,431.137724…
        near(4431.137724550898, Money.convert(1000.0, "THB", "JPY", f))
        assertNull(Money.metalPrice(Metal.GOLD, 1.0, WeightUnit.OUNCE, "THB", f))
        assertNull(Money.parseFrankfurter("""{"base":"EUR","date":"x","rates":{"THB":38.0}}""", 5L))
    }

    @Test
    fun `a broken answer is no table at all`() {
        assertNull(Money.parseFawaz("<html>404</html>", Money.Source.PAGES, 1L))
        assertNull(Money.parseFawaz("""{"date":"2026-09-23","usd":{}}""", Money.Source.PAGES, 1L))
        assertEquals("2026-09-23", t.date)
        assertTrue(t.has("THB"))
        assertFalse(t.has("bad"))
    }

    @Test
    fun `refreshed once a day, and the age in hours`() {
        val day = Money.REFRESH_MS
        assertFalse(Money.needsRefresh(0, day - 1))
        assertTrue(Money.needsRefresh(0, day))
        assertTrue(Money.needsRefresh(5_000, 1_000))                // a clock set back: fetch again
        assertEquals(49, Money.ageHours(0, 49L * 3_600_000 + 5))
        assertEquals(0, Money.ageHours(10, 5))
    }

    // ------------------------------------------------------------ the picker

    @Test
    fun `the catalogue has flags and Thai names, no metals, and pinned ones come first`() {
        val all = Money.catalogue(t)
        val codes = all.map { it.code }
        assertTrue(codes.containsAll(listOf("THB", "USD", "JPY", "EUR")))
        assertFalse(codes.any { it.startsWith("X") || it == "BTC" })
        val thb = all.first { it.code == "THB" }
        assertEquals("🇹🇭", thb.flag)                   // 🇹🇭
        assertEquals("🇺🇸", all.first { it.code == "USD" }.flag)   // 🇺🇸, not Ecuador's
        assertTrue(thb.matches("บาท") && thb.matches("thai") && thb.matches("thb") && thb.matches("ไทย"))
        assertFalse(thb.matches("เยน"))
        assertEquals(listOf("JPY", "THB"), Money.ordered(all, listOf("JPY", "THB", "ZZZ")).take(2).map { it.code })
    }

    @Test
    fun `numbers are shown with separators and never as NaN`() {
        assertEquals("67,270.23", Money.format(67270.23057196169))
        assertEquals("0.030119", Money.format(0.030119085))
        assertEquals("—", Money.format(Double.NaN))
    }
}
