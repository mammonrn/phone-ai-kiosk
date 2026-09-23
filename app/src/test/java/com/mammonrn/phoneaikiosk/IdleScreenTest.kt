package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.IdleScreen.Action
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The five-minute rule, with the clock in the test's hands.
 *
 * Wrong one way, the screen never sleeps — which is where this started. Wrong
 * the other, it goes dark in the middle of an answer.
 */
class IdleScreenTest {

    private val minute = 60_000L
    private val idle = IdleScreen.IDLE_MS

    @Test
    fun `five minutes is the number`() {
        assertEquals(5 * minute, IdleScreen.IDLE_MS)
    }

    @Test
    fun `charging and recently used holds the screen on`() {
        val screen = IdleScreen().apply { used(0) }
        assertEquals(Action.KEEP_ON, screen.decide(4 * minute, charging = true, busy = false))
    }

    @Test
    fun `on battery and recently used leaves it to the system, as before`() {
        val screen = IdleScreen().apply { used(0) }
        assertEquals(Action.RELEASE, screen.decide(minute, charging = false, busy = false))
    }

    @Test
    fun `five idle minutes turns it off, charging or not`() {
        for (charging in listOf(true, false)) {
            val screen = IdleScreen().apply { used(0) }
            assertEquals("charging=$charging",
                         Action.SLEEP, screen.decide(idle, charging, busy = false))
        }
    }

    @Test
    fun `it asks to sleep once per idle spell, not every second`() {
        val screen = IdleScreen().apply { used(0) }
        assertEquals(Action.SLEEP, screen.decide(idle, charging = true, busy = false))
        assertEquals(Action.RELEASE, screen.decide(idle + 1_000, charging = true, busy = false))
        assertEquals(Action.RELEASE, screen.decide(idle + minute, charging = true, busy = false))
    }

    @Test
    fun `a touch starts the five minutes again`() {
        val screen = IdleScreen().apply { used(0) }
        screen.used(4 * minute)
        assertEquals(Action.KEEP_ON, screen.decide(8 * minute, charging = true, busy = false))
        assertEquals(Action.SLEEP, screen.decide(9 * minute, charging = true, busy = false))
    }

    @Test
    fun `an answer in progress is never read to a dark screen`() {
        val screen = IdleScreen().apply { used(0) }
        // Four minutes fifty in, somebody asks; the answer runs past five.
        assertEquals(Action.KEEP_ON, screen.decide(idle + minute, charging = false, busy = true))
        // And the five minutes restart from the end of it.
        assertEquals(Action.RELEASE, screen.decide(idle + 2 * minute, charging = false, busy = false))
    }

    @Test
    fun `waking after a sleep allows the next sleep`() {
        val screen = IdleScreen().apply { used(0) }
        assertEquals(Action.SLEEP, screen.decide(idle, charging = true, busy = false))
        screen.used(idle + minute)   // "Hey Jarvis", screen back on
        assertEquals(Action.SLEEP, screen.decide(2 * idle + minute, charging = true, busy = false))
    }

    @Test
    fun `the voice states that count as busy`() {
        assertTrue(IdleScreen.voiceBusy("heard", "idle", "idle", "idle"))
        assertTrue(IdleScreen.voiceBusy("listening", "recording", "idle", "idle"))
        assertTrue(IdleScreen.voiceBusy("listening", "sending", "idle", "idle"))
        assertTrue(IdleScreen.voiceBusy("listening", "ok", "asking", "idle"))
        assertTrue(IdleScreen.voiceBusy("listening", "ok", "ok", "synthesising"))
        assertTrue(IdleScreen.voiceBusy("listening", "ok", "ok", "speaking"))
    }

    @Test
    fun `a finished or failed turn does not hold the screen`() {
        assertFalse(IdleScreen.voiceBusy("listening", "idle", "idle", "idle"))
        assertFalse(IdleScreen.voiceBusy("listening", "ok", "ok", "ok"))
        assertFalse(IdleScreen.voiceBusy("listening", "error", "error", "failed"))
        // Left set after the phone's own voice finishes; must not pin the screen.
        assertFalse(IdleScreen.voiceBusy("listening", "ok", "ok", "device-fallback"))
    }
}
