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

    /** The last second of microphone frames, for the first syllable (PreRoll). Capture thread only. */
    private val preRoll = PreRoll()

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

    /** Set by a button press, consumed by the capture thread when the capture starts. */
    @Volatile
    private var ackOnStart = false

    override fun onCreate() {
        super.onCreate()
        instance = this
        // Asks whether an on-device Thai recognizer exists, for dumpsys. Asks
        // only: no recognition, no microphone. See DeviceSttProbe.
        runCatching { DeviceSttProbe.probe(this) }
        recorder = Recorder()
        detector = HeyJarvisDetector.fromAssets(this) ?: NoModelDetector("model-load-failed")
        stats = VoiceStats(this)
        SoakProbe.noteCreate(this, "service")
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
        alarmHandler.postDelayed(soakTick, SoakProbe.FIRST_MS)
        // JARVIS RESTS WHILE MEDIA PLAYS (Poom, 2026-09-24): a new service is a
        // new start — whatever held the pause before a restart is gone, so the
        // wake word listens. Players quieted for a question are told on the
        // main thread, which owns their controls.
        WakePause.reset()
        WakePause.clock = { android.os.SystemClock.elapsedRealtime() }
        WakePause.post = { block -> alarmHandler.post(block) }
        WakePause.log = { message -> Log.i(TAG, message) }
        MicTap.log = { message -> Log.i("KioskRec", message) }
    }

    // ------------------------------------------------------------ soak test

    /**
     * Its own thread: a send that hangs on a dead network must never hold up
     * a turn on [network]. One sample every 15 minutes, well under a second
     * of work; a send that did not reach the VPS is retried after a minute.
     */
    private val soakThread = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-soak") }

    private val soakTick = object : Runnable {
        override fun run() {
            soakThread.execute {
                val token = TokenStore(this@VoiceService).token()
                var next = SoakProbe.INTERVAL_MS
                if (!token.isNullOrEmpty()) {
                    try {
                        val sample = SoakProbe.sample(this@VoiceService, stats, detector.ready)
                        val kept = Broker(VoiceState.brokerBaseUrl, token).health(sample)
                        Log.i(TAG, "soak sample sent kept=$kept")
                    } catch (e: Broker.Failure) {
                        // Reached the VPS and was answered: not a network outage.
                        Log.i(TAG, "soak sample refused ${e.status}")
                    } catch (e: Exception) {
                        SoakProbe.sendFailures += 1
                        next = SoakProbe.RETRY_MS
                        Log.i(TAG, "soak sample not sent ${e.javaClass.simpleName}")
                    }
                }
                alarmHandler.postDelayed(this, next)
            }
        }
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
            VoiceState.turnWake = "adb"
            Log.i(TAG, "armed by adb trigger; capture thread will pick it up next frame")
        }

        if (intent?.action == ACTION_TEST_ASK) {
            // Debug builds only send this (TestTriggerReceiver): a question as
            // text through /v1/chat, the voice and the action, as if spoken.
            val typed = intent.getStringExtra(EXTRA_TEXT)?.trim().orEmpty()
            if (typed.length in 1..200) {
                Log.i(TAG, "typed turn chars=${typed.length}")
                network.execute { runTurn(ByteArray(TurnPipeline.WAV_HEADER_BYTES + 2), 0L, typed) }
            }
        }

        if (intent?.action == ACTION_TEST_PERFORM) {
            // Debug builds only send this (TestTriggerReceiver, 0.57.0): an action as the broker
            // would send it, done by the real code — optionally inside a question (WakePause's
            // turn), to check that a spoken pause is not undone when the answer ends.
            val type = intent.getStringExtra(EXTRA_TYPE).orEmpty()
            if (type == KioskAction.VIDEO || type == KioskAction.MUSIC) {
                val action = KioskAction(type, "", mapOf("command" to intent.getStringExtra(EXTRA_COMMAND).orEmpty(),
                                                         "query" to intent.getStringExtra(EXTRA_QUERY).orEmpty()))
                val asTurn = intent.getBooleanExtra(EXTRA_AS_TURN, false)
                if (asTurn) WakePause.turnStarted()
                alarmHandler.postDelayed({
                    val instead = performAction(action)
                    // Whether it was done — never the words, which may carry a name.
                    Log.i(TAG, "test perform type=$type command=${action.params["command"]} done=${instead == null}")
                    if (asTurn) alarmHandler.postDelayed({ WakePause.turnEnded() }, 1_500)
                }, 1_500)
            }
        }

        if (intent?.action == ACTION_AUTH_PASSED) {
            resumePrivate(intent.getStringExtra(EXTRA_METHOD) ?: "face",
                          intent.getBooleanExtra(com.mammonrn.phoneaikiosk.auth.VerifyActivity.EXTRA_FOR_PRIVATE,
                                                 false))
        }

        if (intent?.action == ACTION_ALARM_RING) {
            startAlarm(intent.getIntExtra(EXTRA_ALARM_ID, -1))
        }

        if (intent?.action == ACTION_ALARM_STOP) {
            stopAlarm("stop-button")
        }

        // 0.62.0: the countdown's end, rung by the alarm's own ringer (timer/TimerClock).
        if (intent?.action == ACTION_TIMER_RING) {
            startTimerRing()
        }

        // The Jarvis button stops a ringing alarm rather than asking a question.
        if (intent?.action == ACTION_BUTTON_LISTEN && VoiceState.alarmRinging.isNotEmpty()) {
            stopAlarm("jarvis-button")
        } else if (intent?.action == ACTION_BUTTON_LISTEN) {
            // The taskbar's "จาร์วิส" button: the same as saying Hey Jarvis.
            // Accepted only while listening — a press mid-turn does nothing,
            // so a second turn can never start on top of the first. The beep
            // and "heard" state come from the capture thread when the capture
            // actually starts (ackOnStart), exactly as the wake word's do.
            if (WakeGate.buttonMayStart(VoiceState.wakeOnly, machine.canStartByButton())) {
                ackOnStart = true
                machine.arm()
                VoiceState.wake = "triggered"
                VoiceState.turnWake = "button"
                Log.i(TAG, "armed by the Jarvis button")
            } else {
                Log.i(TAG, "Jarvis button ignored: mode=${machine.mode} wakeOnly=${VoiceState.wakeOnly}")
            }
        }

        if (intent?.action == ACTION_RETURN_HOME) {
            comeHome("adb")
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
                // And while an alarm rings: the alarm tone is loud, close and
                // looping, and must never be heard as the wake word.
                // And while music or a video plays (WakePause, Poom 2026-09-24):
                // the wake word is off, the Jarvis button is not (WakeGate).
                val deaf = WakeGate.deaf(busy, VoiceState.alarmRinging.isNotEmpty(),
                    android.os.SystemClock.elapsedRealtime(), hearingFrom.get(),
                    WakePause.paused())
                // Kept for the first syllable of a command said straight after
                // the wake word (PreRoll). Only what the detector hears anyway,
                // and never while deaf: playback and alarms are not the room.
                if (deaf) preRoll.clear() else preRoll.add(frame, read, peak)
                // THE VOICE RECORDER (0.61.0) reads this same stream rather than
                // opening a second capture (MicTap). Nothing during a Jarvis turn.
                MicTap.offer(frame, read, peak, busy)

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
                    // For the broker's speech gate: how sure the detector was.
                    // Locale.US so it is "0.430" and never "0,430".
                    VoiceState.turnScreen = ""   // the wake word: asked from no particular screen
                    VoiceState.turnWake = String.format(java.util.Locale.US, "%.3f",
                                                        VoiceState.wakeScore)
                    // Something has to tell the person it heard them, or the
                    // only feedback is an answer several seconds later.
                    acknowledge(byButton = false)
                    // A button press in the same frame must not beep twice.
                    ackOnStart = false
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
                        // THE FIRST SYLLABLE (0.49.0): what was already said
                        // after "Jarvis", before the detector fired, goes in
                        // front of the recording. The wake word only; a button
                        // press comes before anybody speaks.
                        if (machine.startedByWakeWord && VoiceState.preRoll) {
                            val keep = PreRoll.framesToKeep(preRoll.peaks(), machine.speechThreshold)
                            preRoll.writeNewest(keep, buffer)
                            val peaks = preRoll.peaks()
                            if (keep > 0 && peaks.takeLast(keep).any { it > machine.speechThreshold }) {
                                machine.speechAlreadyStarted()
                            }
                            VoiceState.lastPreRollMs = keep * recorder.frameSamples * 1000 / Recorder.SAMPLE_RATE
                            Log.i(TAG, "pre-roll frames=$keep ms=${VoiceState.lastPreRollMs}")
                        }
                        preRoll.clear()
                        // Music or a video playing: quiet it for the question
                        // (and the answer); turnEnded lets it carry on.
                        WakePause.turnStarted()
                        // Started by the Jarvis button: the beep, the screen
                        // and "ฟังอยู่ครับ" now, as a wake word would have.
                        if (ackOnStart) {
                            ackOnStart = false
                            acknowledge(byButton = true)
                        }
                        VoiceState.stt = "recording"
                        // Logged at the start as well, so the calibration is
                        // visible on the turns that WORK, not only the ones
                        // that fail. A threshold that is quietly drifting up is
                        // easier to catch before it cancels anything.
                        Log.i(TAG, "capture started ambient=${machine.ambientLevel()} " +
                            "threshold=${machine.speechThreshold}")
                        // HEY JARVIS ALWAYS ENDS ON THE HOME SCREEN (0.43.0,
                        // Poom): over Maps, the camera app, the identity
                        // check, the Control Panel and any screen added later
                        // (KioskScreens tracks them all). Only when home is
                        // not already in front, so a capture on the home
                        // screen starts no activity.
                        // EXCEPT an app's own Jarvis button (0.63.0, Poom):
                        // asked from the music page, the answer comes there
                        // and the page stays — its badge shows listening,
                        // thinking and speaking.
                        if (VoiceState.turnScreen in Broker.SCREENS) {
                            Log.i(TAG, "staying on the app's screen screen=${VoiceState.turnScreen}")
                        } else {
                            alarmHandler.post { comeHome(VoiceState.turnWake.ifEmpty { "wake" }) }
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
                        WakePause.turnEnded()
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
                        VoiceState.homeNotice = ""
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
                        // Debug build only, switched on by TEST_KEEP_CAPTURE (off by default,
                        // forgotten on restart): the question's audio, over the last one, in
                        // the app's own files, to see where the beep falls (DESIGN 11ก).
                        if (VoiceState.keepCapture) runCatching {
                            java.io.File(filesDir, "last_capture.wav").writeBytes(wav)
                            Log.i(TAG, "capture kept for measuring bytes=${wav.size}")
                        }
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
    private fun runTurn(wav: ByteArray, captureEndedAt: Long, typed: String? = null) {
        val token = TokenStore(this).token()
        VoiceState.hasToken = token != null
        if (token == null) {
            VoiceState.lastError = "no device token — see TESTING.md"
            Log.w(TAG, "no device token installed; see TESTING.md")
            stats.recordError()
            return
        }

        // Which app's Jarvis button asked (0.61.0) — the fixed word the broker gets, for adb to check.
        Log.i(TAG, "turn screen=${VoiceState.turnScreen.takeIf { it in Broker.SCREENS } ?: "none"}")
        val broker = Broker(VoiceState.brokerBaseUrl, token)
        val pipeline = TurnPipeline(
            // A typed question (debug TEST_ASK) skips the microphone and the
            // transcriber; everything after it is the production path.
            transcribe = if (typed != null) { _ -> typed } else broker::transcribe,
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
            VoiceState.turnScreen = ""   // the next question starts with no screen unless its button gives one
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
                    if (VoiceState.lastGate.isNotEmpty()) {
                        // The broker's speech gate: room noise that woke the
                        // kiosk. The reason only — the words never came back.
                        VoiceState.lastCancel = "not-a-question"
                        stats.recordGated()
                        Log.i(TAG, "turn gated by the broker: ${VoiceState.lastGate}")
                    } else {
                        VoiceState.lastCancel = "no-question-transcribed"
                    }
                }
                else -> stats.recordError()
            }
        } finally {
            // THE LOCK COMES OFF HERE AND ONLY HERE, on every path including a
            // thrown one. A turn that failed still has to hand the microphone
            // back, or the kiosk goes deaf for good and looks like the wake
            // word stopped working.
            machine.turnFinished()
            // The media quieted for this question carries on — on every path,
            // for the same reason as the line above.
            WakePause.turnEnded()
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
        writer.println("  wake-pause   : ${WakePause.describe()}")
        writer.println("  mic-tap      : ${if (MicTap.isOpen) "recorder reading" else "off"} " +
            "passed=${MicTap.passed} held_back=${MicTap.heldBack}")
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
    /**
     * Closes every other screen of ours — the identity check cancels, never
     * passes, and shuts its camera — then brings the kiosk forward. Main thread.
     */
    private fun comeHome(reason: String) {
        val closed = com.mammonrn.phoneaikiosk.KioskScreens.leaveAllButHome(reason)
        if (closed > 0 || !com.mammonrn.phoneaikiosk.KioskScreens.homeInFront) returnToKiosk(reason)
    }

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
    /**
     * A spoken music command (0.53.0), carried out before the reply is said.
     * Null: done. Otherwise the reason, which replaces the broker's words.
     * The command and whether it worked are logged; never the song's name.
     */
    private fun music(action: KioskAction): String? {
        val player = com.mammonrn.phoneaikiosk.media.MusicPlayer
        val context = this
        val deck = object : com.mammonrn.phoneaikiosk.media.MusicVoice.Deck {
            override val hasQueue get() = !player.queue.isEmpty
            override val hasMedia get() = player.hasMedia
            override val playing get() = player.state == com.mammonrn.phoneaikiosk.media.MusicPlayer.State.PLAYING
            override val volume get() = player.volume
            override fun play(tracks: List<com.mammonrn.phoneaikiosk.media.Track>) = player.play(context, tracks)
            override fun resume() = player.resume(context)
            override fun pause() = player.pause(context)
            override fun next() = player.next(context)
            override fun previous() = player.previous(context)
            override fun stop() = player.stop(context)
            override fun changeVolume(value: Float) = player.setVolume(context, value)
        }
        val command = action.params["command"].orEmpty()
        val failure = com.mammonrn.phoneaikiosk.media.MusicVoice.perform(
            command, action.params["query"].orEmpty(),
            // 0.59.0: only the playlists are searched (Poom: "ค้นเฉพาะใน playlist ที่มีอยู่").
            { com.mammonrn.phoneaikiosk.media.PlaylistStore.read(context) {
                it.searchable(com.mammonrn.phoneaikiosk.media.Playlist.Kind.MUSIC) } }, deck)
        val done = failure == null || isDoneWords(failure)
        VoiceState.lastAction = "music:$command:${if (done) (if (failure == null) "ok" else "ok-guessed") else "not-done"}"
        Log.i(TAG, "action music command=$command done=$done guessed=${isDoneWords(failure)}")
        return failure
    }

    /**
     * A spoken video command (0.57.0), done before the reply is said. A video
     * found is opened on its screen, playing; "หยุดวิดีโอ" pauses it for good
     * (VideoPlayer.pause lets go of the hold, so the end of this question does
     * not start it again). Logged: the command and whether it worked, never a name.
     */
    private fun video(action: KioskAction): String? {
        val player = com.mammonrn.phoneaikiosk.media.VideoPlayer
        val context = this
        val deck = object : com.mammonrn.phoneaikiosk.media.VideoVoice.Deck {
            override val hasMedia get() = player.hasMedia
            override val playing get() = player.state == com.mammonrn.phoneaikiosk.media.VideoPlayer.State.PLAYING
            override val hasLast get() = player.current != null
            private fun openScreen() = runCatching {
                startActivity(Intent(context, com.mammonrn.phoneaikiosk.media.VideoActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            }.onFailure { Log.w(TAG, "video screen not opened: ${it.javaClass.simpleName}") }
            override fun play(videos: List<com.mammonrn.phoneaikiosk.media.Video>, start: Int) {
                player.play(context, videos, start)
                openScreen()
            }
            // 0.58.0: a video plays only on its screen, so going on opens it again.
            override fun resume() { player.resume(context); openScreen() }
            override fun pause() = player.pause(context)
            override fun stop() = player.stop(context)
        }
        val command = action.params["command"].orEmpty()
        val failure = com.mammonrn.phoneaikiosk.media.VideoVoice.perform(
            command, action.params["query"].orEmpty(), {
                // 0.59.0: only the playlists are searched.
                com.mammonrn.phoneaikiosk.media.PlaylistStore.read(context) {
                    it.searchable(com.mammonrn.phoneaikiosk.media.Playlist.Kind.VIDEO)
                }.map { com.mammonrn.phoneaikiosk.media.Video(it) }
            }, deck)
        val done = failure == null || isDoneWords(failure)
        VoiceState.lastAction = "video:$command:${if (done) (if (failure == null) "ok" else "ok-guessed") else "not-done"}"
        Log.i(TAG, "action video command=$command done=$done guessed=${isDoneWords(failure)}")
        return failure
    }

    /**
     * 0.62.0: a line added to a list, or a list read, from this phone's own
     * file (notes/NoteStore). An add is done before the reply (null = added,
     * the broker's words stand; else the reason). A read returns the list's
     * words, which ARE the reply (TurnPipeline.ANSWERED_ON_PHONE).
     * Logged: the list's id, the outcome and counts — never a line's words.
     */
    private fun note(action: KioskAction): String? {
        val store = com.mammonrn.phoneaikiosk.notes.NoteStore
        store.remember(this)
        val list = action.params["list"].orEmpty()
        // Only our own two ids reach a log line; anything else is "other".
        val tag = if (list in com.mammonrn.phoneaikiosk.notes.NoteBook.BUILT_IN) list else "other"
        if (action.type == KioskAction.NOTE_READ) {
            val book = store.book()
            val said = com.mammonrn.phoneaikiosk.notes.NoteVoice.read(list, book)
            VoiceState.lastAction = "note_read:$tag"
            Log.i(TAG, "action note_read list=$tag pending=${book.list(list)?.pending?.size ?: -1}")
            return said
        }
        val done = com.mammonrn.phoneaikiosk.notes.NoteVoice.add(list, action.params["text"].orEmpty(), store)
        VoiceState.lastAction = "note_add:$tag:${if (done.added) "ok" else "not-done"}"
        Log.i(TAG, "action note_add list=$tag added=${done.added}")
        return done.instead
    }

    private fun performAction(action: KioskAction): String? {
        if (action.type == KioskAction.VERIFY_IDENTITY) return verifyForPrivate()
        if (action.type == KioskAction.OPEN_CAMERA_APP) return openCameraApp()
        if (action.type == KioskAction.SET_ALARM) return setAlarm(action)
        if (action.type == KioskAction.ALARM_ENABLE) return enableAlarm(action)
        if (action.type == KioskAction.MUSIC) return music(action)
        if (action.type == KioskAction.VIDEO) return video(action)
        if (action.type == KioskAction.NOTE_ADD || action.type == KioskAction.NOTE_READ) return note(action)
        if (action.type == KioskAction.HOME_UPDATED) {
            // The light is already switched (the broker did it) and the reply
            // says so. Here only the card is told to ask again.
            VoiceState.homeVersion += 1
            VoiceState.lastAction = "home_updated"
            return null
        }
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

    // --------------------------------------------------- private questions

    /** The private question waiting for the identity check, and since when. */
    @Volatile private var pendingPrivate: String? = null
    @Volatile private var pendingSince = 0L

    /**
     * The broker wants the identity check before a private answer. The camera
     * opens only now, for this (Poom: "เปิดกล้องเฉพาะตอนมีคำขอข้อมูลส่วนตัว").
     * The question is kept here, in memory, for a few minutes: after a pass the
     * phone asks for a grant and asks it again, so nobody has to repeat it.
     */
    private fun verifyForPrivate(): String? {
        val question = VoiceState.heard.trim()
        if (question.isEmpty()) return null
        if (com.mammonrn.phoneaikiosk.auth.AuthStore.identityId(this) == null) {
            return "ยังไม่ได้ลงทะเบียนใบหน้าหรือรูปแบบครับ กรุณาตั้งค่าที่แผงควบคุม"
        }
        pendingPrivate = question
        pendingSince = android.os.SystemClock.elapsedRealtime()
        VoiceState.lastAction = "verify_identity:asked"
        Log.i(TAG, "action verify_identity chars=${question.length}")
        startActivity(com.mammonrn.phoneaikiosk.auth.VerifyActivity
            .intent(this, com.mammonrn.phoneaikiosk.auth.VerifyActivity.Mode.VERIFY, returnHome = true)
            .putExtra(com.mammonrn.phoneaikiosk.auth.VerifyActivity.EXTRA_FOR_PRIVATE, true)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        return null
    }

    /**
     * VerifyActivity passed: ask the broker for the grant — always, so the
     * identity reaches the VPS for Poom to approve — and, if the pass was for
     * a private question still waiting, ask that question again. The outcome
     * goes to VoiceState.grantStatus for the Control Panel to show.
     */
    private fun resumePrivate(method: String, forPrivate: Boolean) {
        val question = if (forPrivate) pendingPrivate else null
        if (forPrivate) pendingPrivate = null
        val stillWaiting = question != null &&
            android.os.SystemClock.elapsedRealtime() - pendingSince <= PENDING_PRIVATE_MS
        val identityId = com.mammonrn.phoneaikiosk.auth.AuthStore.identityId(this) ?: return
        val token = TokenStore(this).token() ?: return
        network.execute {
            try {
                Broker(VoiceState.brokerBaseUrl, token).grant(identityId, method)
                Log.i(TAG, "grant ok method=$method")
                VoiceState.grantStatus = "approved"
            } catch (e: Broker.Failure) {
                Log.i(TAG, "grant refused ${e.code} (${e.status})")
                VoiceState.grantStatus = if (e.status == 403) "pending" else "error"
                if (stillWaiting) {
                    VoiceState.lastError = "grant-refused"
                    VoiceState.reply = e.message ?: "ยังเปิดข้อมูลส่วนตัวไม่ได้ครับ"
                    deafWhile { speaker.sayLocally(VoiceState.reply) }
                }
                return@execute
            } catch (e: Exception) {
                Log.i(TAG, "grant failed ${e.javaClass.simpleName}")
                VoiceState.grantStatus = "error"
                if (stillWaiting) deafWhile { speaker.sayLocally("ตอนนี้ติดต่อเซิร์ฟเวอร์ไม่ได้ครับ") }
                return@execute
            }
            if (stillWaiting && question != null) {
                runTurn(ByteArray(TurnPipeline.WAV_HEADER_BYTES + 2), 0L, question)
            }
        }
    }

    // ------------------------------------------------------------ alarms

    private val alarmRinger by lazy { com.mammonrn.phoneaikiosk.alarm.AlarmRinger(this) }
    private val alarmHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val alarmTimeout = Runnable { stopAlarm("timeout") }

    /** "ปลุกตีห้า ไปทำงาน": the broker parsed it; the phone keeps and rings it. */
    private fun setAlarm(action: KioskAction): String? {
        val (hour, minute) = com.mammonrn.phoneaikiosk.alarm.AlarmBook.parseTime(
            action.params["time"].orEmpty()) ?: return "ตั้งปลุกไม่สำเร็จครับ เวลาไม่ถูกต้อง"
        val book = com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(this)
        val alarm = book.set(hour, minute, action.params["label"].orEmpty())
            ?: return "ตั้งปลุกไม่ได้ครับ มีครบ ${com.mammonrn.phoneaikiosk.alarm.AlarmBook.MAX_ALARMS} รายการแล้ว"
        com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(this, book)
        VoiceState.lastAction = "set_alarm:ok"
        Log.i(TAG, "action set_alarm id=${alarm.id}")
        return null
    }

    private fun enableAlarm(action: KioskAction): String? {
        val book = com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(this)
        val target = action.params["target"].orEmpty()
        val on = action.params["enabled"] == "true"
        if (!book.matches(target)) {
            VoiceState.lastAction = "alarm_enable:no-match"
            return "ไม่พบการปลุกนั้นครับ"
        }
        val changed = book.enable(target, on)
        com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(this, book)
        VoiceState.lastAction = "alarm_enable:changed=$changed"
        Log.i(TAG, "action alarm_enable on=$on changed=$changed")
        return null
    }

    /**
     * Rings alarm [id]: the screen on, the kiosk in front, the tone looping at
     * alarm volume, the alarm card pinned at the top with its stop button, and
     * the wake word deaf until it stops. Stops itself after AlarmRinger.MAX_RING_MS.
     */
    fun startAlarm(id: Int) {
        val book = com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(this)
        val alarm = book.byId(id)
        if (alarm == null || !alarm.enabled) {
            Log.i(TAG, "alarm id=$id not rung: gone or off")
            return
        }
        // A one-off switches itself off now that it has rung; the book is saved
        // (and the next alarm booked) before the ringing starts.
        if (alarm.once) {
            book.setEnabled(alarm.id, false)
            com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(this, book)
        }
        SoakProbe.noteAlarmRang(this, alarm.hour, alarm.minute)
        runCatching { ScreenWaker.wakeIfAsleep(this) }
        // Over Maps or the camera app, the kiosk comes back so the stop button
        // is on screen; over the kiosk itself this changes nothing.
        returnToKiosk("alarm")
        val rang = alarmRinger.start()
        VoiceState.alarmRinging = com.mammonrn.phoneaikiosk.voice.DashboardState.clock12(alarm.time) +
            if (alarm.label.isNotEmpty()) "  ${alarm.label}" else ""
        VoiceState.alarmsVersion += 1
        alarmHandler.removeCallbacks(alarmTimeout)
        alarmHandler.postDelayed(alarmTimeout, com.mammonrn.phoneaikiosk.alarm.AlarmRinger.MAX_RING_MS)
        Log.i(TAG, "alarm ringing id=$id sound=$rang")
    }

    fun stopAlarm(reason: String) {
        if (VoiceState.alarmRinging.isEmpty() && !alarmRinger.ringing) return
        alarmHandler.removeCallbacks(alarmTimeout)
        alarmRinger.stop()
        VoiceState.alarmRinging = ""
        VoiceState.alarmsVersion += 1
        // A person stopped it (not the 10-minute limit): a ringing countdown is seen too.
        if (reason != "timeout") com.mammonrn.phoneaikiosk.timer.TimerClock.acknowledge(this)
        Log.i(TAG, "alarm stopped reason=$reason")
    }

    /**
     * 0.62.0: the countdown has ended (timer/TimerClock). Rung EXACTLY as an
     * alarm is — the screen on, the same tone through [alarmRinger] at the
     * alarm volume, [VoiceState.alarmRinging] set so the wake word is deaf
     * while it rings (WakeGate.deaf) and the Jarvis button and the home card's
     * stop button stop it, the same 10-minute limit — and then the countdown's
     * own screen in front, saying "หมดเวลา" until someone stops it.
     */
    fun startTimerRing() {
        val clock = com.mammonrn.phoneaikiosk.timer.TimerClock
        clock.load(this)
        if (clock.countdown.state != com.mammonrn.phoneaikiosk.timer.Countdown.State.RINGING) {
            Log.i(TAG, "timer not rung: already stopped")
            return
        }
        runCatching { ScreenWaker.wakeIfAsleep(this) }
        val rang = alarmRinger.start()
        VoiceState.alarmRinging = getString(R.string.timer_ringing_card,
            com.mammonrn.phoneaikiosk.timer.TimerText.thai(clock.countdown.setMs))
        VoiceState.alarmsVersion += 1
        alarmHandler.removeCallbacks(alarmTimeout)
        alarmHandler.postDelayed(alarmTimeout, com.mammonrn.phoneaikiosk.alarm.AlarmRinger.MAX_RING_MS)
        runCatching { startActivity(com.mammonrn.phoneaikiosk.timer.TimerActivity.ringIntent(this)) }
            .onFailure { Log.w(TAG, "timer: could not show its screen: ${it.javaClass.simpleName}") }
        Log.i(TAG, "timer ringing sound=$rang")
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
    /**
     * [byButton]: the Jarvis button beeps; the wake word does NOT (Poom,
     * 2026-09-25) — measured on the A07, the tone lands right before the first
     * syllable and "พรุ่งนี้" came out wrong 4 of 4 times with it, 4 of 5 right
     * without. The screen says "listening" instead (MainActivity.showListening).
     */
    private fun acknowledge(byButton: Boolean) {
        VoiceState.lastCancel = ""
        VoiceState.heard = ""
        VoiceState.reply = ""
        VoiceState.homeNotice = ""
        VoiceState.wake = "heard"
        // A dark kiosk lights up the moment it hears its name, before the tone,
        // so the screen is already there by the time the question starts. See
        // ScreenWaker; a failure here must not cost the turn.
        runCatching { ScreenWaker.wakeIfAsleep(this) }
        if (byButton || VoiceState.beep) runCatching {
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
        instance = null
        stopAlarm("service-stopped")
        alarmHandler.removeCallbacks(soakTick)
        soakThread.shutdownNow()
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

        /** The on-screen Jarvis button. Sent by MainActivity only (not exported). */
        const val ACTION_BUTTON_LISTEN = "com.mammonrn.phoneaikiosk.BUTTON_LISTEN"

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
        /** The running service, for AlarmManager's receiver. Null when there is none. */
        @Volatile
        private var instance: VoiceService? = null

        const val ACTION_ALARM_RING = "com.mammonrn.phoneaikiosk.ALARM_RING"
        const val ACTION_TEST_ASK = "com.mammonrn.phoneaikiosk.TEST_ASK_TEXT"
        const val ACTION_TEST_PERFORM = "com.mammonrn.phoneaikiosk.TEST_PERFORM_ACTION"
        const val EXTRA_TYPE = "type"
        const val EXTRA_COMMAND = "command"
        const val EXTRA_QUERY = "query"
        const val EXTRA_AS_TURN = "as_turn"
        const val EXTRA_TEXT = "text"
        const val ACTION_ALARM_STOP = "com.mammonrn.phoneaikiosk.ALARM_STOP"
        /** From VerifyActivity, after a pass that was for a private question. */
        const val ACTION_AUTH_PASSED = "com.mammonrn.phoneaikiosk.AUTH_PASSED"
        const val EXTRA_METHOD = "method"
        /** How long a private question waits for its identity check. */
        const val PENDING_PRIVATE_MS = 3 * 60_000L
        const val EXTRA_ALARM_ID = "alarm_id"

        /**
         * From AlarmReceiver. The service normally runs all the time, and then
         * it rings straight away. If it does not — the process was killed —
         * the kiosk activity is brought up with the alarm id; it starts the
         * service from the foreground, which rings. A Device Owner may start
         * its activity from the background; a microphone service may not be.
         */
        fun ringAlarm(context: Context, id: Int) {
            val service = instance
            if (service != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { service.startAlarm(id) }
                return
            }
            runCatching {
                context.startActivity(Intent(context, com.mammonrn.phoneaikiosk.MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    .putExtra(EXTRA_ALARM_ID, id))
            }.onFailure { Log.w(TAG, "alarm: could not bring the kiosk up: ${it.javaClass.simpleName}") }
        }

        /**
         * 0.62.0: the countdown ended (timer/TimerClock). As [ringAlarm]: the
         * running service rings at once; with no service, the countdown's
         * screen comes up (a Device Owner may start its activity from the
         * background) and starts the service from the foreground.
         */
        fun ringTimer(context: Context) {
            val service = instance
            if (service != null) {
                android.os.Handler(android.os.Looper.getMainLooper()).post { service.startTimerRing() }
                return
            }
            runCatching { context.startActivity(com.mammonrn.phoneaikiosk.timer.TimerActivity.ringIntent(context)) }
                .onFailure { Log.w(TAG, "timer: could not bring its screen up: ${it.javaClass.simpleName}") }
        }

        const val ACTION_TIMER_RING = "com.mammonrn.phoneaikiosk.TIMER_RING"

        fun ringFromActivity(context: Context, id: Int) {
            context.startForegroundService(Intent(context, VoiceService::class.java)
                .setAction(ACTION_ALARM_RING).putExtra(EXTRA_ALARM_ID, id))
        }

        fun start(context: Context, action: String? = null) {
            val intent = Intent(context, VoiceService::class.java).apply {
                if (action != null) this.action = action
            }
            context.startForegroundService(intent)
        }
    }
}
