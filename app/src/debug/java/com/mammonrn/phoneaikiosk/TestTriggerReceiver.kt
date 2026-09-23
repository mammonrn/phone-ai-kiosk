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
