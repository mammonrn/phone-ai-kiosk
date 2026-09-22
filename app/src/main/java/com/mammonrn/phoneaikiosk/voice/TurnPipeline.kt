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
    private val ask: (String, String?) -> Pair<String, String>,
    private val speak: (String) -> ByteArray,
    private val play: (ByteArray) -> Boolean,
    private val sayLocally: (String) -> Boolean,
    private val state: VoiceSink,
    private val log: (String) -> Unit = {},
) {

    /** What happened, for the counters and the log. */
    enum class Outcome { COMPLETED, SPOKEN_LOCALLY, FAILED, EMPTY_AUDIO }

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

        val reply: String
        var nextConversation = conversationId
        try {
            state.chat = "asking"
            val started = System.currentTimeMillis()
            val (answer, id) = ask(question, conversationId)
            log("chat ok ${System.currentTimeMillis() - started} ms ${answer.length} chars")
            reply = answer
            nextConversation = id.ifEmpty { conversationId }
            state.reply = answer
            state.chat = "ok"
        } catch (e: Exception) {
            state.chat = "error"
            state.lastError = describe(e)
            log("chat failed: ${describe(e)}")
            speakError(e)
            return Outcome.FAILED to nextConversation
        }

        state.tts = "synthesising"
        val cloudPlayed = try {
            val started = System.currentTimeMillis()
            val audio = speak(reply)
            val ok = play(audio)
            log("tts ${if (ok) "ok" else "play-failed"} ${audio.size} bytes " +
                "${System.currentTimeMillis() - started} ms")
            ok
        } catch (e: Exception) {
            state.lastError = describe(e)
            log("tts failed: ${describe(e)}")
            false
        }

        if (cloudPlayed) {
            state.tts = "ok"
            return Outcome.COMPLETED to nextConversation
        }

        // The answer is worth more than the voice it is said in.
        val locally = sayLocally(reply)
        state.tts = if (locally) "device-fallback" else "failed"
        log("fell back to the device voice: $locally")
        return (if (locally) Outcome.SPOKEN_LOCALLY else Outcome.FAILED) to nextConversation
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
