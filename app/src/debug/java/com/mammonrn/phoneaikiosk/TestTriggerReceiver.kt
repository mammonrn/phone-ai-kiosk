package com.mammonrn.phoneaikiosk

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import com.mammonrn.phoneaikiosk.voice.VoiceService
import com.mammonrn.phoneaikiosk.voice.VoiceState
import com.mammonrn.phoneaikiosk.voice.VoiceStats

/**
 * The hidden way to start a question without saying the wake word.
 *
 * DEBUG BUILDS ONLY. This file lives under src/debug, so it is not compiled into
 * a release build at all — not hidden in one, absent from one. That is the whole
 * reason it is a separate source set rather than a flag: an exported receiver
 * that can make the phone record is not something to ship behind a boolean.
 *
 * It exists so the pipeline — record, transcribe, answer, speak — can be
 * exercised without saying anything, and so the wake word threshold can be
 * moved while measuring false wakes without rebuilding the app. There is no
 * button for any of this on the screen and there never will be.
 *
 * Usage is in TESTING.md.
 */
class TestTriggerReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        when (intent.action) {
            ACTION_ASK -> {
                // A question as text, through the real /v1/chat, voice and
                // action — how a spoken command is checked end to end without
                // anybody speaking. --es text "ตั้งปลุก 11 โมงเช้าได้ไหมครับ"
                context.startForegroundService(android.content.Intent(context, VoiceService::class.java)
                    .setAction(VoiceService.ACTION_TEST_ASK)
                    .putExtra(VoiceService.EXTRA_TEXT, intent.getStringExtra("text")))
            }

