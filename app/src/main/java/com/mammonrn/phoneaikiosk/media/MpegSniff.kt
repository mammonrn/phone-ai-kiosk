package com.mammonrn.phoneaikiosk.media

import java.io.File

/**
 * The picture's size as it is shown, read from an MPEG-1/2 video's sequence
 * header (0.60.0). VLC does not tell it for a VCD (its track list has no size
 * and its layout callback is not called), so the screen stayed 16:9 and the
 * 4:3 picture was drawn small inside it. Only the first [LOOK_BYTES] are read;
 * nothing is logged.
 */
object MpegSniff {
    const val LOOK_BYTES = 2 * 1024 * 1024

    /**
     * MPEG-1's pel aspect ratios (height/width of a pixel), by code 1–14:
     * 8 is a 4:3 PAL picture (352×288 → 384×288), 12 a 4:3 NTSC one.
     */
    private val MPEG1_PEL = doubleArrayOf(0.0, 1.0, 0.6735, 0.7031, 0.7615, 0.8055, 0.8437, 0.8935,
                                          0.9157, 0.9815, 1.0255, 1.0695, 1.0950, 1.1575, 1.2015)

    fun shownSize(file: File): Pair<Int, Int>? = runCatching {
        file.inputStream().use { input ->
            val buf = ByteArray(LOOK_BYTES)
            var n = 0
            while (n < buf.size) { val r = input.read(buf, n, buf.size - n); if (r < 0) break; n += r }
            shownSize(buf, n)
        }
    }.getOrNull()

    /** From the first [length] bytes of a file: the first sequence header (00 00 01 B3). */
    fun shownSize(b: ByteArray, length: Int): Pair<Int, Int>? {
        var i = 0
        while (i + 11 < length) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1 && (b[i + 3].toInt() and 0xFF) == 0xB3) {
                val w = ((b[i + 4].toInt() and 0xFF) shl 4) or ((b[i + 5].toInt() and 0xF0) shr 4)
                val h = ((b[i + 5].toInt() and 0x0F) shl 8) or (b[i + 6].toInt() and 0xFF)
                val code = (b[i + 7].toInt() and 0xF0) shr 4
                if (w in 16..4096 && h in 16..4096 && code in 1..14) return shown(w, h, code, mpeg2 = isMpeg2(b, i + 12, length))
            }
            i++
        }
        return null
    }

    private fun shown(w: Int, h: Int, code: Int, mpeg2: Boolean): Pair<Int, Int> = if (mpeg2) {
        // MPEG-2: the code is the picture's own shape.
        when (code) {
            2 -> h * 4 / 3 to h
            3 -> h * 16 / 9 to h
            4 -> (h * 221 / 100) to h
            else -> w to h
        }
    } else (w / MPEG1_PEL[code]).toInt() to h

    /** MPEG-2 has a sequence extension (00 00 01 B5, id 1) soon after the header. */
    private fun isMpeg2(b: ByteArray, from: Int, length: Int): Boolean {
        var i = from
        val end = minOf(length - 4, from + 256)
        while (i < end) {
            if (b[i].toInt() == 0 && b[i + 1].toInt() == 0 && b[i + 2].toInt() == 1) {
                val code = b[i + 3].toInt() and 0xFF
                if (code == 0xB5) return (b[i + 4].toInt() and 0xF0) shr 4 == 1
                if (code == 0x00 || code == 0xB8) return false
            }
            i++
        }
        return false
    }
}
