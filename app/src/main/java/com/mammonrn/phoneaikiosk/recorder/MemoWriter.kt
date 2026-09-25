package com.mammonrn.phoneaikiosk.recorder

import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import java.io.File
import java.nio.ByteOrder

/**
 * Turns the microphone's frames into a small file for speech (0.61.0).
 *
 * WHAT: Opus in Ogg (".ogg"), mono, 16 kHz, 24 kbit/s — about 11 MB an hour.
 * Opus is built for speech at low rates and 16 kHz is "wideband", the whole of
 * a voice. The frames are the wake word's own (MicTap: 16 kHz mono 16-bit), so
 * nothing is resampled. The encoder is the platform's software Opus encoder
 * and the Ogg writer is the platform's MediaMuxer (both API 29+, both in AOSP
 * rather than the vendor's HAL) — the same two parts MediaRecorder's
 * OutputFormat.OGG + AudioEncoder.OPUS use inside. We cannot use MediaRecorder
 * itself because it opens its own capture (see MicTap for why nothing does).
 *
 * AN OGG FILE CUT SHORT STILL PLAYS: pages are written as the recording goes,
 * so if the process dies mid-recording the file keeps what was said up to the
 * last page. An MP4 that is never finished does not play at all.
 *
 * FALLBACK: [probe] encodes a tenth of a second of silence once per process.
 * If Opus-in-Ogg does not work on the phone, AAC in ".m4a" (32 kbit/s) is used
 * instead — also a sound file the music player opens (MediaKinds). The log
 * says which: `KioskRec: format=ogg|m4a`.
 *
 * NOT THREAD-SAFE: one thread (VoiceMemo's "kiosk-rec") calls everything.
 */
class MemoWriter private constructor(val file: File, private val format: Format) {

    enum class Format(val extension: String, val mime: String, val muxer: Int, val bitRate: Int) {
        OPUS("ogg", MediaFormat.MIMETYPE_AUDIO_OPUS, MediaMuxer.OutputFormat.MUXER_OUTPUT_OGG, 24_000),
        AAC("m4a", MediaFormat.MIMETYPE_AUDIO_AAC, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4, 32_000),
    }

    private val codec: MediaCodec = MediaCodec.createEncoderByType(format.mime)
    private var muxer: MediaMuxer? = null
    private var track = -1
    private var muxing = false
    private val info = MediaCodec.BufferInfo()

    /** Samples given to the encoder: the file's length, pauses left out. */
    var samples = 0L
        private set

    init {
        try {
            codec.configure(mediaFormat(format), null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            codec.start()
            muxer = MediaMuxer(file.path, format.muxer)
        } catch (e: Exception) {
            runCatching { codec.release() }
            runCatching { muxer?.release() }
            // A new name (RecorderNames.freeBase), so this can only be our own empty file.
            if (file.length() == 0L) file.delete()
            throw e
        }
    }

    /** 16-bit mono samples at [SAMPLE_RATE]. */
    fun write(frame: ShortArray, count: Int) {
        var offset = 0
        var waits = 0
        while (offset < count) {
            val index = codec.dequeueInputBuffer(WAIT_US)
            if (index < 0) {
                drain(end = false)
                if (++waits > MAX_WAITS) throw IllegalStateException("encoder input stalled")
                continue
            }
            val buffer = codec.getInputBuffer(index) ?: throw IllegalStateException("no input buffer")
            buffer.clear()
            buffer.order(ByteOrder.LITTLE_ENDIAN)
            val n = minOf(count - offset, buffer.remaining() / 2)
            buffer.asShortBuffer().put(frame, offset, n)
            codec.queueInputBuffer(index, 0, n * 2, timeUs(samples), 0)
            samples += n
            offset += n
        }
        drain(end = false)
    }

    /** Ends the file. Returns its length in ms; a file with no sound in it is removed and 0 returned. */
    fun finish(): Long {
        try {
            val index = codec.dequeueInputBuffer(WAIT_US * 10)
            if (index >= 0) codec.queueInputBuffer(index, 0, 0, timeUs(samples), MediaCodec.BUFFER_FLAG_END_OF_STREAM)
            drain(end = true)
        } finally {
            runCatching { codec.stop() }
            runCatching { codec.release() }
            if (muxing) runCatching { muxer?.stop() }
            runCatching { muxer?.release() }
            muxer = null
        }
        if (!muxing || samples == 0L) {
            file.delete()
            return 0
        }
        return samples * 1000 / SAMPLE_RATE
    }

    /** Something went wrong: close everything, keep whatever was written if it can play. */
    fun abandon() {
        runCatching { codec.stop() }
        runCatching { codec.release() }
        if (muxing) runCatching { muxer?.stop() }
        runCatching { muxer?.release() }
        muxer = null
        if (!muxing) file.delete()
    }

    private fun drain(end: Boolean) {
        var idle = 0
        while (true) {
            val index = codec.dequeueOutputBuffer(info, if (end) WAIT_US else 0)
            when {
                index == MediaCodec.INFO_TRY_AGAIN_LATER -> {
                    if (!end || ++idle > MAX_WAITS) return
                }
                index == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> {
                    val m = muxer ?: return
                    if (!muxing) {
                        track = m.addTrack(codec.outputFormat)
                        m.start()
                        muxing = true
                    }
                }
                index >= 0 -> {
                    val out = codec.getOutputBuffer(index)
                    // The codec's header travels in the track's format (addTrack), not as a sample.
                    val config = info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0
                    if (out != null && muxing && !config && info.size > 0) {
                        out.position(info.offset)
                        out.limit(info.offset + info.size)
                        muxer?.writeSampleData(track, out, info)
                    }
                    codec.releaseOutputBuffer(index, false)
                    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return
                }
            }
        }
    }

    companion object {
        const val SAMPLE_RATE = 16_000
        private const val WAIT_US = 10_000L
        private const val MAX_WAITS = 200

        private fun timeUs(samples: Long) = samples * 1_000_000 / SAMPLE_RATE

        private fun mediaFormat(format: Format) =
            MediaFormat.createAudioFormat(format.mime, SAMPLE_RATE, 1).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, format.bitRate)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, SAMPLE_RATE / 4 * 2)
                if (format == Format.AAC) {
                    setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
                }
            }

        /** Which format works on this phone, found once per process by [probe]. */
        @Volatile
        private var chosen: Format? = null

        /**
         * Opus-in-Ogg if a tenth of a second of silence encodes and muxes into
         * a file with something in it, otherwise AAC. Runs on the caller's
         * thread (the recorder's worker), ~50 ms, once per process.
         */
        fun probe(scratch: File): Format {
            chosen?.let { return it }
            val works = runCatching {
                val test = File(scratch, "rec-probe.ogg")
                test.delete()
                val writer = MemoWriter(test, Format.OPUS)
                val silence = ShortArray(SAMPLE_RATE / 10)
                writer.write(silence, silence.size)
                val ms = writer.finish()
                val ok = ms > 0 && test.length() > 0
                test.delete()
                ok
            }.getOrDefault(false)
            return (if (works) Format.OPUS else Format.AAC).also { chosen = it }
        }

        /** A new writer for [file] in [format]. Throws if the encoder cannot start. */
        fun open(file: File, format: Format): MemoWriter = MemoWriter(file, format)
    }
}
