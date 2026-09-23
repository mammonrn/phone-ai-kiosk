package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.alarm.AlarmBook
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.DashboardState
import com.mammonrn.phoneaikiosk.voice.KioskAction
import java.util.Calendar
import java.util.TimeZone
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The kiosk's alarms (2026-09-23): kept on the phone, rung by the phone. */
class AlarmBookTest {

    private val bangkok = TimeZone.getTimeZone("Asia/Bangkok")

    private fun at(hour: Int, minute: Int, day: Int = 23): Long = Calendar.getInstance(bangkok).apply {
        clear(); set(2026, Calendar.SEPTEMBER, day, hour, minute)
    }.timeInMillis

    @Test
    fun `setting an alarm keeps its time and name and switches it on`() {
        val book = AlarmBook()
        val alarm = book.set(6, 30, "ไปทำงาน")!!
        assertEquals("06:30", alarm.time)
        assertEquals("ไปทำงาน", alarm.label)
        assertTrue(alarm.enabled)
    }

    @Test
    fun `the same time again renames it rather than adding a second`() {
        val book = AlarmBook()
        book.set(6, 30, "ไปทำงาน")
        book.enable("all", false)
        book.set(6, 30, "ตื่นนอน")
        assertEquals(1, book.alarms.size)
        assertEquals("ตื่นนอน", book.alarms[0].label)
        assertTrue("setting it again switches it back on", book.alarms[0].enabled)
    }

    @Test
    fun `alarms switch off by time, by name, or all`() {
        val book = AlarmBook()
        book.set(5, 0, ""); book.set(6, 30, "ไปทำงานเช้า"); book.set(21, 0, "ยา")
        assertEquals(1, book.enable("05:00", false))
        assertEquals(1, book.enable("ไปทำงาน", false))       // part of the name
        assertEquals(0, book.enable("ไปทำงาน", false))       // already off: nothing changed
        assertEquals(2, book.enable("all", true))           // the two that were off
        assertFalse(book.matches("ไม่มีชื่อนี้"))
    }

    @Test
    fun `it rings every day at its time, and the soonest one is next`() {
        val book = AlarmBook()
        book.set(5, 0, ""); book.set(21, 0, "")
        val (alarm, ringsAt) = book.next(at(6, 0), bangkok)!!
        assertEquals("21:00", alarm.time)
        assertEquals(at(21, 0), ringsAt)
        // After 21:00 the next is 05:00 tomorrow.
        assertEquals(at(5, 0, day = 24), book.next(at(21, 0), bangkok)!!.second)
    }

    @Test
    fun `an alarm that is off never rings`() {
        val book = AlarmBook()
        book.set(5, 0, "")
        book.enable("all", false)
        assertNull(book.next(at(1, 0), bangkok))
    }

    @Test
    fun `it keeps at most ten`() {
        val book = AlarmBook()
        for (minute in 0 until AlarmBook.MAX_ALARMS) assertNotNull(book.set(6, minute, ""))
        assertNull(book.set(7, 0, ""))
    }

    @Test
    fun `it survives being saved and read back, and a broken store is empty not a crash`() {
        val book = AlarmBook()
        book.set(6, 30, "ไปทำงาน"); book.set(5, 0, "")
        book.enable("05:00", false)
        val back = AlarmBook.fromJson(book.toJson())
        assertEquals(book.alarms, back.alarms)
        assertTrue(AlarmBook.fromJson("{broken").alarms.isEmpty())
        assertTrue(AlarmBook.fromJson("""[{"h":99,"m":0}]""").alarms.isEmpty())
    }

    @Test
    fun `the broker's alarm actions are read strictly`() {
        val set = Broker.parseAction(JSONObject("""{"type":"set_alarm","time":"06:30","label":"ไปทำงาน"}"""))!!
        assertEquals(KioskAction.SET_ALARM, set.type)
        assertEquals("06:30", set.params["time"])
        assertNull(Broker.parseAction(JSONObject("""{"type":"set_alarm","time":"6.30"}""")))
        assertNull(Broker.parseAction(JSONObject("""{"type":"set_alarm","time":"25:00"}""")))
        val off = Broker.parseAction(JSONObject("""{"type":"alarm_enable","target":"all","enabled":false}"""))!!
        assertEquals("false", off.params["enabled"])
        assertNull(Broker.parseAction(JSONObject("""{"type":"alarm_enable","target":"all"}""")))
    }

