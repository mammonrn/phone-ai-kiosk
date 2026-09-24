package com.mammonrn.phoneaikiosk.media.fx

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The bars under the time (0.55.0, Poom: "กราฟแท่งแสดงระดับเสียงที่
 * เคลื่อนไหวจริงตามเพลง"). Plain Kotlin; SpectrumTest.
 *
 * The samples are the ones the player is sending to the speaker ([Tap]),
 * after the equalizer — not the microphone: nothing here listens to the room.
 * A 1024-point FFT (Hann window), folded into [BARS] bars spaced by octave
 * fractions from 50 Hz to 16 kHz, in dB from [FLOOR_DB] to 0.
 */
object Spectrum {

    const val SIZE = 1024
    const val BARS = 19
    const val FLOOR_DB = -60.0

    private val window = DoubleArray(SIZE) { 0.5 - 0.5 * cos(2 * PI * it / (SIZE - 1)) }

    /** The bars' edges in Hz, log spaced. */
    fun edges(bars: Int = BARS, low: Double = 50.0, high: Double = 16000.0): DoubleArray =
        DoubleArray(bars + 1) { low * (high / low).pow(it.toDouble() / bars) }

    /**
     * [samples] (mono, −1..1, at least [SIZE]; the last [SIZE] are used) as
     * bar heights 0..1. Silence is all zeros.
     */
    fun bars(samples: FloatArray, sampleRate: Int, bars: Int = BARS): FloatArray {
        val n = SIZE
        val re = DoubleArray(n); val im = DoubleArray(n)
        val start = samples.size - n
        for (i in 0 until n) re[i] = samples[start + i] * window[i]
        fft(re, im)
        val edges = edges(bars)
        val out = FloatArray(bars)
        val binHz = sampleRate.toDouble() / n
        for (b in 0 until bars) {
            val from = max(1, (edges[b] / binHz).toInt())
            val to = max(from + 1, (edges[b + 1] / binHz).toInt()).coerceAtMost(n / 2)
            var peak = 0.0
            for (k in from until to) peak = max(peak, sqrt(re[k] * re[k] + im[k] * im[k]))
            // A full-scale sine through the Hann window peaks at n/4.
            val db = 20 * log10(max(peak / (n / 4.0), 1e-9))
            out[b] = ((db - FLOOR_DB) / -FLOOR_DB).coerceIn(0.0, 1.0).toFloat()
        }
        return out
    }

    /** In-place radix-2 FFT. */
    fun fft(re: DoubleArray, im: DoubleArray) {
        val n = re.size
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) { var t = re[i]; re[i] = re[j]; re[j] = t; t = im[i]; im[i] = im[j]; im[j] = t }
        }
        var len = 2
        while (len <= n) {
            val ang = -2 * PI / len
            val wr = cos(ang); val wi = sin(ang)
            var i = 0
            while (i < n) {
                var cr = 1.0; var ci = 0.0
                for (k in 0 until len / 2) {
                    val ur = re[i + k]; val ui = im[i + k]
                    val vr = re[i + k + len / 2] * cr - im[i + k + len / 2] * ci
                    val vi = re[i + k + len / 2] * ci + im[i + k + len / 2] * cr
                    re[i + k] = ur + vr; im[i + k] = ui + vi
                    re[i + k + len / 2] = ur - vr; im[i + k + len / 2] = ui - vi
                    val nr = cr * wr - ci * wi; ci = cr * wi + ci * wr; cr = nr
                }
                i += len
            }
            len = len shl 1
        }
    }

    /**
     * Bars that rise at once and fall slowly, as the reference's did:
     * [fall] of the full height per frame at most.
     */
    fun smooth(previous: FloatArray, next: FloatArray, fall: Float = 0.06f): FloatArray =
        FloatArray(next.size) { max(next[it], (previous.getOrElse(it) { 0f }) - fall) }
}

/**
 * The samples on their way to the speaker, kept for the bars (0.55.0). The
 * player writes ([write]) as it decodes, which is ahead of what is heard; the
 * screen asks for the samples AT the position being heard ([at]), so the bars
 * move with the music rather than a buffer early. Plain Kotlin; SpectrumTest.
 */
class Tap(private val capacity: Int = 1 shl 17) {
    private val ring = FloatArray(capacity)
    private var written = 0L          // mono samples since the stream began
    private var startUs = 0L          // where in the song the stream began
    @Volatile var sampleRate = 44100
        private set

    @Synchronized
    fun restart(positionUs: Long, rate: Int) {
        startUs = positionUs; written = 0; sampleRate = rate
        ring.fill(0f)
    }

    @Synchronized
    fun write(sample: Float) {
        ring[(written % capacity).toInt()] = sample
        written += 1
    }

    /**
     * The [count] samples that end at [positionUs] of the song, or null when
     * they are not here (not decoded yet, or long gone).
     */
    @Synchronized
    fun at(positionUs: Long, count: Int = Spectrum.SIZE): FloatArray? {
        val end = ((positionUs - startUs) * sampleRate / 1_000_000L)
        if (end < count || end > written || written - end > capacity - count) return null
        return FloatArray(count) { ring[((end - count + it) % capacity).toInt()] }
    }
}
