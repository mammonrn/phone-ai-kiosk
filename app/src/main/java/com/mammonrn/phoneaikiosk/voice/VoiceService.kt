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

    /** Capture-thread only: the best score of the phrase being spoken now. */
    private var bestScore = 0f
    private var bestScoreAt = 0L
    private var lastNearMissLog = 0L

    /**
     * A frame of nothing, reused.
     *
     * Fed to the detector while the kiosk is busy or talking, so its buffers
     * stay warm without ever containing the kiosk's own voice. Allocated once:
     * this is on the capture thread, sixteen times a second.
     */
    private val silence = ShortArray(Recorder.SAMPLE_RATE / 16)

    /** The wake acknowledgement beep. Created on first use, released on stop. */
    @Volatile
    private var tone: android.media.ToneGenerator? = null

    override fun onCreate() {
        super.onCreate()
        // Asks whether an on-device Thai recognizer exists, for dumpsys. Asks
        // only: no recognition, no microphone. See DeviceSttProbe.
        runCatching { DeviceSttProbe.probe(this) }
        recorder = Recorder()
        detector = HeyJarvisDetector.fromAssets(this) ?: NoModelDetector("model-load-failed")
        stats = VoiceStats(this)
        speaker = Speaker(this).also { it.warmUp() }
        machine = CaptureMachine(frameMillis = recorder.frameSamples * 1000 / Recorder.SAMPLE_RATE)
        liveDetector = java.lang.ref.WeakReference(detector as? HeyJarvisDetector)
        liveMachine = java.lang.ref.WeakReference(machine)
        liveRecorder = java.lang.ref.WeakReference(recorder)
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

        if (intent?.action == ACTION_RETURN_HOME) {
            returnToKiosk("adb")
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
        // Status only: which source, which effects. No audio, no levels beyond
        // the peak the status line already shows.
        Log.i(TAG, "microphone source=${AudioHelpers.sourceName(recorder.requestedSource)} " +
            "${recorder.helpers.requested()}")
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
                // AND NOT DURING A TURN. The detector is fed only while the
                // machine is actually listening: not while a question is being
                // recorded, not while it is being transcribed, asked or spoken.
                // In versionCode 7 the lock covered playback alone, so a wake
                // word landing between "capture finished" and "tts ok" started
                // a second turn on top of the first — and that second capture
                // recorded the first answer.
                val busy = machine.mode != CaptureMachine.Mode.LISTENING
                val deaf = busy || android.os.SystemClock.elapsedRealtime() < hearingFrom.get()

                // SILENCE, NOT NOTHING. This used to skip the detector entirely
                // while deaf and then reset() it on the way back — and reset
                // empties the feature buffer, which needs SIXTEEN 80 ms chunks
                // to refill. For 1.28 seconds afterwards the detector produced
                // no score at all: a wake word spoken then was not missed, it
                // was unheard. With the 700 ms settle on top, the kiosk was
                // deaf for about two seconds after every single interaction,
                // which is why "Hey Jarvis" so often had to be said twice.
                //
                // Feeding silence keeps every buffer full and advancing while
                // still guaranteeing the kiosk's own voice never enters them —
                // which was the entire point of not feeding it. When hearing
                // resumes the window holds a quiet room, which is exactly what
                // precedes a wake word in normal use.
                val fired = if (deaf) {
                    wasDeaf = true
                    // The result is discarded on purpose: silence must not wake
                    // anything. The call is for its effect on the buffers.
                    if (detector.ready) detector.accept(silence, read)
                    false
                } else {
                    if (wasDeaf) {
                        wasDeaf = false
                        // Naming the state rather than guessing the cause: this
                        // also fires after a cancelled capture, where nothing
                        // was said at all.
                        Log.i(TAG, "listening again (mode=${machine.mode} " +
                            "features=${(detector as? HeyJarvisDetector)?.featureCount ?: 0})")
                    }
                    detector.ready && detector.accept(frame, read)
                }
                (detector as? HeyJarvisDetector)?.let {
                    VoiceState.wakeScore = it.lastScore
                    VoiceState.detections = it.detections
                    if (!deaf) noteScore(it)
                }
                if (fired) {
                    stats.recordWake()
                    val now = android.os.SystemClock.elapsedRealtime()
                    // How long the phrase took to cross the line, measured from
                    // the first frame that was clearly on its way. If this is
                    // large the detector is hearing the word late, which is a
                    // different problem from hearing it quietly.
                    val climb = if (bestScoreAt > 0) now - bestScoreAt else 0
                    val warm = (detector as? HeyJarvisDetector)?.warm ?: false
                    // The score, never the audio.
                    Log.i(TAG, "wake word detected score=%.3f threshold=%.2f "
                        .format(VoiceState.wakeScore, VoiceState.threshold) +
                        "best_before=%.3f climb_ms=%d warm=%s"
                            .format(bestScore, climb, warm))
                    clearScoreWatch()
                    // Something has to tell the person it heard them, or the
                    // only feedback is an answer several seconds later.
                    acknowledge()
                    Log.i(TAG, "beep %d ms after the best score"
                        .format(android.os.SystemClock.elapsedRealtime() - now + climb))
                }

                if (VoiceState.wakeOnly) {
                    // Counted, shown, beeped — and that is all. The frame still
                    // goes through the machine so the ambient level keeps being
                    // tracked, but never as a trigger, so no capture can start
                    // and nothing is ever sent anywhere.
                    machine.onFrame(peak, false)
                    return@listen true
                }

                when (machine.onFrame(peak, fired)) {
                    CaptureMachine.Step.IDLE -> Unit

                    CaptureMachine.Step.STARTED -> {
                        buffer.reset()
                        VoiceState.stt = "recording"
                        // Logged at the start as well, so the calibration is
                        // visible on the turns that WORK, not only the ones
                        // that fail. A threshold that is quietly drifting up is
                        // easier to catch before it cancels anything.
                        Log.i(TAG, "capture started ambient=${machine.ambientLevel()} " +
                            "threshold=${machine.speechThreshold}")
                        // If Maps is on top, saying the wake word should bring
                        // the kiosk back so the person can see what it heard.
                        // Only then: calling this on every capture would be a
                        // no-op most of the time and an activity start from a
                        // service every time, which is not free.
                        // The same for Xiaomi Home: "Hey Jarvis" over the
                        // camera view brings the kiosk back, as Back does.
                        if (VoiceState.lastAction.startsWith("open_maps:opened") ||
                            VoiceState.lastAction.startsWith("open_camera_app:opened")) {
                            returnToKiosk("wake")
                        }
                    }

                    CaptureMachine.Step.CAPTURING -> recorder.appendPcm(buffer, frame, read)

                    CaptureMachine.Step.CANCELLED -> {
                        // Thrown away here, on the phone. Nothing is uploaded,
                        // nothing is transcribed, nothing is asked, nothing is
                        // paid for. Somebody said the wake word and then did
                        // not ask anything, or the room cleared the bar for a
                        // moment and then did not.
                        buffer.reset()
                        VoiceState.stt = "idle"
                        VoiceState.lastCancel = machine.lastStopReason
                        if (machine.startedByWakeWord &&
                            machine.lastStopReason == "no-speech-after-wake") {
                            // A wake with no question behind it. Either the
                            // room said something that sounded like the wake
                            // word, or somebody changed their mind. Counted so
                            // that lowering the threshold to 0.40 can be judged
                            // on evidence rather than on how it feels.
                            stats.recordFalseWakeCandidate()
                        }
                        VoiceState.heard = ""
                        VoiceState.reply = ""
                        // EVERY NUMBER NEEDED TO TELL THE TWO CASES APART.
                        // "The room was silent" and "the bar was set too high"
                        // look identical without peak_while_waiting: if it is
                        // near the threshold somebody spoke and was not heard;
                        // if it is near the ambient level, nobody spoke.
                        Log.i(TAG, "capture cancelled reason=${machine.lastStopReason} " +
                            "ambient=${machine.ambientLevel()} " +
                            "threshold=${machine.speechThreshold} " +
                            "peak_while_waiting=${machine.peakWhileWaiting} " +
                            "margin=%.2f wait_ms=%d"
                                .format(machine.speechMargin, machine.speechWaitMillis))
                        VoiceState.wake = if (detector.ready) "listening" else detector.state
                    }

                    CaptureMachine.Step.FINISHED -> {
                        recorder.appendPcm(buffer, frame, read)
                        val wav = recorder.wrapAsWav(buffer.toByteArray())
                        buffer.reset()
                        val captureEndedAt = android.os.SystemClock.elapsedRealtime()
                        Log.i(TAG, "capture finished reason=${machine.lastStopReason} " +
                            "bytes=${wav.size} silence_ms=${machine.silenceMillis}")
                        VoiceState.wake = if (detector.ready) "listening" else detector.state
                        // Straight to the other executor. This loop must not
                        // wait for the network.
                        network.execute { runTurn(wav, captureEndedAt) }
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
    private fun runTurn(wav: ByteArray, captureEndedAt: Long) {
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
            perform = ::performAction,
            state = VoiceState,
            log = { message -> Log.i(TAG, message) },
        )

        try {
            val (outcome, conversationId) = pipeline.run(wav, VoiceState.conversationId)
            VoiceState.conversationId = conversationId
            // ONE LINE WITH THE WHOLE WAIT IN IT, measured from the moment the
            // person stopped talking — which is when they start waiting, and is
            // not the same as when this class started working. The silence
            // window is part of it and is the only part spent doing nothing.
            Log.i(TAG, "turn finished outcome=$outcome " +
                "since_capture_end_ms=${android.os.SystemClock.elapsedRealtime() - captureEndedAt}")

            when (outcome) {
                TurnPipeline.Outcome.COMPLETED, TurnPipeline.Outcome.SPOKEN_LOCALLY -> {
                    stats.recordTurn()
                    VoiceState.turns += 1
                }
                TurnPipeline.Outcome.NO_QUESTION -> {
                    // Not an error and not a turn: somebody said the wake word
                    // and did not ask anything. Nothing was spoken, so there is
                    // nothing to apologise for either.
                    VoiceState.lastCancel = "no-question-transcribed"
                }
                else -> stats.recordError()
            }
        } finally {
            // THE LOCK COMES OFF HERE AND ONLY HERE, on every path including a
            // thrown one. A turn that failed still has to hand the microphone
            // back, or the kiosk goes deaf for good and looks like the wake
            // word stopped working.
            machine.turnFinished()
            // NO detector.reset() HERE. The detector was fed silence for the
            // whole turn, so it holds nothing of the answer that was just
            // spoken — and resetting would empty the window it needs, leaving
            // the kiosk deaf for the 1.28 seconds right after an answer, which
            // is exactly when somebody is most likely to speak again.
            VoiceState.wake = if (detector.ready) "listening" else detector.state
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
        // The two facts that matter once the screen can go dark: is it dark,
        // and is the phone paying for the microphone in heat or charge. Read
        // here, where there is a Context, rather than kept in VoiceState.
        val power = getSystemService(android.os.PowerManager::class.java)
        writer.println("  screen-on    : ${power?.isInteractive}")
        val battery = registerReceiver(null,
            android.content.IntentFilter(android.content.Intent.ACTION_BATTERY_CHANGED))
        if (battery != null) {
            val level = battery.getIntExtra(android.os.BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(android.os.BatteryManager.EXTRA_SCALE, 100)
            val tenths = battery.getIntExtra(android.os.BatteryManager.EXTRA_TEMPERATURE, 0)
            val plugged = battery.getIntExtra(android.os.BatteryManager.EXTRA_PLUGGED, 0)
            writer.println("  battery      : ${level * 100 / scale.coerceAtLeast(1)}%  " +
                "%.1f°C  plugged=%s".format(tenths / 10.0, plugged != 0))
        }
        writer.println("  capture-mode : ${machine.mode}")
        writer.println("  armed        : ${machine.isArmed()}")
        val deafFor = hearingFrom.get() - android.os.SystemClock.elapsedRealtime()
        writer.println("  deaf-for-ms  : ${if (deafFor > 0) deafFor else 0}")
        writer.println("  speech-floor : ${machine.speechThreshold} " +
            "(ambient ${machine.ambientLevel()}, room now ${machine.currentRoomLevel()})")
        writer.println("  wake-tuning  : margin %.2f  wait %d ms  silence %d ms"
            .format(machine.speechMargin, machine.speechWaitMillis, machine.silenceMillis)
            + "  (adb, resets on restart)")
        val heyJarvis = detector as? HeyJarvisDetector
        writer.println("  detector-warm: ${heyJarvis?.warm ?: false} "
            + "(features ${heyJarvis?.featureCount ?: 0}/${HeyJarvisDetector.CLASSIFIER_FRAMES}, "
            + "chunks ${heyJarvis?.chunksProcessed ?: 0})")
        writer.println("  peak-waiting : ${machine.peakWhileWaiting}")
        writer.println()
        writer.println("microphone")
        writer.println("  source       : ${AudioHelpers.sourceName(recorder.activeSource)}"
            + "  (requested ${AudioHelpers.sourceName(recorder.requestedSource)})")
        writer.println("  asked for    : ${recorder.helpers.requested()}")
        for (state in recorder.helpers.states()) {
            writer.println("  $state")
        }
        writer.println("  (all adb switches reset when the service restarts)")
        writer.println("  stop-reason  : ${machine.lastStopReason}")
        writer.println("  maps-package : ${MapsLauncher.MAPS_PACKAGE} " +
            "installed=${MapsLauncher.isInstalled(this)}")
        // Read on the phone, never assumed: whether Xiaomi Home is here and
        // which version. Nothing about the account it is signed in to.
        writer.println("  camera-package: ${CameraAppLauncher.PACKAGE} " +
            "installed=${CameraAppLauncher.isInstalled(this)} " +
            "version=${CameraAppLauncher.version(this)}")
        writer.println()
        writer.print(stats.report())
    }

    /**
     * Brings the kiosk screen back in front of whatever is showing.
     *
     * ❓ THIS MAY NOT WORK, AND IT IS WRITTEN SO THAT NOT WORKING IS VISIBLE.
     * Android restricts starting an activity from the background, and a
     * foreground service is still the background for that rule. Being the HOME
     * app and the Device Owner may or may not exempt this one; the phone is the
     * only place that answers it, so the attempt and its exception are both
     * logged rather than assumed either way.
     *
     * The routes that do not depend on it: Back from Maps, and the adb command
     * in TESTING.md. Neither needs this to succeed.
     */
    fun returnToKiosk(reason: String) {
        val intent = Intent(this, com.mammonrn.phoneaikiosk.MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
        try {
            startActivity(intent)
            Log.i(TAG, "returning to the kiosk screen reason=$reason")
        } catch (e: Exception) {
            // The interesting case. If this is what the A07 logs, the answer to
            // "why did it stay on Maps" is here and not a mystery.
            Log.w(TAG, "could not return to the kiosk screen: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Carries out an action the broker approved. Returns what to say if it
     * could not be done.
     *
     * The only action there is opens Google Maps. The check for the type is
     * here as well as in Broker.readAction and in the broker itself, because
     * this is the last line before startActivity and three cheap checks in a
     * row is the right number for the one place a string becomes an Intent.
     */
    private fun performAction(action: KioskAction): String? {
        if (action.type == KioskAction.OPEN_CAMERA_APP) return openCameraApp()
        if (action.type != KioskAction.OPEN_MAPS) {
            Log.w(TAG, "refused action type=${action.type}")
            VoiceState.lastAction = "${action.type}:refused"
            return null
        }

        // ASKED AGAIN, EVERY TIME. Maps can appear after the service started —
        // on the A07 it was installed for user 0 with `cmd package
        // install-existing` while the kiosk was running — and a state read once
        // at startup would keep saying "not-installed" until somebody thought
        // to restart the app.
        val readiness = MapsLauncher.refreshState(this)
        VoiceState.mapsState = readiness
        Log.i(TAG, "maps readiness=$readiness")

        val result = MapsLauncher.open(this, action.destination)
        // The type and the outcome. Not the destination: where somebody asked
        // to be taken does not belong in a log.
        VoiceState.lastAction = "open_maps:${result.name.lowercase()}"
        Log.i(TAG, "action open_maps result=${result.name} " +
            "destination_chars=${action.destination.length}")

        if (result == MapsLauncher.Result.OPENED) {
            VoiceState.mapsState = "opened"
            return null
        }
        return MapsLauncher.spokenFailure(result)
    }

    /**
     * "ขอดูกล้อง": Xiaomi Home comes up, after Jarvis has said so.
     *
     * The screen is lit first in case it went dark between the question and
     * the answer — the wake word lit it, but a slow answer can outlast a short
     * system timeout on battery. Logged as a type and an outcome only: nothing
     * about the account or the cameras exists on this side to log.
     *
     * A failure is spoken by the pipeline AND put where the screen shows the
     * reply, because "the app is not installed" is something to read as well
     * as hear.
     */
    private fun openCameraApp(): String? {
        runCatching { ScreenWaker.wakeIfAsleep(this) }
        val result = CameraAppLauncher.open(this)
        VoiceState.lastAction = "open_camera_app:${result.name.lowercase()}"
        Log.i(TAG, "action open_camera_app result=${result.name}")
        if (result == CameraAppLauncher.Result.OPENED) return null
        val failure = CameraAppLauncher.spokenFailure(result)
        VoiceState.reply = failure
        return failure
    }

    /**
     * Watches scores that do not quite make it.
     *
     * A miss and a blind spot look identical from outside — both are silence —
     * so this separates them. A near miss says the detector heard the phrase
     * and scored it below the bar, which is an argument about the threshold. No
     * score at all while `warm` is false says the detector was not able to
     * answer yet, which is an argument about something else entirely.
     *
     * Rate limited: this runs sixteen times a second and a log line per frame
     * would bury everything else.
     */
    private fun noteScore(detector: HeyJarvisDetector) {
        val score = detector.lastScore
        if (score < HeyJarvisDetector.NEAR_MISS_FLOOR) {
            // Far enough below to be room noise. If the climb has gone quiet,
            // forget it so the next phrase is measured from its own start.
            if (bestScoreAt > 0 &&
                android.os.SystemClock.elapsedRealtime() - bestScoreAt > CLIMB_FORGET_MILLIS) {
                clearScoreWatch()
            }
            return
        }

        val now = android.os.SystemClock.elapsedRealtime()
        if (score > bestScore) {
            bestScore = score
            bestScoreAt = now
        }
        if (score >= detector.threshold) return          // about to fire; not a miss

        if (now - lastNearMissLog < NEAR_MISS_LOG_INTERVAL_MILLIS) return
        lastNearMissLog = now
        stats.recordNearMiss()
        Log.i(TAG, "wake near miss score=%.3f threshold=%.2f warm=%s features=%d chunks=%d"
            .format(score, detector.threshold, detector.warm,
                    detector.featureCount, detector.chunksProcessed))
    }

    private fun clearScoreWatch() {
        bestScore = 0f
        bestScoreAt = 0
    }

    /**
     * Tells the person it heard them, the moment it hears them.
     *
     * Without this the only feedback is the answer, several seconds later, and
     * somebody who is not sure whether they were heard says it again — which
     * used to start a second turn. A short tone and a line on the screen cost
     * nothing and remove the reason to repeat yourself.
     *
     * ToneGenerator rather than an audio asset: it is in the platform, it needs
     * no file, and it plays on the notification stream so it does not fight the
     * answer for the music stream.
     */
    private fun acknowledge() {
        VoiceState.lastCancel = ""
        VoiceState.heard = ""
        VoiceState.reply = ""
        VoiceState.wake = "heard"
        // A dark kiosk lights up the moment it hears its name, before the tone,
        // so the screen is already there by the time the question starts. See
        // ScreenWaker; a failure here must not cost the turn.
        runCatching { ScreenWaker.wakeIfAsleep(this) }
        runCatching {
            if (tone == null) {
                tone = android.media.ToneGenerator(
                    android.media.AudioManager.STREAM_NOTIFICATION, TONE_VOLUME,
                )
            }
            tone?.startTone(android.media.ToneGenerator.TONE_PROP_BEEP, TONE_MILLIS)
        }
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
        runCatching { tone?.release() }
        tone = null
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

        /**
         * The live capture machine, so the debug receiver can retune the room
         * calibration without a rebuild. Same weak reference and same reason as
         * liveDetector.
         */
        @Volatile
        private var liveMachine: java.lang.ref.WeakReference<CaptureMachine>? = null

        /**
         * The live recorder, so the adb switches can ask for a different audio
         * source or a different set of effects.
         *
         * They only ever SET A FLAG. The capture thread is the single owner of
         * the AudioRecord and is what closes one and opens the next, so no
         * switch can produce two open recorders — which is the failure mode
         * this whole design exists to avoid.
         */
        @Volatile
        private var liveRecorder: java.lang.ref.WeakReference<Recorder>? = null

        /** Switches one microphone effect. Returns what is now requested. */
        fun setAudioEffect(name: String, on: Boolean): String {
            val recorder = liveRecorder?.get() ?: return "no recorder"
            when (name) {
                AudioHelpers.ECHO -> recorder.helpers.wantEcho = on
                AudioHelpers.NOISE -> recorder.helpers.wantNoise = on
                AudioHelpers.GAIN -> recorder.helpers.wantGain = on
                else -> return "unknown effect $name"
            }
            // Effects bind to a recording session, so they take hold when the
            // next one opens. Asking for that here, not doing it here.
            recorder.reopenRequested = true
            return recorder.helpers.requested()
        }

        /** Switches the audio source. Returns what is now requested. */
        fun setAudioSource(name: String): String {
            val recorder = liveRecorder?.get() ?: return "no recorder"
            val source = AudioHelpers.SOURCES[name] ?: return "unknown source $name"
            recorder.requestedSource = source
            recorder.reopenRequested = true
            return name
        }

        /** Returns the margin actually in force, which may have been clamped. */
        fun setSpeechMargin(value: Float): Float {
            val machine = liveMachine?.get() ?: return -1f
            machine.speechMargin = value
            return machine.speechMargin
        }

        /**
         * How long a pause ends the question. Returns what is now in force.
         *
         * The one piece of the wait between a question ending and an answer
         * starting that is pure doing-nothing, so it is the first place to look
         * for a faster kiosk — and the easiest to make too short.
         */
        fun setSilenceWindow(millis: Int): Int {
            val machine = liveMachine?.get() ?: return -1
            machine.silenceMillis = millis
            return machine.silenceMillis
        }

        /** Returns the wait actually in force, which may have been clamped. */
        fun setSpeechWait(millis: Int): Int {
            val machine = liveMachine?.get() ?: return -1
            machine.speechWaitMillis = millis
            return machine.speechWaitMillis
        }

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

        /** At most one near-miss line a second; this runs sixteen times. */
        const val NEAR_MISS_LOG_INTERVAL_MILLIS = 1_000L

        /** How long a rising score stays interesting before it is a new phrase. */
        const val CLIMB_FORGET_MILLIS = 2_000L

        /**
         * The ceiling on how long the detector stays deaf if playback never
         * reports finishing. A spoken answer is capped at 100 characters, which
         * is some seconds; this is far above that, and it exists only so a
         * wedged MediaPlayer cannot deafen the kiosk permanently.
         */
        const val MAX_SPEECH_MILLIS = 60_000L

        /** Loud enough to hear across a room, short enough not to be annoying. */
        const val TONE_VOLUME = 70
        const val TONE_MILLIS = 120

        /** Used by the debug-only adb trigger. */
        const val ACTION_LISTEN_NOW = "com.mammonrn.phoneaikiosk.LISTEN_NOW"
        const val ACTION_RETURN_HOME = "com.mammonrn.phoneaikiosk.RETURN_HOME"

        /**
         * Wake-word-only test mode. Counted and shown, never recorded.
         *
         * Lives on VoiceState rather than in the detector because it is about
         * what the SERVICE does with a detection, not about detecting.
         */
        fun setWakeOnly(on: Boolean): Boolean {
            VoiceState.wakeOnly = on
            return VoiceState.wakeOnly
        }

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
