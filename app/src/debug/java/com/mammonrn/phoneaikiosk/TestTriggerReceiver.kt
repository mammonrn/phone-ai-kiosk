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
                // on the real phone. groq | groq-hints | google, or "default"
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
        const val ACTION_HOME = "com.mammonrn.phoneaikiosk.TEST_HOME"
        const val ACTION_SET_MARGIN = "com.mammonrn.phoneaikiosk.TEST_SET_MARGIN"
        const val ACTION_SET_WAIT = "com.mammonrn.phoneaikiosk.TEST_SET_WAIT"
        const val ACTION_SET_SILENCE = "com.mammonrn.phoneaikiosk.TEST_SET_SILENCE"
        const val ACTION_AUDIO_EFFECT = "com.mammonrn.phoneaikiosk.TEST_AUDIO_EFFECT"
        const val ACTION_AUDIO_SOURCE = "com.mammonrn.phoneaikiosk.TEST_AUDIO_SOURCE"
    }
}
