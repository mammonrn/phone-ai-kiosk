package com.mammonrn.phoneaikiosk.voice

import java.io.ByteArrayOutputStream

/**
 * THE FIRST SYLLABLE (0.49.0, Poom 2026-09-24). Recording starts when the wake
 * word FIRES, and it fires after "Jarvis" has ended — so somebody who says
 * "Hey Jarvis เปิดไฟหน้าบ้าน" in one breath has already said "เปิ…" by then,
 * and it never reaches the transcriber. On production "เปิดไฟหน้าบ้าน" came
 * back as "ปิดหน้าบ้าน" (11 characters), twice, and the porch light was
 * switched off.
 *
 * So the last second of microphone frames is kept here, in memory only, and
 * when a capture starts from the wake word the frames AFTER THE GAP that
 * follows "Jarvis" are put in front of the recording ([framesToKeep]). No gap
 * — the command ran straight on — and a fixed [FALLBACK_FRAMES] are kept; the
 * broker strips a "จาร์วิส" that comes with them (speech_gate.strip_wake).
 *
 * PRIVACY. Nothing new is kept: the wake detector already hears these same
 * frames. They live in this ring, are overwritten every ~62 ms, and reach the
 * network only as the start of a capture the wake word began — a cancelled
 * capture drops them with the rest. DESIGN.md 11ก.
 *
 * Capture-thread only; not thread-safe on purpose.
 */
class PreRoll(private val capacity: Int = CAPACITY_FRAMES) {

    private val frames = ArrayDeque<ShortArray>(capacity)
    private val peaks = ArrayDeque<Int>(capacity)

    val size: Int get() = frames.size

    /** One microphone frame, copied: the recorder reuses its array. */
    fun add(frame: ShortArray, read: Int, peak: Int) {
        if (frames.size == capacity) {
            frames.removeFirst()
            peaks.removeFirst()
        }
        frames.addLast(frame.copyOf(read))
        peaks.addLast(peak)
    }

    /** A deaf stretch (playback, an alarm) holds no speech worth keeping. */
    fun clear() {
        frames.clear()
        peaks.clear()
    }

    fun peaks(): IntArray = peaks.toIntArray()

    /** Writes the newest [count] frames, oldest first, as 16-bit little-endian PCM. */
    fun writeNewest(count: Int, out: ByteArrayOutputStream) {
        val from = (frames.size - count).coerceAtLeast(0)
        for (i in from until frames.size) {
            for (sample in frames[i]) {
                out.write(sample.toInt() and 0xFF)
                out.write((sample.toInt() shr 8) and 0xFF)
            }
        }
    }

    companion object {
        /** 16 frames of ~62 ms (Recorder.frameSamples at 16 kHz): a second. */
        const val CAPACITY_FRAMES = 16

        /** How far back to look for the gap after "Jarvis": ~625 ms. */
        const val LOOKBACK_FRAMES = 10

        /** No gap found: ~375 ms, about one syllable and a half. */
        const val FALLBACK_FRAMES = 6

        /** A pause between two words is shorter than this; the one after "Jarvis" is not. */
        const val GAP_FRAMES = 2

        /**
         * How many of the newest frames to put in front of the recording.
         * [peaks] is oldest first; the last one is the frame the wake word fired
         * on. Walks back from it to the most recent GAP — [GAP_FRAMES] frames in
         * a row under [threshold], the capture's own speech threshold — and
         * keeps what follows it: the command already under way. A single quiet
         * frame is not a gap: it is as likely the dip between "เปิด" and "ไฟ",
         * and cutting there would lose "เปิด" all over again. The newest frames
         * quiet: nobody has started yet, nothing to keep. No gap within
         * [lookback]: [fallback].
         */
        fun framesToKeep(peaks: IntArray, threshold: Int, lookback: Int = LOOKBACK_FRAMES,
                         fallback: Int = FALLBACK_FRAMES): Int {
            if (peaks.isEmpty()) return 0
            val last = peaks.size - 1
            val floor = (last - lookback + 1).coerceAtLeast(0)
            var quietRun = 0
            for (i in last downTo floor) {
                if (peaks[i] < threshold) {
                    quietRun += 1
                    if (quietRun >= GAP_FRAMES) return last - (i + GAP_FRAMES - 1)
                } else {
                    quietRun = 0
                }
            }
            return minOf(fallback, peaks.size)
        }
    }
}
