package com.mammonrn.phoneaikiosk.timer

import com.mammonrn.phoneaikiosk.voice.DONE_MARK

/**
 * A spoken countdown command (0.63.0, Poom: "จับเวลาห้านาที", "หยุดจับเวลา"),
 * done on this phone BEFORE the reply is said. The broker recognised the
 * sentence in code (server/kiosk_broker/timers.py); only the phone knows
 * whether a countdown is running, so what it could not do it says itself.
 *
 * [perform] returns null when done as the broker said, [DONE_MARK]-words when
 * done in the phone's own words ("status": what is left), or the words to say
 * instead. Plain Kotlin: TimerVoiceTest.
 */
object TimerVoice {

    val COMMANDS = setOf("start", "stop", "status")

    /** The longest countdown a sentence may set (the broker's limit too). */
    const val MAX_SECONDS = 24 * 3600 - 1

    interface Deck {
        val countdown: Countdown
        val now: Long
        /** Any countdown (running, paused, ringing) put back to idle, the ring stopped. */
        fun clear()
        fun start(ms: Long)
    }

    const val NOTHING = "ตอนนี้ไม่มีการนับถอยหลังอยู่ครับ"

    fun perform(command: String, seconds: Int, deck: Deck): String? {
        val c = deck.countdown
        return when (command) {
            "start" -> {
                if (seconds !in 1..MAX_SECONDS) return "ตั้งได้ตั้งแต่ 1 วินาทีถึง 24 ชั่วโมงครับ"
                // A new length replaces whatever was counting: that is what was asked.
                if (c.state != Countdown.State.IDLE) deck.clear()
                deck.start(seconds * 1000L)
                null
            }
            "stop" -> {
                if (c.state == Countdown.State.IDLE) NOTHING
                else { deck.clear(); null }
            }
            // Always the phone's own words — the answer, marked done.
            "status" -> DONE_MARK + when (c.state) {
                Countdown.State.IDLE -> NOTHING
                Countdown.State.RINGING -> "หมดเวลาแล้วครับ"
                Countdown.State.PAUSED -> "หยุดไว้อยู่ เหลืออีก ${TimerText.thai(c.remaining(deck.now))} ครับ"
                Countdown.State.RUNNING -> "เหลืออีก ${TimerText.thai(c.remaining(deck.now))} ครับ"
            }
            else -> null
        }
    }
}
