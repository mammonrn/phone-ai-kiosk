package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.fx.Eq
import com.mammonrn.phoneaikiosk.media.fx.Spectrum
import com.mammonrn.phoneaikiosk.media.fx.Tap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

/** 0.55.0: the equalizer and the bars do what they say, measured on real samples. */
class EqAndSpectrumTest {

    private fun tone(freq: Double, rate: Int = 44100, n: Int = 44100, amp: Double = 0.5) =
        DoubleArray(n) { amp * sin(2 * PI * freq * it / rate) }

    private fun rms(x: DoubleArray, from: Int = x.size / 2) =
        sqrt(x.drop(from).sumOf { it * it } / (x.size - from))

    private fun through(chain: Eq.Chain, x: DoubleArray) = DoubleArray(x.size) { chain.process(x[it], 0) }

    @Test
    fun `the ten bands and eleven sliders are the reference's`() {
        assertEquals(listOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0), Eq.BANDS.toList())
        assertEquals(listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K"), Eq.LABELS)
        assertTrue(Eq.PRESETS.keys.containsAll(listOf("Flat", "Rock", "Pop", "Jazz", "Classical")))
        assertTrue(Eq.PRESETS.values.all { it.size == 10 && it.all { db -> abs(db) <= Eq.MAX_DB } })
    }

    @Test
    fun `a band raised by 12 dB raises a tone at its frequency by 12 dB, and one far away hardly`() {
        val s = Eq.Settings().withGain(4, 12f)                 // 1 kHz
        val chain = Eq.Chain(44100, 1).apply { set(s) }
        val at = 20 * log10(rms(through(chain, tone(1000.0))) / rms(tone(1000.0)))
        chain.reset()
        val far = 20 * log10(rms(through(chain, tone(60.0))) / rms(tone(60.0)))
        assertEquals(12.0, at, 0.3)
        assertTrue("60 Hz moved $far dB", abs(far) < 0.5)
        assertEquals(12.0, Eq.totalDb(s, 1000.0), 0.3)
    }

    @Test
    fun `off, or flat, leaves every sample as it was`() {
        val x = tone(440.0, n = 4410)
        for (s in listOf(Eq.preset("Rock", on = false), Eq.preset("Flat"))) {
            val chain = Eq.Chain(44100, 1).apply { set(s) }
            assertFalse(chain.changesSound)
            val y = through(chain, x)
            assertTrue(x.indices.all { x[it] == y[it] })
        }
    }

    @Test
    fun `the preamp moves everything, and a hand-moved slider leaves the preset's name`() {
        val s = Eq.preset("Pop").withPreamp(-6f)
        assertEquals("", s.preset)
        val chain = Eq.Chain(44100, 1).apply { set(Eq.Settings().withPreamp(-6f)) }
        val db = 20 * log10(rms(through(chain, tone(1000.0))) / rms(tone(1000.0)))
        assertEquals(-6.0, db, 0.05)
        assertEquals(12f, Eq.Settings().withGain(0, 40f).gainsDb[0])
    }

    @Test
    fun `balance fades the far side and keeps the near one`() {
        val chain = Eq.Chain(44100, 2).apply { setBalance(-0.5f) }
        assertEquals(0.5, chain.process(0.5, 0), 1e-9)       // left whole
        assertEquals(0.25, chain.process(0.5, 1), 1e-9)      // right half
        chain.setBalance(0f)
        assertFalse(chain.changesSound)
    }

    @Test
    fun `a 1 kHz tone lights the bar that holds 1 kHz and not the bass`() {
        val x = FloatArray(Spectrum.SIZE) { (0.8 * sin(2 * PI * 1000.0 * it / 44100)).toFloat() }
        val bars = Spectrum.bars(x, 44100)
        val edges = Spectrum.edges()
        val bar = (0 until Spectrum.BARS).first { edges[it] <= 1000.0 && 1000.0 < edges[it + 1] }
        assertEquals(bar, bars.indices.maxByOrNull { bars[it] })
        assertTrue(bars[bar] > 0.9f && bars[0] < 0.2f)
        assertTrue(Spectrum.bars(FloatArray(Spectrum.SIZE), 44100).all { it == 0f })
    }

    @Test
    fun `bars rise at once and fall slowly`() {
        val s = Spectrum.smooth(floatArrayOf(1f, 0f), floatArrayOf(0f, 0.7f))
        assertEquals(0.94f, s[0], 1e-6f)
        assertEquals(0.7f, s[1], 1e-6f)
    }

    @Test
    fun `the tap gives the samples at the position heard, not the ones just decoded`() {
        val tap = Tap(capacity = 8192)
        tap.restart(positionUs = 2_000_000, rate = 1000)     // the stream starts 2 s into the song
        for (i in 0 until 3000) tap.write(i.toFloat())       // 3 s decoded
        val heard = tap.at(positionUs = 3_500_000, count = 4)!!  // 1.5 s of it heard
        assertEquals(listOf(1496f, 1497f, 1498f, 1499f), heard.toList())
        assertNull(tap.at(positionUs = 6_000_000, count = 4))    // not decoded yet
        assertNull(tap.at(positionUs = 2_000_001, count = 4))    // before enough is heard
    }
}
