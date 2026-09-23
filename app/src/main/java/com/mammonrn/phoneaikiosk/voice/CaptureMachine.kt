package com.mammonrn.phoneaikiosk.voice

/**
 * Whether the microphone loop is listening for the wake word, capturing a
 * question, or busy answering one — and when capturing should stop.
 *
 * Pulled out of the service as a pure class with no Android in it, because the
 * bugs that have shipped here were all state-machine bugs and there was nothing
 * to test.
 *
 * WHAT WENT WRONG IN versionCode 4: one single-threaded executor, with the
 * listening loop occupying it forever, so work submitted by the adb trigger
 * queued behind a task that never finishes.
 *
 * WHAT WENT WRONG IN versionCode 7, which is what BUSY exists for: the machine
 * went back to LISTENING the moment a capture FINISHED — while the answer was
 * still being transcribed, asked and spoken — so a wake word mid-answer started
 * a second turn whose capture recorded the first turn's speech.
 *
 * WHAT WENT WRONG IN versionCode 10, which is what the peak history exists for.
 * The ambient level was an EMA updated on every listening frame, including the
 * dozen or so frames that carry "Hey Jarvis" itself. The EMA rose fast enough to
 * converge on the wake word's own loudness before the wake word was recognised —
 * recognition happens at the END of the phrase — so the bar for "somebody is
 * speaking" was set to two and a half times the volume of the person setting it.
 * On the A07:
 *
 *     wake word detected score=0.958 threshold=0.50
 *     capture started
 *     capture cancelled reason=no-speech-after-wake threshold=24355 ambient=9742
 *
 * 9742 is about a third of full scale: a voice close to the microphone, not a
 * room. 9742 x 2.5 = 24355, which no ordinary question can clear, so every
 * question was cancelled as silence. The louder and clearer the wake word, the
 * higher the bar it set against the question after it.
 *
 * The comment on the old line said the level came "from the room as it was just
 * before the wake word". That was the intent; it was not what the code did.
 *
 * So the room is now measured from frames that PREDATE the wake word, and with
 * a median rather than an average, so that one slammed door does not set the
 * bar for the next thing anybody says.
 */