    @Test
    fun `card news is whole degrees, prices, and BTC and ETH in whole percent`() {
        val json = """{"weather":{"ok":true,"temp_c":30.2,"word":"แดดจัด"},
            "gold":{"ok":true,"ornament_sell":69050,"bar_sell":68250},
            "crypto":{"ok":true,"coins":[{"symbol":"BTC","usd":86000,"change_pct":1.81},
                                         {"symbol":"ETH","usd":2700,"change_pct":1.4},
                                         {"symbol":"XRP","usd":1.6,"change_pct":7.5}]}}"""
        val facts = DashboardState.cardFacts(json)
        assertEquals("30|แดดจัด" to "30°C แดดจัด", facts["weather"])
        assertEquals("ทองแท่ง 68,250 บ.", facts["gold"]!!.second)
        assertEquals("BTC:2,ETH:1", facts["crypto"]!!.first)
        // 30.4 is still 30: not news.
        val warmer = DashboardState.cardFacts(json.replace("30.2", "30.4"))
        assertEquals(facts["weather"]!!.first, warmer["weather"]!!.first)
    }

    // ------------------------------------------- the Control Panel (2026-09-23)

    @Test
    fun `chosen days ring only on those days`() {
        val book = AlarmBook()
        val a = book.add(6, 30, "ทำงาน", AlarmBook.WEEKDAYS, once = false)!!
        // 23 Sep 2026 is a Wednesday; Saturday 26th 07:00 -> next is Monday 28th.
        assertEquals(at(6, 30, day = 28), book.nextRing(a, at(7, 0, day = 26), bangkok))
        assertEquals(at(6, 30, day = 24), book.nextRing(a, at(7, 0, day = 23), bangkok))
        assertEquals("จันทร์–ศุกร์", AlarmBook.repeatText(a))
    }

    @Test
    fun `once rings at the next time whatever the day, and says so`() {
        val book = AlarmBook()
        val a = book.add(5, 0, "", 0, once = true)!!
        assertEquals(at(5, 0, day = 24), book.nextRing(a, at(6, 0), bangkok))
        assertEquals("ครั้งเดียว", AlarmBook.repeatText(a))
    }

    @Test
    fun `a repeating alarm with no day never rings`() {
        val book = AlarmBook()
        val a = book.add(5, 0, "", 0, once = false)!!
        assertNull(book.nextRing(a, at(6, 0), bangkok))
        assertNull(book.next(at(6, 0), bangkok))
    }

    @Test
    fun `edit, delete and one time per alarm`() {
        val book = AlarmBook()
        val a = book.add(6, 0, "ก", AlarmBook.EVERY_DAY, false)!!
        val b = book.add(7, 0, "ข", AlarmBook.EVERY_DAY, false)!!
        assertNull("a second alarm at 06:00", book.add(6, 0, "ค", AlarmBook.EVERY_DAY, false))
        assertFalse("moving b onto a's time", book.update(b.id, 6, 0, "ข", AlarmBook.EVERY_DAY, false))
        assertTrue(book.update(b.id, 8, 15, "ข ใหม่", AlarmBook.WEEKEND, false))
        assertEquals("08:15", book.byId(b.id)!!.time)
        assertEquals("เสาร์–อาทิตย์", AlarmBook.repeatText(book.byId(b.id)!!))
        assertTrue(book.remove(a.id))
        assertNull(book.byId(a.id))
    }

    @Test
    fun `days and once survive a save, and old saves read as every day`() {
        val book = AlarmBook()
        book.add(6, 0, "", AlarmBook.WEEKEND, false); book.add(7, 0, "", 0, true)
        assertEquals(book.alarms, AlarmBook.fromJson(book.toJson()).alarms)
        val old = AlarmBook.fromJson("""[{"id":1,"h":6,"m":0,"label":"","on":true}]""").alarms[0]
        assertEquals(AlarmBook.EVERY_DAY, old.days)
        assertFalse(old.once)
    }

    @Test
    fun `a spoken alarm is every day`() {
        val book = AlarmBook()
        assertEquals("ทุกวัน", AlarmBook.repeatText(book.set(6, 0, "")!!))
    }

