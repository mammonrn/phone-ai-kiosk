package com.mammonrn.phoneaikiosk.media

/**
 * A spoken music command, carried out on the phone (0.53.0). The broker
 * recognises the command in code (server/kiosk_broker/music.py) and sends
 * {command, query}; only the phone knows which songs it has, so only the phone
 * can say whether one was found.
 *
 * DONE BEFORE ANY WORD IS SAID (TurnPipeline.DONE_BEFORE_SPEAKING, as the
 * alarms): when it cannot be done, the reply IS the reason — "ไม่พบเพลง …" —
 * and never the broker's "เปิดเพลง…ครับ" (the map and alarm lessons).
 * Plain Kotlin behind [Deck], so MusicVoiceTest drives every case.
 */
object MusicVoice {

    const val PLAY = "play"
    const val PAUSE = "pause"
    const val RESUME = "resume"
    const val NEXT = "next"
    const val PREVIOUS = "previous"
    const val STOP = "stop"
    const val LOUDER = "louder"
    const val QUIETER = "quieter"
    val COMMANDS = setOf(PLAY, PAUSE, RESUME, NEXT, PREVIOUS, STOP, LOUDER, QUIETER)

    /** How much one "เพิ่มเสียง" / "ลดเสียง" moves the player's volume. */
    const val VOLUME_STEP = 0.2f

    /** What a command needs of the player. */
    interface Deck {
        val hasQueue: Boolean
        val hasMedia: Boolean
        val playing: Boolean
        val volume: Float
        fun play(tracks: List<Track>)
        fun resume()
        fun pause()
        fun next()
        fun previous()
        fun stop()
        fun changeVolume(value: Float)
    }

    /** Null when done (the broker's words stand), else the words to say instead. */
    fun perform(command: String, query: String, library: () -> List<Track>, deck: Deck): String? {
        return when (command) {
            PLAY -> if (query.isNotBlank()) playFound(query.trim(), library(), deck) else playAny(library, deck)
            RESUME -> playAny(library, deck)
            PAUSE, STOP -> {
                if (!deck.hasMedia) NOTHING_PLAYING
                else { if (command == PAUSE) deck.pause() else deck.stop(); null }
            }
            NEXT, PREVIOUS -> {
                if (!deck.hasQueue) NO_QUEUE
                else { if (command == NEXT) deck.next() else deck.previous(); null }
            }
            LOUDER, QUIETER -> {
                val to = (deck.volume + if (command == LOUDER) VOLUME_STEP else -VOLUME_STEP).coerceIn(0f, 1f)
                if (to == deck.volume) (if (command == LOUDER) LOUDEST else QUIETEST)
                else { deck.changeVolume(to); null }
            }
            else -> NOT_UNDERSTOOD
        }
    }

    private fun playFound(query: String, library: List<Track>, deck: Deck): String? {
        if (library.isEmpty()) return NO_MUSIC
        val found = MusicLibrary.find(library, query)
        if (found != null) { deck.play(found.tracks); return null }
        // Heard wrong? The closest title, said by its real name; two alike, asked which (0.61.0, Poom).
        return when (val g = MusicLibrary.guess(library, query)) {
            is MusicLibrary.Guess.One -> { deck.play(listOf(g.track)); com.mammonrn.phoneaikiosk.voice.doneWords(playing(g.track.title)) }
            is MusicLibrary.Guess.Two -> which(g.first.title, g.second.title)
            MusicLibrary.Guess.None -> notFound(query)
        }
    }

    /** "เปิดเพลง “ทะเลสีชมพู” ครับ" — the title actually opened, cut to keep the line short. */
    fun playing(title: String): String = "เปิดเพลง \"${cut(title, 40)}\" ครับ"

    /** "หมายถึงเพลง “A” หรือ “B” ครับ" — two titles too close to choose between. */
    fun which(a: String, b: String): String = "หมายถึงเพลง \"${cut(a, 20)}\" หรือ \"${cut(b, 20)}\" ครับ"

    internal fun cut(s: String, max: Int): String = if (s.length > max) s.take(max - 1).trimEnd() + "…" else s

    /** "เปิดเพลง" alone: go on with the list, or play everything from the start. */
    private fun playAny(library: () -> List<Track>, deck: Deck): String? {
        if (deck.playing) return null
        if (deck.hasQueue) { deck.resume(); return null }
        val all = library()
        if (all.isEmpty()) return NO_MUSIC
        deck.play(all)
        return null
    }

    /**
     * "ไม่พบเพลง "…" ใน playlist ครับ", the name cut to keep the line under 70
     * characters. 0.59.0: only the playlists are searched, so that is where it was not found.
     */
    fun notFound(query: String): String {
        val name = if (query.length > 24) query.take(23).trimEnd() + "…" else query
        return "ไม่พบเพลง \"$name\" ใน playlist ครับ"
    }

    const val NO_MUSIC = "ยังไม่มีเพลงใน playlist ครับ"
    const val NOTHING_PLAYING = "ตอนนี้ไม่มีเพลงเล่นอยู่ครับ"
    const val NO_QUEUE = "ยังไม่มีเพลงในรายการครับ"
    const val LOUDEST = "เสียงเพลงดังสุดแล้วครับ"
    const val QUIETEST = "ปิดเสียงเพลงสุดแล้วครับ"
    const val NOT_UNDERSTOOD = "ผมไม่เข้าใจคำสั่งเพลงนี้ครับ"
}
