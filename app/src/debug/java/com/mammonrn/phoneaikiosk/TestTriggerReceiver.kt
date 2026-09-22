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
        const val ACTION_SET_THRESHOLD = "com.mammonrn.phoneaikiosk.TEST_SET_THRESHOLD"
    }
}
