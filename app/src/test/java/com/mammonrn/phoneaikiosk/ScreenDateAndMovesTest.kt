package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.MoveTracker
import com.mammonrn.phoneaikiosk.ui.ScreenDate
import com.mammonrn.phoneaikiosk.voice.DashboardState
import java.util.Calendar
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Dates on screen, price-window news, and today's weather cells (2026-09-23). */
class ScreenDateAndMovesTest {

    private fun on(y: Int, m: Int, d: Int) = Calendar.getInstance().apply { clear(); set(y, m, d) }

    @Test
    fun `the screen's date is Wed 23 slash 09 slash 2026, in English, whatever the locale`() {
        assertEquals("Wed 23/09/2026", ScreenDate.format(on(2026, Calendar.SEPTEMBER, 23)))
        assertEquals("Sun 03/01/2027", ScreenDate.format(on(2027, Calendar.JANUARY, 3)))
    }

    @Test
    fun `a Thai Buddhist-year date becomes the same form`() {
        assertEquals("Wed 23/09/2026", ScreenDate.fromThai("23 กันยายน 2569"))
        assertEquals("ไม่ใช่วันที่", ScreenDate.fromThai("ไม่ใช่วันที่"))
    }

    @Test
    fun `crypto is news at a 3 percent move from the last news, not below`() {
        val t = MoveTracker(MoveTracker.CRYPTO_PCT)
        assertTrue("the first prices open the window", t.update(mapOf("BTC" to 100_000.0)))
        assertFalse(t.update(mapOf("BTC" to 102_900.0)))      // +2.9%
        assertFalse(t.update(mapOf("BTC" to 101_000.0)))
        assertTrue(t.update(mapOf("BTC" to 103_000.0)))        // +3.0% from 100,000
        assertFalse("measured from 103,000 now", t.update(mapOf("BTC" to 105_000.0)))
        assertTrue(t.update(mapOf("BTC" to 99_900.0)))          // -3.0%
    }

    @Test
    fun `gold and fuel are news at 1 percent, any one of them`() {
        val t = MoveTracker(MoveTracker.COMMODITIES_PCT)
        t.update(mapOf("bar_sell" to 68_000.0, "oil:diesel" to 40.0))
        assertFalse(t.update(mapOf("bar_sell" to 68_600.0, "oil:diesel" to 40.3)))   // 0.88%, 0.75%
        assertTrue(t.update(mapOf("bar_sell" to 68_600.0, "oil:diesel" to 40.4)))    // diesel +1.0%
    }

    @Test
    fun `a new price is taken quietly, and a slow drift still counts once it adds up`() {
        val t = MoveTracker(3.0)
        t.update(mapOf("BTC" to 100.0))
        assertFalse(t.update(mapOf("BTC" to 100.0, "SOL" to 50.0)))
        var news = false
        for (step in 1..40) news = t.update(mapOf("BTC" to 100.0 + step * 0.1)) || news
        assertTrue("0.1 a minute for 40 minutes is 4%", news)
    }

    @Test
    fun `today's weather is four cells, and a missing number is simply not there`() {
        val full = JSONObject("""{"high_c":31.4,"low_c":23.4,"rain_chance":12,"wind_kmh":8,"uv":8.3}""")
        assertEquals(listOf("สูง/ต่ำ" to "31°/23°", "โอกาสฝน" to "12%", "ลม กม./ชม." to "8", "UV" to "8 สูงมาก"),
                     DashboardState.weatherStats(full))
        val partial = JSONObject("""{"high_c":31.4,"low_c":23.4,"uv":null}""")
        assertEquals(listOf("สูง/ต่ำ" to "31°/23°"), DashboardState.weatherStats(partial))
    }
}
