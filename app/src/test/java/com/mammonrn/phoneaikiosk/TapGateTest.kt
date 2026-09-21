package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The escape hatch is the one piece of this phase that can be checked without
 * a phone, and the one piece where both failure directions are bad: too strict
 * and the kiosk cannot be left, too loose and it lets itself out.
 */
class TapGateTest {

    private val gate = TapGate(tapsRequired = 10, maxGapMs = 1_500L)

    @Test
    fun `ten taps in a row opens the gate`() {
        var now = 0L
        repeat(9) {
            now += 200
            assertFalse("opened early at tap ${gate.progress}", gate.onTap(now))
        }
        now += 200
        assertTrue(gate.onTap(now))
    }

    @Test
    fun `nine taps is not enough`() {
        var now = 0L
        repeat(9) {
            now += 200
            assertFalse(gate.onTap(now))
        }
        assertEquals(9, gate.progress)
    }

    @Test
    fun `a slow tap restarts the run`() {
        var now = 0L
        repeat(9) {
            now += 200
            gate.onTap(now)
        }

        // One tap arriving late: the run so far is discarded and this tap
        // counts as the first of a new one.
        now += 1_501
        assertFalse(gate.onTap(now))
        assertEquals(1, gate.progress)
    }

    @Test
    fun `a tap exactly on the limit still counts`() {
        assertFalse(gate.onTap(0))
        assertFalse(gate.onTap(1_500))
        assertEquals(2, gate.progress)
    }

    @Test
    fun `nine slow taps never open the gate`() {
        var now = 0L
        repeat(30) {
            now += 5_000
            assertFalse("a gate that opens on slow taps is a gate that opens by itself", gate.onTap(now))
        }
        assertEquals(1, gate.progress)
    }

    @Test
    fun `the counter resets after opening`() {
        var now = 0L
        repeat(10) {
            now += 100
            gate.onTap(now)
        }
        assertEquals(0, gate.progress)

        now += 100
        assertFalse(gate.onTap(now))
        assertEquals(1, gate.progress)
    }
}
