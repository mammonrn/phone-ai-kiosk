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
object VoiceState : VoiceSink {

    /** Where the broker is. Overridable for a staging host over adb. */
    @Volatile var brokerBaseUrl: String = "https://kiosk.xn--l3cgts1b3bzcvf.com"

    @Volatile var mic: String = "off"
    @Volatile var detector: String = "unknown"
    @Volatile var wake: String = "idle"
    @Volatile override var stt: String = "idle"
    @Volatile override var chat: String = "idle"
    @Volatile override var tts: String = "idle"

    /** Peak amplitude of the last frame, for aiming the microphone. */
    @Volatile var level: Int = 0

    @Volatile override var heard: String = ""
    @Volatile override var reply: String = ""
    @Volatile override var lastError: String = ""

    /** Set by the service so `dumpsys` can say whether a token is installed. */
    @Volatile var hasToken: Boolean = false

    /** Captures completed since the service started, for the dump. */
    @Volatile var turns: Int = 0

    @Volatile var conversationId: String? = null

    /** One line for the kiosk screen. Short enough to read across a room. */
    fun statusLine(): String =
        "mic=$mic wake=$wake stt=$stt chat=$chat tts=$tts"

    fun secondLine(): String = buildString {
        append("detector=$detector level=$level")
        if (lastError.isNotEmpty()) append("  last-error=$lastError")
    }

    /**
     * Everything, as plain text, for `adb shell dumpsys activity service ...`.
     *
     * Exists because uiautomator cannot read this screen: the clock ticks every
     * second, so the hierarchy never settles and `uiautomator dump` times out.
     * Reading the state should not depend on the screen holding still.
     *
     * Carries no token and no key — whether a token exists, not what it is.
     */
    fun dump(): String = buildString {
        appendLine("kiosk voice state")
        appendLine("  mic        : $mic")
        appendLine("  detector   : $detector")
        appendLine("  wake       : $wake")
        appendLine("  stt        : $stt")
        appendLine("  chat       : $chat")
        appendLine("  tts        : $tts")
        appendLine("  level      : $level")
        appendLine("  token      : ${if (hasToken) "yes" else "no"}")
        appendLine("  turns      : $turns")
        appendLine("  last-error : ${lastError.ifEmpty { "none" }}")
        appendLine("  broker     : $brokerBaseUrl")
        appendLine("  heard      : ${heard.length} chars")
        appendLine("  reply      : ${reply.length} chars")
    }
}
