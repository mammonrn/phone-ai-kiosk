package com.mammonrn.phoneaikiosk.media

/**
 * A spoken video command, carried out on the phone (0.57.0, Poom: "เปิดวิดีโอ
 * / หยุดวิดีโอ"). The broker recognises it in code (server/kiosk_broker/video.py)
 * and sends {command, query}; only the phone knows its videos, so only the
 * phone can say whether one was found. Done before any word is said, as the
 * music (MusicVoice): when it cannot be done, the reply IS the reason.
 *
 * 0.58.0: a video plays only on its screen, and leaving the screen stops it
 * (Poom). A question closes the screen, so by the time "หยุดวิดีโอ" is heard
 * the video has already stopped: the reply says so ([ALREADY_STOPPED]).
 * "เล่นวิดีโอต่อ" opens the last video again, from where it was left.
 *
 * Plain Kotlin behind [Deck]; VideoVoiceTest drives every case.
 */
object VideoVoice {

    const val PLAY = "play"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val STOP = "stop"
    val COMMANDS = setOf(PLAY, PAUSE, RESUME, STOP)

    interface Deck {
        val hasMedia: Boolean
        val playing: Boolean
        /** A video was watched and can be opened again from its place. */
        val hasLast: Boolean
        fun play(videos: List<Video>, start: Int)
        fun resume()
        fun pause()
        fun stop()
    }

    /** Null when done (the broker's words stand), else the words to say instead. */
    fun perform(command: String, query: String, library: () -> List<Video>, deck: Deck): String? = when (command) {
        PLAY -> if (query.isNotBlank()) playFound(query.trim(), library(), deck) else playAny(library, deck)
        RESUME -> if (deck.hasMedia || deck.hasLast) { if (!deck.playing) deck.resume(); null } else NOTHING_LEFT
        PAUSE, STOP -> if (!deck.hasMedia) ALREADY_STOPPED else { if (command == PAUSE) deck.pause() else deck.stop(); null }
        else -> NOT_UNDERSTOOD
    }

    private fun playFound(query: String, library: List<Video>, deck: Deck): String? {
        if (library.isEmpty()) return NO_VIDEOS
        val found = MusicLibrary.find(library.map { it.track }, query) ?: return notFound(query)
        val first = found.tracks.first()
        deck.play(library, library.indexOfFirst { it.track == first })
        return null
    }

    /** "เปิดวิดีโอ" alone: go on with the one left paused, or say there is none to pick. */
    private fun playAny(library: () -> List<Video>, deck: Deck): String? {
        if (deck.playing) return null
        if (deck.hasMedia || deck.hasLast) { deck.resume(); return null }
        return if (library().isEmpty()) NO_VIDEOS else WHICH_ONE
    }

    fun notFound(query: String): String {
        val name = if (query.length > 24) query.take(23).trimEnd() + "…" else query
        return "ไม่พบวิดีโอ \"$name\" ในเครื่องครับ"
    }

    const val NO_VIDEOS = "ยังไม่มีวิดีโอในเครื่องครับ"
    const val ALREADY_STOPPED = "วิดีโอหยุดอยู่แล้วครับ"
    const val NOTHING_LEFT = "ไม่มีวิดีโอที่ค้างไว้ครับ บอกชื่อวิดีโอที่จะดูได้เลยครับ"
    const val WHICH_ONE = "จะดูวิดีโอเรื่องไหนครับ บอกชื่อได้เลยครับ"
    const val NOT_UNDERSTOOD = "ผมไม่เข้าใจคำสั่งวิดีโอนี้ครับ"
}
