package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.BatteryLabel
import com.mammonrn.phoneaikiosk.voice.CaptureMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class JarvisButtonBatteryTest {

    // ---------------------------------------------------------------- button

    @Test
    fun `the button can start a question while listening`() {
        assertTrue(CaptureMachine(frameMillis = 62).canStartByButton())
    }

    @Test
    fun `the button cannot start a second turn during a capture`() {
        val m = CaptureMachine(frameMillis = 62)
        m.arm()
        m.onFrame(200, false)                    // capture starts
        assertEquals(CaptureMachine.Mode.CAPTURING, m.mode)
        assertFalse(m.canStartByButton())
    }

    // --------------------------------------------------------------- battery

    @Test
    fun `percent is rounded from level and scale`() {
        assertEquals(82, BatteryLabel.percent(82, 100))
        assertEquals(50, BatteryLabel.percent(1, 2))
        assertEquals(-1, BatteryLabel.percent(-1, 100))
        assertEquals("82%", BatteryLabel.text(82))
        assertEquals("--%", BatteryLabel.text(-1))
    }

    @Test
    fun `the picture follows the charger first, then the level`() {
        assertEquals(BatteryLabel.Icon.CHARGING, BatteryLabel.icon(5, charging = true))
        assertEquals(BatteryLabel.Icon.LOW, BatteryLabel.icon(15, charging = false))
        assertEquals(BatteryLabel.Icon.NORMAL, BatteryLabel.icon(16, charging = false))
        assertEquals(BatteryLabel.Icon.NORMAL, BatteryLabel.icon(-1, charging = false))
    }
}
