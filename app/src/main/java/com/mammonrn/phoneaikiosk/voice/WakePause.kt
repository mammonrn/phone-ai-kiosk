package com.mammonrn.phoneaikiosk.voice

/**
 * Jarvis rests while music or a video plays (Poom, 2026-09-24).
 *
 * Poom's decision, instead of testing whether music drowns the wake word:
 * while media plays, the wake word is OFF; the Jarvis card says Jarvis is
 * resting and why; the Jarvis button still asks a question; and the moment the
 * media stops, the wake word listens again by itself.
 *
 * HOW A PLAYER USES IT (the music and video players, next round):
 *
 *     val hold = WakePause.hold(WakePause.Source.MUSIC, media)   // on play
 *     WakePause.renew(hold)                                       // every RENEW_MS while playing
 *     WakePause.release(hold)                                     // on pause, stop, end, or close
 *
 * [Media] is how Jarvis quiets the player for a question asked with the button
 * and lets it carry on after the answer.
 *
 * THE WAKE WORD ALWAYS COMES BACK, three ways, none of which needs the player
 * to get everything right:
 *  1. release() — the normal way.
 *  2. A LEASE: a hold not renewed for [LEASE_MS] lapses on its own, so a player
 *     that dies without releasing (a crash, a screen closed some odd way)
 *     leaves Jarvis resting for at most a minute and a half.
 *  3. MEMORY ONLY: nothing here is written to disk, and VoiceService calls
 *     [reset] when it is created. A restarted app, a killed process or a
 *     reboot always starts listening. A player that resumes after a restart
 *     must call hold() again; the pause never outlives the process that took it.
 *
 * NO ANDROID IN HERE, so a JVM test drives every case with its own clock. The
 * capture thread asks [paused] sixteen times a second; everything is behind
 * one lock and does no allocation on that path.
 */
object WakePause {

    /** What is playing. The word is what the Jarvis card says. */
    enum class Source(val word: String) {
        MUSIC("เล่นเพลง"),
        VIDEO("เล่นวิดีโอ"),

        /** 0.61.0: the voice recorder (recorder/VoiceMemo), only while it is actually recording. */
        RECORDER("บันทึกเสียง"),
        /** The radio (0.61.0): lowered, not paused, while Jarvis is asked something. */
        RADIO("เล่นวิทยุ"),

        /** The debug build's adb switch (TEST_MEDIA_HOLD); never used by a real player. */
        TEST("ทดสอบ"),
    }

    /**
     * What a player gives Jarvis so a question can be heard over it.
     *
     * Quieted is not stopped: the player KEEPS its hold (and keeps renewing)
     * while quieted. Releasing there would mean it is never told to resume,
     * and the wake word would come back in the middle of the music.
     */
    interface Media {
        /** A question is starting: pause, or duck well under the voice. */
        fun quietForJarvis()

        /** The turn is over: carry on as before. */
        fun resumeAfterJarvis()
    }

    /** A player's claim on the pause. Opaque to the player; pass it back. */
    class Hold internal constructor(val source: Source, internal val media: Media?) {
        internal var renewedAt = 0L
        internal var quieted = false
    }

    /** A hold that is not renewed for this long lapses. */
    const val LEASE_MS = 90_000L

    /** How often a player renews: three renewals fit in one lease. */
    const val RENEW_MS = 30_000L

    /** The clock, elapsedRealtime on the phone. Replaced by tests only. */
    @Volatile
    var clock: () -> Long = { System.nanoTime() / 1_000_000 }

    /**
     * Where [Media] calls run. The main thread on the phone (VoiceService sets
     * this), because a player's controls belong to the thread that made it.
     * Direct in tests.
     */
    @Volatile
    var post: (() -> Unit) -> Unit = { it() }

    /** Log lines: the source and what happened, never anything about the media itself. */
    @Volatile
    var log: (String) -> Unit = {}

    private val lock = Any()
    private val holds = ArrayList<Hold>()
    private var turnActive = false

    /** Media is playing: the wake word is off. Removes lapsed holds as a side effect. */
    fun paused(): Boolean = synchronized(lock) {
        dropLapsed(clock())
        holds.isNotEmpty()
    }

    /** Why Jarvis rests: the most recent hold's source, or null when it does not. */
    fun reason(): Source? = synchronized(lock) {
        dropLapsed(clock())
        holds.lastOrNull()?.source
    }

