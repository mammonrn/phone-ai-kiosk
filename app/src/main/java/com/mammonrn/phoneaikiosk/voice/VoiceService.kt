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
import android.util.Log
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.R
import java.io.FileDescriptor
import java.io.PrintWriter
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The microphone, held for as long as the kiosk is up.
 *
 * A foreground service of type `microphone`, which Android requires for
 * continuous capture — and which **cannot be started from the background or
 * from a BOOT_COMPLETED receiver** on Android 15. That is fine here and worth
 * saying why: the app is the persistent HOME activity, so the system opens
 * MainActivity itself at boot, and the service is started from an activity that
 * is on screen.
 *
 * TWO THREADS, NOT ONE. versionCode 4 had a single-threaded executor: the
 * listening loop occupied it forever, so the turn submitted when adb triggered
 * a test was queued behind a task that never finishes. The screen showed
 * `wake=triggered stt=idle` and stayed there, with nothing thrown and nothing
 * logged. The capture thread now does nothing but read the microphone, and the
 * network work runs on its own executor so the loop can go straight back to
 * listening.
 *
 * ONE RECORDER, NOT TWO. The same version opened a second AudioRecord to
 * capture the question while the listening loop still held the first. The
 * capture thread now owns the only recorder and switches modes inside the loop.
 */
class VoiceService : Service() {

    private val running = AtomicBoolean(false)

    /** Owns the microphone. Never does network work. */
    private val capture = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-capture") }

    /** Does the network work. Never touches the microphone. */
    private val network = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-network") }

    private lateinit var recorder: Recorder
    private lateinit var detector: Detector
    private lateinit var stats: VoiceStats
    private lateinit var speaker: Speaker
    private lateinit var machine: CaptureMachine

    /**
     * When the detector may listen again, on the elapsed-realtime clock.
     *
     * Written by the network thread around playback, read by the capture thread
     * every frame, so it is an AtomicLong rather than a plain field.
     */
    private val hearingFrom = java.util.concurrent.atomic.AtomicLong(0)

    /** Capture-thread only: whether the previous frame was during playback. */
    private var wasDeaf = false

    override fun onCreate() {
        super.onCreate()
        recorder = Recorder()
        detector = HeyJarvisDetector.fromAssets(this) ?: NoModelDetector("model-load-failed")
        stats = VoiceStats(this)
        speaker = Speaker(this).also { it.warmUp() }
        machine = CaptureMachine(frameMillis = recorder.frameSamples * 1000 / Recorder.SAMPLE_RATE)
        liveDetector = java.lang.ref.WeakReference(detector as? HeyJarvisDetector)
        VoiceState.detector = if (detector.ready) DETECTOR_NAME else detector.state
        VoiceState.threshold = (detector as? HeyJarvisDetector)?.threshold ?: 0f
        VoiceState.hasToken = TokenStore(this).hasToken()
        Log.i(TAG, "created detector=${VoiceState.detector} ready=${detector.ready} " +
            "token=${VoiceState.hasToken}")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Log.i(TAG, "onStartCommand action=${intent?.action} startId=$startId")

        if (!hasMicPermission()) {
            // Reported rather than crashed: the status line and the dump are how
            // anybody finds out, and the Device Owner grant is what fixes it.
            VoiceState.mic = "no-permission"
            Log.w(TAG, "RECORD_AUDIO not granted; stopping")
            stopSelf()
            return START_NOT_STICKY
        }

        startForegroundCompat()

        if (intent?.action == ACTION_LISTEN_NOW) {
            // Arms a flag the capture thread reads. It does NOT submit work to
            // the capture executor, which is the bug this replaces.
            machine.arm()
            VoiceState.wake = "triggered"
            Log.i(TAG, "armed by adb trigger; capture thread will pick it up next frame")
        }

        if (running.compareAndSet(false, true)) {
            capture.execute { captureLoop() }
        }
        return START_STICKY
    }

