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

    // ---- 0.36.0: the kiosk's fixed order (DESIGN.md, ก and ข) ------------

    private fun kiosk() = CardBoard(fixedOrder = true).apply {
        register(CardBoard.Spec("weather", 60 * MINUTE, alwaysOpen = true))
        register(CardBoard.Spec("alarms", 10 * MINUTE))
        register(CardBoard.Spec("gold", 120 * MINUTE))
        register(CardBoard.Spec("crypto", 60 * MINUTE, openOnFirst = false))
    }

    @Test
    fun `news never moves a card in the fixed order`() {
        val b = kiosk()
        b.report("weather", "29", 0); b.report("gold", "a", 0); b.report("crypto", "move:1", 0)
        b.report("crypto", "move:2", 30 * MINUTE)
        assertEquals(listOf("weather", "alarms", "gold", "crypto"), b.ids(31 * MINUTE))
        assertTrue("the news shows as the badge", b.slot("crypto", 31 * MINUTE).fresh)
    }

    @Test
    fun `a ringing alarm still comes first in the fixed order`() {
        val b = kiosk()
        b.pin("alarms", true)
        assertEquals("alarms", b.ids(0).first())
        b.pin("alarms", false)
        assertEquals("weather", b.ids(0).first())
    }

    @Test
    fun `the weather is open even after hours without news`() {
        val b = kiosk()
        b.report("weather", "29", 0)
        assertTrue(b.slot("weather", 10 * 60 * MINUTE).open)
        assertTrue("open before any data too", kiosk().slot("weather", 0).open)
    }

    @Test
    fun `crypto stays folded on its first prices and opens on a real move`() {
        val b = kiosk()
        b.report("crypto", "move:1", 0)
        assertFalse(b.slot("crypto", 0).open)
        b.report("crypto", "move:2", 5 * MINUTE)
        assertTrue(b.slot("crypto", 5 * MINUTE).open)
        assertFalse("folds again after its hour", b.slot("crypto", 66 * MINUTE).open)
    }

    @Test
    fun `a tap opens folded crypto`() {
        val b = kiosk()
        b.report("crypto", "move:1", 0)
        b.touch("crypto", MINUTE)
        assertTrue(b.slot("crypto", 2 * MINUTE).open)
        assertFalse(b.slot("crypto", 4 * MINUTE).open)
    }

    // ---- fit: an open card is never cut off by the one below it ----------

    private val openPx = mapOf("weather" to 400, "alarms" to 140, "gold" to 360, "crypto" to 250)
    private val barPx = mapOf("weather" to 60, "alarms" to 60, "gold" to 60, "crypto" to 60)

    @Test
    fun `when everything open is too tall the oldest news folds`() {
        val b = kiosk()
        b.report("weather", "29", 0); b.report("gold", "a", 0); b.report("crypto", "move:1", 0)
        b.report("alarms", "x", 5 * MINUTE)           // opened by a new alarm
        b.report("crypto", "move:2", 8 * MINUTE)      // then a 3% move
        val now = 9 * MINUTE
        val fitted = b.fit(b.layout(now), openPx, barPx, available = 1180, nowMs = now)
        // 400 + 140 + 360 + 250 = 1150 fits; nothing folds.
        assertEquals(4, fitted.count { it.open })
        val tight = b.fit(b.layout(now), openPx, barPx, available = 1100, nowMs = now)
        val folded = tight.filterNot { it.open }.map { it.id }
        assertEquals("the gold's news (t=0) is the oldest", listOf("gold"), folded)
    }

    @Test
    fun `the weather and a ringing alarm are never folded to fit`() {
        val b = kiosk()
        b.report("weather", "29", 0); b.report("gold", "a", 0); b.report("crypto", "move:1", 0)
        b.pin("alarms", true)
        b.touch("crypto", MINUTE)
        val fitted = b.fit(b.layout(MINUTE), openPx, barPx, available = 500, nowMs = MINUTE)
        val open = fitted.filter { it.open }.map { it.id }.toSet()
        // 0.53.1: a tapped card stays only while there is room; the weather and
        // the ringing alarm stay whatever it costs.
        assertEquals(setOf("weather", "alarms"), open)
    }

    @Test
    fun `the card tapped last is kept when there is room for it`() {
        val b = kiosk()
        b.report("weather", "29", 0); b.report("gold", "a", 0); b.report("crypto", "move:1", 0)
        b.touch("gold", MINUTE)
        b.touch("crypto", MINUTE + 1000)                 // tapped last
        // weather 400 + gold 360 + crypto 250 = 1010 > 800: one must fold.
        val fitted = b.fit(b.layout(2 * MINUTE), openPx, barPx, available = 800, nowMs = 2 * MINUTE)
        assertEquals(setOf("weather", "crypto"), fitted.filter { it.open }.map { it.id }.toSet())
    }

    // ---- 0.53.1: Poom tapped every card open on the A07; Jarvis fell to ~58dp ----

    /** The sizes MainActivity measured on the A07 that day (KioskScreen "fit" log), px. */
    private val a07Open = mapOf("weather" to 442, "gold" to 357, "crypto" to 274, "home" to 303, "music" to 191,
                                "alarms" to 260)
    private val a07Bar = mapOf("weather" to 77, "gold" to 77, "crypto" to 77, "home" to 77, "music" to 77,
                               "alarms" to 77)
    /** Room above Jarvis's 156dp floor on the A07 that day, px. */
    private val a07Available = 1196

    private fun a07Board() = CardBoard(fixedOrder = true).apply {
        register(CardBoard.Spec("weather", 60 * MINUTE, alwaysOpen = true))
        register(CardBoard.Spec("alarms", 10 * MINUTE))
        register(CardBoard.Spec("gold", 120 * MINUTE))
        register(CardBoard.Spec("crypto", 60 * MINUTE, openOnFirst = false))
        register(CardBoard.Spec("home", 60 * MINUTE))
        register(CardBoard.Spec("music", 60 * MINUTE))
    }

    private fun height(slots: List<CardBoard.Slot>) =
        slots.sumOf { (if (it.open) a07Open else a07Bar).getValue(it.id) }

    @Test
    fun `every card tapped open at once still leaves Jarvis its 156dp`() {
        for (withMusic in listOf(false, true)) for (ringing in listOf(false, true)) {
            val b = a07Board()
            for (id in listOf("weather", "alarms", "gold", "crypto", "home", "music")) b.report(id, "x", 0)
            b.holdOpen("music", withMusic)
            b.pin("alarms", ringing)
            // Every card tapped, one after another, as Poom did.
            var t = 10 * MINUTE
            for (id in listOf("gold", "crypto", "home", "alarms")) { b.touch(id, t); t += 3_000 }
            val slots = b.layout(t)
            assertTrue("all open before fitting", slots.all { it.open || it.id == "music" && !withMusic })
            val fitted = b.fit(slots, a07Open, a07Bar, a07Available, t)
            assertTrue("music=$withMusic ringing=$ringing: ${height(fitted)} px > $a07Available",
                       height(fitted) <= a07Available)
            val open = fitted.filter { it.open }.map { it.id }
            assertTrue("the weather stays open", "weather" in open)
            if (ringing) assertTrue("a ringing alarm stays open", "alarms" in open)
            if (withMusic) assertTrue("the music stays open when it fits", "music" in open)
        }
    }

    @Test
    fun `with every card tapped, the oldest news folds first`() {
        val b = a07Board()
        b.report("weather", "w", 0); b.report("gold", "g", 1 * MINUTE)
        b.report("crypto", "c", 2 * MINUTE); b.report("home", "h", 3 * MINUTE)
        var t = 10 * MINUTE
        for (id in listOf("home", "crypto", "gold")) { b.touch(id, t); t += 3_000 }   // gold tapped last
        // No alarm set and no music: those cards are hidden, as MainActivity does.
        val shown = b.layout(t).filterNot { it.id == "alarms" || it.id == "music" }
        val fitted = b.fit(shown, a07Open, a07Bar, a07Available, t)
        val folded = fitted.filterNot { it.open }.map { it.id }
        // weather 442 + gold 357 + crypto 274 + home 303 = 1376 > 1196: crypto's
        // news (t=2) is older than home's (t=3), and gold was tapped last.
        assertEquals(listOf("crypto"), folded)
    }

    @Test
    fun `fit keeps the order and changes nothing when the stack is not measured yet`() {
        val b = kiosk()
        b.report("weather", "29", 0); b.report("gold", "a", 0)
        val slots = b.layout(0)
        assertEquals(slots, b.fit(slots, openPx, barPx, available = 0, nowMs = 0))
        assertEquals(slots.map { it.id }, b.fit(slots, openPx, barPx, available = 300, nowMs = 0).map { it.id })
    }
}
