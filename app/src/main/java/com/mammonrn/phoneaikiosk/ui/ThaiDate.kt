package com.mammonrn.phoneaikiosk.ui

import java.util.Calendar

/**
 * "พ. 23 ก.ย." — the taskbar's date, in Thai, as Poom asked.
 *
 * WRITTEN OUT RATHER THAN LEFT TO Locale("th"). Two reasons. The platform's
 * Thai locale can bring the Buddhist calendar with it, and whether it does
 * differs between the JVM the tests run on and the phone — for a day and a
 * month that is harmless today and a trap the day somebody adds the year. And
 * the abbreviations are the part a reader sees; fixing them here means a test
 * can say exactly what the screen will say, on any machine.
 */
object ThaiDate {

    /** Calendar.SUNDAY is 1, so index [day - 1]. */
    private val DAYS = arrayOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.")

    /** Calendar.JANUARY is 0. */
    private val MONTHS = arrayOf(
        "ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
        "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค.",
    )

    /** The date [calendar] holds, in its own time zone. */
    fun short(calendar: Calendar): String =
        "${DAYS[calendar.get(Calendar.DAY_OF_WEEK) - 1]} " +
            "${calendar.get(Calendar.DAY_OF_MONTH)} " +
            MONTHS[calendar.get(Calendar.MONTH)]
}