            ACTION_VERIFY -> {
                // Opens the identity check as a private request will in part 2,
                // and goes back to the kiosk after. The outcome is in logcat
                // under KioskAuth. --es mode VERIFY|ENROLL|SET_PATTERN
                val mode = runCatching {
                    com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.valueOf(
                        intent.getStringExtra("mode") ?: "VERIFY")
                }.getOrDefault(com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.VERIFY)
                context.startActivity(com.mammonrn.phoneaikiosk.auth.VerifyActivity
                    .intent(context, mode, returnHome = true)
                    .addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK))
            }

            ACTION_LIGHTS_PAGE -> {
                // A sample "ไฟในบ้าน" page (0.47.0), to see it on the A07 before
                // the VPS is deployed. --ez on true|false. Read the next time
                // the page opens; a change made on it still goes to the broker.
                com.mammonrn.phoneaikiosk.home.HomeSettings.override =
                    if (intent.getBooleanExtra("on", true)) SAMPLE_LIGHTS else null
                android.util.Log.i("KioskHome", "sample lights page ${if (com.mammonrn.phoneaikiosk.home.HomeSettings.override != null) "on" else "off"}")
            }

            ACTION_HOME_CARD -> {
                // A sample "อุปกรณ์ในบ้าน" panel, for seeing the card on the A07
                // before an eWeLink account is connected (0.45.0). --ez on
                // true|false. Shown at the next dashboard refresh (a minute).
                // The names are made up; nothing here reaches the broker.
                com.mammonrn.phoneaikiosk.home.HomeCard.override =
                    if (intent.getBooleanExtra("on", true)) SAMPLE_HOME else null
                android.util.Log.i("KioskHome", "sample home card ${if (com.mammonrn.phoneaikiosk.home.HomeCard.override != null) "on" else "off"}")
            }

            ACTION_FX -> {
                // 0.55.0: checks the equalizer on the phone, in numbers. --es preset Rock|Flat|…
                // sets it (--ez on false turns it off); then the bars are averaged over 3 s of
                // what is playing and logged (KioskMusic "fx bars"), with the file's format.
                // Never a title or a path.
                val mp = com.mammonrn.phoneaikiosk.media.MusicPlayer
                intent.getStringExtra("preset")?.let { name ->
                    mp.setEq(context, com.mammonrn.phoneaikiosk.media.fx.Eq.preset(name, intent.getBooleanExtra("on", true)))
                }
                // --es tones "60,170,310": also each tone's level in dB, by Goertzel on the same
                // samples — exact at a band's centre, where the bars' FFT is 43 Hz coarse (0.57.0).
                val tones = intent.getStringExtra("tones")?.split(',')?.mapNotNull { it.trim().toDoubleOrNull() }.orEmpty()
                val toneSums = DoubleArray(tones.size)
                val sums = FloatArray(com.mammonrn.phoneaikiosk.media.fx.Spectrum.BARS)
                var n = 0
                var tries = 0
                val main = android.os.Handler(android.os.Looper.getMainLooper())
                val pending = goAsync()
                val sample = object : Runnable {
                    override fun run() {
                        mp.fx.tap.at(mp.positionMs * 1000)?.let { s ->
                            val b = com.mammonrn.phoneaikiosk.media.fx.Spectrum.bars(s, mp.fx.tap.sampleRate)
                            for (i in b.indices) sums[i] += b[i]
                            // 8192 samples (Hann): eleven cycles of 60 Hz, and the tones do not leak into each other.
                            val long = if (tones.isEmpty()) null else mp.fx.tap.at(mp.positionMs * 1000, 8192)
                            if (long != null) for ((k, f) in tones.withIndex()) {
                                val w = 2 * Math.PI * f / mp.fx.tap.sampleRate
                                val c = 2 * Math.cos(w)
                                var s1 = 0.0; var s2 = 0.0
                                val m = long.size
                                for (i in 0 until m) {
                                    val x = long[i] * (0.5 - 0.5 * Math.cos(2 * Math.PI * i / (m - 1)))
                                    val s0 = x + c * s1 - s2; s2 = s1; s1 = s0
                                }
                                val power = s1 * s1 + s2 * s2 - c * s1 * s2
                                toneSums[k] += 20 * Math.log10(Math.sqrt(power.coerceAtLeast(1e-18)) * 4 / m)
                            }
                            n += 1
                        }
                        // At most 5 s: a song with no samples (one the phone cannot decode) held
                        // the broadcast open past Android's limit once — an ANR on the A07.
                        tries += 1
                        if (n < 30 && mp.hasMedia && tries < 50) { main.postDelayed(this, 100); return }
                        val info = mp.fileInfo()
                        android.util.Log.i("KioskMusic", "fx bars eq=${mp.eq.on}/${mp.eq.preset} working=${mp.fx.working} " +
                            "kbps=${info.kbps} khz=${info.khz} ch=${info.channels} n=$n " +
                            sums.joinToString(" ") { "%.2f".format(if (n == 0) 0f else it / n) } +
                            (if (tones.isEmpty()) "" else " tones " + tones.indices.joinToString(" ") {
                                "%.0f=%.1f".format(tones[it], if (n == 0) 0.0 else toneSums[it] / n) }))
                        pending.finish()
                    }
                }
                main.postDelayed(sample, 1000)
            }

            ACTION_CLOCK -> {
                // 0.57.0: the taskbar shows --es text "12:59 PM" for 60 s (DESIGN.md: check the
                // tray at 10-12 o'clock, the widest time). Only the words shown change.
                MainActivity.clockOverride = intent.getStringExtra("text")?.take(8)
                MainActivity.clockOverrideUntil = android.os.SystemClock.elapsedRealtime() + 60_000
                android.util.Log.i("KioskHome", "test clock for 60 s")
            }

            ACTION_PERFORM -> {
                // 0.57.0: a music or video action as the broker sends it, done by the real
                // code. --es type video --es command pause|play|resume|stop [--es query …]
                // [--ez as_turn true: inside a question, as the Jarvis button would].
                context.startForegroundService(android.content.Intent(context, VoiceService::class.java)
                    .setAction(VoiceService.ACTION_TEST_PERFORM)
                    .putExtra(VoiceService.EXTRA_TYPE, intent.getStringExtra("type"))
                    .putExtra(VoiceService.EXTRA_COMMAND, intent.getStringExtra("command"))
                    .putExtra(VoiceService.EXTRA_QUERY, intent.getStringExtra("query"))
                    .putExtra(VoiceService.EXTRA_AS_TURN, intent.getBooleanExtra("as_turn", false)))
            }

            ACTION_VIDEO_ROTATE -> {
                // 0.57.0: the sensor's word, for a test without turning the phone:
                // --es to landscape|portrait. Only in full screen and not locked.
                val landscape = intent.getStringExtra("to") == "landscape"
                val turn = com.mammonrn.phoneaikiosk.media.VideoActivity.debugTurn
                android.util.Log.i("KioskVideo", "test turn to=${if (landscape) "landscape" else "portrait"} screen=${turn != null}")
                turn?.let { android.os.Handler(android.os.Looper.getMainLooper()).post { it(landscape) } }
            }

            ACTION_MEDIA_HOLD -> {
                // Pretends music is playing, for the Jarvis card's "resting"
                // state and the button-during-media path, before a real player
                // exists. --ez on true|false. The fake player only logs what
                // Jarvis asked of it (KioskVoice: "test media quiet/resume").
                // Renewed by nobody: it lapses after WakePause.LEASE_MS, which
                // is also how the lease is checked on the phone.
                val on = intent.getBooleanExtra("on", true)
                testHold?.let { com.mammonrn.phoneaikiosk.voice.WakePause.release(it) }
                testHold = null
                if (on) testHold = com.mammonrn.phoneaikiosk.voice.WakePause.hold(
                    com.mammonrn.phoneaikiosk.voice.WakePause.Source.MUSIC,
                    object : com.mammonrn.phoneaikiosk.voice.WakePause.Media {
                        override fun quietForJarvis() { android.util.Log.i(VoiceService.TAG, "test media quiet") }
                        override fun resumeAfterJarvis() { android.util.Log.i(VoiceService.TAG, "test media resume") }
                    })
                android.util.Log.i(VoiceService.TAG, "test media hold on=$on")
            }

            ACTION_ALARM_IN -> {
                // An alarm named "ทดสอบ" N minutes from now (default 1), set
                // through the same store and scheduler a spoken command uses,
                // so ringing, the screen and the stop button can be checked
                // without the broker. TEST_ALARM_CLEAR removes it again.
                val minutes = intent.getIntExtra("in_minutes", 1).coerceIn(1, 60)
                val at = java.util.Calendar.getInstance().apply {
                    add(java.util.Calendar.MINUTE, minutes)
                }
                val book = com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(context)
                book.set(at.get(java.util.Calendar.HOUR_OF_DAY), at.get(java.util.Calendar.MINUTE),
                         TEST_ALARM_LABEL)
                com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(context, book)
                android.util.Log.i("KioskAlarm", "test alarm set in $minutes min")
            }

            ACTION_ALARM_CLEAR -> {
                val book = com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(context)
                val gone = book.removeNamed(TEST_ALARM_LABEL)
                com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(context, book)
                android.util.Log.i("KioskAlarm", "test alarms removed: $gone")
            }

            ACTION_SHOW_REPLY -> {
                // A fixed long answer on screen, "spoken" for 15 seconds with
                // no audio, no network and no cost — so the Jarvis window's
                // scrolling and voice-following can be checked on the phone
                // with a screenshot. Our own sample text, never anybody's.
                // --ei times N repeats the answer (1-4), for a window that has
                // to scroll; the "speaking" time grows with it.
                val times = intent.getIntExtra("times", 1).coerceIn(1, 4)
                VoiceState.heard = SAMPLE_QUESTION
                VoiceState.reply = List(times) { SAMPLE_REPLY }.joinToString(" ")
                VoiceState.speakingDurationMs = SAMPLE_SPEAKING_MS * times
                VoiceState.speakingSinceMs = android.os.SystemClock.elapsedRealtime()
            }

            ACTION_LISTEN -> {
                // Goes through the service, not around it: the service owns the
                // microphone and the foreground notification, and starting a
                // recording from a receiver would be the background start that
                // Android 15 refuses.
                VoiceService.start(context, VoiceService.ACTION_LISTEN_NOW)
            }

            ACTION_STATS -> {
                // adb reads this back with `logcat -s KioskStats:I -d`.
                for (line in VoiceStats(context).report().lines()) {
                    android.util.Log.i("KioskStats", line)
                }
            }

            ACTION_RESET_STATS -> {
                VoiceStats(context).reset()
                android.util.Log.i("KioskStats", "counters reset")
            }

            ACTION_SET_THRESHOLD -> {
                // Tuning this is a measurement, not a guess: too low and the
                // kiosk wakes to the television, too high and it ignores you
                // from across the room. Being able to move it without a rebuild
                // is what makes an eight-hour false-wake run worth doing.
                val value = intent.getStringExtra("value")?.toFloatOrNull()
                if (value == null) {
                    android.util.Log.i("KioskStats", "threshold unchanged: pass --es value 0.5")
                } else {
                    val applied = VoiceService.setWakeThreshold(value)
                    android.util.Log.i("KioskStats", "wake threshold now $applied")
                }
            }

            ACTION_SET_SILENCE -> {
                val value = intent.getStringExtra("value")?.toIntOrNull()
                if (value == null) {
                    android.util.Log.i("KioskStats", "silence unchanged: pass --es value 900")
                } else {
                    android.util.Log.i("KioskStats",
                                       "silence window now ${VoiceService.setSilenceWindow(value)} ms")
                }
            }

            ACTION_AUDIO_EFFECT -> {
                // One effect at a time, on purpose: turning two on together and
                // seeing an improvement says nothing about which one did it.
                // TESTING.md walks the echo canceler first, then the noise
                // suppressor, for that reason.
                val name = intent.getStringExtra("name")
                val on = intent.getStringExtra("value")?.lowercase() in setOf("on", "1", "true")
                if (name == null) {
                    android.util.Log.i("KioskStats",
                                       "pass --es name echo|noise|gain --es value on|off")
                } else {
                    android.util.Log.i("KioskStats",
                                       "audio effects now ${VoiceService.setAudioEffect(name, on)}")
                }
            }

            ACTION_AUDIO_SOURCE -> {
                val name = intent.getStringExtra("name")
                if (name == null) {
                    android.util.Log.i("KioskStats",
                                       "pass --es name voice_recognition|voice_communication|mic")
                } else {
                    android.util.Log.i("KioskStats",
                                       "audio source now ${VoiceService.setAudioSource(name)}")
                }
            }

            ACTION_SET_MARGIN -> {
                // How far above the room a voice has to be. Too high and the
                // question is cancelled as silence — which is exactly what
                // versionCode 10 did once the room level was wrong. Tunable
                // here so the right number can be found by measuring in the
                // actual room rather than guessed from a desk.
                val value = intent.getStringExtra("value")?.toFloatOrNull()
                if (value == null) {
                    android.util.Log.i("KioskStats", "margin unchanged: pass --es value 1.8")
                } else {
                    android.util.Log.i("KioskStats",
                                       "speech margin now ${VoiceService.setSpeechMargin(value)}")
                }
            }

            ACTION_SET_WAIT -> {
                val value = intent.getStringExtra("value")?.toIntOrNull()
                if (value == null) {
                    android.util.Log.i("KioskStats", "wait unchanged: pass --es value 4000")
                } else {
                    android.util.Log.i("KioskStats",
                                       "speech wait now ${VoiceService.setSpeechWait(value)} ms")
                }
            }

            ACTION_HOME -> {
                // The route back to the kiosk that does not depend on Android
                // letting a service start an activity, and does not depend on
                // Back doing what it is supposed to either.
                VoiceService.start(context, VoiceService.ACTION_RETURN_HOME)
                android.util.Log.i("KioskStats", "asked the kiosk to come forward")
            }

            ACTION_PREROLL -> {
                // The before/after of the first-syllable fix (0.49.0), on one
                // APK: --es value off, speak the sentences, --es value on,
                // speak them again. Forgotten on restart (on again).
                val on = intent.getStringExtra("value")?.lowercase() !in setOf("off", "0", "false")
                com.mammonrn.phoneaikiosk.voice.VoiceState.preRoll = on
                android.util.Log.i("KioskStats", "pre-roll ${if (on) "ON" else "off"}")
            }

            ACTION_FOCUS -> {
                // 0.61.0: another app's sound, for VlcDeck's audio focus. --es kind
                // loss|transient|duck [--ei ms 4000]: focus is taken as that kind of
                // sound would, held for ms, then given back.
                val kind = intent.getStringExtra("kind") ?: "transient"
                val ms = intent.getIntExtra("ms", 4000).toLong()
                val gain = when (kind) {
                    "loss" -> android.media.AudioManager.AUDIOFOCUS_GAIN
                    "duck" -> android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK
                    else -> android.media.AudioManager.AUDIOFOCUS_GAIN_TRANSIENT
                }
                val audio = context.getSystemService(android.media.AudioManager::class.java)
                val request = android.media.AudioFocusRequest.Builder(gain)
                    .setAudioAttributes(android.media.AudioAttributes.Builder()
                        .setUsage(android.media.AudioAttributes.USAGE_ASSISTANCE_NAVIGATION_GUIDANCE).build())
                    .setOnAudioFocusChangeListener { }
                    .build()
                val got = audio.requestAudioFocus(request)
                android.util.Log.i("KioskStats", "test focus kind=$kind granted=${got == android.media.AudioManager.AUDIOFOCUS_REQUEST_GRANTED}")
                android.os.Handler(android.os.Looper.getMainLooper()).postDelayed({
                    audio.abandonAudioFocusRequest(request)
                    android.util.Log.i("KioskStats", "test focus given back")
                }, ms)
            }

            ACTION_KEEP_CAPTURE -> {
                // 0.61.0: keep the last question's audio (files/last_capture.wav) to see
                // where the beep falls. --es value on|off; "off" also deletes the file.
                val on = intent.getStringExtra("value")?.lowercase() in setOf("on", "1", "true")
                com.mammonrn.phoneaikiosk.voice.VoiceState.keepCapture = on
                if (!on) java.io.File(context.filesDir, "last_capture.wav").delete()
                android.util.Log.i("KioskStats", "keep capture ${if (on) "ON" else "off"}")
            }

            ACTION_BEEP -> {
                // Is the first syllable lost under the wake tone? (0.61.0, DESIGN 11ก)
                // --es value off, play the same clip N times, --es value on, again.
                val on = intent.getStringExtra("value")?.lowercase() !in setOf("off", "0", "false")
                com.mammonrn.phoneaikiosk.voice.VoiceState.beep = on
                android.util.Log.i("KioskStats", "beep ${if (on) "ON" else "off"}")
            }

            ACTION_WAKE_ONLY -> {
                // Measuring how often the wake word actually fires needs twenty
                // attempts, and twenty full turns is twenty transcriptions,
                // twenty model calls and twenty spoken answers — minutes of
                // waiting and real money, to measure something that happens in
                // the first 80 ms. In this mode a detection is counted, shown
                // and beeped, and nothing else happens at all.
                val on = intent.getStringExtra("value")?.lowercase() in setOf("on", "1", "true")
                val applied = VoiceService.setWakeOnly(on)
                android.util.Log.i("KioskStats", "wake-only mode ${if (applied) "ON" else "off"}")
            }

            ACTION_DIAGNOSTICS -> {
                // The technical lines back on the screen, for somebody
                // debugging with the phone in their hand. Off by default and
                // off again after a restart; see VoiceState.showDiagnostics.
                val on = intent.getStringExtra("value")?.lowercase() in setOf("on", "1", "true")
                VoiceState.showDiagnostics = on
                android.util.Log.i("KioskStats", "on-screen diagnostics ${if (on) "ON" else "off"}")
            }

            ACTION_STT_PROVIDER -> {
                // Which transcriber the broker should use, for comparing them
                // on the real phone. groq | groq-hints | google | qwen, or "default"
                // to go back to the broker's own choice (Groq). In memory
                // only: a restart forgets it.
                val value = intent.getStringExtra("value")?.trim()?.lowercase()
                VoiceState.sttOverride = when {
                    value == null || value == "default" || value == "off" -> null
                    com.mammonrn.phoneaikiosk.voice.Broker.sttProviderHeader(value) != null -> value
                    else -> {
                        android.util.Log.i("KioskStats", "stt provider unchanged: " +
                            "pass --es value groq|groq-hints|google|default")
                        VoiceState.sttOverride
                    }
                }
                android.util.Log.i("KioskStats",
                    "stt provider now ${VoiceState.sttOverride ?: "broker default"}")
            }

            ACTION_SET_BROKER -> {
                val url = intent.getStringExtra("url")
                if (!url.isNullOrBlank()) {
                    VoiceState.brokerBaseUrl = url.trim()
                    android.util.Log.i("KioskStats", "broker set to ${VoiceState.brokerBaseUrl}")
                }
            }
        }
    }

    companion object {
        const val ACTION_LISTEN = "com.mammonrn.phoneaikiosk.TEST_LISTEN"
        const val ACTION_SHOW_REPLY = "com.mammonrn.phoneaikiosk.TEST_SHOW_REPLY"
        const val ACTION_ALARM_IN = "com.mammonrn.phoneaikiosk.TEST_ALARM_IN"
        const val ACTION_ASK = "com.mammonrn.phoneaikiosk.TEST_ASK"
        const val ACTION_ALARM_CLEAR = "com.mammonrn.phoneaikiosk.TEST_ALARM_CLEAR"
        const val ACTION_MEDIA_HOLD = "com.mammonrn.phoneaikiosk.TEST_MEDIA_HOLD"
        const val ACTION_FX = "com.mammonrn.phoneaikiosk.TEST_FX"
        const val ACTION_VIDEO_ROTATE = "com.mammonrn.phoneaikiosk.TEST_VIDEO_ROTATE"
        const val ACTION_PERFORM = "com.mammonrn.phoneaikiosk.TEST_PERFORM"
        const val ACTION_CLOCK = "com.mammonrn.phoneaikiosk.TEST_CLOCK"
        const val ACTION_HOME_CARD = "com.mammonrn.phoneaikiosk.TEST_HOME_CARD"
        const val ACTION_LIGHTS_PAGE = "com.mammonrn.phoneaikiosk.TEST_LIGHTS_PAGE"

        /** Poom's house as home_settings.view would send it after naming one channel. */
        private const val SAMPLE_LIGHTS = """{"ok": true, "age_seconds": 0, "control": true, "devices": [
            {"key": "sample0000000002", "name": "Light2", "own_name": "", "ewelink_name": "Light2",
             "room": "ห้องนอน", "kind": "plug", "online": false, "on": null, "allowed": true, "clash": [], "channels": []},
            {"key": "sample0000000001", "name": "Light1", "own_name": "", "ewelink_name": "Light1",
             "room": "ห้องนั่งเล่น", "kind": "plug", "online": true, "on": true, "allowed": true, "clash": [], "channels": []},
            {"key": "sample0000000003", "name": "Switch1", "own_name": "", "ewelink_name": "Switch1",
             "room": "ห้องนั่งเล่น", "kind": "switch", "online": true, "on": true, "allowed": true, "clash": [], "channels": [
               {"channel": 0, "name": "ไฟหน้าบ้าน", "own_name": "ไฟหน้าบ้าน", "ewelink_name": "", "on": true,
                "allowed": true, "active": true, "clash": []},
               {"channel": 1, "name": "Switch1 ช่อง 2", "own_name": "", "ewelink_name": "", "on": false,
                "allowed": true, "active": true, "clash": []},
               {"channel": 2, "name": "Switch1 ช่อง 3", "own_name": "", "ewelink_name": "", "on": false,
                "allowed": false, "active": true, "clash": []}]}]}"""

        /**
         * Made-up devices in the broker's `home` panel shape (home_control.card),
         * shaped like Poom's house on 2026-09-24: two plugs (one offline) and
         * Switch1's three real channels, one still unnamed. Showing it reaches
         * no broker; the card has nothing to press (0.47.0).
         */
        private const val SAMPLE_HOME = """{"home": {"ok": true, "age_seconds": 0, "control": true,
            "systems": [{"id": "ewelink", "name": "eWeLink", "devices": [
            {"name": "Light1", "room": "ห้องนั่งเล่น", "kind": "plug", "online": true, "on": true, "channels": [], "icon": "bulb", "target": "sample0000000001"},
            {"name": "Light2", "room": "ห้องนอน", "kind": "plug", "online": false, "on": null, "channels": [], "icon": "fan", "reason": "offline"},
            {"name": "ไฟหน้าบ้าน", "room": "ห้องนั่งเล่น", "kind": "switch", "online": true, "on": true, "channels": [], "icon": "switch", "target": "sample0000000003"},
            {"name": "แอร์", "room": "ห้องนั่งเล่น", "kind": "switch", "online": true, "on": false, "channels": [], "icon": "aircon", "target": "sample0000000004"},
            {"name": "ทีวี", "room": "ห้องนั่งเล่น", "kind": "switch", "online": true, "on": true, "channels": [], "icon": "tv", "reason": "not-allowed"}]}]}}"""

        /** The adb switch's pretend player (TEST_MEDIA_HOLD). */
        @Volatile private var testHold: com.mammonrn.phoneaikiosk.voice.WakePause.Hold? = null
        const val ACTION_VERIFY = "com.mammonrn.phoneaikiosk.TEST_VERIFY"
        const val TEST_ALARM_LABEL = "ทดสอบ"

        const val SAMPLE_QUESTION = "วันนี้อากาศที่เชียงรายเป็นยังไงบ้าง แล้วควรพกร่มไหม"
        const val SAMPLE_REPLY = "ตอนนี้เชียงราย 28 องศา ฝนปรอย ความชื้น 70% ครับ " +
            "ช่วงบ่ายร้อนสุดราว 31 องศา ส่วนกลางคืนเย็นลงเหลือ 22 องศาครับ " +
            "ถ้าออกไปข้างนอกควรพกร่มไปด้วย เพราะอาจมีฝนตกเป็นช่วงๆ ครับ " +
            "และอย่าลืมดื่มน้ำเยอะๆ นะครับ"
        const val SAMPLE_SPEAKING_MS = 15_000L
        const val ACTION_STATS = "com.mammonrn.phoneaikiosk.TEST_STATS"
        const val ACTION_RESET_STATS = "com.mammonrn.phoneaikiosk.TEST_RESET_STATS"
        const val ACTION_SET_BROKER = "com.mammonrn.phoneaikiosk.TEST_SET_BROKER"
        const val ACTION_DIAGNOSTICS = "com.mammonrn.phoneaikiosk.TEST_DIAGNOSTICS"
        const val ACTION_STT_PROVIDER = "com.mammonrn.phoneaikiosk.TEST_STT_PROVIDER"
        const val ACTION_SET_THRESHOLD = "com.mammonrn.phoneaikiosk.TEST_SET_THRESHOLD"
        const val ACTION_WAKE_ONLY = "com.mammonrn.phoneaikiosk.TEST_WAKE_ONLY"
        const val ACTION_PREROLL = "com.mammonrn.phoneaikiosk.TEST_PREROLL"
        const val ACTION_BEEP = "com.mammonrn.phoneaikiosk.TEST_BEEP"
        const val ACTION_FOCUS = "com.mammonrn.phoneaikiosk.TEST_FOCUS"
        const val ACTION_KEEP_CAPTURE = "com.mammonrn.phoneaikiosk.TEST_KEEP_CAPTURE"
        const val ACTION_HOME = "com.mammonrn.phoneaikiosk.TEST_HOME"
        const val ACTION_SET_MARGIN = "com.mammonrn.phoneaikiosk.TEST_SET_MARGIN"
        const val ACTION_SET_WAIT = "com.mammonrn.phoneaikiosk.TEST_SET_WAIT"
        const val ACTION_SET_SILENCE = "com.mammonrn.phoneaikiosk.TEST_SET_SILENCE"
        const val ACTION_AUDIO_EFFECT = "com.mammonrn.phoneaikiosk.TEST_AUDIO_EFFECT"
        const val ACTION_AUDIO_SOURCE = "com.mammonrn.phoneaikiosk.TEST_AUDIO_SOURCE"
    }
}
