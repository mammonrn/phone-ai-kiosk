package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calendar.Lunar
import java.time.LocalDate
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * วันพระ are computed (Poom 0.65: shown only if every one of 2569's 49 matches).
 * 2569: the list สวท. ประจวบคีรีขันธ์ printed (a copy on a government site), with its
 * misprint 26 April for ขึ้น 8 ค่ำ เดือนหก taken from myhora.com as 24 April.
 * 2541–2570: the start and length of every lunar year in myhora.com's calendars.
 */
class LunarTest {

    private val official2569 = listOf(
        "2026-01-03 ขึ้น 15 ค่ำ เดือนยี่",
        "2026-01-11 แรม 8 ค่ำ เดือนยี่",
        "2026-01-18 แรม 15 ค่ำ เดือนยี่",
        "2026-01-26 ขึ้น 8 ค่ำ เดือนสาม",
        "2026-02-02 ขึ้น 15 ค่ำ เดือนสาม",
        "2026-02-10 แรม 8 ค่ำ เดือนสาม",
        "2026-02-16 แรม 14 ค่ำ เดือนสาม",
        "2026-02-24 ขึ้น 8 ค่ำ เดือนสี่",
        "2026-03-03 ขึ้น 15 ค่ำ เดือนสี่",
        "2026-03-11 แรม 8 ค่ำ เดือนสี่",
        "2026-03-18 แรม 15 ค่ำ เดือนสี่",
        "2026-03-26 ขึ้น 8 ค่ำ เดือนห้า",
        "2026-04-02 ขึ้น 15 ค่ำ เดือนห้า",
        "2026-04-10 แรม 8 ค่ำ เดือนห้า",
        "2026-04-16 แรม 14 ค่ำ เดือนห้า",
        "2026-04-24 ขึ้น 8 ค่ำ เดือนหก",
        "2026-05-01 ขึ้น 15 ค่ำ เดือนหก",
        "2026-05-09 แรม 8 ค่ำ เดือนหก",
        "2026-05-16 แรม 15 ค่ำ เดือนหก",
        "2026-05-24 ขึ้น 8 ค่ำ เดือนเจ็ด",
        "2026-05-31 ขึ้น 15 ค่ำ เดือนเจ็ด",
        "2026-06-08 แรม 8 ค่ำ เดือนเจ็ด",
        "2026-06-14 แรม 14 ค่ำ เดือนเจ็ด",
        "2026-06-22 ขึ้น 8 ค่ำ เดือนแปด",
        "2026-06-29 ขึ้น 15 ค่ำ เดือนแปด",
        "2026-07-07 แรม 8 ค่ำ เดือนแปด",
        "2026-07-14 แรม 15 ค่ำ เดือนแปด",
        "2026-07-22 ขึ้น 8 ค่ำ เดือนแปดหลัง",
        "2026-07-29 ขึ้น 15 ค่ำ เดือนแปดหลัง",
        "2026-08-06 แรม 8 ค่ำ เดือนแปดหลัง",
        "2026-08-13 แรม 15 ค่ำ เดือนแปดหลัง",
        "2026-08-21 ขึ้น 8 ค่ำ เดือนเก้า",
        "2026-08-28 ขึ้น 15 ค่ำ เดือนเก้า",
        "2026-09-05 แรม 8 ค่ำ เดือนเก้า",
        "2026-09-11 แรม 14 ค่ำ เดือนเก้า",
        "2026-09-19 ขึ้น 8 ค่ำ เดือนสิบ",
        "2026-09-26 ขึ้น 15 ค่ำ เดือนสิบ",
        "2026-10-04 แรม 8 ค่ำ เดือนสิบ",
        "2026-10-11 แรม 15 ค่ำ เดือนสิบ",
        "2026-10-19 ขึ้น 8 ค่ำ เดือนสิบเอ็ด",
        "2026-10-26 ขึ้น 15 ค่ำ เดือนสิบเอ็ด",
        "2026-11-03 แรม 8 ค่ำ เดือนสิบเอ็ด",
        "2026-11-09 แรม 14 ค่ำ เดือนสิบเอ็ด",
        "2026-11-17 ขึ้น 8 ค่ำ เดือนสิบสอง",
        "2026-11-24 ขึ้น 15 ค่ำ เดือนสิบสอง",
        "2026-12-02 แรม 8 ค่ำ เดือนสิบสอง",
        "2026-12-09 แรม 15 ค่ำ เดือนสิบสอง",
        "2026-12-17 ขึ้น 8 ค่ำ เดือนอ้าย",
        "2026-12-24 ขึ้น 15 ค่ำ เดือนอ้าย"
    )

    @Test
    fun `every วันพระ of 2569 falls on the printed day with the printed name`() {
        assertEquals(49, official2569.size)
        val computed = Lunar.holyDays(2569).map { "${it.date} ${it.label}" }
        assertEquals(official2569.filter { it < "2026-12-10" }, computed.filter { it >= "2026-01" })
        val calendarYear = Lunar.between(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31)).map { "${it.date} ${it.label}" }
        assertEquals(official2569, calendarYear)
    }

    @Test
    fun `2569 has เดือนแปด twice and no extra day`() {
        assertTrue(Lunar.adhikamasa(2569))
        assertFalse(Lunar.adhikavara(2569))
        assertFalse(Lunar.uncertain(2569))
    }

    /** พ.ศ. to (ขึ้น 1 ค่ำ เดือนอ้าย, days in the year): 354, 355 อธิกวาร, 384 อธิกมาส. */
    private val years = mapOf(
        2541 to ("1997-11-30" to 354),
        2542 to ("1998-11-19" to 384),
        2543 to ("1999-12-08" to 355),
        2544 to ("2000-11-27" to 354),
        2545 to ("2001-11-16" to 384),
        2546 to ("2002-12-05" to 354),
        2547 to ("2003-11-24" to 384),
        2548 to ("2004-12-12" to 354),
        2549 to ("2005-12-01" to 355),
        2550 to ("2006-11-21" to 384),
        2551 to ("2007-12-10" to 354),
        2552 to ("2008-11-28" to 355),
        2553 to ("2009-11-18" to 384),
        2554 to ("2010-12-07" to 354),
        2555 to ("2011-11-26" to 384),
        2556 to ("2012-12-14" to 354),
        2557 to ("2013-12-03" to 354),
        2558 to ("2014-11-22" to 384),
        2559 to ("2015-12-11" to 355),
        2560 to ("2016-11-30" to 354),
        2561 to ("2017-11-19" to 384),
        2562 to ("2018-12-08" to 354),
        2563 to ("2019-11-27" to 355),
        2564 to ("2020-11-16" to 384),
        2565 to ("2021-12-05" to 354),
        2566 to ("2022-11-24" to 384),
        2567 to ("2023-12-13" to 354),
        2568 to ("2024-12-01" to 355),
        2569 to ("2025-11-21" to 384),
        2570 to ("2026-12-10" to 354)
    )

    @Test
    fun `every year 2541 to 2570 starts and ends where the printed calendars have it`() {
        for ((be, v) in years) {
            val holy = Lunar.holyDays(be)
            assertEquals("$be starts", LocalDate.parse(v.first).plusDays(7), holy.first().date)
            val days = when (v.second) { 384 -> 384; 355 -> 355; else -> 354 }
            assertEquals("$be kind", days == 384, Lunar.adhikamasa(be))
            assertEquals("$be kind", days == 355, Lunar.adhikavara(be))
            assertEquals("$be ends", LocalDate.parse(v.first).plusDays(days - 1L), holy.last().date)
            assertEquals("$be count", if (days == 384) 52 else 48, holy.size)
        }
    }

    @Test
    fun `a year of 355 days gives เดือนเจ็ด แรม 15`() {
        val seventh = Lunar.holyDays(2568).filter { it.month == 7 }
        assertEquals(listOf("ขึ้น 8 ค่ำ เดือนเจ็ด", "ขึ้น 15 ค่ำ เดือนเจ็ด", "แรม 8 ค่ำ เดือนเจ็ด", "แรม 15 ค่ำ เดือนเจ็ด"),
            seventh.map { it.label })
    }

    @Test
    fun `outside the computed years there are none and nothing breaks`() {
        assertTrue(Lunar.between(LocalDate.of(1990, 1, 1), LocalDate.of(1990, 12, 31)).isEmpty())
        assertTrue(Lunar.between(LocalDate.of(2100, 1, 1), LocalDate.of(2100, 12, 31)).isEmpty())
        assertTrue(Lunar.holyDays(2601).isEmpty())
    }

    @Test
    fun `only unchecked years can be uncertain`() {
        for (be in Lunar.FIRST_YEAR..Lunar.LAST_CHECKED) assertFalse("$be", Lunar.uncertain(be))
        assertTrue((Lunar.LAST_CHECKED + 1..Lunar.LAST_YEAR).any { Lunar.uncertain(it) })
    }
}