    @Test
    fun `fuel lines share a price between brands and give a dearer one its own`() {
        val cheapest = org.json.JSONArray("""[{"brand":"PT","price":39.9},{"brand":"ปตท.","price":39.94},
            {"brand":"บางจาก","price":39.94}]""")
        assertEquals("โซฮอล์ 95 39.90 PT · 39.94 ปตท. บางจาก", DashboardState.fuelLine("โซฮอล์ 95", cheapest))
        assertEquals("", DashboardState.fuelLine("ดีเซล", org.json.JSONArray()))
    }

    @Test
    fun `the commodities window shows fuel only when the broker sends it`() {
        val gold = """"gold":{"ok":true,"ornament_sell":68900,"bar_sell":68100}"""
        val oil = """"oil":{"ok":true,"area":"กรุงเทพฯ","date":"23 กันยายน 2569","fuels":[
            {"id":"diesel","label":"ดีเซล","cheapest":[{"brand":"ปตท.","price":40.69}]}]}"""
        assertNull(DashboardState.parse("{$gold}", "-").oil)
        val screen = DashboardState.parse("{$gold,$oil}", "-")
        assertEquals("ดีเซล 40.69 ปตท.\n(ราคากรุงเทพฯ · 23 กันยายน 2569)", screen.oil!!.text)
        assertEquals("ทองแท่ง 68,100 บ. · ดีเซล 40.69", DashboardState.cardFacts("{$gold,$oil}")["gold"]!!.second)
        val down = """"oil":{"ok":false,"error":"URLError"}"""
        assertEquals("น้ำมัน: -", DashboardState.parse("{$gold,$down}", "-").oil!!.text)
    }

    // ------------------------------------ the commodities table (2026-09-23)

    private val table = """{"gold":{"ok":true,"ornament_sell":68950,"bar_sell":68150,
        "ornament_sell_change_pct":0.07,"bar_sell_change_pct":0.07,"change_basis":"เทียบครั้งก่อน"},
        "oil":{"ok":true,"area":"กรุงเทพฯ","date":"23 กันยายน 2569","fuels":[
        {"id":"diesel","label":"ดีเซล","cheapest":[{"brand":"ปตท.","price":40.69},{"brand":"บางจาก","price":40.69},{"brand":"เชลล์","price":40.99}]},
        {"id":"gasohol_95","label":"โซฮอล์ 95","cheapest":[{"brand":"PT","price":39.9},{"brand":"ปตท.","price":39.94}]}]}}"""

    @Test
    fun `the price is its own cell, so it can line up in a column`() {
        val c = DashboardState.commodities(table, "-")
        assertEquals(listOf("รูปพรรณ", "ทองแท่ง"), c.gold.map { it.label })
        assertEquals(listOf("68,950", "68,150"), c.gold.map { it.price })
        assertEquals("+0.07%", c.gold[0].extra)
        val diesel = c.oil!![0]
        assertEquals(Triple("ดีเซล", "40.69", "ปตท. บางจาก เชลล์ +0.30"),
                     Triple(diesel.label, diesel.price, diesel.extra))
        assertEquals("39.90", c.oil!![1].price)                 // the cheapest is the column
        assertEquals("PT ปตท. +0.04", c.oil!![1].extra)
    }

    @Test
    fun `each section says what its numbers are, once, in its header`() {
        val c = DashboardState.commodities(table, "-")
        assertEquals("ทองคำ บาทละ · +/− เทียบครั้งก่อน", c.goldHeader)
        assertEquals("น้ำมันถูกสุด บาท/ลิตร · ราคากรุงเทพฯ 23 ก.ย.", c.oilHeader)
    }

    @Test
    fun `no fuel from an older broker, and a source that is down, are told apart`() {
        val goldOnly = DashboardState.commodities("""{"gold":{"ok":true,"bar_sell":68150}}""", "ข้อมูลไม่พร้อม")
        assertNull(goldOnly.oilHeader)
        val down = DashboardState.commodities("""{"gold":{"ok":false},"oil":{"ok":false}}""", "ข้อมูลไม่พร้อม")
        assertEquals("ทองคำ: ข้อมูลไม่พร้อม", down.goldHeader)
        assertEquals("น้ำมัน: ข้อมูลไม่พร้อม", down.oilHeader)
    }
}
