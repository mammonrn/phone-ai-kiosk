package com.mammonrn.phoneaikiosk.voice

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.Closeable
import java.nio.FloatBuffer

/**
 * The wake word stage. Nothing leaves the room until this says so.
 *
 * Whatever implementation is behind it, one property must hold: it runs on the
 * phone. No audio is sent anywhere before [Detector.accept] returns true.
 */
interface Detector {

    /** Whether this detector can actually detect anything. */
    val ready: Boolean

    /** A short word for the status line: "no-model", "listening", "loading". */
    val state: String

    /**
     * Feeds one frame of 16 kHz mono PCM and says whether the wake word ended
     * on it. Called from the capture thread, so it must not block.
     */
    fun accept(frame: ShortArray, length: Int): Boolean

    /** Drops every buffer. Used after the kiosk has been speaking. */
    fun reset() {}

    fun close() {}
}

/**
 * "Hey Jarvis", using openWakeWord's own pre-trained model, on the phone.
 *
 * THREE MODELS IN A ROW, which is openWakeWord's design and not ours:
 *
 *   audio -> melspectrogram.onnx  -> 32-bin mel frames, one per 10 ms
 *         -> embedding_model.onnx -> one 96-number embedding per 80 ms
 *         -> hey_jarvis_v0.1.onnx -> one score from the last 16 embeddings
 *
 * The middle model is Google's `speech_embedding`; it is what makes a wake word
 * model this small work at all, and it is shared by every openWakeWord model.
 *
 * THIS IS A RE-IMPLEMENTATION, so every constant here was read out of
 * openWakeWord v0.6.0's `utils.py` rather than chosen, and `WakeWordParityTest`
 * holds it to numbers produced by the Python original rather than to itself:
 *
 *  - audio enters as int16 VALUES in a float tensor — not divided by 32768;
 *  - the mel output is transformed by `x / 10 + 2` (utils.py's default
 *    `melspec_transform`, which exists to match Google's TF implementation);
 *  - each mel call is given the new samples plus 480 samples of the previous
 *    audio (`160 * 3`), which is what makes the streaming frames line up;
 *  - the mel buffer starts as 76 rows of 1.0, not 0.0;
 *  - the embedding window is 76 mel frames, stepping 8 frames per 80 ms;
 *  - the classifier reads the last 16 embeddings, shape (1, 16, 96).
 *
 * ONE DELIBERATE DIFFERENCE. openWakeWord seeds its feature buffer with four
 * seconds of random noise. We seed with zeros: a kiosk that scores its own
 * startup noise is a kiosk that can wake itself before anybody has spoken. The
 * first [WARMUP_CHUNKS] scores are suppressed either way, which is openWakeWord's
 * own behaviour, and after 16 chunks of real audio the buffer holds no seed at
 * all — which is why the parity test compares from chunk 20.
 */
