package com.mammonrn.phoneaikiosk.media.fx

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The equalizer (0.55.0, Poom: "พรีแอมป์และ 10 ย่านความถี่"). Plain Kotlin;
 * EqTest measures its response.
 *
 * WHY OUR OWN. Media3 has no equalizer, and Android's audiofx Equalizer
 * gives the bands the phone chooses (five on most Samsungs), not these ten.
 * So the ten bands are ten peaking filters (the RBJ Audio EQ Cookbook) run on
 * the decoded samples inside the player ([AudioFx]) — they change what is
 * heard on every phone, and the spectrum is read after them, so the bars show
 * what the equalizer did.
 *
 * Gains are ±12 dB, like the reference; the preamp is ±12 dB too. Settings
 * are a plain value ([Settings]) saved by the player.
 */
object Eq {

    /** The ten bands, Hz — the reference's own labels. */
    val BANDS = doubleArrayOf(60.0, 170.0, 310.0, 600.0, 1000.0, 3000.0, 6000.0, 12000.0, 14000.0, 16000.0)

    /** What the band is called on screen. */
    val LABELS = listOf("60", "170", "310", "600", "1K", "3K", "6K", "12K", "14K", "16K")

    const val MAX_DB = 12f

    /** One octave wide: neighbours overlap, so a curve drawn by the sliders is smooth. */
    private const val Q = 1.41

    data class Settings(
        val on: Boolean = true,
        val preampDb: Float = 0f,
        val gainsDb: List<Float> = List(BANDS.size) { 0f },
        /** The preset last chosen, "" once a slider is moved by hand. */
        val preset: String = "Flat",
    ) {
        init { require(gainsDb.size == BANDS.size) }

        fun withGain(band: Int, db: Float) =
            copy(gainsDb = gainsDb.toMutableList().also { it[band] = clamp(db) }, preset = "")

        fun withPreamp(db: Float) = copy(preampDb = clamp(db), preset = "")

        /** Nothing to do: off, or every gain at zero. */
        val flat: Boolean get() = !on || (preampDb == 0f && gainsDb.all { it == 0f })
    }

