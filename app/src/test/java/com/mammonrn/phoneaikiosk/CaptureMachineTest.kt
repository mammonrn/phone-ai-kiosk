package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.CaptureMachine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The state machine that versionCode 4 did not have, and the bug it did.
 *
 * On the A07: `adb shell am broadcast -a ...TEST_LISTEN -p ...` set the screen to
 * `wake=triggered stt=idle` and it never moved. Nothing threw. The cause was a
 * single-threaded executor with an endless task on it, so the work submitted by
 * the trigger was queued behind something that never finishes.
 *
 * The first test here is that arming leads to a capture on the very next frame,
 * with no executor involved at all.
 */
class CaptureMachineTest {

    private val frameMs = 62
    private fun machine() = CaptureMachine(frameMillis = frameMs)

    private val quiet = 100
    private val speech = 9000

    @Test
    fun `arming starts a capture on the next frame`() {
        val m = machine()
        assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
        assertEquals(CaptureMachine.Step.IDLE, m.onFrame(quiet, false))

        m.arm()
        assertTrue(m.isArmed())

        // The regression: this must be STARTED, not IDLE, and it must happen
        // without anything being submitted anywhere.
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(quiet, false))
        assertEquals(CaptureMachine.Mode.CAPTURING, m.mode)
        assertFalse("the flag must be consumed, not left set", m.isArmed())
    }

    @Test
    fun `the wake word also starts a capture`() {
        val m = machine()
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(speech, true))
        assertEquals(CaptureMachine.Mode.CAPTURING, m.mode)
    }

    @Test
    fun `nothing happens while neither armed nor triggered`() {
        val m = machine()
        repeat(200) {
            assertEquals(CaptureMachine.Step.IDLE, m.onFrame(speech, false))
        }
        assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
    }

    @Test
    fun `a capture ends after speech is followed by silence`() {
        val m = machine()
        m.arm()
        m.onFrame(quiet, false)          // STARTED

        repeat(20) { assertEquals(CaptureMachine.Step.CAPTURING, m.onFrame(speech, false)) }

        var step = CaptureMachine.Step.CAPTURING
        var frames = 0
        while (step == CaptureMachine.Step.CAPTURING && frames < 100) {
            step = m.onFrame(quiet, false)
            frames++
        }
        assertEquals(CaptureMachine.Step.FINISHED, step)
        assertEquals("end-of-speech", m.lastStopReason)
        assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
    }

    @Test
    fun `silence alone does not end a capture early`() {
        // Somebody who takes a moment before speaking must not be cut off.
        val m = machine()
        m.arm()
        m.onFrame(quiet, false)

        repeat(40) { assertEquals(CaptureMachine.Step.CAPTURING, m.onFrame(quiet, false)) }
        assertTrue(m.heardNothing())
    }

    @Test
    fun `a capture cannot run forever`() {
        val m = CaptureMachine(frameMillis = frameMs, maxCaptureMillis = 2_000)
        m.arm()
        m.onFrame(speech, false)

        var frames = 0
        var step = CaptureMachine.Step.CAPTURING
        while (step == CaptureMachine.Step.CAPTURING && frames < 1000) {
            step = m.onFrame(speech, false)
            frames++
        }
        assertEquals(CaptureMachine.Step.FINISHED, step)
        assertEquals("max-length", m.lastStopReason)
        assertTrue("stopped after ${frames * frameMs} ms", frames * frameMs <= 2_100)
    }

    @Test
    fun `a capture of pure silence still ends, and says so`() {
        val m = CaptureMachine(frameMillis = frameMs, maxCaptureMillis = 1_000)
        m.arm()
        m.onFrame(quiet, false)

        var step = CaptureMachine.Step.CAPTURING
        while (step == CaptureMachine.Step.CAPTURING) step = m.onFrame(quiet, false)

        assertEquals(CaptureMachine.Step.FINISHED, step)
        assertEquals("silence-timeout", m.lastStopReason)
        assertTrue(m.heardNothing())
    }

    @Test
    fun `it goes back to listening and can be armed again`() {
        val m = CaptureMachine(frameMillis = frameMs, maxCaptureMillis = 500)
        repeat(3) {
            m.arm()
            assertEquals(CaptureMachine.Step.STARTED, m.onFrame(quiet, false))
            var step = CaptureMachine.Step.CAPTURING
            while (step == CaptureMachine.Step.CAPTURING) step = m.onFrame(speech, false)
            assertEquals(CaptureMachine.Step.FINISHED, step)
            assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
        }
    }

    @Test
    fun `arming twice does not start two captures`() {
        val m = machine()
        m.arm()
        m.arm()
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(quiet, false))
        assertEquals(CaptureMachine.Step.CAPTURING, m.onFrame(speech, false))
    }

    @Test
    fun `a wake word during a capture does not restart it`() {
        val m = machine()
        m.arm()
        m.onFrame(quiet, false)
        assertEquals(CaptureMachine.Step.CAPTURING, m.onFrame(speech, true))
    }
}
