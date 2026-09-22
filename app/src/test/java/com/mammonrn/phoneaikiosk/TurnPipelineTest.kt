package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.SpokenAudio
import com.mammonrn.phoneaikiosk.voice.TurnPipeline
import com.mammonrn.phoneaikiosk.voice.VoiceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** Somewhere to watch the transitions the screen shows. */
private class Sink : VoiceSink {
    override var stt = "idle"
    override var chat = "idle"
    override var tts = "idle"
    override var heard = ""
    override var reply = ""
    override var lastError = ""
    val trail = mutableListOf<String>()
    fun snapshot() = "stt=$stt chat=$chat tts=$tts"
}

private const val WAV_HEADER = 44

class TurnPipelineTest {

    private fun wav(bytes: Int = 4000) = ByteArray(WAV_HEADER + bytes)

    private fun pipeline(
        sink: Sink,
        transcribe: (ByteArray) -> String = { "วันนี้อากาศเป็นยังไง" },
        ask: (String, String?) -> Pair<String, String> = { _, _ -> "ผมยังดูให้ไม่ได้ครับ" to "c1" },
        speak: (String) -> SpokenAudio = { SpokenAudio(ByteArray(2000), "") },
        play: (ByteArray) -> Boolean = { true },
        sayLocally: (String) -> Boolean = { true },
    ) = TurnPipeline(transcribe, ask, speak, play, sayLocally, sink) { sink.trail.add(it) }

    @Test
    fun `a good turn goes all the way through`() {
        val sink = Sink()
        val (outcome, conversation) = pipeline(sink).run(wav(), null)

        assertEquals(TurnPipeline.Outcome.COMPLETED, outcome)
        assertEquals("c1", conversation)
        assertEquals("stt=ok chat=ok tts=ok", sink.snapshot())
        assertEquals("วันนี้อากาศเป็นยังไง", sink.heard)
        assertEquals("ผมยังดูให้ไม่ได้ครับ", sink.reply)
    }

    @Test
    fun `an empty recording never reaches the network`() {
        val sink = Sink()
        var called = false
        val outcome = pipeline(sink, transcribe = { called = true; "" })
            .run(ByteArray(WAV_HEADER), null).first

        assertEquals(TurnPipeline.Outcome.EMPTY_AUDIO, outcome)
        assertFalse("silence must not be uploaded", called)
        assertEquals("empty", sink.stt)
    }

    @Test
    fun `a failing transcription stops there and says so out loud`() {
        val sink = Sink()
        var spoken: String? = null
        val outcome = pipeline(
            sink,
            transcribe = { throw Broker.Failure(502, "stt_error", "ถอดเสียงไม่สำเร็จครับ") },
            sayLocally = { spoken = it; true },
        ).run(wav(), null).first

        assertEquals(TurnPipeline.Outcome.FAILED, outcome)
        assertEquals("error", sink.stt)
        assertEquals("idle", sink.chat)
        assertEquals("ถอดเสียงไม่สำเร็จครับ", spoken)
        assertTrue(sink.lastError.contains("stt_error"))
    }

    @Test
    fun `an exhausted budget is read out in the broker's own words`() {
        val sink = Sink()
        var spoken: String? = null
        val message = "งบค่าใช้งานของเดือนนี้หมดแล้วครับ ระบบจะกลับมาใช้ได้อีกครั้งวันที่ 1 ของเดือนหน้า"
        pipeline(
            sink,
            ask = { _, _ -> throw Broker.Failure(402, "budget_exhausted", message) },
            sayLocally = { spoken = it; true },
        ).run(wav(), null)

        assertEquals(message, spoken)
        assertTrue(sink.lastError.contains("budget_exhausted"))
    }

    @Test
    fun `when cloud speech fails the answer is still spoken`() {
        val sink = Sink()
        var spoken: String? = null
        val outcome = pipeline(
            sink,
            speak = { throw Broker.Failure(502, "tts_error", "สร้างเสียงไม่สำเร็จครับ") },
            sayLocally = { spoken = it; true },
        ).run(wav(), null).first

        assertEquals(TurnPipeline.Outcome.SPOKEN_LOCALLY, outcome)
        assertEquals("device-fallback", sink.tts)
        assertEquals("ผมยังดูให้ไม่ได้ครับ", spoken)
    }

    @Test
    fun `when playback fails the answer is still spoken`() {
        val sink = Sink()
        val outcome = pipeline(sink, play = { false }).run(wav(), null).first
        assertEquals(TurnPipeline.Outcome.SPOKEN_LOCALLY, outcome)
        assertEquals("device-fallback", sink.tts)
    }

    @Test
    fun `when even the device voice fails it is reported and not pretended`() {
        val sink = Sink()
        val outcome = pipeline(sink, play = { false }, sayLocally = { false })
            .run(wav(), null).first
        assertEquals(TurnPipeline.Outcome.FAILED, outcome)
        assertEquals("failed", sink.tts)
    }

