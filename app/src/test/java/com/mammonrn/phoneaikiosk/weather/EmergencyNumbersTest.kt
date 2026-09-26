package com.mammonrn.phoneaikiosk.weather

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The local constant kept identical to server/kiosk_broker/emergency.py's
 * TABLE (this repo has no single shared source for the two: an Android test
 * and a Python test each check their own side's copy against the numbers
 * Poom gave, so an edit to one that drifts from the other is still caught by
 * BOTH sides agreeing with the same six numbers, not by importing one file
 * into the other language).
 */
class EmergencyNumbersTest {

    @Test
    fun `the table is exactly Poom's six numbers, in order`() {
        val digits = EmergencyNumbers.TABLE.map { it.digits }
        assertEquals(listOf("1784", "1669", "1586", "1146", "1129", "1130"), digits)
        assertEquals("@1784DDPM", EmergencyNumbers.LINE_ID)
    }

    @Test
    fun `every number has an agency, a full name and what it is for`() {
        for (n in EmergencyNumbers.TABLE) {
            assertTrue(n.digits, n.agency.isNotBlank())
            assertTrue(n.digits, n.fullName.isNotBlank())
            assertTrue(n.digits, n.forWhat.isNotBlank())
        }
    }

    @Test
    fun `the card shows exactly two lines, never a third and never taller`() {
        assertEquals(2, EmergencyNumbers.CARD_LINES.size)
    }

    @Test
    fun `the card lines carry the flood and ambulance numbers, and both electricity agencies`() {
        // The card is two short lines, not the whole table (that is what the
        // road numbers, 1586/1146, are for on request only, from Jarvis or
        // ที่มาข้อมูล-style detail — see the report) — but the numbers a
        // flood warning is actually FOR (ปภ., กู้ชีพ) and both electricity
        // agencies (split by area) must all be on the card itself.
        val shown = EmergencyNumbers.CARD_LINES.joinToString(" ")
        for (digits in listOf("1784", "1669", "1129", "1130")) assertTrue(digits, digits in shown)
        assertTrue("กฟภ." in shown)
        assertTrue("กฟน." in shown)
    }

    @Test
    fun `the card lines are formal written Thai, no colloquial words`() {
        // Same banned list the other screens' "formal written Thai" tests use
        // (SolarScreenTest, MusicScreenTest, ...): nothing spoken-register on
        // screen, even here where the words are Jarvis's own voice's style
        // elsewhere (server/kiosk_broker/emergency.py's spoken replies).
        val banned = listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว", "ไม่ใส่ก็ได้", "ครับ", "ค่ะ")
        for (line in EmergencyNumbers.CARD_LINES) for (word in banned) {
            assertFalse("$line contains colloquial \"$word\"", line.contains(word))
        }
    }

    @Test
    fun `showing the numbers lasts a bounded, sane amount of time`() {
        assertTrue(EmergencyNumbers.SHOW_MS in 5_000L..60_000L)
    }
}
