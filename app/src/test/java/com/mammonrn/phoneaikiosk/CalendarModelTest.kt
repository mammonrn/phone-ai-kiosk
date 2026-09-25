package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calendar.CalendarModel
import com.mammonrn.phoneaikiosk.calendar.CalendarModel.Mark
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/** The calendar app's month (0.63.0): the Buddhist year, six weeks from Sunday, marks by shape. */
class CalendarModelTest {

    private val json = JSONObject("""{"events": [
        {"id": "ev1", "title": "หาหมอฟัน", "date": "2026-10-05", "allDay": false, "start": "14:00", "end": "15:30", "endDate": "2026-10-05"},
        {"id": "ev2", "title": "ไปต่างจังหวัด", "date": "2026-10-10", "allDay": true, "start": "", "end": "", "endDate": "2026-10-12"},
        {"id": "ev3", "title": "", "date": "2026-10-10", "allDay": false, "start": "09:00", "end": "10:00", "endDate": "2026-10-10"}
      ], "holidays": [
        {"date": "2026-10-23", "title": "วันปิยมหาราช", "kind": "holiday"},
        {"date": "2026-10-26", "title": "วันออกพรรษา", "kind": "observance"},
        {"date": "2026-11-24", "title": "วันลอยกระทง", "kind": "unknown"}
      ], "holidaysOk": true}""")

    private val m = CalendarModel.parse(json)

    @Test fun `the year is Buddhist and the words are Thai`() {
        assertEquals("ตุลาคม 2569", CalendarModel.monthTitle(YearMonth.of(2026, 10)))
        assertEquals("วันศุกร์ที่ 23 ตุลาคม 2569", CalendarModel.dayTitle(LocalDate.of(2026, 10, 23)))
    }

    @Test fun `six weeks from the Sunday on or before the first`() {
        val days = CalendarModel.gridDays(YearMonth.of(2026, 10))
        assertEquals(42, days.size)
        assertEquals(DayOfWeek.SUNDAY, days.first().dayOfWeek)
        assertEquals(LocalDate.of(2026, 9, 27), days.first())             // 1 Oct 2026 is a Thursday
        assertEquals(LocalDate.of(2026, 11, 7), days.last())
        assertEquals(CalendarModel.range(YearMonth.of(2026, 10)), days.first() to days.last())
        // A month that starts on a Sunday starts on its own first day.
        assertEquals(LocalDate.of(2026, 11, 1), CalendarModel.gridStart(YearMonth.of(2026, 11)))
    }

    @Test fun `marks are shapes - an appointment, a public holiday, anything else on the holiday calendar`() {
        assertEquals(listOf(Mark.APPOINTMENT), CalendarModel.marks(m, LocalDate.of(2026, 10, 5)))
        assertEquals(listOf(Mark.APPOINTMENT), CalendarModel.marks(m, LocalDate.of(2026, 10, 11)))   // inside a 3-day trip
        assertEquals(listOf(Mark.HOLIDAY), CalendarModel.marks(m, LocalDate.of(2026, 10, 23)))
        assertEquals(listOf(Mark.OBSERVANCE), CalendarModel.marks(m, LocalDate.of(2026, 10, 26)))
        assertEquals(listOf(Mark.OBSERVANCE), CalendarModel.marks(m, LocalDate.of(2026, 11, 24)))
        assertEquals(emptyList<Mark>(), CalendarModel.marks(m, LocalDate.of(2026, 10, 6)))
        assertEquals(setOf("●", "◆", "◇"), Mark.values().map { it.symbol }.toSet())
    }

    @Test fun `a day lists the whole-day ones first, then by time, and names the nameless`() {
        val day = CalendarModel.eventsOn(m, LocalDate.of(2026, 10, 10))
        assertEquals(listOf("ev2", "ev3"), day.map { it.id })
        assertEquals("ทั้งวัน", CalendarModel.timeText(day[0]))
        assertEquals("09:00–10:00", CalendarModel.timeText(day[1]))
        assertEquals("นัดไม่มีชื่อ", day[1].title)
    }

    @Test fun `the holidays missing is known, the appointments stand`() {
        val partial = CalendarModel.parse(JSONObject("""{"events": [], "holidays": [], "holidaysOk": false}"""))
        assertFalse(partial.holidaysOk)
        assertEquals("วันศุกร์ที่ 23 ตุลาคม 2569 · วันปิยมหาราช", CalendarModel.daySaid(m, LocalDate.of(2026, 10, 23)))
    }
}
