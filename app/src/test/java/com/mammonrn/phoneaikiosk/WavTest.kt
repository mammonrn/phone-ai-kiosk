package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Recorder
import java.lang.reflect.Method
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The WAV header the transcriber has to be able to read.
 *
 * Worth a test because a wrong header is the kind of failure that comes back as
 * "transcription returns nonsense" rather than as an error, and it costs a
 * billed request every time it is tried.
 */
class WavTest {

    private val wav: Method = Recorder::class.java
        .getDeclaredMethod("wav", ByteArray::class.java)
        .apply { isAccessible = true }

    private fun header(pcmBytes: Int): ByteArray =
        wav.invoke(Recorder(), ByteArray(pcmBytes)) as ByteArray

    private fun ascii(bytes: ByteArray, at: Int, length: Int) =
        String(bytes, at, length, Charsets.US_ASCII)

    private fun le32(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or
            ((bytes[at + 1].toInt() and 0xFF) shl 8) or
            ((bytes[at + 2].toInt() and 0xFF) shl 16) or
            ((bytes[at + 3].toInt() and 0xFF) shl 24)

    private fun le16(bytes: ByteArray, at: Int): Int =
        (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

    @Test
    fun `it is a RIFF WAVE file`() {
        val out = header(1000)
        assertEquals("RIFF", ascii(out, 0, 4))
        assertEquals("WAVE", ascii(out, 8, 4))
        assertEquals("fmt ", ascii(out, 12, 4))
        assertEquals("data", ascii(out, 36, 4))
    }

    @Test
    fun `the header is 44 bytes and the sizes agree with the payload`() {
        val out = header(1000)
        assertEquals(44 + 1000, out.size)
        assertEquals(36 + 1000, le32(out, 4))   // RIFF chunk size
        assertEquals(16, le32(out, 16))         // fmt chunk size
        assertEquals(1000, le32(out, 40))       // data chunk size
    }

    @Test
    fun `it declares 16 kHz mono 16-bit, which is what Groq asks for`() {
        val out = header(0)
        assertEquals(1, le16(out, 20))                       // PCM
        assertEquals(1, le16(out, 22))                       // mono
        assertEquals(Recorder.SAMPLE_RATE, le32(out, 24))    // 16000
        assertEquals(Recorder.SAMPLE_RATE * 2, le32(out, 28))// byte rate
        assertEquals(2, le16(out, 32))                       // block align
        assertEquals(16, le16(out, 34))                      // bits per sample
    }

    @Test
    fun `an empty recording is still a valid file`() {
        val out = header(0)
        assertEquals(44, out.size)
        assertEquals(0, le32(out, 40))
    }

    @Test
    fun `a frame is short enough for a wake word stage to work on`() {
        val recorder = Recorder()
        val frameMs = recorder.frameSamples * 1000 / Recorder.SAMPLE_RATE
        assertTrue("frames of $frameMs ms are too coarse", frameMs in 20..100)
    }

    @Test
    fun `the speech threshold sits between room noise and a voice`() {
        // 16-bit samples run to 32767. Too low and a fan wakes it; too high and
        // a voice across a room never registers.
        assertTrue(Recorder.SPEECH_THRESHOLD in 500..8000)
    }
}
