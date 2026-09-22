package com.mammonrn.phoneaikiosk.voice

/**
 * Whether the microphone loop is listening for the wake word, capturing a
 * question, or busy answering one — and when capturing should stop.
 *
 * Pulled out of the service as a pure class with no Android in it, because the
 * bugs that have shipped here were all state-machine bugs and there was nothing
 * to test.
 *
 * WHAT WENT WRONG IN versionCode 4: the service had one single-threaded
 * executor. The listening loop occupied it forever, so the work submitted when
 * the adb trigger fired queued behind a task that never finishes.
 *
 * WHAT WENT WRONG IN versionCode 7, which is what BUSY exists for. The machine
 * went back to LISTENING the moment a capture FINISHED — while the answer was
 * still being transcribed, asked and spoken. So the wake word could fire again
 * mid-answer and start a second turn on top of the first. On the A07 that
 * produced this, from one "Hey Jarvis" and no question at all:
 *
 *     15:48:35.057 capture finished ... bytes=112044
 *     15:48:37.298 chat ok 1235 ms 35 chars
 *     15:48:37.317 wake word detected score=0.405     <- second turn starts
 *     15:48:40.927 tts ok ... play=2934ms             <- first turn speaks
 *     15:48:42.009 capture finished ... bytes=150044  <- recorded that speech
 *
 * The second capture recorded the first answer, sent it to be transcribed, and
 * asked the model about it. A turn now holds the machine until the service says
 * the whole thing is over, speaking included.
 *
 * WHAT WENT WRONG WITH THE TELEVISION. "Speech" was a fixed peak threshold, so
 * a television kept every capture alive: `heardSpeech` became true on the first
 * frame and the recording ran to `end-of-speech` or all the way to the 12-second
 * cap, then the room's noise was transcribed and asked as a question. Speech is
 * now measured RELATIVE to the room: the machine watches the ambient level while
 * it is listening and asks for a margin above it.
 */
