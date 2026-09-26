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
        val partial = JSONObject("""{"high_c":31.4,"low_c":23.4}""")  // keys an older broker never sent
        assertEquals(listOf("สูง/ต่ำ" to "31°/23°"), DashboardState.weatherStats(partial))
    }

    @Test
    fun `UV under 1 shows one decimal so it never reads as zero`() {
        // THE BUG this exists for (2026-09-26): the card said "UV 0" at 07:52
        // with the sun already up. Math.round(0.4) is 0, indistinguishable
        // from "no data" and from a real zero — one decimal below 1 fixes it.
        val low = JSONObject("""{"uv":0.4}""")
        assertEquals(listOf("UV" to "0.4 ต่ำ"), DashboardState.weatherStats(low))
        val zero = JSONObject("""{"uv":0.0}""")
        assertEquals(listOf("UV" to "0.0 ต่ำ"), DashboardState.weatherStats(zero))
    }

    @Test
    fun `UV at 1 and above is still the whole number it always was`() {
        val one = JSONObject("""{"uv":1.3}""")
        assertEquals(listOf("UV" to "1 ต่ำ"), DashboardState.weatherStats(one))
        val high = JSONObject("""{"uv":8.3}""")
        assertEquals(listOf("UV" to "8 สูงมาก"), DashboardState.weatherStats(high))
    }

    @Test
    fun `PM2_5 is the value and its level word, after the weather's own numbers`() {
        val weather = JSONObject("""{"high_c":31.4,"low_c":23.4,"rain_chance":12,"wind_kmh":8,"uv":8.3}""")
        val air = JSONObject("""{"ok":true,"age_seconds":60,"pm25":41.3,"pm25_word":"เริ่มมีผลต่อสุขภาพ"}""")
        val stats = DashboardState.weatherStats(weather, air)
        assertEquals(5, stats.size)
        assertEquals("PM2.5 มคก./ลบ.ม." to "41.3 เริ่มมีผลต่อสุขภาพ", stats.last())
        val whole = JSONObject("""{"ok":true,"age_seconds":0,"pm25":9.0,"pm25_word":"ดีมาก"}""")
        assertEquals("9 ดีมาก", DashboardState.pm25(whole)?.second)
    }

    @Test
    fun `PM2_5 that no source vouches for is a dash, no air panel at all is no cell`() {
        val weather = JSONObject("""{"high_c":31.4,"low_c":23.4}""")
        val failed = JSONObject("""{"ok":false,"age_seconds":0,"error":"upstream"}""")
        val old = JSONObject("""{"ok":false,"age_seconds":20000,"stale":{"pm25":12.0,"pm25_word":"ดีมาก"}}""")
        val staleButRecent = JSONObject("""{"ok":false,"age_seconds":1800,"stale":{"pm25":12.0,"pm25_word":"ดีมาก"}}""")
        val expected = listOf("สูง/ต่ำ" to "31°/23°")
        // Poom 0.67: "ถ้าไม่มีแหล่งไหนผ่าน แสดง '—' ห้ามแสดงค่าผิด".
        val dash = expected + ("PM2.5 มคก./ลบ.ม." to DashboardState.DASH)
        assertEquals(expected, DashboardState.weatherStats(weather, null))
        assertEquals(dash, DashboardState.weatherStats(weather, failed))
        assertEquals(dash, DashboardState.weatherStats(weather, old))
        assertEquals(expected + ("PM2.5 มคก./ลบ.ม." to "12 ดีมาก"),
                     DashboardState.weatherStats(weather, staleButRecent))
        assertEquals(dash, DashboardState.weatherStats(weather,
            JSONObject("""{"ok":true,"age_seconds":0,"pm25":null}""")))
    }

    @Test
    fun `a weather value that failed every check is a dash in its place`() {
        val failed = JSONObject("""{"high_c":null,"low_c":23.4,"rain_chance":null,"wind_kmh":7,"uv":null,"is_day":1}""")
        assertEquals(listOf("สูง/ต่ำ" to DashboardState.DASH, "โอกาสฝน" to DashboardState.DASH,
                            "ลม กม./ชม." to "7", "UV" to DashboardState.DASH),
                     DashboardState.weatherStats(failed))
        // At night the broker sends no UV at all: nothing to dash.
        assertEquals(listOf("ลม กม./ชม." to "7"),
                     DashboardState.weatherStats(JSONObject("""{"wind_kmh":7,"is_day":0}""")))
    }

    @Test
    fun `the dust shows even while the weather is missing`() {
        val air = JSONObject("""{"ok":true,"age_seconds":0,"pm25":8.8,"pm25_word":"ดีมาก"}""")
        assertEquals(listOf("PM2.5 มคก./ลบ.ม." to "8.8 ดีมาก"), DashboardState.weatherStats(null, air))
    }
}
