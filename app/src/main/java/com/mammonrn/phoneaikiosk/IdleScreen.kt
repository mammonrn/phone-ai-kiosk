package com.mammonrn.phoneaikiosk

/**
 * When the kiosk's screen goes dark, as a rule with the clock passed in.
 *
 * THIS REPLACES A DECISION, AND POOM MADE THE CHANGE. The screen used to stay
 * on for as long as the phone was charging, which on a kiosk that is always
 * charging meant always. Now it stays on while somebody is using it and goes
 * off after [idleMs] of nobody doing so — five minutes — charging or not. The
 * microphone does not care either way: VoiceService keeps listening with the
 * screen off, and "Hey Jarvis" turns it back on (see voice/ScreenWaker).
 *
 * WHAT COUNTS AS USE: a touch anywhere, the screen coming back on, and every
 * second that the voice pipeline is in the middle of a turn — see [voiceBusy].
 * A thirty-second answer must not be read out to a dark screen because the
 * question started four minutes and forty seconds after the last touch.
 *
 * ON BATTERY the old rule still holds while nobody is talking: the flag that
 * holds the screen on is not set, so the system's own timeout decides, and it
 * can only be shorter than this one. The five-minute sleep applies on top.
 *
 * Split out of the activity, like TapGate, because getting it wrong is either a
 * screen that never sleeps or one that goes off in somebody's face.
 */
class IdleScreen(private val idleMs: Long = IDLE_MS) {

    /** What the activity should do with the screen this second. */
    enum class Action {
        /** Hold the screen on: FLAG_KEEP_SCREEN_ON set. */
        KEEP_ON,

        /** Let go and let the system's own timeout decide. */
        RELEASE,

        /** Idle long enough: turn the screen off now. Returned once per idle spell. */
        SLEEP,
    }

    private var lastUseAt = 0L
    private var sleptThisSpell = false

    /** Somebody touched it, talked to it, or it has just come back on. */
    fun used(nowMs: Long) {
        lastUseAt = nowMs
        sleptThisSpell = false
    }

    /** How long since the last use, for the status line. */
    fun idleFor(nowMs: Long): Long = (nowMs - lastUseAt).coerceAtLeast(0L)

    fun decide(nowMs: Long, charging: Boolean, busy: Boolean): Action {
        if (busy) used(nowMs)
        if (idleFor(nowMs) >= idleMs) {
            if (sleptThisSpell) return Action.RELEASE
            sleptThisSpell = true
            return Action.SLEEP
        }
        // Mid-answer the screen is held on even on battery: it is seconds, and
        // the answer is on it. Otherwise only while charging, as before.
        return if (charging || busy) Action.KEEP_ON else Action.RELEASE
    }

    companion object {
        /** Five minutes, which is what Poom asked for. */
        const val IDLE_MS = 5L * 60L * 1000L

        /**
         * Whether a voice turn is under way, from the states VoiceState reports.
         *
         * A list of the busy states rather than "anything but idle": an error
         * state that stuck would otherwise hold the screen on for ever, which
         * is the old behaviour arriving by accident. "device-fallback" is NOT
         * here for exactly that reason: TurnPipeline leaves it set after the
         * phone's own voice has finished, until the next turn.
         */
        fun voiceBusy(wake: String, stt: String, chat: String, tts: String): Boolean =
            wake == "heard" ||
                stt == "recording" || stt == "sending" ||
                chat == "asking" ||
                tts == "speaking" || tts == "synthesising"
    }
}
