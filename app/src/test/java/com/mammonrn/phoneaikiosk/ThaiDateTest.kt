package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.ThaiDate
import java.util.Calendar
import java.util.GregorianCalendar
import java.util.TimeZone
import org.junit.Assert.assertEquals
import org.junit.Test

class ThaiDateTest {

    private fun on(year: Int, month: Int, day: Int): Calendar =
        GregorianCalendar(TimeZone.getTimeZone("Asia/Bangkok")).apply {
            clear()
            set(year, month, day, 8, 27)
        }

    @Test
    fun `the example poom gave`() {
        assertEquals("พ. 23 ก.ย.", ThaiDate.short(on(2026, Calendar.SEPTEMBER, 23)))
    }

    @Test
    fun `thursday is the two-letter one`() {
        assertEquals("พฤ. 24 ก.ย.", ThaiDate.short(on(2026, Calendar.SEPTEMBER, 24)))
    }

    @Test
    fun `every month has its abbreviation`() {
        val got = (0..11).map { ThaiDate.short(on(2026, it, 1)).substringAfter(" 1 ") }
        assertEquals(listOf("ม.ค.", "ก.พ.", "มี.ค.", "เม.ย.", "พ.ค.", "มิ.ย.",
                            "ก.ค.", "ส.ค.", "ก.ย.", "ต.ค.", "พ.ย.", "ธ.ค."), got)
    }

    @Test
    fun `a sunday and a new year`() {
        assertEquals("อา. 3 ม.ค.", ThaiDate.short(on(2027, Calendar.JANUARY, 3)))
    }
}
