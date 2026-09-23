package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.SoakProbe
import org.junit.Assert.assertEquals
import org.junit.Test
import java.util.Calendar
import java.util.TimeZone

/** Phase 6: how late an alarm rang, the number the soak's alarm criterion reads. */
class SoakProbeTest {

    private val zone = TimeZone.getTimeZone("Asia/Bangkok")

    private fun at(hour: Int, minute: Int, second: Int, dayOffset: Int = 0): Long =
        Calendar.getInstance(zone).apply {
            set(2026, Calendar.SEPTEMBER, 24 + dayOffset, hour, minute, second)
            set(Calendar.MILLISECOND, 0)
        }.timeInMillis

    @Test
    fun `on the minute is zero, after it is positive`() {
        assertEquals(0L, SoakProbe.lateSeconds(6, 30, at(6, 30, 0), zone))
        assertEquals(42L, SoakProbe.lateSeconds(6, 30, at(6, 30, 42), zone))
    }

    @Test
    fun `early is negative`() {
        assertEquals(-5L, SoakProbe.lateSeconds(6, 30, at(6, 29, 55), zone))
    }

    @Test
    fun `an alarm for 23-59 that rang after midnight is late, not a day early`() {
        assertEquals(90L, SoakProbe.lateSeconds(23, 59, at(0, 0, 30, dayOffset = 1), zone))
    }
}
