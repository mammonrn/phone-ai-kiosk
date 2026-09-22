package com.mammonrn.phoneaikiosk.voice

import android.media.MediaRecorder
import android.media.audiofx.AcousticEchoCanceler
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor

/**
 * The platform's own microphone processing: echo cancellation, noise
 * suppression, automatic gain.
 *
 * WHY THIS IS A SWITCH AND NOT A SETTING. Whether any of the three helps here
 * is not knowable from a desk. They are implemented by the phone's audio DSP,
 * every vendor tunes them differently, and openWakeWord was trained on audio
 * that had none of them applied — so noise suppression that makes a recording
 * sound cleaner to a person can just as easily make the wake word score worse.
 * The A07 is the only place that answers it, so every one of them is reported,
 * every one can be switched over adb, and NONE of the defaults change until
 * Poom has measured.
 *
 * The one with an obvious reason to help is the echo canceler: this kiosk plays
 * its own answers into the room its own microphone is listening to. The one
 * with an obvious reason to hurt is automatic gain, which raises the level of a
 * quiet room until the room is as loud as a voice — which is the opposite of
 * what the capture stage needs to tell them apart.
 *
 * ALL THREE ARE OFF BY DEFAULT here, which is not the same as "not applied":
 * VOICE_RECOGNITION already asks the platform for speech-tuned capture. These
 * are the effects layered on top of that, explicitly, by us.
 *
 * THREADING. An effect is bound to one AudioRecord's session and must be
 * created and released on the thread that owns it. Nothing here is called from
 * anywhere but the capture thread; the adb switches set a flag that the capture
 * loop reads, so no second recorder is ever opened. See Recorder.listen.
 */
class AudioHelpers {

    /** What one effect is doing, for the dump and the log. */
    class State(val name: String, val available: Boolean, val enabled: Boolean) {
        override fun toString(): String = when {
            !available -> "$name: not available on this device"
            enabled -> "$name: ON"
            else -> "$name: off"
        }
    }

    private var echo: AcousticEchoCanceler? = null
    private var noise: NoiseSuppressor? = null
    private var gain: AutomaticGainControl? = null

    /**
     * What has been ASKED for, which survives the recorder being reopened.
     *
     * Separate from what is actually attached: the request outlives any one
     * AudioRecord, and applying it can still fail on a device that reports the
     * effect as available.
     */
    @Volatile var wantEcho: Boolean = false
    @Volatile var wantNoise: Boolean = false
    @Volatile var wantGain: Boolean = false

    /** Attaches the requested effects to a recording session. */
    fun attach(sessionId: Int) {
        release()
        if (wantEcho && AcousticEchoCanceler.isAvailable()) {
            echo = runCatching { AcousticEchoCanceler.create(sessionId) }.getOrNull()
            runCatching { echo?.enabled = true }
        }
        if (wantNoise && NoiseSuppressor.isAvailable()) {
            noise = runCatching { NoiseSuppressor.create(sessionId) }.getOrNull()
            runCatching { noise?.enabled = true }
        }
        if (wantGain && AutomaticGainControl.isAvailable()) {
            gain = runCatching { AutomaticGainControl.create(sessionId) }.getOrNull()
            runCatching { gain?.enabled = true }
        }
    }

    fun release() {
        runCatching { echo?.release() }; echo = null
        runCatching { noise?.release() }; noise = null
        runCatching { gain?.release() }; gain = null
    }

    /**
     * Availability from the platform, and whether the effect is actually on.
     *
     * "Available" and "enabled" are asked separately and reported separately,
     * because a device can advertise an effect and then fail to create it —
     * and a report that collapsed the two would say "on" for something that
     * never attached.
     */
    fun states(): List<State> = listOf(
        State("echo-canceler", runCatching { AcousticEchoCanceler.isAvailable() }.getOrDefault(false),
              runCatching { echo?.enabled == true }.getOrDefault(false)),
        State("noise-suppressor", runCatching { NoiseSuppressor.isAvailable() }.getOrDefault(false),
              runCatching { noise?.enabled == true }.getOrDefault(false)),
        State("auto-gain", runCatching { AutomaticGainControl.isAvailable() }.getOrDefault(false),
              runCatching { gain?.enabled == true }.getOrDefault(false)),
    )

    /** What has been asked for, whether or not it took. */
    fun requested(): String =
        "echo=${on(wantEcho)} noise=${on(wantNoise)} gain=${on(wantGain)}"

    private fun on(value: Boolean) = if (value) "on" else "off"

    companion object {
        const val ECHO = "echo"
        const val NOISE = "noise"
        const val GAIN = "gain"

        /**
         * The audio sources worth trying, and what each one is for.
         *
         * VOICE_RECOGNITION is the default and the one openWakeWord's training
         * data most resembles: the platform applies speech-tuned capture and,
         * on most devices, leaves the signal otherwise alone.
         *
         * VOICE_COMMUNICATION is the telephony path. It usually brings echo
         * cancellation and aggressive noise reduction whether or not they are
         * asked for, which is either exactly what a kiosk that talks to itself
         * needs, or enough processing to make the wake word unrecognisable.
         * Worth measuring, not worth assuming.
         */
        val SOURCES = mapOf(
            "voice_recognition" to MediaRecorder.AudioSource.VOICE_RECOGNITION,
            "voice_communication" to MediaRecorder.AudioSource.VOICE_COMMUNICATION,
            "mic" to MediaRecorder.AudioSource.MIC,
        )

        const val DEFAULT_SOURCE = "voice_recognition"

        fun sourceName(value: Int): String =
            SOURCES.entries.firstOrNull { it.value == value }?.key ?: "unknown($value)"
    }
}
