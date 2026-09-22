package com.mammonrn.phoneaikiosk.voice

/**
 * What the status line reads, and what `dumpsys` prints.
 *
 * Plain volatile fields shared between the capture thread and the UI thread.
 * Nothing here is a token, a key, or audio. The transcript and the reply are
 * here because the phone is supposed to show what it heard — misheard Thai is
 * the most common failure in this whole pipeline and the person in the room is
 * the only one who can catch it.
 */
object VoiceState {

    /** Where the broker is. Overridable for a staging host over adb. */
    @Volatile var brokerBaseUrl: String = "https://kiosk.xn--l3cgts1b3bzcvf.com"

    @Volatile var mic: String = "off"
    @Volatile var detector: String = "unknown"
    @Volatile var wake: String = "idle"
    @Volatile var stt: String = "idle"
    @Volatile var chat: String = "idle"
    @Volatile var tts: String = "idle"

    /** Peak amplitude of the last frame, for aiming the microphone. */
    @Volatile var level: Int = 0

    @Volatile var heard: String = ""
    @Volatile var reply: String = ""
    @Volatile var lastError: String = ""

    @Volatile var conversationId: String? = null

    /** One line for the kiosk screen. Short enough to read across a room. */
    fun statusLine(): String =
        "mic=$mic wake=$wake stt=$stt chat=$chat tts=$tts"

    fun secondLine(): String = buildString {
        append("detector=$detector level=$level")
        if (lastError.isNotEmpty()) append("  last-error=$lastError")
    }
}
