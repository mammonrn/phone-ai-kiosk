package com.mammonrn.phoneaikiosk.voice

/**
 * Whether the microphone loop is listening for the wake word or capturing a
 * question, and when capturing should stop.
 *
 * Pulled out of the service as a pure class with no Android in it, because the
 * bug that shipped in versionCode 4 was exactly a state-machine bug and there
 * was nothing to test.
 *
 * WHAT WENT WRONG: the service had one single-threaded executor. The listening
 * loop occupied it forever, so the work submitted when the adb trigger fired was
 * queued behind a task that never finishes. `wake` became "triggered" — that
 * happens on the binder thread — and `stt` stayed "idle" for good. Nothing threw,
 * so nothing was logged.
 *
 * The other half of the same bug: capturing the question used to open a SECOND
 * AudioRecord while the listening loop still held the first one. This machine
 * exists so that one recorder, on one thread, does both jobs.
 */
class CaptureMachine(
    private val frameMillis: Int,
    private val maxCaptureMillis: Int = 12_000,
    private val silenceMillis: Int = 1_200,
    private val speechThreshold: Int = Recorder.SPEECH_THRESHOLD,
) {

    enum class Mode { LISTENING, CAPTURING }

    enum class Step {
        /** Still listening; nothing to do. */
        IDLE,

        /** A trigger was consumed this frame; capture has begun. */
        STARTED,

        /** Capturing; keep the frame. */
        CAPTURING,

        /** Capture finished on this frame; the buffer is a complete question. */
        FINISHED,
    }

    var mode: Mode = Mode.LISTENING
        private set

    private var armed = false
    private var elapsed = 0
    private var quiet = 0
    private var heardSpeech = false

    /** Reason the last capture ended, for the log line. */
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
     * Advances the machine by one frame of audio.
     *
     * [peak] is the frame's peak amplitude, [wakeWordFired] whether the detector
     * matched on this frame.
     */
    @Synchronized
    fun onFrame(peak: Int, wakeWordFired: Boolean): Step {
        if (mode == Mode.LISTENING) {
            if (!armed && !wakeWordFired) return Step.IDLE
            armed = false
            mode = Mode.CAPTURING
            elapsed = 0
            quiet = 0
            heardSpeech = false
            lastStopReason = ""
            return Step.STARTED
        }

        elapsed += frameMillis
        if (peak > speechThreshold) {
            heardSpeech = true
            quiet = 0
        } else if (heardSpeech) {
            quiet += frameMillis
        }

        if (elapsed >= maxCaptureMillis) {
            return finish(if (heardSpeech) "max-length" else "silence-timeout")
        }
        if (heardSpeech && quiet >= silenceMillis) {
            return finish("end-of-speech")
        }
        return Step.CAPTURING
    }

    private fun finish(reason: String): Step {
        lastStopReason = reason
        mode = Mode.LISTENING
        return Step.FINISHED
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
        heardSpeech = false
    }
}