    /**
     * A player started. If a question is being asked right now, the player is
     * quieted at once — music that starts mid-question must not drown it.
     */
    fun hold(source: Source, media: Media? = null): Hold {
        val hold = Hold(source, media)
        val quietNow: Boolean
        synchronized(lock) {
            hold.renewedAt = clock()
            holds.add(hold)
            quietNow = turnActive && media != null
            if (quietNow) hold.quieted = true
        }
        log("wake pause on source=${source.name.lowercase()} holds=${count()}")
        if (quietNow) post { media?.quietForJarvis() }
        return hold
    }

    /** Still playing. Returns false if the hold had already lapsed or been released. */
    fun renew(hold: Hold): Boolean = synchronized(lock) {
        dropLapsed(clock())
        if (hold !in holds) return false
        hold.renewedAt = clock()
        true
    }

    /** The player stopped. Safe to call twice, or for a hold that lapsed. */
    fun release(hold: Hold) {
        val removed = synchronized(lock) { holds.remove(hold) }
        if (removed) log("wake pause off source=${hold.source.name.lowercase()} holds=${count()}")
    }

    /**
     * A question is starting — by the button, since the wake word is off while
     * paused (or by adb). Every playing player is quieted until [turnEnded].
     */
    fun turnStarted() {
        val quiet = ArrayList<Media>()
        synchronized(lock) {
            if (turnActive) return
            turnActive = true
            dropLapsed(clock())
            for (hold in holds) {
                val media = hold.media ?: continue
                if (!hold.quieted) {
                    hold.quieted = true
                    quiet.add(media)
                }
            }
        }
        if (quiet.isNotEmpty()) log("wake pause: quieted ${quiet.size} player(s) for a question")
        for (media in quiet) post { media.quietForJarvis() }
    }

    /**
     * The turn is over, however it ended — answered, cancelled, failed. Players
     * still holding carry on; a player released during the turn is left alone,
     * because somebody stopped it on purpose. Safe to call with no turn active.
     */
    fun turnEnded() {
        val resume = ArrayList<Media>()
        synchronized(lock) {
            if (!turnActive) return
            turnActive = false
            dropLapsed(clock())
            for (hold in holds) {
                if (hold.quieted) {
                    hold.quieted = false
                    hold.media?.let(resume::add)
                }
            }
        }
        if (resume.isNotEmpty()) log("wake pause: resumed ${resume.size} player(s)")
        for (media in resume) post { media.resumeAfterJarvis() }
    }

    /** Nothing held, no turn: what a new process starts from. VoiceService.onCreate. */
    fun reset() = synchronized(lock) {
        holds.clear()
        turnActive = false
    }

    /** One line for dumpsys. */
    fun describe(): String = synchronized(lock) {
        val now = clock()
        dropLapsed(now)
        if (holds.isEmpty()) return "off (wake word listening)"
        holds.joinToString(prefix = "ON — ", separator = ", ") { hold ->
            "${hold.source.name.lowercase()} lease ${(LEASE_MS - (now - hold.renewedAt)) / 1000}s" +
                if (hold.quieted) " quieted" else ""
        } + if (turnActive) "  (turn in progress)" else ""
    }

    private fun count(): Int = synchronized(lock) { holds.size }

    /** Caller holds [lock]. */
    private fun dropLapsed(now: Long) {
        val iterator = holds.iterator()
        while (iterator.hasNext()) {
            val hold = iterator.next()
            if (now - hold.renewedAt >= LEASE_MS) {
                iterator.remove()
                // Logged from inside the lock is fine: log() never calls back in.
                log("wake pause lapsed source=${hold.source.name.lowercase()} (not renewed)")
            }
        }
    }
}

/**
 * The capture loop's two decisions, pulled out so a JVM test can hold them:
 * when the wake word detector is deaf, and when the Jarvis button may start a
 * question. THE PAUSE IS IN THE FIRST AND NOT IN THE SECOND — that is the
 * whole of "the Jarvis button always works" (Poom, 2026-09-24).
 */
object WakeGate {

    /** Fed silence instead of the microphone: mid-turn, alarm, after speech, or media playing. */
    fun deaf(turnBusy: Boolean, alarmRinging: Boolean, now: Long, hearingFrom: Long,
             mediaPlaying: Boolean): Boolean =
        turnBusy || alarmRinging || now < hearingFrom || mediaPlaying

    /** The button needs only a listening machine; media playing does not stop it. */
    fun buttonMayStart(wakeOnly: Boolean, machineListening: Boolean): Boolean =
        !wakeOnly && machineListening
}
