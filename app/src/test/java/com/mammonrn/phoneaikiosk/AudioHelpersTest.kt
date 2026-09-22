package com.mammonrn.phoneaikiosk

import android.media.MediaRecorder
import com.mammonrn.phoneaikiosk.voice.AudioHelpers
import com.mammonrn.phoneaikiosk.voice.Recorder
import java.util.concurrent.atomic.AtomicInteger
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The microphone switches, and the one property they must not break.
 *
 * The effects themselves live in the phone's audio DSP and cannot be exercised
 * off-device — `AcousticEchoCanceler.isAvailable()` is an empty stub in a JVM
 * test. What CAN be tested here is the part that would actually break the
 * kiosk: whether asking for a different source or a different effect can leave
 * two AudioRecords open at once.
 */
class AudioHelpersTest {

    // ------------------------------------------------------------- the sources

    @Test
    fun `the offered sources are the ones worth measuring`() {
        assertEquals(
            setOf("voice_recognition", "voice_communication", "mic"),
            AudioHelpers.SOURCES.keys,
        )
        assertEquals(
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            AudioHelpers.SOURCES["voice_recognition"],
        )
        assertEquals(
            MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            AudioHelpers.SOURCES["voice_communication"],
        )
    }

    @Test
    fun `the default source is unchanged and is what the dump reports`() {
        assertEquals("voice_recognition", AudioHelpers.DEFAULT_SOURCE)
        assertEquals(
            "voice_recognition",
            AudioHelpers.sourceName(MediaRecorder.AudioSource.VOICE_RECOGNITION),
        )
        // An unrecognised value is reported as itself rather than guessed at.
        assertTrue(AudioHelpers.sourceName(9999).startsWith("unknown("))
    }

    @Test
    fun `every effect starts off, and the defaults are not changed by anything here`() {
        val helpers = AudioHelpers()
        assertEquals("echo=off noise=off gain=off", helpers.requested())
    }

    @Test
    fun `a request is remembered so it survives the recorder reopening`() {
        val helpers = AudioHelpers()
        helpers.wantEcho = true
        assertEquals("echo=on noise=off gain=off", helpers.requested())
        helpers.wantNoise = true
        helpers.wantEcho = false
        assertEquals("echo=off noise=on gain=off", helpers.requested())
    }

    @Test
    fun `availability and enabled are reported separately`() {
        // Off-device both come back false, which is the point: a device can
        // advertise an effect and still fail to create it, and a report that
        // collapsed the two would claim "on" for something never attached.
        val states = AudioHelpers().states()
        assertEquals(listOf("echo-canceler", "noise-suppressor", "auto-gain"),
                     states.map { it.name })
        assertTrue(states.all { !it.enabled })
    }

    // ---------------------------------------------- the invariant that matters

    /** Counts how many sessions are open at once, and remembers the worst. */
    private class SessionCounter {
        val open = AtomicInteger(0)
        val peak = AtomicInteger(0)
        var opened = 0

        fun session(onRead: () -> Int): Recorder.Session {
            opened += 1
            val now = open.incrementAndGet()
            peak.updateAndGet { maxOf(it, now) }
            return object : Recorder.Session {
                override fun read(into: ShortArray) = onRead()
                override fun close() { open.decrementAndGet() }
            }
        }
    }

    @Test
    fun `switching the source never leaves two microphones open`() {
        val recorder = Recorder()
        val counter = SessionCounter()
        var frames = 0

        recorder.runLoop(
            shouldStop = { frames >= 60 },
            openSession = {
                counter.session {
                    frames += 1
                    // Ask for a different source part-way through, the way the
                    // adb switch does: set a flag and let the loop act on it.
                    if (frames == 10 || frames == 30) recorder.reopenRequested = true
                    16
                }
            },
            onFrame = { _, _ -> true },
        )

        assertEquals("a session was left open", 0, counter.open.get())
        assertEquals("two microphones were open at once", 1, counter.peak.get())
        // It really did reopen rather than ignoring the request.
        assertEquals(3, counter.opened)
    }

    @Test
    fun `switching an effect also reopens exactly once per request`() {
        val recorder = Recorder()
        val counter = SessionCounter()
        var frames = 0

        recorder.runLoop(
            shouldStop = { frames >= 40 },
            openSession = {
                counter.session {
                    frames += 1
                    if (frames == 5) {
                        recorder.helpers.wantEcho = true
                        recorder.reopenRequested = true
                    }
                    16
                }
            },
            onFrame = { _, _ -> true },
        )

        assertEquals(0, counter.open.get())
        assertEquals(1, counter.peak.get())
        assertEquals(2, counter.opened)
        assertEquals("echo=on noise=off gain=off", recorder.helpers.requested())
    }

    @Test
    fun `the session is closed even when a frame handler stops the loop`() {
        val recorder = Recorder()
        val counter = SessionCounter()
        var frames = 0

        recorder.runLoop(
            shouldStop = { false },
            openSession = { counter.session { frames += 1; 16 } },
            // Returning false is how the loop is stopped from inside.
            onFrame = { _, _ -> frames < 5 },
        )

        assertEquals("the microphone was not released on the way out",
                     0, counter.open.get())
    }

    @Test
    fun `a reopen request is cleared so the loop does not spin`() {
        val recorder = Recorder()
        val counter = SessionCounter()
        var frames = 0

        recorder.reopenRequested = true
        recorder.runLoop(
            shouldStop = { frames >= 20 },
            openSession = { counter.session { frames += 1; 16 } },
            onFrame = { _, _ -> true },
        )

        // One open, not twenty: the flag is consumed when the session opens.
        assertEquals(1, counter.opened)
    }
}