    /**
     * The only thread that touches the microphone.
     *
     * Reads frames, hands them to the wake word stage while listening, and
     * accumulates them while capturing. When a capture completes, the audio goes
     * to the network executor and this loop is immediately back to listening.
     */
    private fun captureLoop() {
        VoiceState.mic = "open"
        VoiceState.wake = if (detector.ready) "listening" else detector.state
        Log.i(TAG, "capture loop started")

        val buffer = java.io.ByteArrayOutputStream()

        try {
            recorder.listen(shouldStop = { !running.get() }) { frame, read ->
                val peak = peak(frame, read)
                VoiceState.level = peak

                // THE KIOSK MUST NOT ANSWER ITSELF. While it is speaking, its
                // own voice is in the microphone, and "Hey Jarvis" spoken by the
                // assistant is still "Hey Jarvis". So the detector is not fed at
                // all during playback, and for a moment afterwards — a speaker
                // keeps ringing, and the tail of the answer is still in the air.
                //
                // Not fed rather than ignored: if the frames went in and only
                // the result were discarded, the answer would still be sitting
                // in the mel buffer afterwards, and the first real detection
                // would be scored against a window half full of the kiosk's own
                // voice. The buffers are dropped when hearing resumes.
                val deaf = android.os.SystemClock.elapsedRealtime() < hearingFrom.get()
                if (deaf) {
                    wasDeaf = true
                } else if (wasDeaf) {
                    wasDeaf = false
                    detector.reset()
                    Log.i(TAG, "listening again after speaking")
                }

                val fired = !deaf && detector.ready && detector.accept(frame, read)
                (detector as? HeyJarvisDetector)?.let {
                    VoiceState.wakeScore = it.lastScore
                    VoiceState.detections = it.detections
                }
                if (fired) {
                    stats.recordWake()
                    // The score, never the audio.
                    Log.i(TAG, "wake word detected score=%.3f threshold=%.2f"
                        .format(VoiceState.wakeScore, VoiceState.threshold))
                }

                when (machine.onFrame(peak, fired)) {
                    CaptureMachine.Step.IDLE -> Unit

                    CaptureMachine.Step.STARTED -> {
                        buffer.reset()
                        VoiceState.stt = "recording"
                        Log.i(TAG, "capture started")
                    }

                    CaptureMachine.Step.CAPTURING -> recorder.appendPcm(buffer, frame, read)

                    CaptureMachine.Step.FINISHED -> {
                        recorder.appendPcm(buffer, frame, read)
                        val wav = recorder.wrapAsWav(buffer.toByteArray())
                        buffer.reset()
                        Log.i(TAG, "capture finished reason=${machine.lastStopReason} " +
                            "bytes=${wav.size}")
                        VoiceState.wake = if (detector.ready) "listening" else detector.state
                        // Straight to the other executor. This loop must not
                        // wait for the network.
                        network.execute { runTurn(wav) }
                    }
                }
                true
            }
        } catch (e: Exception) {
            VoiceState.mic = "error"
            VoiceState.lastError = "mic: ${e.javaClass.simpleName}"
            Log.e(TAG, "capture loop failed", e)
        } finally {
            VoiceState.mic = "closed"
            Log.i(TAG, "capture loop ended")
        }
    }

