package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.HeyJarvisDetector
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test

/**
 * Holds the Kotlin wake word pipeline to numbers openWakeWord produced.
 *
 * The detector is a re-implementation of somebody else's design, and the only
 * way to know a re-implementation is right is to compare it with the original.
 * `wakeword/make_parity_fixture.py` runs the real Python openWakeWord over fixed
 * audio and writes every score down; this feeds the same samples through the
 * same three ONNX files and expects the same numbers back.
 *
 * WHAT A FAILURE HERE MEANS: the phone is not hearing what openWakeWord hears,
 * so its thresholds, its false-wake rate and its recall are all somebody else's
 * numbers that no longer apply. It is not a rounding problem to wave through.
 */
class WakeWordParityTest {

    private val models = File("src/main/assets/models")
    private val fixtures = File("src/test/resources/parity")

    private fun detector(threshold: Float = 0.5f, cooldownFrames: Int = 25) =
        HeyJarvisDetector.fromBytes(
            File(models, "melspectrogram.onnx").readBytes(),
            File(models, "embedding_model.onnx").readBytes(),
            File(models, "hey_jarvis_v0.1.onnx").readBytes(),
            threshold = threshold, cooldownFrames = cooldownFrames,
        )

    /** The fixture's 16 kHz mono PCM, as shorts. */
    private fun audio(): ShortArray {
        val bytes = File(fixtures, "audio.wav").readBytes()
        val out = ShortArray((bytes.size - WAV_HEADER) / 2)
        for (i in out.indices) {
            val lo = bytes[WAV_HEADER + i * 2].toInt() and 0xFF
            val hi = bytes[WAV_HEADER + i * 2 + 1].toInt()
            out[i] = ((hi shl 8) or lo).toShort()
        }
        return out
    }

    private fun expected(): Map<String, String> {
        // A tiny reader rather than a JSON dependency: the fixture is written by
        // our own script, one value per line, and a test that needs a library to
        // read four fields is a test with more to go wrong.
        val text = File(fixtures, "expected.json").readText()
        return Regex("\"([a-z_0-9]+)\"\\s*:\\s*(\\[[^\\]]*\\]|\\[\\[.*?\\]\\]|[^,\\n}]+)",
                     RegexOption.DOT_MATCHES_ALL)
            .findAll(text).associate { it.groupValues[1] to it.groupValues[2].trim() }
    }

    /** The two mel frames from the fixture, as 2 x 32 floats. */
    private fun melFixture(): List<FloatArray> {
        val text = File(fixtures, "expected.json").readText()
        val body = text.substringAfter("\"mel_first_frames\":").substringAfter("[")
        // Each inner [...] is one frame. Taken by bracket rather than by regex
        // over the whole file, because a regex that has to know where one array
        // ends and the next begins is a regex that will read the wrong numbers.
        return Regex("\\[([^\\[\\]]+)\\]").findAll(body).take(2)
            .map { match -> numbers(match.groupValues[1]).toFloatArray() }
            .toList()
    }

    private fun numbers(raw: String): List<Float> =
        Regex("-?\\d+\\.?\\d*(?:[eE][-+]?\\d+)?").findAll(raw).map { it.value.toFloat() }.toList()

    private fun haveFixtures(): Boolean =
        models.isDirectory && File(fixtures, "audio.wav").isFile

    /** Score by score, against the Python original. */
    @Test
    fun `the kotlin pipeline scores what openWakeWord scores`() {
        assumeTrue("fixtures missing", haveFixtures())
        val fields = expected()
        val want = numbers(fields.getValue("scores_1280"))
        val from = fields.getValue("compare_from").trim().toInt()
        val samples = audio()

        val subject = detector(threshold = 2f)   // never fires; we want the scores
        val got = mutableListOf<Float>()
        var at = 0
        while (at + HeyJarvisDetector.CHUNK <= samples.size) {
            subject.accept(samples.copyOfRange(at, at + HeyJarvisDetector.CHUNK),
                           HeyJarvisDetector.CHUNK)
            got.add(subject.lastScore)
            at += HeyJarvisDetector.CHUNK
        }
        subject.close()

        assertEquals("chunk count", want.size, got.size)
        // Before `compare_from`, openWakeWord's window still holds its random
        // seed, which nothing else can reproduce. See the fixture script.
        for (i in from until want.size) {
            assertEquals("chunk $i: openWakeWord ${want[i]}, kotlin ${got[i]}",
                         want[i].toDouble(), got[i].toDouble(), 2e-3)
        }
    }

