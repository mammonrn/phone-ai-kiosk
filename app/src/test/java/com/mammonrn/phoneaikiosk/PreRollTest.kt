package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Answer
import com.mammonrn.phoneaikiosk.voice.CaptureMachine
import com.mammonrn.phoneaikiosk.voice.KioskAction
import com.mammonrn.phoneaikiosk.voice.PreRoll
import com.mammonrn.phoneaikiosk.voice.SpokenAudio
import com.mammonrn.phoneaikiosk.voice.TurnPipeline
import com.mammonrn.phoneaikiosk.voice.VoiceSink
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * THE FIRST SYLLABLE (0.49.0): what was said after "Jarvis" and before the
 * detector fired goes in front of the recording. "เปิดไฟหน้าบ้าน" came back
 * "ปิดหน้าบ้าน" on production because "เปิ…" was never recorded.
 */
class PreRollTest {

    private val t = 1000          // the capture's speech threshold
    private val q = 100           // a quiet frame
    private val s = 5000          // a spoken one

    @Test
    fun `the command after the gap that follows Jarvis is kept`() {
        // "Hey Jarvis" · gap · "เปิ-ด" — the detector fires two frames into it.
        val peaks = intArrayOf(s, s, s, s, q, q, q, s, s)
        assertEquals(2, PreRoll.framesToKeep(peaks, t))
    }

    @Test
    fun `a dip between two words is not the gap`() {
        // "…Jarvis" · gap · "เปิด" dip "ไฟ": one quiet frame inside the command.
        val peaks = intArrayOf(s, s, q, q, s, s, q, s, s)
        assertEquals(5, PreRoll.framesToKeep(peaks, t))
    }

    @Test
    fun `nobody has started yet, nothing is kept`() {
        assertEquals(0, PreRoll.framesToKeep(intArrayOf(s, s, s, q, q), t))
        assertEquals(0, PreRoll.framesToKeep(intArrayOf(), t))
    }

    @Test
    fun `no gap at all keeps the fallback, never the whole second`() {
        val peaks = IntArray(16) { s }
        assertEquals(PreRoll.FALLBACK_FRAMES, PreRoll.framesToKeep(peaks, t))
        // A gap further back than the lookback does not count.
        val far = intArrayOf(q, q) + IntArray(12) { s }
        assertEquals(PreRoll.FALLBACK_FRAMES, PreRoll.framesToKeep(far, t))
    }

    @Test
    fun `the ring keeps the newest second, copied, and writes it oldest first`() {
        val ring = PreRoll(capacity = 3)
        val frame = ShortArray(2)
        for (i in 1..5) {
            frame[0] = i.toShort(); frame[1] = (-i).toShort()
            ring.add(frame, 2, i * 100)                 // the recorder reuses its array
        }
        assertEquals(3, ring.size)
        assertArrayEquals(intArrayOf(300, 400, 500), ring.peaks())
        val out = ByteArrayOutputStream()
        ring.writeNewest(2, out)
        val pcm = out.toByteArray()
        assertEquals(8, pcm.size)                          // two frames of two 16-bit samples
        assertEquals(4, pcm[0].toInt())                    // frame 4 first, little-endian
        assertEquals(5, pcm[4].toInt())
        ring.clear()
        assertEquals(0, ring.size)
    }

    @Test
    fun `a capture that already holds speech is not cancelled as no-speech`() {
        val m = CaptureMachine(frameMillis = 62, speechWaitMillis = 2_500)
        repeat(20) { m.onFrame(q, false) }
        assertEquals(CaptureMachine.Step.STARTED, m.onFrame(s, true))
        m.speechAlreadyStarted()
        // The command ended under the beep's guard; then the room is quiet.
        val steps = (1..60).map { m.onFrame(q, false) }
        assertTrue("finished, not cancelled", CaptureMachine.Step.FINISHED in steps)
        assertFalse(CaptureMachine.Step.CANCELLED in steps)
        // Without it, the same capture is thrown away.
        val n = CaptureMachine(frameMillis = 62, speechWaitMillis = 2_500)
        repeat(20) { n.onFrame(q, false) }
        n.onFrame(s, true)
        assertTrue(CaptureMachine.Step.CANCELLED in (1..60).map { n.onFrame(q, false) })
    }

    @Test
    fun `only a wake word capture gets a pre-roll, and it is dropped when deaf`() {
        val service = listOf(File("src/main/java/com/mammonrn/phoneaikiosk/voice/VoiceService.kt"),
                             File("app/src/main/java/com/mammonrn/phoneaikiosk/voice/VoiceService.kt"))
            .first { it.exists() }.readText()
        assertTrue("if (machine.startedByWakeWord && VoiceState.preRoll)" in service)
        assertTrue("if (deaf) preRoll.clear() else preRoll.add(frame, read, peak)" in service)
    }
}

/** The alarms are done before the reply is said, and a failure replaces it (0.49.0). */
class AlarmTruthTest {

    private class Sink : VoiceSink {
        override var stt = "idle"
        override var chat = "idle"
        override var tts = "idle"
        override var heard = ""
        override var reply = ""
        override var lastError = ""
    }

    private fun run(action: KioskAction, failure: String?): Pair<Sink, List<String>> {
        val sink = Sink()
        val said = mutableListOf<String>()
        var performed = 0
        TurnPipeline(
            transcribe = { "เปิดไฟแต่ปลุก แถม" },
            ask = { _, _ -> Answer("เปิดปลุก ไฟแต่ปลุกแถม แล้วครับ", "c1", action) },
            speak = { text -> said += text; SpokenAudio(ByteArray(2000), "") },
            play = { true },
            sayLocally = { text -> said += "local:$text"; true },
            perform = { performed += 1; failure },
            state = sink,
            log = {},
        ).run(ByteArray(44 + 4000), null)
        assertEquals("performed once", 1, performed)
        return sink to said
    }

    @Test
    fun `an alarm that is not there is said as not there, never as done`() {
        val (sink, said) = run(KioskAction(KioskAction.ALARM_ENABLE, "",
            mapOf("target" to "ไฟแต่ปลุกแถม", "enabled" to "true")), "ไม่พบการปลุกนั้นครับ")
        assertEquals(listOf("ไม่พบการปลุกนั้นครับ"), said)
        assertEquals("ไม่พบการปลุกนั้นครับ", sink.reply)
    }

    @Test
    fun `an alarm that is there is changed first, then the reply is said`() {
        val (sink, said) = run(KioskAction(KioskAction.SET_ALARM, "",
            mapOf("time" to "11:00", "label" to "")), null)
        assertEquals(listOf("เปิดปลุก ไฟแต่ปลุกแถม แล้วครับ"), said)
        assertEquals("", sink.lastError)
    }
}
