package com.mammonrn.phoneaikiosk.media

import java.text.Normalizer

/** One song: where it is, and the names it can be asked for by. */
data class Track(
    /** "local:/storage/emulated/0/Music/a.flac" or "nas:Music\\a.flac" — also its identity. */
    val id: String,
    val title: String,
    val artist: String = "",
    val album: String = "",
    val durationMs: Long = 0,
) {
    val onNas: Boolean get() = id.startsWith(NAS)
    /** The path after the source prefix. */
    val path: String get() = id.substringAfter(':')

    companion object {
        const val LOCAL = "local:"
        const val NAS = "nas:"
    }
}

/**
 * Which files are music, and finding a song by what somebody said (0.53.0).
 * Plain Kotlin; MusicLibraryTest.
 *
 * FORMATS, Poom's decision: FLAC, ALAC, WAV, MP3, AAC, Opus. Nothing needs a
 * decoder of our own — Android's MediaCodec decodes all six — so no 22 MB
 * library. DSD (.dsf .dff), APE and WavPack are left out on purpose and say
 * so when met ([unsupportedReason]).
 *
 * FINDING A SONG BY VOICE. What arrives is a transcript, so it can be off by a
 * tone mark or a letter ("ภูชี้ฟ้า" came back "พูชีฟ้า" once). [find] compares
 * with spaces, punctuation, case, and Thai tone marks set aside, and allows a
 * small edit distance on names of any length. Title before artist before
 * album: "เปิดเพลงคิดถึง" is the song, not an album that has it in its name.
 * Nothing close enough is NOT FOUND — the phone then says so, and never that
 * a song is playing (the map and the alarm lessons).
 */
object MusicLibrary {

    /** Extensions played, lower case. m4a is AAC or ALAC; ogg/opus is Opus. */
    val PLAYABLE = setOf("flac", "wav", "mp3", "m4a", "aac", "opus", "ogg", "alac")

    /** Known music formats that are not played, and why (Poom's decision). */
    private val REFUSED = mapOf("dsf" to "DSD", "dff" to "DSD", "ape" to "APE", "wv" to "WavPack")

    fun extension(name: String): String = name.substringAfterLast('.', "").lowercase()

    fun playable(name: String): Boolean = extension(name) in PLAYABLE

    /** "DSD" / "APE" / "WavPack" for a format left out on purpose, else null. */
    fun unsupportedReason(name: String): String? = REFUSED[extension(name)]

    /** The name without its extension, for a file with no tags. */
    fun titleFromFile(name: String): String = name.substringBeforeLast('.').ifBlank { name }

    // ------------------------------------------------------------ search

    enum class By { TITLE, ARTIST, ALBUM }

    /** What a spoken name matched: how, and the tracks to play in order. */
    data class Found(val by: By, val name: String, val tracks: List<Track>)

    /**
     * The best match for [spoken] among [tracks], or null. An artist or album
     * gives all its songs, in title order; a title gives the one song.
     */
    fun find(tracks: List<Track>, spoken: String): Found? {
        val q = key(spoken)
        if (q.length < 2) return null
        // Exact (after normalising), then contained, then close — for each field in turn.
        for (pass in 0..2) {
            for (by in By.entries) {
                val hits = tracks.filter { t -> matches(field(t, by), q, pass) }
                if (hits.isEmpty()) continue
                return when (by) {
                    By.TITLE -> Found(by, hits.first().title, listOf(bestTitle(hits, q)))
                    else -> Found(by, field(hits.first(), by), hits.sortedBy { it.title.lowercase() })
                }
            }
        }
        return null
    }

    private fun bestTitle(hits: List<Track>, q: String): Track =
        hits.minByOrNull { kotlin.math.abs(key(it.title).length - q.length) } ?: hits.first()

    private fun field(t: Track, by: By): String = when (by) {
        By.TITLE -> t.title
        By.ARTIST -> t.artist
        By.ALBUM -> t.album
    }

    private fun matches(value: String, q: String, pass: Int): Boolean {
        val v = key(value)
        if (v.isEmpty()) return false
        return when (pass) {
            0 -> v == q
            1 -> q.length >= 3 && (v.contains(q) || (v.length >= 3 && q.contains(v)))
            else -> close(v, q)
        }
    }

    /** Close enough: one edit in five characters, at least one allowed. */
    internal fun close(a: String, b: String): Boolean {
        val limit = maxOf(1, minOf(a.length, b.length) / 5)
        if (kotlin.math.abs(a.length - b.length) > limit) return false
        return distance(a, b) <= limit
    }

    /** Lower case, no spaces or punctuation, no Thai tone marks or accents. */
    fun key(text: String): String {
        val decomposed = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
        return buildString {
            for (c in decomposed) {
                if (c.isWhitespace()) continue
                if (Character.getType(c) == Character.NON_SPACING_MARK.toInt() && c !in THAI_VOWEL_MARKS) continue
                if (c in THAI_TONES) continue
                if (!c.isLetterOrDigit() && c !in THAI_VOWEL_MARKS) continue
                append(c)
            }
        }
    }

    /** Tone marks and the silent mark — the part a transcriber most often gets wrong. */
    private const val THAI_TONES = "่้๊๋์"
    /** Thai vowels written above or below: they are part of the word, not accents. */
    private const val THAI_VOWEL_MARKS = "ัิีึืฺุู็ํ"

    private fun distance(a: String, b: String): Int {
        var prev = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            val cur = IntArray(b.length + 1)
            cur[0] = i
            for (j in 1..b.length) {
                cur[j] = minOf(prev[j] + 1, cur[j - 1] + 1, prev[j - 1] + if (a[i - 1] == b[j - 1]) 0 else 1)
            }
            prev = cur
        }
        return prev[b.length]
    }
}
