package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.MediaKinds
import com.mammonrn.phoneaikiosk.recorder.RecorderFlow
import com.mammonrn.phoneaikiosk.recorder.RecorderFlow.State
import com.mammonrn.phoneaikiosk.recorder.RecorderNames
import com.mammonrn.phoneaikiosk.recorder.RecorderNames.Problem
import com.mammonrn.phoneaikiosk.voice.MicTap
import com.mammonrn.phoneaikiosk.voice.WakeGate
import com.mammonrn.phoneaikiosk.voice.WakePause
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The voice recorder (0.61.0, DESIGN.md 5ฐ): its names, its list, and — Poom's
 * locked rule — the wake word rests exactly while it records and comes back
 * the moment it pauses or stops. Plus the shared microphone (MicTap).
 */
class RecorderRulesTest {

    private var now = 1_000L
    private val quieted = ArrayList<String>()

    @Before
    fun setUp() {
        WakePause.reset()
        WakePause.clock = { now }
        WakePause.post = { it() }
        WakePause.log = {}
    }

    @After
    fun tearDown() {
        WakePause.reset()
    }

    // ------------------------------------------------ names

    @Test
    fun `the default name says when, and sorts by date as text`() {
        assertEquals("บันทึก 2026-09-25 14.03", RecorderNames.defaultBase(2026, 9, 25, 14, 3))
        assertEquals("บันทึก 2026-01-05 09.00", RecorderNames.defaultBase(2026, 1, 5, 9, 0))
        assertTrue(RecorderNames.defaultBase(2026, 9, 25, 9, 5) < RecorderNames.defaultBase(2026, 9, 25, 14, 3))
    }

    @Test
    fun `the recordings are sound files the file manager and the music player know`() {
        for (ext in listOf("ogg", "m4a")) {
            assertTrue(ext, MediaKinds.isAudio("บันทึก 2026-09-25 14.03.$ext"))
            assertTrue(ext, RecorderNames.isRecording("x.$ext"))
        }
        assertFalse(RecorderNames.isRecording("notes.txt"))
        assertEquals("บันทึก 2026-09-25 14.03", RecorderNames.base("บันทึก 2026-09-25 14.03.ogg"))
    }

    @Test
    fun `two recordings in the same minute get their own names`() {
        val here = listOf("บันทึก 2026-09-25 14.03.ogg", "บันทึก 2026-09-25 14.03 (2).ogg", "other.txt")
        assertEquals("บันทึก 2026-09-25 14.03 (3)", RecorderNames.freeBase("บันทึก 2026-09-25 14.03", here))
        assertEquals("ใหม่", RecorderNames.freeBase("ใหม่", here))
        // A document of that name is not a recording: no clash.
        assertEquals("other", RecorderNames.freeBase("other", here))
    }

    @Test
    fun `a new name is checked before anything is renamed`() {
        val here = listOf("ประชุม.ogg", "เล่า.m4a", "ภาพ.jpg")
        assertEquals(Problem.EMPTY, RecorderNames.problem("   ", here))
        for (bad in listOf("a/b", "a\\b", "a:b", "a*b", "a?b", "a\"b", "a<b", "a>b", "a|b", "a\nb")) {
            assertEquals(bad, Problem.BAD_CHARACTER, RecorderNames.problem(bad, here))
        }
        assertEquals(Problem.DOTS, RecorderNames.problem(".", here))
        assertEquals(Problem.DOTS, RecorderNames.problem(".hidden", here))
        assertEquals(Problem.TOO_LONG, RecorderNames.problem("ก".repeat(41), here))
        assertNull(RecorderNames.problem("ก".repeat(40), here))
        // A clash is by the name a person sees, in any case, and any sound file.
        assertEquals(Problem.TAKEN, RecorderNames.problem("ประชุม", here))
        assertEquals(Problem.TAKEN, RecorderNames.problem(" เล่า ", here))
        assertNull(RecorderNames.problem("ภาพ", here))
        // Its own name is not a clash (saving without a change, or another case).
        assertNull(RecorderNames.problem("ประชุม", here, self = "ประชุม.ogg"))
        assertNull(RecorderNames.problem("ประชุม เช้า", here))
    }

    @Test
    fun `the list is newest first, and never jumps between equals`() {
        val items = listOf(RecorderNames.Item("b.ogg", 100, 1), RecorderNames.Item("c.ogg", 300, 1),
                           RecorderNames.Item("a.ogg", 100, 1), RecorderNames.Item("d.ogg", 200, 1))
        assertEquals(listOf("c.ogg", "d.ogg", "a.ogg", "b.ogg"), RecorderNames.newestFirst(items).map { it.name })
    }

    @Test
    fun `lengths and the level meter`() {
        assertEquals("0:00", RecorderNames.length(0))
        assertEquals("0:07", RecorderNames.length(7_900))
        assertEquals("12:03", RecorderNames.length(723_000))
        assertEquals("1:02:03", RecorderNames.length(3_723_000))
        assertEquals(0f, RecorderNames.level(0))
        assertEquals(1f, RecorderNames.level(32767))
        // A voice across the room (about 6% of full scale) is well on the meter, not nearly nothing.
        assertTrue(RecorderNames.level(2000) > 0.4f)
        assertTrue(RecorderNames.level(20) < RecorderNames.level(2000))
    }

