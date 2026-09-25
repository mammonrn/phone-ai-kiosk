package com.mammonrn.phoneaikiosk.voice

/**
 * What Jarvis is doing, as the badge on every screen shows it (0.61.0, Poom:
 * in place of the beep after the wake word). One answer from the same states
 * the voice pipeline already reports — the same order as
 * DashboardState.jarvisState, so the home card and the badge never disagree.
 * Plain Kotlin: JarvisStatusTest.
 */
enum class JarvisStatus {
    READY, LISTENING, THINKING, SPEAKING, RESTING, UNAVAILABLE;

    companion object {
        /**
         * [online]: the phone has a network; [hasToken]: it can reach the broker
         * at all; [resting]: the wake word rests (media playing, recording).
         */
        fun of(mic: String, stt: String, chat: String, tts: String,
               resting: Boolean, online: Boolean, hasToken: Boolean): JarvisStatus = when {
            mic == "no-permission" || mic == "error" -> UNAVAILABLE
            tts == "speaking" || tts == "synthesising" || tts == "device-fallback" -> SPEAKING
            chat == "asking" || stt == "sending" -> THINKING
            stt == "recording" -> LISTENING
            !online || !hasToken -> UNAVAILABLE
            resting -> RESTING
            else -> READY
        }
    }
}
