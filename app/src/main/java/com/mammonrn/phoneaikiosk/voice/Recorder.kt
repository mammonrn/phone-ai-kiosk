package com.mammonrn.phoneaikiosk.voice

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.ByteArrayOutputStream

/**
 * Microphone capture at exactly what the transcriber wants.
 *
 * 16 kHz mono 16-bit, because that is what Groq's documentation recommends
 * converting to before sending — doing it at the source means no resampling
 * anywhere, and it is also the cheapest thing to upload.
 *
 * Nothing here writes a file. Audio exists as a byte array for as long as one
 * question takes and is then dropped.
 */
class Recorder(
    val helpers: AudioHelpers = AudioHelpers(),
    /** To find the phone's own microphone (0.68, [builtInMic]); null in tests. */
    private val audio: android.media.AudioManager? = null,
) {

    /** One frame, ~64 ms. Small enough for a wake word stage to work on. */
    val frameSamples = SAMPLE_RATE / 16

    /**
     * The audio source to use NEXT time the microphone is opened.
     *
     * Not applied in place: an AudioRecord's source is fixed when it is
     * constructed, so changing this asks the capture loop to close the one it
     * has and open another. That is the only safe way to do it — the loop is
     * the single owner of the recorder, and two AudioRecords open at once is
     * the bug that shipped in versionCode 4.
     */
    @Volatile
    var requestedSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION

    /** The source actually in use right now. */
    @Volatile
    var activeSource: Int = MediaRecorder.AudioSource.VOICE_RECOGNITION
        private set

    /** Set by the adb switches; read by the capture loop between frames. */
    @Volatile
    var reopenRequested: Boolean = false

    @SuppressLint("MissingPermission") // the service checks before it starts
    private fun open(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffer = maxOf(minBuffer, frameSamples * 2 * 8)
        // VOICE_RECOGNITION by default rather than MIC: the platform applies
        // capture tuned for speech, which is what a device across the room
        // needs. Overridable over adb so the alternatives can be MEASURED on
        // the A07 — see AudioHelpers.SOURCES — without the default moving.
        activeSource = requestedSource
        val record = AudioRecord(
            activeSource,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer,
        )
        preferBuiltInMic(record)
        return record
    }

    /**
     * THE WAKE WORD ALWAYS HEARS THE PHONE (0.68, Poom): with headphones or a speaker
     * on Bluetooth the SOUND may go there, but the microphone that listens for "Hey
     * Jarvis" and the question stays the phone's own — a headset's microphone is
     * across the room or in someone's ears, and taking it would need a call-style
     * (SCO) link that drops music to phone quality. So the recorder asks for the
     * built-in microphone by name, and nothing in this app starts SCO or sets a
     * communication device (BluetoothTest reads the code for it).
     */
    private fun preferBuiltInMic(record: AudioRecord) {
        val am = audio ?: return
        val inputs = runCatching { am.getDevices(android.media.AudioManager.GET_DEVICES_INPUTS).toList() }.getOrDefault(emptyList())
        val id = builtInMic(inputs.map { it.type to it.id })
        val device = inputs.firstOrNull { it.id == id }
        val ok = device != null && runCatching { record.setPreferredDevice(device) }.getOrDefault(false)
        if (ok != lastPreferred) {
            lastPreferred = ok
            // Counts and a yes/no only: no device names.
            runCatching { android.util.Log.i("KioskMic", "preferred builtin=$ok inputs=${inputs.size}") }
        }
    }

    @Volatile private var lastPreferred: Boolean? = null

    /**
     * Reads frames until [onFrame] returns false or [shouldStop] says to stop.
     *
     * The caller owns the loop so the same capture can serve the wake word stage
     * and, once it fires, the question — without releasing and re-acquiring the
     * microphone in between, which is where a "wake word heard, first word lost"
     * bug would come from.
     */
    fun listen(shouldStop: () -> Boolean, onFrame: (ShortArray, Int) -> Boolean) {
        runLoop(
            shouldStop = shouldStop,
            openSession = {
                val record = open()
                helpers.attach(record.audioSessionId)
                record.startRecording()
                liveSession = record.audioSessionId
                object : Session {
                    override fun read(into: ShortArray) = record.read(into, 0, into.size)
                    override fun close() {
                        liveSession = 0
                        helpers.release()
                        runCatching { record.stop() }
                        record.release()
                    }
                }
            },
            onFrame = onFrame,
        )
    }

    /** One open microphone. Exists so the loop below can be tested. */
    interface Session {
        fun read(into: ShortArray): Int
        fun close()
    }

    /**
     * The capture loop, with the microphone behind an interface.
     *
     * THE INVARIANT THIS SHAPE EXISTS FOR: at most one session open at any
     * moment, and a session is always closed before the next is opened. The
     * adb switches for the audio source and the microphone effects both work by
     * setting [reopenRequested], and both arrive on the binder thread — so the
     * thing that must not happen is a switch opening a recorder while this
     * thread still holds one. Two AudioRecords at once is the bug that shipped
     * in versionCode 4 and it is not shipping again.
     *
     * Separated from [listen] so a test can count the overlap with no Android
     * in the room; the real implementation is four lines above it.
     */
    internal fun runLoop(
        shouldStop: () -> Boolean,
        openSession: () -> Session,
        onFrame: (ShortArray, Int) -> Boolean,
    ) {
        val frame = ShortArray(frameSamples)
        while (!shouldStop()) {
            reopenRequested = false
            val session = openSession()
            try {
                while (!shouldStop() && !reopenRequested) {
                    val read = session.read(frame)
                    if (read <= 0) continue
                    if (!onFrame(frame, read)) return
                }
            } finally {
                session.close()
            }
        }
    }

    /**
     * Appends one frame of samples to a buffer as little-endian 16-bit PCM.
     *
     * Public so the capture loop can accumulate a question using the recorder it
     * already has open. The previous version had a `recordQuestion` that opened
     * a SECOND AudioRecord while the listening loop still held the first, which
     * is not something a phone reliably allows.
     */
    fun appendPcm(out: ByteArrayOutputStream, frame: ShortArray, read: Int) {
        for (i in 0 until read) {
            val sample = frame[i].toInt()
            out.write(sample and 0xFF)
            out.write((sample shr 8) and 0xFF)
        }
    }

    /** Puts a RIFF header in front of accumulated PCM. */
    fun wrapAsWav(pcm: ByteArray): ByteArray = wav(pcm)

    /** A 44-byte RIFF header in front of the samples. */
    private fun wav(pcm: ByteArray): ByteArray {
        val out = ByteArrayOutputStream(44 + pcm.size)
        fun ascii(s: String) = out.write(s.toByteArray(Charsets.US_ASCII))
        fun le32(v: Int) {
            out.write(v and 0xFF); out.write((v shr 8) and 0xFF)
            out.write((v shr 16) and 0xFF); out.write((v shr 24) and 0xFF)
        }
        fun le16(v: Int) { out.write(v and 0xFF); out.write((v shr 8) and 0xFF) }

        ascii("RIFF"); le32(36 + pcm.size); ascii("WAVE")
        ascii("fmt "); le32(16); le16(1); le16(1)
        le32(SAMPLE_RATE); le32(SAMPLE_RATE * 2); le16(2); le16(16)
        ascii("data"); le32(pcm.size)
        out.write(pcm)
        return out.toByteArray()
    }

    companion object {
        const val SAMPLE_RATE = 16_000

        /**
         * The phone's own microphone among the inputs, as (type, id) pairs: the id of
         * the first TYPE_BUILTIN_MIC, or null when there is none. Never a Bluetooth
         * (SCO or LE) or wired headset's microphone. Plain Kotlin: BluetoothTest.
         */
        fun builtInMic(inputs: List<Pair<Int, Int>>): Int? =
            inputs.firstOrNull { it.first == android.media.AudioDeviceInfo.TYPE_BUILTIN_MIC }?.second

        /**
         * The audio session of the wake word's capture while it is open, else 0. Lets
         * social/SocialVisit tell ANOTHER app's recording from ours (0.65.0).
         */
        @Volatile
        var liveSession: Int = 0

        /**
         * Peak amplitude that counts as speech rather than room noise. 16-bit
         * samples run to 32767; 2000 is about 6% of full scale, which a voice
         * across a room clears and a fan does not. Tune it on the A07 with the
         * levels the status line reports.
         */
        const val SPEECH_THRESHOLD = 2000
    }
}