    /** Runs on the network executor. Never reads the microphone. */
    private fun runTurn(wav: ByteArray) {
        val token = TokenStore(this).token()
        VoiceState.hasToken = token != null
        if (token == null) {
            VoiceState.lastError = "no device token — see TESTING.md"
            Log.w(TAG, "no device token installed; see TESTING.md")
            stats.recordError()
            return
        }

        val broker = Broker(VoiceState.brokerBaseUrl, token)
        val pipeline = TurnPipeline(
            transcribe = broker::transcribe,
            ask = broker::chat,
            speak = broker::speak,
            play = { audio -> deafWhile { speaker.play(audio, "ogg") } },
            sayLocally = { text -> deafWhile { speaker.sayLocally(text) } },
            state = VoiceState,
            log = { message -> Log.i(TAG, message) },
        )

        val (outcome, conversationId) = pipeline.run(wav, VoiceState.conversationId)
        VoiceState.conversationId = conversationId
        Log.i(TAG, "turn finished outcome=$outcome")

        when (outcome) {
            TurnPipeline.Outcome.COMPLETED, TurnPipeline.Outcome.SPOKEN_LOCALLY -> {
                stats.recordTurn()
                VoiceState.turns += 1
            }
            else -> stats.recordError()
        }
    }

    /**
     * `adb shell dumpsys activity service .../.voice.VoiceService`
     *
     * The way to read the state without uiautomator, which cannot dump this
     * screen at all: the clock ticks every second so the hierarchy never
     * settles and the dump times out.
     */
    override fun dump(fd: FileDescriptor, writer: PrintWriter, args: Array<out String>?) {
        writer.print(VoiceState.dump())
        writer.println("  capture-mode : ${machine.mode}")
        writer.println("  armed        : ${machine.isArmed()}")
        val deafFor = hearingFrom.get() - android.os.SystemClock.elapsedRealtime()
        writer.println("  deaf-for-ms  : ${if (deafFor > 0) deafFor else 0}")
        writer.println()
        writer.print(stats.report())
    }

    /**
     * Runs something that makes noise, with the wake word detector switched off
     * for the whole of it and for [SETTLE_MILLIS] afterwards.
     *
     * The deadline is pushed out before the sound starts and again when it
     * stops, so a failure part-way through still leaves the detector deaf for
     * the settle window rather than opening its ears mid-syllable.
     */
    private fun <T> deafWhile(block: () -> T): T {
        val far = android.os.SystemClock.elapsedRealtime() + MAX_SPEECH_MILLIS
        hearingFrom.set(far)
        try {
            return block()
        } finally {
            hearingFrom.set(android.os.SystemClock.elapsedRealtime() + SETTLE_MILLIS)
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
        capture.shutdownNow()
        network.shutdownNow()
        detector.close()
        speaker.shutdown()
        VoiceState.mic = "stopped"
        Log.i(TAG, "destroyed")
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null

    companion object {
        /** One tag for everything, so `logcat -s KioskVoice:I` is the whole story. */
        const val TAG = "KioskVoice"

        private const val CHANNEL = "kiosk-voice"
        private const val NOTIFICATION_ID = 1

        /** What the status line calls the wake word stage when it is working. */
        const val DETECTOR_NAME = "hey_jarvis"

        /**
         * The live detector, so the debug receiver can retune it without a
         * rebuild. Weakly held and nullable on purpose: the receiver can fire
         * when no service is running, and a static strong reference to a
         * Service is a leak of everything it holds.
         */
        @Volatile
        private var liveDetector: java.lang.ref.WeakReference<HeyJarvisDetector>? = null

        /** Returns the threshold actually in force, which may have been clamped. */
        fun setWakeThreshold(value: Float): Float {
            val detector = liveDetector?.get() ?: return -1f
            detector.threshold = value
            VoiceState.threshold = detector.threshold
            return detector.threshold
        }

        /**
         * How long after a sound stops before the detector is trusted again.
         *
         * A phone speaker keeps ringing for a moment, the room has an echo, and
         * the last word of the answer is still travelling. Long enough to cover
         * that; short enough that somebody who replies immediately is heard.
         */
        const val SETTLE_MILLIS = 700L

        /**
         * The ceiling on how long the detector stays deaf if playback never
         * reports finishing. A spoken answer is capped at 100 characters, which
         * is some seconds; this is far above that, and it exists only so a
         * wedged MediaPlayer cannot deafen the kiosk permanently.
         */
        const val MAX_SPEECH_MILLIS = 60_000L

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