class CaptureMachine(
    private val frameMillis: Int,
    private val maxCaptureMillis: Int = 12_000,
    silenceMillis: Int = DEFAULT_SILENCE_MILLIS,
    /**
     * The floor under the adaptive threshold. A silent room must not make the
     * machine so sensitive that its own hiss counts as speech.
     */
    private val minSpeechThreshold: Int = Recorder.SPEECH_THRESHOLD,
    speechMargin: Float = DEFAULT_SPEECH_MARGIN,
    speechWaitMillis: Int = DEFAULT_SPEECH_WAIT_MILLIS,
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

    /**
     * How far above the room a frame has to be to count as somebody speaking.
     *
     * Adjustable over adb while measuring, because the right number depends on
     * the room and cannot be chosen from here. It is a `var` for that reason
     * alone; it is not persisted, so a restart brings back the default.
     */
    @Volatile
    var speechMargin: Float = speechMargin
        set(value) {
            field = value.coerceIn(1.2f, 10f)
        }

    /**
     * How long after the beep to wait for the question to start.
     *
     * Somebody who says "Hey Jarvis" and then nothing must cost nothing: no
     * upload, no transcription, no model call.
     */
    @Volatile
    var speechWaitMillis: Int = speechWaitMillis
        set(value) {
            field = value.coerceIn(500, 10_000)
        }

    /**
     * How long a pause has to last before the question counts as finished.
     *
     * The single biggest piece of delay this side of the network: it is pure
     * waiting, after the person has already stopped talking. Adjustable over
     * adb so the shortest value that does not cut people off can be found in
     * the room rather than guessed here.
     */
    @Volatile
    var silenceMillis: Int = silenceMillis
        set(value) {
            field = value.coerceIn(300, 3_000)
        }

    /**
     * Whether the capture in progress was started by the wake word rather than
     * by the adb trigger.
     *
     * Only the first kind counts towards false-wake candidates: an adb-armed
     * capture that nobody spoke into says nothing about the wake word.
     */
    var startedByWakeWord: Boolean = false
        private set

    private var armed = false
    private var elapsed = 0
    private var quiet = 0
    private var waited = 0
    private var guard = 0
    private var heardSpeech = false

    /**
     * Frame peaks while listening, newest last.
     *
     * Long enough to hold the wake word AND a stretch of room in front of it.
     * This is the whole fix for versionCode 10: the room is read from the far
     * end of this, where the wake word has not reached.
     */
    private val peaks = ArrayDeque<Int>()

    /** The room level this capture's threshold was built from. */
    var ambient: Int = 0
        private set

    /** What counts as speech for the capture in progress. */
    var speechThreshold: Int = minSpeechThreshold
        private set

    /**
     * The loudest frame seen while waiting for the question to start.
     *
     * Logged on a cancellation, and it is the number that says which of two
     * very different things happened: a bar set too high, or a room that really
     * was silent. Without it the two are indistinguishable from the outside.
     */
    var peakWhileWaiting: Int = 0
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

    /**
     * Whether a press of the on-screen Jarvis button may start a question now:
     * only while listening. During a capture or a turn (BUSY) the press is
     * refused outright rather than armed — arm() while BUSY would be dropped
     * anyway, but refusing up front means no beep promises a turn that will
     * not happen, and no second turn can ever start on top of the first.
     */
    @Synchronized
    fun canStartByButton(): Boolean = mode == Mode.LISTENING

    /** The room level behind the current threshold, for the dump. */
    @Synchronized
    fun ambientLevel(): Int = ambient

    /** What the room looks like right now, without waiting for a wake word. */
    @Synchronized
    fun currentRoomLevel(): Int = median(peaks.toList())

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
            rememberPeak(peak)
            if (!armed && !wakeWordFired) return Step.IDLE
            startedByWakeWord = !armed && wakeWordFired
            armed = false
            mode = Mode.CAPTURING
            elapsed = 0
            quiet = 0
            waited = 0
            guard = 0
            heardSpeech = false
            peakWhileWaiting = 0
            lastStopReason = ""
            ambient = roomBeforeTheWakeWord()
            speechThreshold = maxOf(minSpeechThreshold, (ambient * speechMargin).toInt())
            return Step.STARTED
        }

        elapsed += frameMillis

        // THE BEEP IS NOT THE QUESTION. The kiosk plays a short tone the instant
        // it hears its name, into the room its own microphone is listening to.
        // Without this the tone is the first thing louder than the room, so the
        // machine would decide the question had started before anybody opened
        // their mouth — and then end it on the silence that follows.
        if (guard < GUARD_MILLIS) {
            guard += frameMillis
            return Step.CAPTURING
        }

        // THE HARD CAP IS CHECKED FIRST, and it is checked before the wait.
        // Without this the wait window could outlast it — the "nobody has
        // started yet" branch returns early — so a machine configured with a
        // cap shorter than the wait would ignore its own cap. Production never
        // has been, but a limit that only holds for some settings is not a
        // limit.
        if (elapsed >= maxCaptureMillis) {
            return if (heardSpeech) finish("max-length") else cancel("no-speech-after-wake")
        }

        val speaking = peak > speechThreshold

        if (!heardSpeech) {
            // Nobody has started yet. This is the window that stops "Hey Jarvis"
            // followed by silence from becoming a transcription and a question.
            if (peak > peakWhileWaiting) peakWhileWaiting = peak
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

        if (heardSpeech && quiet >= silenceMillis) {
            return finish("end-of-speech")
        }
        return Step.CAPTURING
    }

    /** Keeps the last few seconds of frame peaks while nothing is recording. */
    private fun rememberPeak(peak: Int) {
        peaks.addLast(peak)
        while (peaks.size > HISTORY_FRAMES) peaks.removeFirst()
    }

    /**
     * The room, from before the person started speaking.
     *
     * Wake word recognition happens at the END of the phrase — openWakeWord
     * scores the last sixteen 80 ms embeddings — so the frames immediately
     * behind this moment are the wake word itself and must be skipped. What is
     * left is the room.
     *
     * A median, not an average: a door, a cough or a chair is one frame among
     * thirty and moves a median not at all, while it drags an average up and
     * with it the bar for whatever is said next.
     */
    private fun roomBeforeTheWakeWord(): Int {
        val all = peaks.toList()
        val end = all.size - WAKE_WORD_FRAMES
        if (end <= 0) {
            // NOT ENOUGH HISTORY TO KNOW THE ROOM — the service has only just
            // started, or a turn has just cleared the buffer. Zero, so the
            // floor decides, rather than a median of the few frames available:
            // those frames are the wake word, and taking their median is the
            // exact mistake versionCode 10 made on a larger scale.
            //
            // An unknown room gets the most sensitive setting, not the least.
            // The cost of being too sensitive is a cancelled capture; the cost
            // of being too deaf is a kiosk that ignores people.
            return 0
        }
        val start = maxOf(0, end - ROOM_FRAMES)
        return median(all.subList(start, end))
    }

    private fun median(values: List<Int>): Int {
        if (values.isEmpty()) return 0
        val sorted = values.sorted()
        return sorted[sorted.size / 2]
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
        // The answer was just played into the room the microphone is in. Those
        // peaks are the kiosk's own voice and would set the bar for the next
        // question against itself.
        peaks.clear()
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
        guard = 0
        heardSpeech = false
        peaks.clear()
        ambient = 0
        peakWhileWaiting = 0
        speechThreshold = minSpeechThreshold
    }

    companion object {
        /**
         * How long a pause has to be before the question is over.
         *
         * 1,200 ms, then 900 ms, now 1,500 ms — and the last move was measured
         * on the A07, not reasoned about. At 900 ms Poom's "ขอดูกล้องหน่อยครับ"
         * was cut after the first word: 46 KB of audio, a 4-character
         * transcript. The pause a Thai speaker leaves between "ขอดู" and
         * "กล้อง" is longer than 900 ms. Set to 1,800 over adb, the same
         * sentence came through whole, twice (132 KB and 142 KB, 14 characters).
         *
         * 1,500 rather than 1,800: it clears the pause that cut the sentence by
         * more than half again, and every turn pays this wait in full after
         * the last word — 300 ms less of dead air per question. A television
         * does not make it worse: TV sound keeps a capture open whatever this
         * is, until the 12-second ceiling, because it rarely falls silent for
         * even 900 ms. If 1,500 still cuts Poom off, TEST_SET_SILENCE sets
         * 1,800 at once (until the next restart) and this constant follows.
         */
        const val DEFAULT_SILENCE_MILLIS = 1_500

        /**
         * 2.5x amplitude, about 8 dB above the room. Above a television at
         * conversational volume and under a person addressing the kiosk.
         */
        const val DEFAULT_SPEECH_MARGIN = 2.5f

        /**
         * Measured from the end of the beep, not from the wake word, so the
         * whole of it is available to the person. The wake word itself, the
         * detector's own 80 ms of lag and the tone have all already happened.
         */
        const val DEFAULT_SPEECH_WAIT_MILLIS = 3_500

        /** Long enough to cover the acknowledgement tone and its echo. */
        const val GUARD_MILLIS = 300

        /**
         * How far back the wake word reaches. At ~62 ms a frame this is about
         * two seconds, which covers "Hey Jarvis" said slowly plus the detector's
         * own window, with room to spare. Skipping too much is harmless; the
         * room is still the room a second earlier.
         */
        const val WAKE_WORD_FRAMES = 32

        /** About two seconds of room to take the median of. */
        const val ROOM_FRAMES = 32

        /** Both windows, plus slack. */
        const val HISTORY_FRAMES = WAKE_WORD_FRAMES + ROOM_FRAMES + 16
    }
}
