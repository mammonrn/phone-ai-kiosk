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

    /** The wake word model's last score, and the bar it has to clear. */
    @Volatile var wakeScore: Float = 0f
    @Volatile var threshold: Float = 0f

    /** How many times the wake word has fired since the service started. */
    @Volatile var detections: Int = 0

    /**
     * Wake-word-only test mode, switched on over adb.
     *
     * The wake word is counted and shown and nothing else happens: no
     * recording, no transcription, no model call, no speech. It exists so the
     * twenty-times-at-three-metres measurement costs nothing and takes as long
     * as it takes to say the words, instead of a full turn each time.
     */
    @Volatile var wakeOnly: Boolean = false

    /** Why the last capture was thrown away, if it was. */
    @Volatile var lastCancel: String = ""

    /**
     * The last action and how it went — the TYPE and the OUTCOME, never where
     * somebody asked to be taken. Where you are going is not something to leave
     * on a screen in a kitchen or in a log file.
     */
    @Volatile var lastAction: String = "none"

    /**
     * What the broker said about this phone's identity at the last pass:
     * "" (not asked yet this run), "approved", "pending" (Poom has not run
     * approve-enrollment), or "error" (the VPS could not be reached).
     */
    @Volatile var grantStatus: String = ""

    /**
     * The phone's coarse position (two decimals), for biasing a Maps search
     * toward home (MapsLauncher). Set by MainActivity with each dashboard fix;
     * null until there is one. Never sent anywhere by this.
     */
    @Volatile var near: Pair<Double, Double>? = null

    /** Google Maps: installed, and does it have its location permission. */
    @Volatile var mapsState: String = "unknown"

    /**
     * The kiosk's OWN position pipeline: whether there is a fix and how it was
     * obtained. Set by MainActivity from KioskLocation.describe().
     *
     * NEVER A COORDINATE, and this is the field where that rule is easiest to
     * break by accident: it is a free-text status string that goes on a screen
     * facing a room and into `dumpsys`, which is the first thing anybody pastes
     * into a bug report. "fix=yes via=last-known age=42s" is everything a
     * person debugging this needs and nothing a person reading over a shoulder
     * can use. See KioskLocation.describe, which is the only thing that should
     * ever write to it.
     */
    @Volatile var locationState: String = "fix=none via=not-asked"

    /**
     * Whether the broker had to fall back to the university for the weather.
     *
     * Reported by the broker in the dashboard payload. "THAT the fallback was
     * used" is the operational fact worth showing; where the phone actually is
     * stays off the screen either way.
     *
     * Null until a dashboard has actually arrived. It was a plain false, which
     * made the status line say "weather=here" all morning on a phone whose
     * every refresh was failing — there was no weather, from anywhere.
     */
    @Volatile var weatherFallback: Boolean? = null
    @Volatile var wake: String = "idle"
    @Volatile override var stt: String = "idle"
    @Volatile override var chat: String = "idle"
    @Volatile override var tts: String = "idle"

    /** Peak amplitude of the last frame, for aiming the microphone. */
    @Volatile var level: Int = 0

    @Volatile override var heard: String = ""
    @Volatile override var reply: String = ""
    @Volatile override var lastError: String = ""

    /**
     * When the cloud answer now playing started (elapsedRealtime), 0 when none
     * is, and how long MediaPlayer says it lasts. The Jarvis window scrolls
     * the answer along with these, so the line being said stays in view.
     */
    /**
     * What started the turn now being transcribed, for the broker's speech
     * gate: the wake score as "0.430", or "button" / "adb". Sent as X-Wake.
     */
    @Volatile var turnWake: String = "unknown"

    /** Why the broker's gate stopped the last transcript, or "" if it did not. */
    @Volatile var lastGate: String = ""

    /**
     * What the last tap on the home card did, or why it could not (0.48.0),
     * shown in the Jarvis window so the lights card never grows. Cleared when
     * a voice turn starts.
     */
    @Volatile var homeNotice: String = ""

    /** Bumped when the broker says it switched a light (home_updated): redraw the card now. */
    @Volatile var homeVersion: Int = 0

    /** Bumped whenever the alarms change or one starts or stops ringing. */
    @Volatile var alarmsVersion: Int = 0

    /** "6:30 AM  ไปทำงาน" while an alarm rings, "" otherwise. */
    @Volatile var alarmRinging: String = ""

    @Volatile var speakingSinceMs: Long = 0
    @Volatile var speakingDurationMs: Long = 0

    /** Set by the service so `dumpsys` can say whether a token is installed. */
    @Volatile var hasToken: Boolean = false

    /** Captures completed since the service started, for the dump. */
    @Volatile var turns: Int = 0

    @Volatile var conversationId: String? = null

    /** One line for the kiosk screen. Short enough to read across a room. */
    fun statusLine(): String =
        "mic=$mic wake=$wake stt=$stt chat=$chat tts=$tts"

    fun secondLine(): String = buildString {
        append("detector=$detector score=%.3f level=%d".format(wakeScore, level))
        if (lastError.isNotEmpty()) append("  last-error=$lastError")
    }

    /**
     * Whether the diagnostics lines are drawn ON THE SCREEN. Off by default.
     *
     * Poom asked for the screen to hold only what the household uses: the
     * data, and what Jarvis is doing in words. The lines this used to draw —
     * mic=, wake=, detector=, loc=, idle=, owner=, taps= — are all still in
     * `dumpsys` (see dump()) and in logcat; only the screen stops showing them.
     *
     * Switched on only by the debug-only TestTriggerReceiver over adb
     * (TEST_DIAGNOSTICS), and held in memory, so a restart puts it back off.
     */
    @Volatile var showDiagnostics: Boolean = false

    /**
     * Which transcriber to ask the broker for, set ONLY by the debug build's
     * adb override (TEST_STT_PROVIDER) and forgotten on restart. Null means
     * "the broker's default", which is Groq until Poom chooses otherwise.
     */
    @Volatile var sttOverride: String? = null

    /** What the broker said it used on the last transcription. */
    @Volatile var lastSttProvider: String = "none yet"

    /**
     * Android's own on-device recognizer, as probed at start-up: available
     * or not, and where Thai stands. Read-only facts — nothing here ever
     * starts a recognition or opens the microphone. See DeviceSttProbe.
     */
    @Volatile var deviceStt: String = "not probed"

    /**
     * The kiosk's own line — owner, lock, awake, token, taps — written by
     * MainActivity every second. It used to be the taskbar's middle; it is in
     * the dump now, where a person debugging looks, and off the screen.
     */
    @Volatile var kioskLine: String = ""

    /**
     * The screen's side of the idle rule, for the status line and the dump.
     *
     * Written by MainActivity (idle time, sleeps, why a sleep was refused) and
     * by ScreenWaker (wakes, and whether the last one actually lit the display).
     * `screenNote` is the one to read when something is wrong: "lock-refused"
     * means the Device Owner could not turn the screen off, "wake-denied"
     * means the platform would not let a wake lock turn it back on.
     */
    @Volatile var screenIdleSeconds: Long = 0
    @Volatile var screenSleeps: Int = 0
    @Volatile var screenWakes: Int = 0
    @Volatile var screenNote: String = ""

    /** The third diagnostics line: where the kiosk thinks it is, not where. */
    fun thirdLine(): String =
        "loc $locationState weather=${weatherSource(weatherFallback)}" +
            "  idle=${screenIdleSeconds}s sleeps=$screenSleeps wakes=$screenWakes" +
            (if (screenNote.isEmpty()) "" else " $screenNote")

    /** "here", "fallback", or "none" before any dashboard has arrived. */
    fun weatherSource(fallback: Boolean?): String = when (fallback) {
        null -> "none"
        true -> "fallback"
        false -> "here"
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
        // 0.43.0: which of our screens are open, and whether home is in front.
        appendLine("  screens    : home-front=${com.mammonrn.phoneaikiosk.KioskScreens.homeInFront} " +
            "open=${com.mammonrn.phoneaikiosk.KioskScreens.openScreens().ifEmpty { listOf("none") }.joinToString(",")}")
        appendLine("  detector   : $detector")
        appendLine("  score      : %.4f  (threshold %.2f)".format(wakeScore, threshold))
        appendLine("  detections : $detections")
        appendLine("  wake-only  : ${if (wakeOnly) "ON — no STT, chat or TTS" else "off"}")
        if (lastCancel.isNotEmpty()) appendLine("  last-cancel: $lastCancel")
        appendLine("  last-action: $lastAction")
        appendLine("  maps       : $mapsState")
        appendLine("  location   : $locationState")
        appendLine("  weather-loc: " + when (weatherFallback) {
            null -> "none yet (no dashboard received)"
            true -> "FALLBACK (university)"
            false -> "phone"
        })
        appendLine("  kiosk      : ${kioskLine.ifEmpty { "(activity not started)" }}")
        appendLine("  status-line: ${statusLine()}")
        appendLine("  third-line : ${thirdLine()}")
        appendLine("  on-screen  : diagnostics ${if (showDiagnostics) "SHOWN (debug)" else "hidden"}")
        appendLine("  stt-engine : asked=${sttOverride ?: "broker default"} " +
            "last-used=$lastSttProvider  (override via adb, resets on restart)")
        appendLine("  device-stt : $deviceStt")
        appendLine("  screen-idle: ${screenIdleSeconds}s  sleeps=$screenSleeps  " +
            "wakes=$screenWakes  note=${screenNote.ifEmpty { "none" }}")
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
