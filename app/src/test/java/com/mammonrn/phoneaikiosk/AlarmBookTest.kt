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
}