    /** The frame size the Recorder actually produces is not 1280. */
    @Test
    fun `odd frame sizes are buffered the way openWakeWord buffers them`() {
        assumeTrue("fixtures missing", haveFixtures())
        val fields = expected()
        val want = numbers(fields.getValue("scores_1000"))
        val from = fields.getValue("compare_from").trim().toInt()
        val samples = audio()

        val subject = detector(threshold = 2f)
        val got = mutableListOf<Float>()
        var at = 0
        while (at + 1000 <= samples.size) {
            subject.accept(samples.copyOfRange(at, at + 1000), 1000)
            got.add(subject.lastScore)
            at += 1000
        }
        subject.close()

        assertEquals("frame count", want.size, got.size)
        for (i in from until want.size) {
            assertEquals("frame $i: openWakeWord ${want[i]}, kotlin ${got[i]}",
                         want[i].toDouble(), got[i].toDouble(), 2e-3)
        }
    }

    /**
     * The mel stage on its own, against openWakeWord's own output.
     *
     * The end-to-end tests would catch a broken mel model too, but only as a
     * wrong score at the far end of three models. This one says which model.
     */
    @Test
    fun `the melspectrogram matches including the divide by ten plus two`() {
        assumeTrue("fixtures missing", haveFixtures())
        val want = melFixture()
        assertEquals("fixture should carry two frames of 32 bins", 2, want.size)
        assertEquals(HeyJarvisDetector.MEL_BINS, want[0].size)

        val subject = detector(threshold = 2f)
        // The same slice the fixture was computed from: two chunks, in one call.
        val got = subject.melFramesFor(audio().copyOfRange(0, HeyJarvisDetector.CHUNK * 2))
        subject.close()

        assertTrue("expected at least 2 mel frames, got ${got.size}", got.size >= 2)
        for (frame in want.indices) {
            for (bin in 0 until HeyJarvisDetector.MEL_BINS) {
                assertEquals(
                    "mel frame $frame bin $bin: openWakeWord ${want[frame][bin]}, " +
                        "kotlin ${got[frame][bin]}",
                    want[frame][bin].toDouble(), got[frame][bin].toDouble(), 1e-3,
                )
            }
        }
    }

    /** Audio with no wake word in it must not wake the kiosk. */
    @Test
    fun `speech-like audio without the wake word never fires`() {
        assumeTrue("fixtures missing", haveFixtures())
        val samples = audio()
        val subject = detector(threshold = HeyJarvisDetector.DEFAULT_THRESHOLD)
        var fired = false
        var at = 0
        var peak = 0f
        while (at + HeyJarvisDetector.CHUNK <= samples.size) {
            if (subject.accept(samples.copyOfRange(at, at + HeyJarvisDetector.CHUNK),
                               HeyJarvisDetector.CHUNK)) {
                fired = true
            }
            peak = maxOf(peak, subject.lastScore)
            at += HeyJarvisDetector.CHUNK
        }
        subject.close()
        assertTrue("fired on audio with no wake word; peak score $peak", !fired)
        assertTrue("peak $peak should be far below the threshold", peak < 0.1f)
    }

    /** One utterance must wake the kiosk once. */
    @Test
    fun `the cooldown stops one wake word from firing repeatedly`() {
        assumeTrue("fixtures missing", haveFixtures())
        val samples = audio()
        // Threshold below every score in the fixture, so every chunk would fire
        // if nothing held it back. What is being tested is the cooldown, not the
        // model: with 25 frames of cooldown, 40 chunks can fire at most twice.
        val subject = detector(threshold = 0.0001f, cooldownFrames = 25)
        var fires = 0
        var at = 0
        while (at + HeyJarvisDetector.CHUNK <= samples.size) {
            if (subject.accept(samples.copyOfRange(at, at + HeyJarvisDetector.CHUNK),
                               HeyJarvisDetector.CHUNK)) {
                fires += 1
            }
            at += HeyJarvisDetector.CHUNK
        }
        subject.close()
        assertTrue("fired $fires times in 40 chunks with a 25-chunk cooldown", fires in 1..2)
    }

    /** After the kiosk has spoken, its own voice must not be sitting in the buffers. */
    @Test
    fun `reset clears the buffers so the next score starts from warm-up again`() {
        assumeTrue("fixtures missing", haveFixtures())
        val samples = audio()
        val subject = detector(threshold = 2f)
        var at = 0
        while (at + HeyJarvisDetector.CHUNK <= samples.size) {
            subject.accept(samples.copyOfRange(at, at + HeyJarvisDetector.CHUNK),
                           HeyJarvisDetector.CHUNK)
            at += HeyJarvisDetector.CHUNK
        }
        subject.reset()
        assertEquals(0f, subject.lastScore, 0f)

        // The warm-up suppression is back, so the first chunks after speaking
        // cannot produce a detection from a half-filled buffer.
        subject.accept(samples.copyOfRange(0, HeyJarvisDetector.CHUNK), HeyJarvisDetector.CHUNK)
        assertEquals(0f, subject.lastScore, 0f)
        subject.close()
    }

    private companion object {
        /** Our fixture writer emits a canonical 44-byte header. */
        const val WAV_HEADER = 44
    }
}
