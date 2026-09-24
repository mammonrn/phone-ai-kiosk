package com.mammonrn.phoneaikiosk.media

import java.io.File
import java.io.RandomAccessFile

/**
 * What is really inside an .m4a (0.60.0, Poom: "m4a ต้องแยกจาก codec จริงข้างใน
 * ไฟล์ ไม่ใช่จากนามสกุล"): AAC plays through Media3 (with the equalizer and the
 * bars), ALAC through LibVLC, since the A07 has no ALAC decoder.
 *
 * Reads only the boxes it needs — the top-level headers, then the "moov" box —
 * and walks moov › trak › mdia (a "soun" handler) › minf › stbl › stsd to the
 * first sample entry's four letters: "mp4a", "alac", … A moov at the end of
 * the file (not "fast start") is found the same way. Plain Kotlin; Mp4SniffTest.
 */
object Mp4Sniff {

    /** A moov bigger than this is not read (a sound file's is a few hundred KB at most). */
    private const val MAX_MOOV = 16 * 1024 * 1024

    /** The audio codec of a local file, or null when it cannot be told. */
    fun audioCodec(file: File): String? = runCatching {
        RandomAccessFile(file, "r").use { f ->
            audioCodec(f.length()) { pos, len ->
                val b = ByteArray(len)
                f.seek(pos)
                f.readFully(b)
                b
            }
        }
    }.getOrNull()

    /** [read] gives [len] bytes at [pos] of a file [size] long. */
    fun audioCodec(size: Long, read: (pos: Long, len: Int) -> ByteArray): String? {
        var pos = 0L
        while (pos + 8 <= size) {
            val head = read(pos, minOf(16L, size - pos).toInt())
            var boxSize = u32(head, 0)
            val type = String(head, 4, 4, Charsets.ISO_8859_1)
            var headLen = 8
            if (boxSize == 1L && head.size >= 16) { boxSize = u64(head, 8); headLen = 16 }
            if (boxSize == 0L) boxSize = size - pos
            if (boxSize < headLen) return null
            if (type == "moov") {
                val len = boxSize - headLen
                if (len > MAX_MOOV) return null
                return inMoov(read(pos + headLen, len.toInt()))
            }
            pos += boxSize
        }
        return null
    }

    private fun inMoov(moov: ByteArray): String? {
        for ((type, s, e) in boxes(moov, 0, moov.size)) {
            if (type != "trak") continue
            val mdia = child(moov, s, e, "mdia") ?: continue
            val hdlr = child(moov, mdia.first, mdia.second, "hdlr") ?: continue
            // hdlr: version/flags (4), pre_defined (4), handler_type (4).
            if (hdlr.first + 12 > hdlr.second) continue
            if (String(moov, hdlr.first + 8, 4, Charsets.ISO_8859_1) != "soun") continue
            val minf = child(moov, mdia.first, mdia.second, "minf") ?: continue
            val stbl = child(moov, minf.first, minf.second, "stbl") ?: continue
            val stsd = child(moov, stbl.first, stbl.second, "stsd") ?: continue
            // stsd: version/flags (4), entry_count (4), then the first entry: size (4), type (4).
            val at = stsd.first + 8
            if (at + 8 > stsd.second) continue
            return String(moov, at + 4, 4, Charsets.ISO_8859_1)
        }
        return null
    }

    /** The body (start, end) of the first [type] box directly inside [from]..[to]. */
    private fun child(b: ByteArray, from: Int, to: Int, type: String): Pair<Int, Int>? =
        boxes(b, from, to).firstOrNull { it.first == type }?.let { it.second to it.third }

    /** Every box directly inside [from]..[to]: its type and its body's start and end. */
    private fun boxes(b: ByteArray, from: Int, to: Int): List<Triple<String, Int, Int>> {
        val out = ArrayList<Triple<String, Int, Int>>()
        var p = from
        while (p + 8 <= to) {
            var size = u32(b, p)
            var head = 8
            if (size == 1L && p + 16 <= to) { size = u64(b, p + 8); head = 16 }
            if (size == 0L) size = (to - p).toLong()
            if (size < head || p + size > to) break
            out += Triple(String(b, p + 4, 4, Charsets.ISO_8859_1), p + head, (p + size).toInt())
            p += size.toInt()
        }
        return out
    }

    private fun u32(b: ByteArray, at: Int): Long =
        ((b[at].toLong() and 0xFF) shl 24) or ((b[at + 1].toLong() and 0xFF) shl 16) or
            ((b[at + 2].toLong() and 0xFF) shl 8) or (b[at + 3].toLong() and 0xFF)

    private fun u64(b: ByteArray, at: Int): Long = (u32(b, at) shl 32) or u32(b, at + 4)
}
