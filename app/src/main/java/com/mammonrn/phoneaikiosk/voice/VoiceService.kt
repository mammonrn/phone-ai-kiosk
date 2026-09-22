package com.mammonrn.phoneaikiosk.voice

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.R
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone, held for as long as the kiosk is up.
 *
 * A foreground service of type `microphone`, which Android requires for
 * continuous capture — and which **cannot be started from the background or
 * from a BOOT_COMPLETED receiver** on Android 15. That is not a problem here
 * and it is worth saying why: the app is the persistent HOME activity, so the
 * system opens MainActivity itself at boot, and the service is started from an
 * activity that is on screen. The phase 1 design already put us on the right
 * side of that rule.
 *
 * If this service is killed, [Service.onStartCommand] returning START_STICKY
 * brings it back, and MainActivity.onResume starts it again regardless — two
 * independent paths, neither of which is BOOT_COMPLETED.
 */
class VoiceService : Service() {

    private val running = AtomicBoolean(false)
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var recorder: Recorder
    private lateinit var detector: Detector
    private lateinit var stats: VoiceStats
    private lateinit var speaker: Speaker

    override fun onCreate() {
        super.onCreate()
        recorder = Recorder()
        detector = NoModelDetector(this)
        stats = VoiceStats(this)
        speaker = Speaker(this).also { it.warmUp() }
        VoiceState.detector = detector.state
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!hasMicPermission()) {
            // Reported rather than crashed: the status line is how anybody finds
            // out, and the Device Owner grant is what fixes it.
            VoiceState.mic = "no-permission"
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (intent?.action == ACTION_LISTEN_NOW) {
            // The adb trigger: skip the wake word and take a question straight
            // away, so the rest of the pipeline can be tested before a model
            // exists.
            VoiceState.wake = "triggered"
            worker.execute { runOneTurn(confirmed = true) }
            return START_STICKY
        }

        if (running.compareAndSet(false, true)) {
            worker.execute { listenLoop() }
        }
        return START_STICKY
    }

    /**
     * Listens on the device, forever, and sends nothing anywhere.
     *
     * This is the loop that has to be right for the promise to hold: audio is
     * read into a frame, handed to the detector, and dropped. Nothing leaves
     * this method until [Detector.accept] returns true.
     */
    private fun listenLoop() {
        VoiceState.mic = "open"
        try {
            recorder.listen(shouldStop = { !running.get() }) { frame, read ->
                VoiceState.level = peak(frame, read)
                if (detector.accept(frame, read)) {
                    VoiceState.wake = "detected"
                    stats.recordWake()
                    runOneTurn(confirmed = false)
                }
                true
            }
        } catch (e: Exception) {
            VoiceState.mic = "error"
            VoiceState.lastError = "mic: ${e.javaClass.simpleName}"
        } finally {
            VoiceState.mic = "closed"
        }
    }

    /** Record, transcribe, answer, speak. Each stage reports itself. */
    private fun runOneTurn(confirmed: Boolean) {
        if (confirmed) stats.recordConfirmed()

        val token = TokenStore(this).token()
        if (token == null) {
            VoiceState.lastError = "no device token — see TESTING.md"
            stats.recordError()
            return
        }
        val broker = Broker(VoiceState.brokerBaseUrl, token)

        try {
            VoiceState.stt = "recording"
            val wav = recorder.recordQuestion(maxMillis = 12_000, silenceMillis = 1_200)

            VoiceState.stt = "sending"
            val question = broker.transcribe(wav)
            VoiceState.heard = question
            VoiceState.stt = "ok"

            VoiceState.chat = "asking"
            val (reply, conversationId) = broker.chat(question, VoiceState.conversationId)
            VoiceState.conversationId = conversationId.ifEmpty { null }
            VoiceState.reply = reply
            VoiceState.chat = "ok"

            VoiceState.tts = "synthesising"
            val spoken = try {
                val audio = broker.speak(reply)
                if (speaker.play(audio, "ogg")) "cloud" else null
            } catch (e: Broker.Failure) {
                VoiceState.lastError = "tts ${e.code}"
                null
            }

            if (spoken == null) {
                // The answer is worth more than the voice it is said in.
                VoiceState.tts = if (speaker.sayLocally(reply)) "device-fallback" else "failed"
            } else {
                VoiceState.tts = "ok"
            }

            stats.recordTurn()
        } catch (e: Broker.Failure) {
            // The broker's own Thai message, including "the month's budget is
            // gone" — said out loud, because a kiosk that just stops is a kiosk
            // nobody can diagnose from the sofa.
            VoiceState.lastError = "${e.code}"
            VoiceState.chat = "error"
            stats.recordError()
            speaker.sayLocally(e.message)
        } catch (e: Exception) {
            VoiceState.lastError = e.javaClass.simpleName
            stats.recordError()
        } finally {
            VoiceState.wake = if (detector.ready) "listening" else detector.state
        }
    }

    private fun peak(frame: ShortArray, read: Int): Int {
        var peak = 0
        for (i in 0 until read) {
            val magnitude = kotlin.math.abs(frame[i].toInt())
            if (magnitude > peak) peak = magnitude
        }
        return peak
    }

    private fun hasMicPermission(): Boolean =
        ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) ==
            PackageManager.PERMISSION_GRANTED

    private fun startForegroundCompat() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, getString(R.string.voice_channel),
                                NotificationManager.IMPORTANCE_LOW),
        )
        val notification: Notification = Notification.Builder(this, CHANNEL)
            .setContentTitle(getString(R.string.voice_notification))
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setOngoing(true)
            .build()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            startForeground(NOTIFICATION_ID, notification,
                            ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    override fun onDestroy() {
        running.set(false)
        worker.shutdownNow()
        detector.close()
        speaker.shutdown()
        VoiceState.mic = "stopped"
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        private const val CHANNEL = "kiosk-voice"
        private const val NOTIFICATION_ID = 1

        /** Used by the debug-only adb trigger. */
        const val ACTION_LISTEN_NOW = "com.mammonrn.phoneaikiosk.LISTEN_NOW"

        /**
         * Started from a visible activity, never from a receiver: a microphone
         * foreground service cannot be launched from the background on
         * Android 15.
         */
        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, VoiceService::class.java).apply {
                if (action != null) this.action = action
            }
            context.startForegroundService(intent)
        }
    }
}