    /**
     * Presets, in dB for the ten bands. Our own numbers, shaped the way these
     * names are usually heard: Rock a V, Pop the middle up, Jazz warm lows and
     * soft highs, Classical the lows and highs a little up.
     */
    val PRESETS: Map<String, List<Float>> = linkedMapOf(
        "Flat" to List(10) { 0f },
        "Rock" to listOf(5f, 4f, 2f, -1f, -2f, -1f, 2f, 4f, 5f, 5f),
        "Pop" to listOf(-1f, 1f, 3f, 4f, 4f, 2f, 0f, -1f, -1f, -1f),
        "Jazz" to listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f),
        "Classical" to listOf(0f, 0f, 0f, 0f, 0f, 0f, -2f, -3f, -3f, -4f),
        "เสียงร้อง" to listOf(-2f, -2f, -1f, 2f, 4f, 4f, 3f, 1f, 0f, -1f),
        // 0.57.0: measured on the A07 with pink noise, the old 7/6/4/2 lifted
        // 170-300 Hz as much as 60 Hz (the bands overlap) — more boom than bass.
        // Now +9 dB at 60, +6 at 170, and nothing from 450 Hz up.
        "เบสหนัก" to listOf(9f, 5f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f),
    )

    fun preset(name: String, on: Boolean = true): Settings =
        Settings(on = on, preampDb = 0f, gainsDb = PRESETS.getValue(name), preset = name)

    fun clamp(db: Float): Float = db.coerceIn(-MAX_DB, MAX_DB)

    // ------------------------------------------------------------ filters

    /** One biquad's coefficients, normalised (a0 = 1). */
    class Coeffs(val b0: Double, val b1: Double, val b2: Double, val a1: Double, val a2: Double)

    /** A peaking filter at [freq] of [gainDb] (RBJ cookbook). */
    fun peaking(freq: Double, gainDb: Double, sampleRate: Int, q: Double = Q): Coeffs {
        // Above the Nyquist limit a band cannot be heard: leave it flat.
        if (freq >= sampleRate / 2.0 * 0.95 || gainDb == 0.0) return Coeffs(1.0, 0.0, 0.0, 0.0, 0.0)
        val a = 10.0.pow(gainDb / 40.0)
        val w0 = 2 * PI * freq / sampleRate
        val alpha = sin(w0) / (2 * q)
        val cosw = cos(w0)
        val a0 = 1 + alpha / a
        return Coeffs((1 + alpha * a) / a0, (-2 * cosw) / a0, (1 - alpha * a) / a0,
                      (-2 * cosw) / a0, (1 - alpha / a) / a0)
    }

    /** The gain, in dB, of [c] at [freq] — for the curve on screen and for the tests. */
    fun responseDb(c: Coeffs, freq: Double, sampleRate: Int): Double {
        val w = 2 * PI * freq / sampleRate
        val cw = cos(w); val sw = sin(w); val c2 = cos(2 * w); val s2 = sin(2 * w)
        val nr = c.b0 + c.b1 * cw + c.b2 * c2; val ni = -(c.b1 * sw + c.b2 * s2)
        val dr = 1 + c.a1 * cw + c.a2 * c2; val di = -(c.a1 * sw + c.a2 * s2)
        val mag = sqrt((nr * nr + ni * ni) / (dr * dr + di * di))
        return 20 * kotlin.math.log10(mag)
    }

    /** The whole chain's gain at [freq]: preamp plus every band. */
    fun totalDb(s: Settings, freq: Double, sampleRate: Int = 44100): Double {
        if (!s.on) return 0.0
        var db = s.preampDb.toDouble()
        for (i in BANDS.indices) db += responseDb(peaking(BANDS[i], s.gainsDb[i].toDouble(), sampleRate), freq, sampleRate)
        return db
    }

    /**
     * Runs the settings on interleaved samples, in place: preamp, ten
     * filters per channel, then balance. Keeps each channel's filter memory
     * between calls, so a stream can come in pieces.
     */
    class Chain(val sampleRate: Int, val channels: Int) {
        private var coeffs: Array<Coeffs> = emptyArray()
        private var preamp = 1.0
        private var active = false
        // Per channel, per band: x1 x2 y1 y2.
        private val memory = Array(channels) { Array(BANDS.size) { DoubleArray(4) } }
        private var left = 1.0
        private var right = 1.0

        fun set(s: Settings) {
            active = !s.flat
            preamp = 10.0.pow(s.preampDb / 20.0)
            coeffs = Array(BANDS.size) { peaking(BANDS[it], s.gainsDb[it].toDouble(), sampleRate) }
        }

        /** −1 all left … 0 centre … +1 all right. The other side fades, the near side stays whole. */
        fun setBalance(balance: Float) {
            val b = balance.coerceIn(-1f, 1f).toDouble()
            left = if (b > 0) 1 - b else 1.0
            right = if (b < 0) 1 + b else 1.0
        }

        val changesSound: Boolean get() = active || left != 1.0 || right != 1.0

        fun reset() { for (ch in memory) for (m in ch) m.fill(0.0) }

        /** One sample of channel [ch], in the range −1..1; returns the result, not clipped. */
        fun process(x: Double, ch: Int): Double {
            var v = x
            if (active) {
                v *= preamp
                val mem = memory[ch]
                for (b in coeffs.indices) {
                    val c = coeffs[b]; val m = mem[b]
                    val y = c.b0 * v + c.b1 * m[0] + c.b2 * m[1] - c.a1 * m[2] - c.a2 * m[3]
                    m[1] = m[0]; m[0] = v; m[3] = m[2]; m[2] = if (abs(y) < 1e-20) 0.0 else y
                    v = y
                }
            }
            if (channels == 2) v *= if (ch == 0) left else right
            return v
        }
    }
}
