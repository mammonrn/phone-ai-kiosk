package com.mammonrn.phoneaikiosk.ui

import java.util.Calendar

/**
 * Dates on the screen: "Wed 23/09/2026" (Poom, 2026-09-23), the same form
 * everywhere a date is shown — the taskbar and the fuel prices' date. Times on
 * the screen are "1:40 PM" (DashboardState.clock12); what Jarvis SAYS is the
 * Thai clock, never either of these.
 *
 * Written out rather than left to SimpleDateFormat: the phone runs a Thai
 * locale, which brings Thai day names and can bring the Buddhist year, and
 * this has to be the same on the test JVM and on the A07.
 */
object ScreenDate {

    /** Calendar.SUNDAY is 1, so index [day - 1]. */
    private val DAYS = arrayOf("Sun", "Mon", "Tue", "Wed", "Thu", "Fri", "Sat")

    /** "Wed 23/09/2026" for the date [calendar] holds, Gregorian. */
    fun format(calendar: Calendar): String = String.format(
        java.util.Locale.US, "%s %02d/%02d/%04d",
        DAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1], calendar.get(Calendar.DAY_OF_MONTH),
        calendar.get(Calendar.MONTH) + 1, calendar.get(Calendar.YEAR))

    private val THAI_MONTHS = listOf(
        "มกราคม", "กุมภาพันธ์", "มีนาคม", "เมษายน", "พฤษภาคม", "มิถุนายน",
        "กรกฎาคม", "สิงหาคม", "กันยายน", "ตุลาคม", "พฤศจิกายน", "ธันวาคม",
    )

    /**
     * "23 กันยายน 2569" (a Thai date in the Buddhist year, as the fuel source
     * writes it) -> "Wed 23/09/2026". Anything else comes back as it was.
     */
    fun fromThai(date: String): String {
        val parts = date.trim().split(Regex("\\s+"))
        if (parts.size != 3) return date.trim()
        val day = parts[0].toIntOrNull() ?: return date.trim()
        val month = THAI_MONTHS.indexOf(parts[1])
        val year = parts[2].toIntOrNull() ?: return date.trim()
        if (month < 0 || day !in 1..31) return date.trim()
        val gregorian = if (year > 2400) year - 543 else year
        val calendar = Calendar.getInstance().apply {
            clear()
            set(gregorian, month, day)
        }
        return format(calendar)
    }
}
