package com.mammonrn.phoneaikiosk.weather

import com.mammonrn.phoneaikiosk.voice.DashboardState
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

/** The country-wide warnings on the weather window's last line, and their turns. */
class WeatherAlertsTest {

    private val zone: ZoneId = ZoneId.of("Asia/Bangkok")

    /** 2026-09-26 14:30 Thai time. */
    private val updatedS = ZonedDateTime.of(2026, 9, 26, 14, 30, 0, 0, zone).toEpochSecond()
    private val nowMs = (updatedS + 10 * 60) * 1000

    private fun block(ok: Boolean = true, updated: String = "$updatedS", until: String = "\"2026-09-27T18:00:00+07:00\"") =
        JSONObject("""
            {"items": [
               {"kind": "warning", "title": "ฝนตกหนักมาก", "areas": "22 จังหวัด", "until": $until,
                "source": "กรมอุตุฯ", "line": "⚠ ฝนตกหนักมาก 22 จังหวัด ถึง 27 ก.ย. (กรมอุตุฯ)"},
               {"kind": "warning", "title": "คลื่นลมแรง", "areas": "อ่าวไทยตอนบน", "until": null,
                "source": "กรมอุตุฯ", "line": "คลื่นลมแรง อ่าวไทยตอนบน (กรมอุตุฯ)"}],
             "updated": $updated, "ok": $ok}
        """.trimIndent())

    private val outlook = "3 วันข้างหน้า มีฝน 2 วัน (ศ. ส.) โอกาสสูงสุด 57%"

    // ------------------------------------------------------------ parsing

    @Test
    fun `the block is read`() {
        val b = WeatherAlerts.parse(block(), zone)
        assertEquals(2, b.items.size)
        assertEquals(updatedS, b.updatedS)
        assertTrue(b.ok)
        assertEquals("กรมอุตุฯ", b.items[0].source)
        assertEquals(ZonedDateTime.of(2026, 9, 27, 18, 0, 0, 0, zone).toInstant().toEpochMilli(), b.items[0].untilMs)
        assertNull(b.items[1].untilMs)
    }

    @Test
    fun `an older broker without alerts has none, and the dashboard carries them`() {
        assertEquals(WeatherAlerts.NONE, WeatherAlerts.parse(null))
        assertEquals(WeatherAlerts.NONE, DashboardState.parse("""{"place":"เชียงราย"}""", "-").alerts)
        assertEquals(WeatherAlerts.NONE, WeatherAlerts.fromDashboard("not json"))
        val screen = DashboardState.parse("""{"place":"เชียงราย","alerts":${block()}}""", "-")
        assertEquals(2, screen.alerts.items.size)
    }

    @Test
    fun `a null updated, an empty list, and items with nothing to say`() {
        val b = WeatherAlerts.parse(JSONObject("""{"items": [{"kind": "warning", "line": "", "title": ""}],
                                                  "updated": null, "ok": true}"""))
        assertTrue(b.items.isEmpty())
        assertNull(b.updatedS)
        assertEquals(listOf(outlook), WeatherAlerts.lines(outlook, b, nowMs, zone))
    }

