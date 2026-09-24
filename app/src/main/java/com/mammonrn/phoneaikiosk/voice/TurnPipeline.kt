package com.mammonrn.phoneaikiosk.voice

/**
 * One question, from recorded audio to spoken answer.
 *
 * Android-free on purpose so the sequence can be tested: transcribe, ask,
 * speak, and fall back to the on-device voice when the cloud one cannot be
 * reached. The service supplies the three steps; this drives them and reports
 * where it got to.
 */
class TurnPipeline(
    private val transcribe: (ByteArray) -> String,
    private val ask: (String, String?) -> Answer,
    private val speak: (String) -> SpokenAudio,
    private val play: (ByteArray) -> Boolean,
    private val sayLocally: (String) -> Boolean,
    /**
     * Carries out an approved action. Returns what to say if it could not be
     * done, or null when it worked or there was nothing to do.
     *
     * Runs AFTER the answer has been spoken, so the person hears "กำลังเปิด
     * แผนที่..." before the screen changes under them rather than after.
     */
    private val perform: (KioskAction) -> String? = { null },
    private val state: VoiceSink,
    private val log: (String) -> Unit = {},
) {

    /** What happened, for the counters and the log. */
    enum class Outcome {
        COMPLETED, SPOKEN_LOCALLY, FAILED, EMPTY_AUDIO,

        /**
         * Something was recorded and transcribed, but it was not a question.
         * Nothing was asked and nothing was spoken.
         */
        NO_QUESTION,
    }

    fun run(wav: ByteArray, conversationId: String?): Pair<Outcome, String?> {
        if (wav.size <= WAV_HEADER_BYTES) {
            state.stt = "empty"
            log("no audio captured (${wav.size} bytes)")
            return Outcome.EMPTY_AUDIO to conversationId
        }

        val question: String
        try {
            state.stt = "sending"
            val started = System.currentTimeMillis()
            question = transcribe(wav)
            log("stt ok ${wav.size} bytes ${System.currentTimeMillis() - started} ms " +
                "${question.length} chars")
            state.heard = question
            state.stt = "ok"
        } catch (e: Exception) {
            state.stt = "error"
            state.lastError = describe(e)
            log("stt failed: ${describe(e)}")
            speakError(e)
            return Outcome.FAILED to conversationId
        }

        // A SECOND GATE, AFTER TRANSCRIPTION.
        //
        // The first gate is upstream: a capture that never heard anybody speak
        // is cancelled before it reaches this class at all, and that is the one
        // that does the real work. This one catches what gets past it — a cough,
        // a door, a moment of television loud enough to clear the bar — where
        // the transcriber returns a word or two of nothing in particular.
        //
        // Length is a weak signal and is treated as one. Real Thai questions get
        // short: "กี่โมง" is six characters and "อะไร" is four, so the bar has to
        // sit below them, which means it only rejects the obviously empty. It is
        // a backstop, not the defence.
        val asked = question.trim()
        if (asked.length < MIN_QUESTION_CHARS) {
            state.stt = "too-short"
            state.reply = ""
            // The count, never the words.
            log("no question after the wake word: ${asked.length} chars; " +
                "not asking and not speaking")
            return Outcome.NO_QUESTION to conversationId
        }

        var reply: String
        var action: KioskAction? = null
        var nextConversation = conversationId
        try {
            state.chat = "asking"
            val started = System.currentTimeMillis()
            val answer = ask(question, conversationId)
            // The type and whether there was one — never where somebody asked
            // to be taken.
            log("chat ok ${System.currentTimeMillis() - started} ms " +
                "${answer.reply.length} chars action=${answer.action?.type ?: "none"}")
            reply = answer.reply
            action = answer.action
            nextConversation = answer.conversationId.ifEmpty { conversationId }
            state.reply = answer.reply
            state.chat = "ok"
        } catch (e: Exception) {
            state.chat = "error"
            state.lastError = describe(e)
            log("chat failed: ${describe(e)}")
            speakError(e)
            return Outcome.FAILED to nextConversation
        }

        // THE ALARMS ARE DONE FIRST, THEN SAID (0.49.0). They live on this
        // phone, so only the phone knows whether "เปิดปลุก…" found an alarm —
        // and on the A07 a garbled "…ปลุก…" was answered "เปิดปลุก …แล้วครับ"
        // with no alarm at all. So the book is changed before a word is said,
        // and when it could not be, the reply IS the reason ("ไม่พบการปลุก
        // นั้นครับ"), never the broker's claim. Maps and the camera stay after
        // the words: they open another app over the screen.
        val first = action
        if (first != null && first.type in DONE_BEFORE_SPEAKING) {
            val failure = perform(first)
            action = null
            if (failure != null) {
                log("action ${first.type} failed before speaking")
                state.lastError = "action-failed"
                reply = failure
                state.reply = failure
            }
        }

        state.tts = "synthesising"
        val cloudPlayed = try {
            // TWO TIMERS, NOT ONE. The first version wrapped a single timer
            // around synthesis AND playback, and `play` blocks until the audio
            // has finished playing — so a ten-second answer logged as ten
            // seconds of latency. That reading sent us looking for a slow
            // vendor when what was slow was the sentence being long. Synthesis
            // is latency and worth chasing; playback is the answer being said
            // out loud, and the only way to shorten it is a shorter answer.
            val synthStarted = System.currentTimeMillis()
            val audio = speak(reply)
            val synthMs = System.currentTimeMillis() - synthStarted

            state.tts = "speaking"
            val playStarted = System.currentTimeMillis()
            val ok = play(audio.bytes)
            val playMs = System.currentTimeMillis() - playStarted

            log("tts ${if (ok) "ok" else "play-failed"} ${audio.bytes.size} bytes " +
                "synth=${synthMs}ms play=${playMs}ms " +
                "played=${SpokenAudio.played(playMs, audio.audioMs)} ${audio.timing}")
            ok
        } catch (e: Exception) {
            state.lastError = describe(e)
            log("tts failed: ${describe(e)}")
            false
        }

        if (cloudPlayed) {
            state.tts = "ok"
            return finishWith(action, nextConversation, Outcome.COMPLETED)
        }

        // The answer is worth more than the voice it is said in.
        val locally = sayLocally(reply)
        state.tts = if (locally) "device-fallback" else "failed"
        log("fell back to the device voice: $locally")
        return finishWith(action, nextConversation,
                          if (locally) Outcome.SPOKEN_LOCALLY else Outcome.FAILED)
    }

    /**
     * Carries out the action, if there is one, after the words have been said.
     *
     * A failure here is spoken but does not turn a delivered answer into a
     * failed turn: the person was told what was about to happen, and then told
     * why it did not. Both are more use than an outcome enum.
     */
    private fun finishWith(
        action: KioskAction?,
        conversationId: String?,
        outcome: Outcome,
    ): Pair<Outcome, String?> {
        if (action == null) return outcome to conversationId
        val failure = perform(action)
        if (failure != null) {
            log("action ${action.type} failed")
            state.lastError = "action-failed"
            sayLocally(failure)
        }
        return outcome to conversationId
    }

    /**
     * Says the failure out loud.
     *
     * A kiosk that goes quiet is a kiosk nobody can diagnose from the sofa, and
     * the broker already writes its errors in Thai fit to read aloud —
     * including the one that says the month's budget is gone.
     */
    private fun speakError(e: Exception) {
        val message = (e as? Broker.Failure)?.message ?: "ระบบขัดข้องครับ ลองอีกครั้งนะ"
        sayLocally(message)
    }

    /** The exception, named but not quoted: its message can carry the prompt. */
    private fun describe(e: Exception): String =
        (e as? Broker.Failure)?.let { "${it.code} (${it.status})" } ?: e.javaClass.simpleName

    companion object {
        const val WAV_HEADER_BYTES = 44

        /**
         * Below this, a transcript is not a question worth paying to answer.
         *
         * Deliberately low. The job of not answering the television belongs to
         * the capture stage, which never records it in the first place; this
         * only stops the empty and the single stray character.
         */
        const val MIN_QUESTION_CHARS = 3
    }
}

