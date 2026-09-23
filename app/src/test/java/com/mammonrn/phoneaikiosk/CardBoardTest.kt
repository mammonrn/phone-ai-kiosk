package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.CardBoard
import com.mammonrn.phoneaikiosk.ui.CardBoard.Companion.MINUTE
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CardBoardTest {

    private fun board() = CardBoard().apply {
        register(CardBoard.Spec("weather", 60 * MINUTE))
        register(CardBoard.Spec("gold", 60 * MINUTE))
        register(CardBoard.Spec("crypto", 30 * MINUTE))
    }

    private fun CardBoard.ids(now: Long) = layout(now).map { it.id }
    private fun CardBoard.slot(id: String, now: Long) = layout(now).first { it.id == id }

    @Test
    fun `cards that never reported keep their registered order`() {
        assertEquals(listOf("weather", "gold", "crypto"), board().ids(0))
    }

    @Test
    fun `the first data opens a card but is not news`() {
        val b = board()
        b.report("weather", "29|แดด", 0)
        assertTrue(b.slot("weather", 0).open)
        assertFalse("after a restart nothing is news", b.slot("weather", 0).fresh)
    }

    @Test
    fun `the most recent update goes first`() {
        val b = board()
        b.report("weather", "29", 0); b.report("gold", "69000", 0); b.report("crypto", "a", 0)
        b.layout(0)
        b.report("crypto", "b", 5 * MINUTE)
        assertEquals("crypto", b.ids(5 * MINUTE).first())
    }

    @Test
    fun `the same signature again is not an update`() {
        val b = board()
        b.report("gold", "69000", 0)
        b.report("gold", "69000", 50 * MINUTE)
        assertFalse(b.slot("gold", 50 * MINUTE).fresh)
        assertFalse(b.slot("gold", 61 * MINUTE).open)
    }

    @Test
    fun `a card collapses after its own quiet time and reopens on news`() {
        val b = board()
        b.report("crypto", "a", 0)
        assertTrue(b.slot("crypto", 29 * MINUTE).open)
        assertFalse(b.slot("crypto", 30 * MINUTE).open)
        b.report("crypto", "b", 31 * MINUTE)
        val slot = b.slot("crypto", 31 * MINUTE + 1)
        assertTrue(slot.open && slot.fresh)
    }

    @Test
    fun `fresh lasts ten minutes`() {
        val b = board()
        b.report("weather", "28", -MINUTE)
        b.report("weather", "29", 0)
        assertTrue(b.slot("weather", 9 * MINUTE).fresh)
        assertFalse(b.slot("weather", 10 * MINUTE).fresh)
    }

    @Test
    fun `the order does not shuffle more than once a minute`() {
        val b = board()
        b.report("weather", "1", 0); b.report("gold", "1", 0); b.report("crypto", "1", 0)
        val first = b.ids(1_000)
        b.report("crypto", "2", 2_000)
        assertEquals("still settled", first, b.ids(3_000))
        assertEquals("crypto", b.ids(1_000 + MINUTE).first())
    }

    @Test
    fun `a pinned card is open and first at once`() {
        val b = board()
        b.register(CardBoard.Spec("alarm", 60 * MINUTE))
        b.report("weather", "1", 0)
        b.layout(0)
        b.pin("alarm", true)
        val slot = b.layout(1_000).first()
        assertEquals("alarm", slot.id)
        assertTrue(slot.open)
    }

    @Test
    fun `touching a bar opens it for two minutes without moving it`() {
        val b = board()
        b.report("weather", "1", 0); b.report("gold", "1", 0)
        val order = b.ids(0)
        b.touch("gold", 90 * MINUTE)
        assertTrue(b.slot("gold", 91 * MINUTE).open)
        assertFalse(b.slot("gold", 92 * MINUTE).open)
        assertEquals(order, b.ids(92 * MINUTE))
    }
}