    @Test
    fun `until is read in its three shapes`() {
        val end = ZonedDateTime.of(2026, 9, 27, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(end, WeatherAlerts.until("2026-09-27T18:00:00+07:00", zone))
        assertEquals(end, WeatherAlerts.until("2026-09-27T11:00:00Z", zone))
        assertEquals(end, WeatherAlerts.until("2026-09-27T18:00:00", zone))
        // A date alone lasts to the end of that day.
        assertEquals(ZonedDateTime.of(2026, 9, 28, 0, 0, 0, 0, zone).toInstant().toEpochMilli(),
                     WeatherAlerts.until("2026-09-27", zone))
        assertNull(WeatherAlerts.until("", zone))
        assertNull(WeatherAlerts.until("พรุ่งนี้", zone))
    }

    // ------------------------------------------------------------ expiry

    @Test
    fun `a warning whose end has passed is dropped on the phone too`() {
        val b = WeatherAlerts.parse(block(), zone)
        assertEquals(2, WeatherAlerts.live(b, nowMs).size)
        val after = ZonedDateTime.of(2026, 9, 27, 18, 0, 0, 0, zone).toInstant().toEpochMilli()
        assertEquals(listOf("คลื่นลมแรง"), WeatherAlerts.live(b, after).map { it.title })
        // Once all have ended, the line is the forecast again, as before.
        val one = WeatherAlerts.parse(block(until = "\"2026-09-26T12:00:00+07:00\""), zone)
        assertEquals(1, WeatherAlerts.live(one, nowMs).size)
    }

    // ------------------------------------------------------------ the words

    @Test
    fun `a fresh warning says its source and the time it was read`() {
        val b = WeatherAlerts.parse(block(), zone)
        assertEquals("⚠ ฝนตกหนักมาก 22 จังหวัด ถึง 27 ก.ย. (กรมอุตุฯ 2:30 PM)",
                     WeatherAlerts.line(b.items[0], b, nowMs, zone))
    }

    @Test
    fun `every warning starts with the triangle, whatever the broker wrote`() {
        val b = WeatherAlerts.parse(block(), zone)
        assertEquals("⚠ คลื่นลมแรง อ่าวไทยตอนบน (กรมอุตุฯ 2:30 PM)", WeatherAlerts.line(b.items[1], b, nowMs, zone))
        val bare = b.items[1].copy(line = "")
        assertEquals("⚠ คลื่นลมแรง อ่าวไทยตอนบน (กรมอุตุฯ 2:30 PM)", WeatherAlerts.line(bare, b, nowMs, zone))
    }

    @Test
    fun `old or unread data says how old`() {
        val threeHours = WeatherAlerts.parse(block(ok = false), zone)
        val later = (updatedS + 3 * 3600) * 1000
        assertEquals("⚠ ฝนตกหนักมาก 22 จังหวัด ถึง 27 ก.ย. (กรมอุตุฯ · ข้อมูลเมื่อ 3 ชม.ที่แล้ว)",
                     WeatherAlerts.line(threeHours.items[0], threeHours, later, zone))
        // ok, but over six hours old.
        val fresh = WeatherAlerts.parse(block(), zone)
        val sevenHours = (updatedS + 7 * 3600) * 1000
        assertTrue(WeatherAlerts.line(fresh.items[0], fresh, sevenHours, zone).endsWith("(กรมอุตุฯ · ข้อมูลเมื่อ 7 ชม.ที่แล้ว)"))
        // Not read, and no time: said, without a number.
        val unknown = WeatherAlerts.parse(block(ok = false, updated = "null"), zone)
        assertTrue(WeatherAlerts.line(unknown.items[0], unknown, nowMs, zone).endsWith("(กรมอุตุฯ · ข้อมูลอาจไม่เป็นปัจจุบัน)"))
    }

    @Test
    fun `ages and clock`() {
        assertEquals("1 นาทีที่แล้ว", WeatherAlerts.ago(20))
        assertEquals("45 นาทีที่แล้ว", WeatherAlerts.ago(45 * 60))
        assertEquals("3 ชม.ที่แล้ว", WeatherAlerts.ago(3 * 3600 + 100))
        assertEquals("2 วันที่แล้ว", WeatherAlerts.ago(49 * 3600))
        assertEquals("12:05 AM", WeatherAlerts.clock(ZonedDateTime.of(2026, 9, 26, 0, 5, 0, 0, zone).toEpochSecond(), zone))
        assertEquals("12:00 PM", WeatherAlerts.clock(ZonedDateTime.of(2026, 9, 26, 12, 0, 0, 0, zone).toEpochSecond(), zone))
    }

    @Test
    fun `lines - forecast alone as before, or marked first with the warnings after`() {
        assertEquals(listOf(outlook), WeatherAlerts.lines(outlook, WeatherAlerts.NONE, nowMs, zone))
        assertTrue(WeatherAlerts.lines("", WeatherAlerts.NONE, nowMs, zone).isEmpty())
        val lines = WeatherAlerts.lines(outlook, WeatherAlerts.parse(block(), zone), nowMs, zone)
        assertEquals(3, lines.size)
        assertEquals("▸ $outlook", lines[0])
        assertTrue(lines.drop(1).all { it.startsWith("⚠") })
        // Warnings and no forecast: only the warnings.
        assertEquals(2, WeatherAlerts.lines("", WeatherAlerts.parse(block(), zone), nowMs, zone).size)
    }

    @Test
    fun `the sample warnings are debug only`() {
        fun file(path: String) = listOf(java.io.File(path), java.io.File("app/$path")).first { it.exists() }
        val main = file("src/main/java").walk().filter { it.isFile && it.extension == "kt" }
        assertTrue(main.none { "WeatherAlerts.override =" in it.readText() || "WeatherAlerts.override=" in it.readText() })
        assertTrue("WeatherAlerts.override =" in file("src/debug/java/com/mammonrn/phoneaikiosk/TestTriggerReceiver.kt").readText())
    }

    // ------------------------------------------------------------ the turns

    @Test
    fun `each line shows for the period, round and round`() {
        val r = LineRotation(6_000)
        assertEquals(0, r.current(3, 1_000))
        assertEquals(0, r.current(3, 6_999))
        assertEquals(1, r.current(3, 7_000))
        assertEquals(2, r.current(3, 13_000))
        assertEquals(0, r.current(3, 19_000))
    }

    @Test
    fun `a tap shows the next at once and gives it a full period`() {
        val r = LineRotation(6_000)
        r.current(3, 0)
        assertEquals(1, r.advance(3, 2_000))
        assertEquals(1, r.current(3, 7_999))
        assertEquals(2, r.current(3, 8_000))
        assertEquals(0, r.advance(3, 9_000))       // past the last, back to the forecast
    }

    @Test
    fun `a new set of lines starts from the forecast, one line never turns`() {
        val r = LineRotation(6_000)
        r.current(3, 0)
        assertEquals(2, r.current(3, 12_000))
        assertEquals(0, r.current(2, 12_500))       // a warning ended
        assertEquals(0, r.current(1, 50_000))
        assertEquals(0, r.advance(1, 50_000))
    }
}