/**
 * An action the broker approved, in the only shape the phone accepts.
 *
 * `type` is carried even though there is exactly one, because the day a second
 * is added this class should fail to compile rather than quietly do the wrong
 * thing with it.
 */
/** Actions on the phone's own state: done before the reply is said (TurnPipeline). */
val DONE_BEFORE_SPEAKING = setOf(KioskAction.SET_ALARM, KioskAction.ALARM_ENABLE, KioskAction.MUSIC, KioskAction.VIDEO)

class KioskAction(
    val type: String,
    val destination: String,
    /** The alarm actions' fields, already checked by Broker.parseAction. */
    val params: Map<String, String> = emptyMap(),
) {
    companion object {
        /** From the broker's alarm grammar, never a model: {time "06:30", label}. */
        const val SET_ALARM = "set_alarm"

        /** {target "all" | "06:30" | a name, enabled "true" | "false"}. */
        const val ALARM_ENABLE = "alarm_enable"

        const val OPEN_MAPS = "open_maps"

        /**
         * Opens the Xiaomi Home app, and nothing else. No destination, no
         * argument of any kind: the package is fixed on the phone
         * (CameraAppLauncher) and the broker only ever sends the type, which
         * it decides from a phrase in code rather than from a model's reply.
         */
        const val OPEN_CAMERA_APP = "open_camera_app"

        /**
         * Round 2A: the broker has a private question (the calendar) and no
         * live grant for this phone. Opens the identity check; after a pass the
         * phone asks for a grant and repeats the question (VoiceService).
         */
        const val VERIFY_IDENTITY = "verify_identity"

        /**
         * 0.46.0: the broker switched a light for a spoken command (in code,
         * from eWeLink's answer). The phone does nothing but redraw the
         * "อุปกรณ์ในบ้าน" card now instead of within the minute. No fields.
         */
        const val HOME_UPDATED = "home_updated"

        /**
         * 0.53.0: a music command the broker recognised in code, {command,
         * query}. Done before speaking: only the phone knows its songs, so a
         * song not found is said as not found (media/MusicVoice).
         */
        const val MUSIC = "music"

        /** 0.57.0: a video command, {command, query}, done before speaking like the music (media/VideoVoice). */
        const val VIDEO = "video"
    }
}