class CaptureMachine(
    private val frameMillis: Int,
    private val maxCaptureMillis: Int = 12_000,
    private val silenceMillis: Int = 1_200,
    /**
     * The floor under the adaptive threshold. A silent room must not make the
     * machine so sensitive that its own hiss counts as speech.
     */
    private val minSpeechThreshold: Int = Recorder.SPEECH_THRESHOLD,
    /**
     * How far above the room a frame has to be to count as somebody speaking.
     * 2.5x amplitude is about 8 dB — comfortably above a television at
     * conversational volume, and well under a person addressing the kiosk.
     */
    private val speechMargin: Float = 2.5f,
    /**
     * How long after the wake word to wait for the question to start. Somebody
     * who says "Hey Jarvis" and then nothing must cost nothing: no upload, no
     * transcription, no model call.
     */
    private val speechWaitMillis: Int = 2_500,
) {

    enum class Mode {
        /** Waiting for the wake word. The only mode in which one is accepted. */
        LISTENING,

        /** Recording a question. */
        CAPTURING,

        /** A turn is being answered. Wake words are ignored until it is over. */
        BUSY,
    }

    enum class Step {
        /** Nothing to do this frame. */
        IDLE,

        /** A trigger was consumed this frame; capture has begun. */
        STARTED,

        /** Capturing; keep the frame. */
        CAPTURING,

        /** Capture finished on this frame; the buffer is a complete question. */
        FINISHED,

        /**
         * Capture ended with nothing worth sending. The buffer is to be thrown
         * away: no transcription, no model call, no money spent.
         */
        CANCELLED,
    }

    var mode: Mode = Mode.LISTENING
        private set

    private var armed = false
    private var elapsed = 0
    private var quiet = 0
    private var waited = 0
    private var heardSpeech = false

    /** The room's own level, tracked while listening. */
    private var ambient = 0f

    /** What counts as speech for the capture in progress. */
    var speechThreshold: Int = minSpeechThreshold
        private set

    /** Reason the last capture ended, for the log line and the status. */
    var lastStopReason: String = ""
        private set

    /**
     * Asks for a capture on the next frame.
     *
     * Called from another thread — the binder thread when adb triggers it — so
     * it does nothing but set a flag. The capture thread is the only thread that
     * touches the recorder, which is what stops the two-AudioRecord problem from
     * coming back.
     */
    @Synchronized
    fun arm() {
        armed = true
    }

    @Synchronized
    fun isArmed(): Boolean = armed

    /** The ambient level the adaptive threshold is built on, for the dump. */
    @Synchronized
    fun ambientLevel(): Int = ambient.toInt()

    /**
     * Advances the machine by one frame of audio.
     *
     * [peak] is the frame's peak amplitude, [wakeWordFired] whether the detector
     * matched on this frame.
     */
    @Synchronized
    fun onFrame(peak: Int, wakeWordFired: Boolean): Step {
        if (mode == Mode.BUSY) {
            // THE SINGLE-TURN LOCK. A wake word here is not queued for later
            // either: the kiosk is already talking to this person, and the
            // sound that triggered it was most likely the kiosk itself.
            armed = false
            return Step.IDLE
        }

        if (mode == Mode.LISTENING) {
            observeAmbient(peak)
            if (!armed && !wakeWordFired) return Step.IDLE
            armed = false
            mode = Mode.CAPTURING
            elapsed = 0
            quiet = 0
            waited = 0
            heardSpeech = false
            lastStopReason = ""
            // Fixed for the whole capture, from the room as it was just before
            // the wake word. Re-measuring during the capture would let a voice
            // raise the bar against itself.
            speechThreshold = maxOf(minSpeechThreshold, (ambient * speechMargin).toInt())
            return Step.STARTED
        }

        elapsed += frameMillis
        val speaking = peak > speechThreshold

        if (!heardSpeech) {
            // Nobody has started yet. This is the window that stops "Hey Jarvis"
            // followed by silence from becoming a transcription and a question.
            if (speaking) {
                heardSpeech = true
                quiet = 0
            } else {
                waited += frameMillis
                if (waited >= speechWaitMillis) return cancel("no-speech-after-wake")
                return Step.CAPTURING
            }
        } else if (speaking) {
            quiet = 0
        } else {
            quiet += frameMillis
        }

        if (elapsed >= maxCaptureMillis) {
            // Reaching the cap without ever hearing speech cannot happen now —
            // the wait window fires long before — so this is a real question
            // that ran long, and it is kept.
            return finish("max-length")
        }
        if (heardSpeech && quiet >= silenceMillis) {
            return finish("end-of-speech")
        }
        return Step.CAPTURING
    }

    /**
     * Tracks the room while nothing is being recorded.
     *
     * Rises quickly and falls slowly: a television that comes on should raise
     * the bar within a second or two, while a single loud word should not leave
     * the kiosk deaf to the sentence after it.
     */
    private fun observeAmbient(peak: Int) {
        val value = peak.toFloat()
        ambient = if (value > ambient) {
            ambient + (value - ambient) * AMBIENT_RISE
        } else {
            ambient + (value - ambient) * AMBIENT_FALL
        }
    }

    private fun finish(reason: String): Step {
        lastStopReason = reason
        // Not back to LISTENING: the service has a turn to run, and until it
        // says otherwise this machine is busy. See the class comment.
        mode = Mode.BUSY
        return Step.FINISHED
    }

    private fun cancel(reason: String): Step {
        lastStopReason = reason
        // Straight back to listening: there is no turn to wait for.
        mode = Mode.LISTENING
        return Step.CANCELLED
    }

    /**
     * The service saying the answer is finished and spoken.
     *
     * The only way out of BUSY. Called after playback, so the wake word cannot
     * be heard again until the kiosk has stopped talking.
     */
    @Synchronized
    fun turnFinished() {
        if (mode == Mode.BUSY) mode = Mode.LISTENING
        armed = false
    }

    /** True when the last capture never heard anything above the threshold. */
    @Synchronized
    fun heardNothing(): Boolean = !heardSpeech

    @Synchronized
    fun reset() {
        mode = Mode.LISTENING
        armed = false
        elapsed = 0
        quiet = 0
        waited = 0
        heardSpeech = false
        ambient = 0f
        speechThreshold = minSpeechThreshold
    }

    private companion object {
        /** Per frame. At ~62 ms a frame, the room is tracked within a second. */
        const val AMBIENT_RISE = 0.25f
        const val AMBIENT_FALL = 0.05f
    }
}
