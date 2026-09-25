package com.mammonrn.phoneaikiosk.calendar

import org.json.JSONObject
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.YearMonth

/**
 * What the calendar app shows (0.63.0, Poom): a month of Poom's appointments and
 * the Thai holidays, from the broker (/v1/calendar/list — Google Calendar behind
 * the identity grant). Plain Kotlin: CalendarModelTest.
 *
 * The year is the Buddhist year (Poom: พ.ศ.). A day's marks are SHAPES, not only
 * colours: ● an appointment, ◆ a public holiday, ◇ an observance or a day of
 * unknown kind on Google's holiday calendar.
 */
object CalendarModel {

    data class Appointment(
        val id: String, val title: String, val date: LocalDate, val allDay: Boolean,
        val start: String, val end: String, val endDate: LocalDate,
    ) {
        fun covers(day: LocalDate) = !day.isBefore(date) && !day.isAfter(endDate)
    }

    enum class Kind { HOLIDAY, OBSERVANCE, UNKNOWN }

    data class Holiday(val date: LocalDate, val title: String, val kind: Kind)

    /** One answer from the broker. [holidaysOk] false: the appointments stand, the holidays are missing. */
    data class Month(val events: List<Appointment>, val holidays: List<Holiday>, val holidaysOk: Boolean)

    enum class Mark(val symbol: String) { APPOINTMENT("●"), HOLIDAY("◆"), OBSERVANCE("◇") }

    fun parse(json: JSONObject): Month {
        val events = ArrayList<Appointment>()
        json.optJSONArray("events")?.let { a ->
            for (i in 0 until a.length()) {
                val e = a.optJSONObject(i) ?: continue
                val date = runCatching { LocalDate.parse(e.optString("date")) }.getOrNull() ?: continue
                val end = runCatching { LocalDate.parse(e.optString("endDate")) }.getOrNull() ?: date
                events.add(Appointment(e.optString("id"), e.optString("title").ifBlank { "นัดไม่มีชื่อ" },
                    date, e.optBoolean("allDay"), e.optString("start"), e.optString("end"), maxOf(date, end)))
            }
        }
        val holidays = ArrayList<Holiday>()
        json.optJSONArray("holidays")?.let { a ->
            for (i in 0 until a.length()) {
                val h = a.optJSONObject(i) ?: continue
                val date = runCatching { LocalDate.parse(h.optString("date")) }.getOrNull() ?: continue
                val kind = when (h.optString("kind")) { "holiday" -> Kind.HOLIDAY; "observance" -> Kind.OBSERVANCE; else -> Kind.UNKNOWN }
                holidays.add(Holiday(date, h.optString("title"), kind))
            }
        }
        return Month(events, holidays, json.optBoolean("holidaysOk", true))
    }

    /** The six weeks the month's page shows, from the Sunday on or before the 1st. */
    fun gridStart(month: YearMonth): LocalDate {
        val first = month.atDay(1)
        return first.minusDays((first.dayOfWeek.value % 7).toLong())
    }

    fun gridDays(month: YearMonth): List<LocalDate> = (0 until 42).map { gridStart(month).plusDays(it.toLong()) }

    /** The days asked of the broker for [month]: the whole page. */
    fun range(month: YearMonth): Pair<LocalDate, LocalDate> = gridStart(month) to gridStart(month).plusDays(41)

    fun eventsOn(m: Month, day: LocalDate): List<Appointment> =
        m.events.filter { it.covers(day) }.sortedWith(compareBy({ !it.allDay }, { it.start }))

    fun holidaysOn(m: Month, day: LocalDate): List<Holiday> = m.holidays.filter { it.date == day }

    fun marks(m: Month, day: LocalDate): List<Mark> = buildList {
        if (m.events.any { it.covers(day) }) add(Mark.APPOINTMENT)
        val kinds = holidaysOn(m, day).map { it.kind }
        if (Kind.HOLIDAY in kinds) add(Mark.HOLIDAY)
        else if (kinds.isNotEmpty()) add(Mark.OBSERVANCE)
    }

    private val MONTHS = listOf("มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน", "กรกฎาคม",
                                "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม")
    private val DAYS = mapOf(DayOfWeek.MONDAY to "จันทร์", DayOfWeek.TUESDAY to "อังคาร", DayOfWeek.WEDNESDAY to "พุธ",
                             DayOfWeek.THURSDAY to "พฤหัสบดี", DayOfWeek.FRIDAY to "ศุกร์", DayOfWeek.SATURDAY to "เสาร์",
                             DayOfWeek.SUNDAY to "อาทิตย์")
    /** The week's heads, Sunday first. */
    val WEEK_HEADS = listOf("อา", "จ", "อ", "พ", "พฤ", "ศ", "ส")

    fun buddhistYear(year: Int) = year + 543

    /** "ตุลาคม 2569". */
    fun monthTitle(m: YearMonth) = "${MONTHS[m.monthValue - 1]} ${buddhistYear(m.year)}"

    /** "วันศุกร์ที่ 23 ตุลาคม 2569". */
    fun dayTitle(d: LocalDate) = "วัน${DAYS.getValue(d.dayOfWeek)}ที่ ${d.dayOfMonth} ${MONTHS[d.monthValue - 1]} ${buddhistYear(d.year)}"

    /** "14:00–15:30" or "ทั้งวัน". */
    fun timeText(a: Appointment) = if (a.allDay) "ทั้งวัน" else "${a.start}–${a.end}"

    /** A day's words for the screen reader: the date, then what is on it. */
    fun daySaid(m: Month, day: LocalDate): String {
        val n = m.events.count { it.covers(day) }
        val h = holidaysOn(m, day)
        return buildString {
            append(dayTitle(day))
            if (n > 0) append(" มีนัด $n นัด")
            for (x in h) append(" · ${x.title}")
        }
    }
}
