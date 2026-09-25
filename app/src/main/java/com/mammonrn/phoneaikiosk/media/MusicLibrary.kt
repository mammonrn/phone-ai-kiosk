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


    /** Known music formats that are not played, and why (Poom's decision). */
    private val REFUSED = mapOf("dsf" to "DSD", "dff" to "DSD", "ape" to "APE", "wv" to "WavPack")

    fun extension(name: String): String = MediaKinds.extension(name)

    /** Played by the phone now (0.59.0: decided in [PlayerChoice], the one place). */
    fun playable(name: String): Boolean = PlayerChoice.playsAudio(name)

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

    // ------------------------------------------------------------ parts of one story (0.63.0, Poom)

    // In key() form: tone marks gone, so "ตอนที่" is written "ตอนที".
    private val SEQUEL = Regex("^(.+?)(?:ภาค|ตอนที|ตอน|part|pt|ep|vol)?(?<!\\d)(\\d{1,3})$")

    /**
     * "คู่โจร1" → ("คู่โจร", 1); "Toy Story 2" → ("toystory", 2); "ภาค 3", "ตอนที่ 4",
     * "Part 2" the same. Null when the title does not end in a part number (a
     * year like "2024" is four digits and is not one).
     */
    fun sequel(title: String): Pair<String, Int>? {
        val k = key(title).map { if (it in '๐'..'๙') '0' + (it - '๐') else it }.joinToString("")
        val m = SEQUEL.matchEntire(k) ?: return null
        val stem = m.groupValues[1]
        return if (stem.length < 2) null else stem to m.groupValues[2].toInt()
    }

    /**
     * The parts of one story [spoken] names without its number ("เปิดวิดีโอ
     * คู่โจร" with คู่โจร1 and คู่โจร2 in the list), in part order; empty when it
     * names one title, a part by number, or titles that are not parts of one.
     */
    fun parts(tracks: List<Track>, spoken: String): List<Track> {
        val q = key(spoken)
        if (q.length < 2 || sequel(spoken) != null) return emptyList()
        val numbered = tracks.mapNotNull { t -> sequel(t.title)?.let { t to it } }
            .filter { (_, s) -> s.first == q || (q.length >= 3 && s.first.contains(q)) }
        val stems = numbered.map { it.second.first }.toSet()
        if (numbered.size < 2 || stems.size != 1) return emptyList()
        return numbered.sortedBy { it.second.second }.map { it.first }.distinctBy { key(it.title) }
    }

    // ------------------------------------------------------------ a title heard wrong (0.61.0, Poom)

    /** What a spoken title matched when [find] found nothing: one title, two too close to call, or none. */
    sealed class Guess {
        data class One(val track: Track, val score: Double) : Guess()
        data class Two(val first: Track, val second: Track) : Guess()
        object None : Guess()
    }

    /** At least this alike to be taken (1 = the same). */
    const val GUESS_MIN = 0.6
    /** The best must be this far ahead of the next, or the phone asks which. */
    const val GUESS_MARGIN = 0.15

    /**
     * The title the transcriber most likely meant, when [find] found nothing.
     * The speech-to-text gets Thai titles wrong in the vowels far more than in
     * the consonants — on the A07 "ทะเลสีชมพู" came back as "ทรสีชมพู",
     * "ทรสี ชมพุง", "ทรัล สี ชมพูง" — so a title is scored three ways and the
     * best counts: edit distance and shared letter pairs of the whole
     * normalised title, and edit distance of its consonants alone.
     */
    fun guess(tracks: List<Track>, spoken: String): Guess {
        val q = key(spoken)
        if (q.length < 3 || tracks.isEmpty()) return Guess.None
        val scored = tracks.distinctBy { key(it.title) }.map { it to likeness(q, key(it.title)) }
            .sortedByDescending { it.second }
        val best = scored.first()
        if (best.second < GUESS_MIN) return Guess.None
        val next = scored.getOrNull(1)
        if (next != null && next.second >= GUESS_MIN && best.second - next.second < GUESS_MARGIN) return Guess.Two(best.first, next.first)
        return Guess.One(best.first, best.second)
    }

    /** 0..1: how alike two normalised titles are (see [guess]). */
    internal fun likeness(a: String, b: String): Double {
        if (a.isEmpty() || b.isEmpty()) return 0.0
        fun edits(x: String, y: String) = 1.0 - distance(x, y).toDouble() / maxOf(x.length, y.length, 1)
        val sa = consonants(a); val sb = consonants(b)
        return maxOf(edits(a, b), pairs(a, b), if (sa.isNotEmpty() && sb.isNotEmpty()) edits(sa, sb) else 0.0)
    }

    private fun pairs(a: String, b: String): Double {
        val x = a.windowed(2).toSet(); val y = b.windowed(2).toSet()
        if (x.isEmpty() || y.isEmpty()) return 0.0
        return 2.0 * (x intersect y).size / (x.size + y.size)
    }

    /** Thai vowels and vowel marks out: what is left is the consonants (and any Latin letters, digits). */
    private fun consonants(s: String): String = s.filterNot { it in 'ะ'..'ฺ' || it in 'เ'..'ๅ' || it == '็' || it == 'ํ' || it == '๎' }

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