/** One answer from /v1/chat. */
class Answer(val reply: String, val conversationId: String, val action: KioskAction?)

/**
 * Audio for one reply, and where the time went getting it.
 *
 * Lives here rather than in Broker so the pipeline stays free of anything that
 * knows about HTTP, which is what lets it be tested without Android.
 *
 * `timing` is a formatted string, not structured data, on purpose: its only
 * consumer is a log line. Giving it fields would invite something to start
 * making decisions on a number measured across two different clocks.
 */
class SpokenAudio(
    val bytes: ByteArray,
    val timing: String,
    /** How long the broker says the audio plays, when it could read it. */
    val audioMs: Long? = null,
) {
    companion object {
        /**
         * "full" when playback ran at least as long as the audio (less a small
         * margin for timer granularity), "early" when it stopped short, "?"
         * without a duration. With the broker's spoken/input characters this
         * is what tells "cut before synthesis" from "stopped while playing".
         */
        fun played(playMs: Long, audioMs: Long?): String = when {
            audioMs == null -> "?"
            playMs + 150 >= audioMs -> "full"
            else -> "early"
        }
    }
}

/**
 * The fields the pipeline reports into. An interface so a test can watch the
 * transitions without Android.
 */
interface VoiceSink {
    var stt: String
    var chat: String
    var tts: String
    var heard: String
    var reply: String
    var lastError: String
}