    // ------------------------------------------------ Jarvis rests while recording, and only then

    private fun recorder() = RecorderFlow(object : WakePause.Media {
        override fun quietForJarvis() { quieted += "quiet" }
        override fun resumeAfterJarvis() { quieted += "resume" }
    })

    @Test
    fun `recording rests the wake word, and says why`() {
        val flow = recorder()
        assertFalse(WakePause.paused())
        assertTrue(flow.start())
        assertEquals(State.RECORDING, flow.state)
        assertTrue(WakePause.paused())
        assertEquals(WakePause.Source.RECORDER, WakePause.reason())
        assertEquals("บันทึกเสียง", WakePause.Source.RECORDER.word)
        // The detector is deaf; the Jarvis button is not.
        assertTrue(WakeGate.deaf(false, false, now, 0, WakePause.paused()))
        assertTrue(WakeGate.buttonMayStart(wakeOnly = false, machineListening = true))
    }

    @Test
    fun `stopping lets go at once`() {
        val flow = recorder()
        flow.start()
        assertTrue(flow.stop())
        assertEquals(State.IDLE, flow.state)
        assertFalse(WakePause.paused())
        assertFalse(flow.holding)
        assertFalse("nothing to stop twice", flow.stop())
    }

    @Test
    fun `pausing lets go, carrying on takes it again`() {
        val flow = recorder()
        flow.start()
        assertTrue(flow.pause())
        assertFalse(WakePause.paused())
        assertFalse(flow.tapping)
        assertTrue(flow.resume())
        assertTrue(WakePause.paused())
        assertTrue(flow.tapping)
        // Stopped from paused: nothing held, nothing released twice.
        flow.pause()
        assertTrue(flow.stop())
        assertFalse(WakePause.paused())
    }

    @Test
    fun `moves that make no sense do nothing`() {
        val flow = recorder()
        assertFalse(flow.pause())
        assertFalse(flow.resume())
        flow.start()
        assertFalse(flow.start())
        assertFalse(flow.resume())
        assertTrue(WakePause.paused())
    }

    @Test
    fun `renewed while recording it lasts, and a lapsed lease is taken again`() {
        val flow = recorder()
        flow.start()
        for (i in 1..10) {
            now += WakePause.RENEW_MS
            flow.renew()
        }
        assertTrue(WakePause.paused())
        // The main thread held up past the lease: it lapses, and the next renew takes it again.
        now += WakePause.LEASE_MS + 1
        assertFalse(WakePause.paused())
        flow.renew()
        assertTrue(flow.holding)
        assertTrue(WakePause.paused())
        // Renewing while paused never takes a hold.
        flow.pause()
        flow.renew()
        assertFalse(WakePause.paused())
    }

    @Test
    fun `a recorder that dies without letting go rests Jarvis for one lease at most`() {
        recorder().start()
        now += WakePause.LEASE_MS
        assertFalse(WakePause.paused())
    }

    @Test
    fun `a Jarvis question quiets the recorder, and the recorder stopping lets go`() {
        lateinit var flow: RecorderFlow
        flow = RecorderFlow(object : WakePause.Media {
            override fun quietForJarvis() { flow.stop() }
            override fun resumeAfterJarvis() = Unit
        })
        flow.start()
        WakePause.turnStarted()
        assertEquals(State.IDLE, flow.state)
        assertFalse(WakePause.paused())
        WakePause.turnEnded()
        // Started during a question: stopped at once, and no hold is left behind.
        WakePause.turnStarted()
        flow.start()
        assertEquals(State.IDLE, flow.state)
        assertFalse(flow.holding)
        assertFalse(WakePause.paused())
        WakePause.turnEnded()
    }

    @Test
    fun `the recorder rests Jarvis alongside music, and each lets go of its own`() {
        val flow = recorder()
        val music = WakePause.hold(WakePause.Source.MUSIC, null)
        flow.start()
        flow.stop()
        assertTrue("music still holds", WakePause.paused())
        WakePause.release(music)
        assertFalse(WakePause.paused())
    }

    // ------------------------------------------------ one microphone, shared

    @Test
    fun `the recorder reads the wake word's frames, and none during a Jarvis turn`() {
        val got = ArrayList<Int>()
        val sink = MicTap.Sink { _, count, _ -> got += count }
        val frame = ShortArray(1000)
        assertFalse("no recorder, nothing passed", MicTap.offer(frame, 1000, 0, turnBusy = false))
        MicTap.open(sink)
        assertTrue(MicTap.isOpen)
        assertTrue(MicTap.offer(frame, 1000, 10, turnBusy = false))
        assertFalse("a question is not recorded", MicTap.offer(frame, 1000, 10, turnBusy = true))
        assertFalse(MicTap.offer(frame, 0, 0, turnBusy = false))
        assertEquals(listOf(1000), got)
        assertEquals(1L, MicTap.passed)
        assertEquals(1L, MicTap.heldBack)
        // Only the sink that is open can close it.
        MicTap.close(MicTap.Sink { _, _, _ -> })
        assertTrue(MicTap.isOpen)
        MicTap.close(sink)
        assertFalse(MicTap.isOpen)
        assertFalse(MicTap.offer(frame, 1000, 10, turnBusy = false))
    }
}
