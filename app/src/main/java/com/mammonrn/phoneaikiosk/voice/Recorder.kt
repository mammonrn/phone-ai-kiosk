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
class Recorder {

    /** One frame, ~64 ms. Small enough for a wake word stage to work on. */
    val frameSamples = SAMPLE_RATE / 16

    @SuppressLint("MissingPermission") // the service checks before it starts
    private fun open(): AudioRecord {
        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
        )
        val buffer = maxOf(minBuffer, frameSamples * 2 * 8)
        return AudioRecord(
            // VOICE_RECOGNITION rather than MIC: the platform applies the noise
            // suppression tuned for speech, which is what a device across the
            // room needs.
            MediaRecorder.AudioSource.VOICE_RECOGNITION,
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT, buffer,
        )
    }

    /**
     * Reads frames until [onFrame] returns false or [shouldStop] says to stop.
     *
     * The caller owns the loop so the same capture can serve the wake word stage
     * and, once it fires, the question — without releasing and re-acquiring the
     * microphone in between, which is where a "wake word heard, first word lost"
     * bug would come from.
     */
    fun listen(shouldStop: () -> Boolean, onFrame: (ShortArray, Int) -> Boolean) {
        val record = open()
        try {
            record.startRecording()
            val frame = ShortArray(frameSamples)
            while (!shouldStop()) {
                val read = record.read(frame, 0, frame.size)
                if (read <= 0) continue
                if (!onFrame(frame, read)) return
            }
        } finally {
            runCatching { record.stop() }
            record.release()
        }
    }

    /**
     * Records one question and returns it as a WAV.
     *
     * Stops on [maxMillis] or after [silenceMillis] of quiet, whichever comes
     * first. The silence test is a plain amplitude threshold: a room is not
     * silent, so the bar is "quieter than speech", not "zero".
     */
    fun recordQuestion(maxMillis: Int, silenceMillis: Int): ByteArray {
        val pcm = ByteArrayOutputStream()
        val frameMs = frameSamples * 1000 / SAMPLE_RATE
        var elapsed = 0
        var quiet = 0
        var heardAnything = false

        listen(shouldStop = { false }) { frame, read ->
            for (i in 0 until read) {
                val sample = frame[i].toInt()
                pcm.write(sample and 0xFF)
                pcm.write((sample shr 8) and 0xFF)
            }

            var peak = 0
            for (i in 0 until read) {
                val magnitude = kotlin.math.abs(frame[i].toInt())
                if (magnitude > peak) peak = magnitude
            }

            if (peak > SPEECH_THRESHOLD) {
                heardAnything = true
                quiet = 0
            } else if (heardAnything) {
                quiet += frameMs
            }

            elapsed += frameMs
            elapsed < maxMillis && quiet < silenceMillis
        }

        return wav(pcm.toByteArray())
    }

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
         * Peak amplitude that counts as speech rather than room noise. 16-bit
         * samples run to 32767; 2000 is about 6% of full scale, which a voice
         * across a room clears and a fan does not. Tune it on the A07 with the
         * levels the status line reports.
         */
        const val SPEECH_THRESHOLD = 2000
    }
}
