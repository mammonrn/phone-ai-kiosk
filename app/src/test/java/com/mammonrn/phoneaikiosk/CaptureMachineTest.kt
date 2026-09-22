package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.CaptureMachine
import com.mammonrn.phoneaikiosk.voice.Recorder
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
        // CHANGED IN versionCode 8. A finished capture no longer goes back to
        // listening: the question still has to be transcribed, asked and
        // spoken, and a wake word during any of that used to start a second
        // turn that recorded the first one's answer. The machine is held until
        // the service reports the turn over.
        assertEquals(CaptureMachine.Mode.BUSY, m.mode)
        m.turnFinished()
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
    fun `a capture of pure silence is thrown away rather than sent`() {
        val m = CaptureMachine(frameMillis = frameMs, maxCaptureMillis = 1_000)
        m.arm()
        m.onFrame(quiet, false)

        var step = CaptureMachine.Step.CAPTURING
        while (step == CaptureMachine.Step.CAPTURING) step = m.onFrame(quiet, false)

        // CHANGED IN versionCode 8. This used to be FINISHED with the reason
        // "silence-timeout", which meant a recording of nothing was uploaded,
        // transcribed and asked about — which is exactly what the A07 did when
        // Poom said "Hey Jarvis" and then nothing. Now nothing leaves the phone.
        assertEquals(CaptureMachine.Step.CANCELLED, step)
        assertEquals("no-speech-after-wake", m.lastStopReason)
        assertTrue(m.heardNothing())
        assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
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
            assertEquals(CaptureMachine.Mode.BUSY, m.mode)
            // The service releases it once the answer has been spoken.
            m.turnFinished()
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

/**
 * The versionCode 7 bugs: turns stacking on each other, and a television
 * keeping the microphone open.
 *
 * Both came off the A07 with logs, and both are reproduced here as failures of
 * this class before they are fixed in it.
 */
class CaptureMachineTurnLockTest {

    private val frameMs = 62
    private val quiet = 100
    private val speech = 9000

    /** A television: loud, constant, and never a question. */
    private val television = 3000

    private fun machine(
        speechWaitMillis: Int = 2_500,
        maxCaptureMillis: Int = 12_000,
    ) = CaptureMachine(
        frameMillis = frameMs,
        maxCaptureMillis = maxCaptureMillis,
        speechWaitMillis = speechWaitMillis,
    )

    private fun CaptureMachine.frames(n: Int, peak: Int, wake: Boolean = false) =
        (1..n).map { onFrame(peak, wake) }

    // ---- the single-turn lock ----------------------------------------------

    @Test
    fun `a wake word during a turn cannot start a second one`() {
        val m = machine()
        m.frames(20, quiet)                       // settle the ambient level
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(quiet, true))
        // Long enough to outlast the acknowledgement-tone guard and then be
        // heard: the first GUARD_MILLIS of every capture is the kiosk's own
        // beep and cannot count as the question starting.
        m.frames(15, speech)
        // End the question.
        var step = CaptureMachine.Step.CAPTURING
        repeat(40) { if (step != CaptureMachine.Step.FINISHED) step = m.onFrame(quiet, false) }
        assertEquals(CaptureMachine.Step.FINISHED, step)

        // THE BUG. At this point versionCode 7 was back in LISTENING while the
        // answer was still being transcribed, asked and spoken — so this frame
        // started a whole second turn, and its capture recorded the first
        // answer coming out of the speaker.
        assertEquals(CaptureMachine.Mode.BUSY, m.mode)
        repeat(50) {
            assertEquals("a wake word during the turn must be ignored",
                         CaptureMachine.Step.IDLE, m.onFrame(speech, true))
        }
        assertEquals(CaptureMachine.Mode.BUSY, m.mode)
    }

    @Test
    fun `the lock only comes off when the service says the turn is over`() {
        val m = machine()
        m.frames(20, quiet)
        m.onFrame(quiet, true)
        m.frames(15, speech)
        repeat(40) { m.onFrame(quiet, false) }
        assertEquals(CaptureMachine.Mode.BUSY, m.mode)

        m.turnFinished()
        assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(quiet, true))
    }

    @Test
    fun `arming during a turn is dropped rather than queued`() {
        val m = machine()
        m.frames(20, quiet)
        m.onFrame(quiet, true)
        m.frames(15, speech)
        repeat(40) { m.onFrame(quiet, false) }

        m.arm()
        assertEquals(CaptureMachine.Step.IDLE, m.onFrame(quiet, false))
        m.turnFinished()
        // Not queued: the person is not waiting for a capture they asked for
        // several seconds and one whole answer ago.
        assertEquals(CaptureMachine.Step.IDLE, m.onFrame(quiet, false))
    }

    // ---- the wake word followed by nothing ---------------------------------

    @Test
    fun `the wake word then silence cancels instead of sending anything`() {
        val m = machine(speechWaitMillis = 2_000)
        m.frames(20, quiet)
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(quiet, true))

        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        var frames = 0
        while (step == CaptureMachine.Step.CAPTURING && frames < 200) {
            step = m.onFrame(quiet, false)
            frames += 1
        }

        assertEquals(CaptureMachine.Step.CANCELLED, step)
        assertEquals("no-speech-after-wake", m.lastStopReason)
        // Straight back to listening: there is no turn to wait for, because
        // nothing is going to be transcribed or asked.
        assertEquals(CaptureMachine.Mode.LISTENING, m.mode)
        assertTrue("cancelled within the wait window", frames * frameMs <= 2_500)
    }

    @Test
    fun `a cancelled capture is never reported as finished`() {
        val m = machine(speechWaitMillis = 500)
        m.frames(20, quiet)
        m.onFrame(quiet, true)
        val steps = m.frames(30, quiet)
        assertFalse("nothing may be sent for transcription",
                    steps.contains(CaptureMachine.Step.FINISHED))
        assertTrue(steps.contains(CaptureMachine.Step.CANCELLED))
    }

    // ---- the television ----------------------------------------------------

    @Test
    fun `a television does not hold the microphone open to the timeout`() {
        val m = machine(speechWaitMillis = 2_000, maxCaptureMillis = 12_000)
        // The room has had the television on for a while before the wake word.
        m.frames(60, television)
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(television, true))

        // The bar is now above the television, so its noise is not "speech".
        assertTrue("threshold ${m.speechThreshold} should be above the television",
                   m.speechThreshold > television)

        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        var frames = 0
        while (step == CaptureMachine.Step.CAPTURING && frames < 400) {
            step = m.onFrame(television, false)
            frames += 1
        }

        assertEquals("the television alone must cancel, not record for 12 seconds",
                     CaptureMachine.Step.CANCELLED, step)
        assertEquals("no-speech-after-wake", m.lastStopReason)
        assertTrue("stopped after ${frames * frameMs} ms, the cap is 12000",
                   frames * frameMs < 3_000)
    }

    @Test
    fun `somebody speaking over a television is still heard`() {
        val m = machine()
        m.frames(60, television)
        m.onFrame(television, true)

        // A person addressing the kiosk is louder than the television behind
        // them; the whole point of the margin is that this still gets through.
        val steps = m.frames(10, speech)
        assertTrue(steps.all { it == CaptureMachine.Step.CAPTURING })

        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        repeat(60) { if (step != CaptureMachine.Step.FINISHED) step = m.onFrame(television, false) }
        assertEquals("their question must be sent", CaptureMachine.Step.FINISHED, step)
        assertEquals("end-of-speech", m.lastStopReason)
    }

    @Test
    fun `a quiet room does not become so sensitive that hiss counts as speech`() {
        val m = machine()
        m.frames(60, 10)        // nearly silent
        m.onFrame(10, true)
        // The floor holds, so room hiss cannot start a recording that runs on.
        assertEquals(Recorder.SPEECH_THRESHOLD, m.speechThreshold)
        val steps = m.frames(20, 50)
        assertFalse(steps.contains(CaptureMachine.Step.FINISHED))
    }

    /**
     * REPLACES an exponential-average test. versionCode 10 tracked the room
     * with an EMA that rose fast, and it rose fast enough to swallow the wake
     * word before the wake word was recognised — which set the bar for the
     * question at two and a half times the voice that asked for it. The room is
     * now a median over frames that predate the wake word.
     */
    @Test
    fun `the room level is a median and ignores brief noises`() {
        val m = machine()
        // A quiet room with one slammed door in it.
        m.frames(40, 120)
        m.onFrame(28000, false)
        m.frames(40, 120)
        assertEquals("one loud frame must not move a median", 120, m.currentRoomLevel())
    }

    @Test
    fun `the room level follows a television that stays on`() {
        val m = machine()
        m.frames(20, 120)
        assertEquals(120, m.currentRoomLevel())
        m.frames(60, 3000)              // the television, continuously
        assertEquals("a sustained change must move it", 3000, m.currentRoomLevel())
    }

    /**
     * THE versionCode 10 BUG, as a test.
     *
     * The wake word is loud and immediately precedes the trigger. If those
     * frames reach the calculation, the threshold is built from the voice of
     * the person asking rather than from the room, and their question cannot
     * clear it. On the A07 that produced ambient=9742 and threshold=24355 in a
     * room that was not remotely that loud.
     */
    @Test
    fun `the wake word does not set the bar against the question after it`() {
        val m = machine()
        m.frames(50, 300)                       // a quiet room
        m.frames(16, 12000)                     // "Hey Jarvis", loudly
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(12000, true))

        assertTrue("ambient ${m.ambientLevel()} came from the wake word, not the room",
                   m.ambientLevel() < 1000)
        assertTrue("threshold ${m.speechThreshold} is too high for an ordinary question",
                   m.speechThreshold < 4000)

        // And the question that follows is heard rather than cancelled.
        m.frames(6, 300)                        // the beep guard
        val steps = m.frames(20, 7000)
        assertTrue(steps.all { it == CaptureMachine.Step.CAPTURING })
        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        repeat(60) { if (step != CaptureMachine.Step.FINISHED) step = m.onFrame(300, false) }
        assertEquals(CaptureMachine.Step.FINISHED, step)
        assertEquals("end-of-speech", m.lastStopReason)
    }

    /** The beep the kiosk plays must not be mistaken for the question. */
    @Test
    fun `the acknowledgement tone is not the start of speech`() {
        val m = machine()
        m.frames(60, 200)
        m.onFrame(200, true)

        // The tone, loud, right at the start of the capture.
        val duringGuard = m.frames(4, 30000)
        assertTrue("the tone must not start the question",
                   duringGuard.all { it == CaptureMachine.Step.CAPTURING })
        assertTrue("and it must not have counted as speech", m.heardNothing())

        // Then nothing. The capture is cancelled, not sent.
        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        var frames = 0
        while (step == CaptureMachine.Step.CAPTURING && frames < 200) {
            step = m.onFrame(200, false); frames += 1
        }
        assertEquals(CaptureMachine.Step.CANCELLED, step)
        assertEquals("no-speech-after-wake", m.lastStopReason)
    }

    @Test
    fun `the peak heard while waiting is recorded for diagnosis`() {
        val m = machine()
        m.frames(60, 200)
        m.onFrame(200, true)
        m.frames(6, 200)                      // past the guard
        m.frames(4, 1500)                     // somebody spoke, but too quietly
        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        var frames = 0
        while (step == CaptureMachine.Step.CAPTURING && frames < 200) {
            step = m.onFrame(200, false); frames += 1
        }
        assertEquals(CaptureMachine.Step.CANCELLED, step)
        // This is what says "somebody DID speak and was not heard" rather than
        // "the room was silent", which is the difference between lowering the
        // margin and looking somewhere else entirely.
        assertEquals(1500, m.peakWhileWaiting)
    }

    @Test
    fun `an unknown room gets the floor and not a guess`() {
        val m = machine()
        // Triggered immediately, with no history at all: the only frames that
        // exist are the wake word, and their median is the wake word.
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(20000, true))
        assertEquals(0, m.ambientLevel())
        assertEquals(Recorder.SPEECH_THRESHOLD, m.speechThreshold)
    }

    /** A hard cap has to hold whatever the wait is set to. */
    @Test
    fun `the maximum length outlasts no wait window`() {
        val m = CaptureMachine(frameMillis = frameMs, maxCaptureMillis = 1_000,
                               speechWaitMillis = 9_000)
        m.frames(60, 200)
        m.onFrame(200, true)
        var step: CaptureMachine.Step = CaptureMachine.Step.CAPTURING
        var frames = 0
        while (step == CaptureMachine.Step.CAPTURING && frames < 500) {
            step = m.onFrame(200, false); frames += 1
        }
        assertEquals(CaptureMachine.Step.CANCELLED, step)
        assertTrue("stopped after ${frames * frameMs} ms, the cap is 1000",
                   frames * frameMs <= 1_100)
    }
}
