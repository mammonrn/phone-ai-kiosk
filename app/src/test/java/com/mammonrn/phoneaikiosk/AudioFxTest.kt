package com.mammonrn.phoneaikiosk

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.mammonrn.phoneaikiosk.media.fx.AudioFx
import com.mammonrn.phoneaikiosk.media.fx.Eq
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * 0.55.0: the processor in the player's audio path. The A07 met a 12-channel
 * m4a that the first version refused by throwing — which stops the whole
 * sink, so the song would not play at all. Now every channel count is taken,
 * and a format that is not 16-bit passes by untouched instead of failing.
 */
class AudioFxTest {

    private fun pcm(channels: Int, frames: Int, freq: Double, rate: Int = 48000): ByteBuffer {
        val b = ByteBuffer.allocateDirect(frames * channels * 2).order(ByteOrder.nativeOrder())
        for (f in 0 until frames) {
            val v = (0.1 * sin(2 * PI * freq * f / rate) * 32767).toInt().toShort()
            repeat(channels) { b.putShort(v) }
        }
        b.flip()
        return b
    }

    private fun rms(b: ByteBuffer, channels: Int, from: Int): Double {
        val shorts = b.order(ByteOrder.nativeOrder()).asShortBuffer()
        var sum = 0.0; var n = 0
        for (i in from * channels until shorts.limit() step channels) { val x = shorts.get(i) / 32768.0; sum += x * x; n++ }
        return sqrt(sum / n)
    }

    @Test
    fun `a twelve-channel file is taken, and a float one passes by without failing`() {
        val fx = AudioFx()
        fx.configure(AudioProcessor.AudioFormat(48000, 12, C.ENCODING_PCM_16BIT))
        fx.flush(AudioProcessor.StreamMetadata.DEFAULT)
        assertTrue(fx.isActive && fx.working)

        val other = AudioFx()
        other.configure(AudioProcessor.AudioFormat(48000, 2, C.ENCODING_PCM_FLOAT))   // no exception
        assertFalse(other.isActive)
        assertFalse(other.working)
    }

    @Test
    fun `a band raised 12 dB raises a tone at it by about 12 dB, on every channel`() {
        val fx = AudioFx()
        fx.settings = Eq.Settings().withGain(4, 12f)   // 1 kHz
        fx.configure(AudioProcessor.AudioFormat(48000, 12, C.ENCODING_PCM_16BIT))
        fx.flush(AudioProcessor.StreamMetadata.DEFAULT)
        val input = pcm(12, 9600, 1000.0)
        val inRms = rms(input.duplicate(), 12, 4800)
        fx.queueInput(input)
        val out = fx.getOutput()
        val gainDb = 20 * kotlin.math.log10(rms(out, 12, 4800) / inRms)
        assertEquals(12.0, gainDb, 0.5)
    }
}