    @Test
    fun `the conversation id is carried forward and an empty one does not erase it`() {
        val sink = Sink()
        val kept = pipeline(sink, ask = { _, _ -> "ครับ" to "" }).run(wav(), "existing").second
        assertEquals("existing", kept)
    }

    /**
     * The bug this file now guards against.
     *
     * The A07 logged "tts ok 28813 bytes 9343 ms" and that was read as nine
     * seconds of synthesis. It was not: one timer was wrapped around synthesis
     * AND playback, and playback blocks until the audio has finished playing.
     *
     * The stubs here are deliberately lopsided — synthesis returns at once, and
     * "playing" takes a measurable fraction of a second. Under the old single
     * timer both would have been added into one number with no way to tell them
     * apart, which is exactly how a long answer came to look like a slow vendor.
     */
    @Test
    fun `synthesis and playback are timed separately`() {
        val sink = Sink()
        val playFor = 250L

        pipeline(
            sink,
            speak = { SpokenAudio(ByteArray(28813), "broker=40ms vendor=35ms audio=9600ms") },
            play = { Thread.sleep(playFor); true },
        ).run(wav(), null)

        val line = sink.trail.single { it.startsWith("tts ok") }

        val synth = Regex("synth=(\\d+)ms").find(line)!!.groupValues[1].toLong()
        val play = Regex(" play=(\\d+)ms").find(line)!!.groupValues[1].toLong()

        assertTrue("playback should be the big number, got: $line", play >= playFor)
        assertTrue("synthesis must not include playback, got: $line", synth < playFor)

        // And the server's own account of itself rides along, so one line on the
        // phone says which layer was slow.
        assertTrue(line.contains("vendor=35ms"))
        assertTrue(line.contains("audio=9600ms"))
    }

    /** While the audio plays, the screen says so rather than still saying "synthesising". */
    @Test
    fun `the state says speaking while the audio is playing`() {
        val sink = Sink()
        val seen = mutableListOf<String>()

        pipeline(sink, play = { seen.add(sink.tts); true }).run(wav(), null)

        assertEquals(listOf("speaking"), seen)
        assertEquals("ok", sink.tts)
    }

    /**
     * The backstop for what the capture stage lets through.
     *
     * On the A07 a wake word with no question behind it still produced
     * "stt ok ... 10 chars" and then a model call and a spoken answer. The
     * capture stage now cancels that before it is ever uploaded; this is what
     * happens when something clears that bar and still is not a question.
     */
    @Test
    fun `an empty transcript is never asked and never spoken`() {
        val sink = Sink()
        var asked = false
        var spoke = false

        val (outcome, _) = pipeline(
            sink,
            transcribe = { "  " },
            ask = { _, _ -> asked = true; "ไม่ควรถูกเรียก" to "c1" },
            speak = { spoke = true; SpokenAudio(ByteArray(10), "") },
        ).run(wav(), null)

        assertEquals(TurnPipeline.Outcome.NO_QUESTION, outcome)
        assertFalse("the model must not be asked", asked)
        assertFalse("nothing must be spoken", spoke)
        assertEquals("too-short", sink.stt)
    }

    @Test
    fun `a one character transcript is treated the same way`() {
        val sink = Sink()
        var asked = false
        val (outcome, _) = pipeline(
            sink,
            transcribe = { "อ" },
            ask = { _, _ -> asked = true; "x" to "c1" },
        ).run(wav(), null)

        assertEquals(TurnPipeline.Outcome.NO_QUESTION, outcome)
        assertFalse(asked)
    }

    /**
     * The bar has to sit below real Thai questions, which get very short.
     * "กี่โมง" is six characters and is a perfectly ordinary thing to ask.
     */
    @Test
    fun `a short but real thai question still goes through`() {
        val sink = Sink()
        var asked = false
        val (outcome, _) = pipeline(
            sink,
            transcribe = { "กี่โมง" },
            ask = { _, _ -> asked = true; "บ่ายโมงครับ" to "c1" },
        ).run(wav(), null)

        assertEquals(TurnPipeline.Outcome.COMPLETED, outcome)
        assertTrue("a real question must be asked", asked)
    }

    @Test
    fun `a cancelled turn says nothing out loud`() {
        val sink = Sink()
        var spokeLocally = false
        pipeline(
            sink,
            transcribe = { "" },
            sayLocally = { spokeLocally = true; true },
        ).run(wav(), null)

        // Not even an apology: the person said the wake word and did not ask
        // anything, and a kiosk that announces that every time is a kiosk
        // nobody wants in the kitchen.
        assertFalse(spokeLocally)
        assertEquals("", sink.reply)
    }

    @Test
    fun `the log carries lengths and never the words`() {
        val sink = Sink()
        pipeline(sink).run(wav(), null)

        val logged = sink.trail.joinToString(" ")
        assertFalse(logged.contains("วันนี้อากาศเป็นยังไง"))
        assertFalse(logged.contains("ผมยังดูให้ไม่ได้ครับ"))
        assertTrue("expected byte and char counts, got: $logged", logged.contains("chars"))
        assertTrue(logged.contains("ms"))
    }
}
