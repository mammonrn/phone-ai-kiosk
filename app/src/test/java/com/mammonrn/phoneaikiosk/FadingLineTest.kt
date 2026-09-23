package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.FadingLine
import org.junit.Assert.assertEquals
import org.junit.Test

class FadingLineTest {

    private val heard = "ได้ยิน: ขอดูกล่องหน่อยครับ\nตอบ: …"

    @Test
    fun `what was heard is shown after the turn`() {
        val line = FadingLine()
        assertEquals(heard, line.visible(heard, 1_000, busy = false))
        assertEquals(heard, line.visible(heard, 59_000, busy = false))
    }

    @Test
    fun `and gone a minute later`() {
        val line = FadingLine()
        line.visible(heard, 1_000, busy = false)
        assertEquals("", line.visible(heard, 61_001, busy = false))
    }

    @Test
    fun `a new turn shows again at once`() {
        val line = FadingLine()
        line.visible(heard, 1_000, busy = false)
        line.visible(heard, 100_000, busy = false)
        assertEquals("ได้ยิน: เปิดกล้อง", line.visible("ได้ยิน: เปิดกล้อง", 100_500, busy = false))
    }

    @Test
    fun `nothing fades while jarvis is still answering`() {
        val line = FadingLine()
        line.visible(heard, 1_000, busy = true)
        assertEquals(heard, line.visible(heard, 90_000, busy = true))
        assertEquals(heard, line.visible(heard, 140_000, busy = false))
        assertEquals("", line.visible(heard, 150_001, busy = false))
    }
}