class HeyJarvisDetector private constructor(
    private val environment: OrtEnvironment,
    private val melspec: OrtSession,
    private val embedding: OrtSession,
    private val wakeword: OrtSession,
    private val melspecInput: String,
    private val embeddingInput: String,
    private val wakewordInput: String,
    threshold: Float,
    private val cooldownFrames: Int,
    private val now: () -> Long = System::currentTimeMillis,
) : Detector, Closeable {

    /** Adjustable from adb while testing; see TESTING.md. */
    @Volatile
    var threshold: Float = threshold
        set(value) {
            field = value.coerceIn(0.01f, 0.999f)
        }

    @Volatile
    var lastScore: Float = 0f
        private set

    @Volatile
    var detections: Int = 0
        private set

    @Volatile
    var lastDetectionAt: Long = 0
        private set

    /**
     * How many 80 ms embeddings are in the window the classifier reads.
     *
     * Below CLASSIFIER_FRAMES the detector produces NO score at all — not a low
     * one, none — so a wake word spoken then is not missed, it is unheard. That
     * distinction is invisible from a score of 0.0, which is why this is
     * reported.
     */
    val featureCount: Int get() = features.size

    /** Whether a score from this detector means anything yet. */
    val warm: Boolean get() = features.size >= CLASSIFIER_FRAMES && chunksSeen > WARMUP_CHUNKS

    /** 80 ms chunks seen since the last reset, for the same reason. */
    val chunksProcessed: Int get() = chunksSeen

    override val ready: Boolean = true
    override val state: String get() = "listening"

    // ---- buffers, all touched only by the capture thread --------------------

    /** Samples not yet part of a whole 1280-sample chunk. */
    private var pending = ShortArray(0)

    /** The tail of the audio, for the 480-sample overlap each mel call needs. */
    private val recent = ShortArray(RECENT_SAMPLES)
    private var recentFilled = 0
    private var recentWrite = 0

    /** Mel frames, newest last. Starts as ones, exactly as openWakeWord does. */
    private var mel = ArrayDeque<FloatArray>().apply {
        repeat(MEL_WINDOW) { add(FloatArray(MEL_BINS) { 1f }) }
    }

    /** 96-number embeddings, newest last. */
    private var features = ArrayDeque<FloatArray>()

    private var chunksSeen = 0
    private var cooldownLeft = 0

    override fun reset() {
        pending = ShortArray(0)
        recentFilled = 0
        recentWrite = 0
        mel = ArrayDeque<FloatArray>().apply {
            repeat(MEL_WINDOW) { add(FloatArray(MEL_BINS) { 1f }) }
        }
        features = ArrayDeque()
        chunksSeen = 0
        cooldownLeft = 0
        lastScore = 0f
    }

    /**
     * Returns true on the frame the wake word completes.
     *
     * The score only moves when a whole 1280-sample chunk has arrived, which is
     * what openWakeWord does too: with anything less it reports the previous
     * score rather than a fresh one.
     */
    override fun accept(frame: ShortArray, length: Int): Boolean {
        if (length <= 0) return false

        pending = pending + frame.copyOf(length)
        var fired = false

        while (pending.size >= CHUNK) {
            val chunk = pending.copyOfRange(0, CHUNK)
            pending = pending.copyOfRange(CHUNK, pending.size)
            if (processChunk(chunk)) fired = true
        }
        return fired
    }

    /** One 80 ms chunk: mel, embedding, score. Returns whether it woke. */
    private fun processChunk(chunk: ShortArray): Boolean {
        appendRecent(chunk)

        // The new samples plus 480 of the previous ones. openWakeWord uses
        // `[-n_samples - 160*3:]`; with a fresh buffer there is simply less.
        val wanted = CHUNK + MEL_OVERLAP
        val take = minOf(wanted, recentFilled)
        val audio = FloatArray(take)
        for (i in 0 until take) {
            // int16 values in a float tensor, NOT normalised to [-1, 1]:
            // the mel model was exported expecting the raw sample values.
            audio[i] = tailSample(take - i).toFloat()
        }

        val melFrames = runMel(audio)
        for (row in melFrames) mel.addLast(row)
        while (mel.size > MEL_MAX) mel.removeFirst()

        if (mel.size >= MEL_WINDOW) {
            features.addLast(runEmbedding(mel, mel.size))
            while (features.size > FEATURE_MAX) features.removeFirst()
        }

        chunksSeen += 1
        if (cooldownLeft > 0) cooldownLeft -= 1

        if (features.size < CLASSIFIER_FRAMES) return false

        val score = runWakeword(features)
        // openWakeWord zeroes its first five predictions while the buffers fill.
        lastScore = if (chunksSeen <= WARMUP_CHUNKS) 0f else score

        if (lastScore < threshold || cooldownLeft > 0) return false

        cooldownLeft = cooldownFrames
        detections += 1
        lastDetectionAt = now()
        return true
    }

    // ---- the three models ---------------------------------------------------

    private fun runMel(audio: FloatArray): List<FloatArray> {
        val tensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(audio), longArrayOf(1, audio.size.toLong()),
        )
        tensor.use {
            melspec.run(mapOf(melspecInput to it)).use { result ->
                val out = result[0] as OnnxTensor
                val flat = out.floatBuffer
                // (time, 1, frames, 32) with a leading batch that is always 1.
                val frames = flat.remaining() / MEL_BINS
                return List(frames) { f ->
                    FloatArray(MEL_BINS) { b ->
                        // openWakeWord's default melspec_transform, which exists
                        // to bring this ONNX model in line with Google's TF one.
                        flat.get(f * MEL_BINS + b) / 10f + 2f
                    }
                }
            }
        }
    }

    /** The last 76 mel frames ending at [end], as (1, 76, 32, 1). */
    private fun runEmbedding(rows: ArrayDeque<FloatArray>, end: Int): FloatArray {
        val window = FloatArray(MEL_WINDOW * MEL_BINS)
        var at = 0
        for (i in end - MEL_WINDOW until end) {
            rows[i].copyInto(window, at)
            at += MEL_BINS
        }
        val tensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(window),
            longArrayOf(1, MEL_WINDOW.toLong(), MEL_BINS.toLong(), 1),
        )
        tensor.use {
            embedding.run(mapOf(embeddingInput to it)).use { result ->
                val flat = (result[0] as OnnxTensor).floatBuffer
                return FloatArray(EMBEDDING) { flat.get(it) }
            }
        }
    }

    private fun runWakeword(rows: ArrayDeque<FloatArray>): Float {
        val input = FloatArray(CLASSIFIER_FRAMES * EMBEDDING)
        var at = 0
        for (i in rows.size - CLASSIFIER_FRAMES until rows.size) {
            rows[i].copyInto(input, at)
            at += EMBEDDING
        }
        val tensor = OnnxTensor.createTensor(
            environment, FloatBuffer.wrap(input),
            longArrayOf(1, CLASSIFIER_FRAMES.toLong(), EMBEDDING.toLong()),
        )
        tensor.use {
            wakeword.run(mapOf(wakewordInput to it)).use { result ->
                return (result[0] as OnnxTensor).floatBuffer.get(0)
            }
        }
    }

    /**
     * The mel stage alone, on a whole buffer, with no streaming state involved.
     *
     * Exists for the parity test. When the end-to-end scores disagree with
     * openWakeWord, the first question is which of the three models drifted, and
     * without this the answer is a bisect through a black box.
     */
    fun melFramesFor(samples: ShortArray): List<FloatArray> =
        runMel(FloatArray(samples.size) { samples[it].toFloat() })

    // ---- the ring of recent samples ----------------------------------------

    private fun appendRecent(chunk: ShortArray) {
        for (sample in chunk) {
            recent[recentWrite] = sample
            recentWrite = (recentWrite + 1) % recent.size
            if (recentFilled < recent.size) recentFilled += 1
        }
    }

    /** [back] = 1 is the newest sample. */
    private fun tailSample(back: Int): Short {
        val index = ((recentWrite - back) % recent.size + recent.size) % recent.size
        return recent[index]
    }

    override fun close() {
        runCatching { melspec.close() }
        runCatching { embedding.close() }
        runCatching { wakeword.close() }
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /** 80 ms. openWakeWord's step; everything downstream assumes it. */
        const val CHUNK = 1280

        /** `160 * 3` in utils.py: the overlap each streaming mel call needs. */
        const val MEL_OVERLAP = 480

        const val MEL_BINS = 32
        const val MEL_WINDOW = 76
        const val MEL_MAX = 10 * 97
        const val EMBEDDING = 96
        const val FEATURE_MAX = 120
        const val CLASSIFIER_FRAMES = 16

        /** openWakeWord reports 0.0 for this many chunks while the buffers fill. */
        const val WARMUP_CHUNKS = 5

        /** Room for one chunk plus the overlap, with slack. */
        const val RECENT_SAMPLES = 4096

        /**
         * Measured on the A07 and approved by Poom, not chosen here.
         *
         * 0.50 needed "Hey Jarvis" said twice more often than not. At 0.40 one
         * call lands reliably, at a distance of one to two metres. The
         * false-wake side of that trade is now counted rather than assumed —
         * see VoiceStats.falseWakeCandidates — so if 0.40 turns out to wake the
         * kiosk at the television there will be a number saying so.
         */
        const val DEFAULT_THRESHOLD = 0.40f

        /**
         * Scores above this but below the threshold are worth a log line: they
         * are the ones that say "it nearly heard you" rather than "nothing
         * happened", and they are how a threshold gets chosen from evidence.
         */
        const val NEAR_MISS_FLOOR = 0.20f

        /** 2 s of chunks. One "Hey Jarvis" must wake the kiosk once, not six times. */
        const val DEFAULT_COOLDOWN_FRAMES = 25

        const val MELSPEC_ASSET = "models/melspectrogram.onnx"
        const val EMBEDDING_ASSET = "models/embedding_model.onnx"
        const val WAKEWORD_ASSET = "models/hey_jarvis_v0.1.onnx"

        /** Built from the APK's own assets. Returns null if anything is missing. */
        fun fromAssets(
            context: Context,
            threshold: Float = DEFAULT_THRESHOLD,
            cooldownFrames: Int = DEFAULT_COOLDOWN_FRAMES,
        ): HeyJarvisDetector? = runCatching {
            val bytes = { name: String -> context.assets.open(name).use { it.readBytes() } }
            fromBytes(bytes(MELSPEC_ASSET), bytes(EMBEDDING_ASSET), bytes(WAKEWORD_ASSET),
                      threshold, cooldownFrames)
        }.getOrNull()

        /** The same detector, from bytes, so a JVM test can build one. */
        fun fromBytes(
            melspecBytes: ByteArray,
            embeddingBytes: ByteArray,
            wakewordBytes: ByteArray,
            threshold: Float = DEFAULT_THRESHOLD,
            cooldownFrames: Int = DEFAULT_COOLDOWN_FRAMES,
            now: () -> Long = System::currentTimeMillis,
        ): HeyJarvisDetector {
            val environment = OrtEnvironment.getEnvironment()
            val options = { OrtSession.SessionOptions().apply { setIntraOpNumThreads(1) } }
            val melspec = environment.createSession(melspecBytes, options())
            val embedding = environment.createSession(embeddingBytes, options())
            val wakeword = environment.createSession(wakewordBytes, options())
            return HeyJarvisDetector(
                environment, melspec, embedding, wakeword,
                melspec.inputNames.first(), embedding.inputNames.first(),
                wakeword.inputNames.first(),
                threshold, cooldownFrames, now,
            )
        }
    }
}

/**
 * What runs when the models cannot be loaded: it listens to nothing and fires
 * never, and says so on the status line rather than looking broken.
 */
class NoModelDetector(private val reason: String = "no-model") : Detector {
    override val ready: Boolean get() = false
    override val state: String get() = reason
    override fun accept(frame: ShortArray, length: Int): Boolean = false
}
