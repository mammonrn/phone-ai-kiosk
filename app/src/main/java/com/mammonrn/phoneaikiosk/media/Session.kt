package com.mammonrn.phoneaikiosk.media

/**
 * The list kept for next time (0.55.0, Poom: "บันทึกรายการเพลงไว้ใช้ครั้งหน้า"
 * and "เล่นต่อจากตำแหน่งเดิมเมื่อกลับมา"): the tracks, the one playing, where
 * in it, shuffle and repeat. Plain Kotlin text so it is tested off the phone
 * (SessionTest); MusicPlayer writes it to the app's own files, never a log.
 *
 * One line of settings, then a line per track, fields split by tabs; a tab,
 * a newline or a backslash in a name is escaped.
 */
data class Session(
    val tracks: List<Track>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val repeat: PlayQueue.Repeat,
    /** 0.59.0: the playlist this list is, or null (a list made by voice). Absent in 0.55–0.58's files. */
    val playlistId: String? = null,
) {
    fun encode(): String = buildString {
        append(VERSION).append('\t').append(index).append('\t').append(positionMs).append('\t')
            .append(if (shuffle) 1 else 0).append('\t').append(repeat.name)
        if (playlistId != null) append('\t').append(esc(playlistId))
        append('\n')
        for (t in tracks) {
            append(listOf(t.id, t.title, t.artist, t.album, t.durationMs.toString()).joinToString("\t") { esc(it) })
            append('\n')
        }
    }

    companion object {
        private const val VERSION = "s1"

        /** The session in [text], or null when it is not one (a damaged file starts empty). */
        fun decode(text: String): Session? = runCatching {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            val head = lines.first().split('\t')
            if (head[0] != VERSION) return null
            val tracks = lines.drop(1).map { line ->
                val f = line.split('\t').map(::unesc)
                Track(f[0], f[1], f[2], f[3], f[4].toLong())
            }
            Session(tracks, head[1].toInt().coerceIn(-1, tracks.size - 1), head[2].toLong().coerceAtLeast(0),
                    head[3] == "1", PlayQueue.Repeat.valueOf(head[4]), head.getOrNull(5)?.let(::unesc)?.ifEmpty { null })
        }.getOrNull()

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unesc(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                    i += 2
                } else { out.append(c); i += 1 }
            }
            return out.toString()
        }
    }
}
