package com.mammonrn.phoneaikiosk.recorder

import android.content.Context
import android.media.MediaScannerConnection
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import com.mammonrn.phoneaikiosk.voice.MicTap
import com.mammonrn.phoneaikiosk.voice.WakePause
import java.io.File
import java.util.concurrent.Executors

/**
 * The one recording in progress (0.61.0), for the whole process.
 *
 * Outside the screen on purpose: the screen draws it, but closing the screen is
 * what STOPS AND SAVES it (RecorderActivity), and a screen rebuilt by the
 * system while the display is off finds the recording still going.
 *
 * WHERE THE SOUND COMES FROM: MicTap — the wake word's own microphone frames,
 * never a second capture. WHERE IT GOES: [MemoWriter] on the "kiosk-rec"
 * thread, into the phone's Recordings/Kiosk folder.
 *
 * JARVIS RESTS WHILE IT RECORDS ([RecorderFlow]): the hold is taken on start
 * and resume, renewed every WakePause.RENEW_MS, and released the moment the
 * recording pauses or stops. The Jarvis button still asks a question: its turn
 * calls [quietForJarvis], which stops and saves the recording (and the screen
 * closes, DESIGN 11); the question itself never reaches the file (MicTap
 * holds frames back during a turn).
 *
 * THE SCREEN GOING OFF DOES NOT STOP IT: VoiceService keeps the microphone
 * with the screen off, so a lecture is not cut when the display sleeps.
 *
 * LOGS (`logcat -s KioskRec:I`): states and counts, never a name or a path.
 * Main thread for everything but the writer.
 */
object VoiceMemo : WakePause.Media {

    const val TAG = "KioskRec"

    /** Stop the recording when the phone has less than this left. */
    private const val MIN_FREE_BYTES = 50L * 1000 * 1000

    enum class Problem { NONE, NO_FOLDER, WRITE_FAILED, NO_SPACE }

    val flow = RecorderFlow(this)

    private val main = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-rec") }

    /** Kiosk-rec thread only. */
    private var writer: MemoWriter? = null

    /** The file name being written right now, so the list leaves it out until it is finished. */
    @Volatile var writing: String? = null
        private set

    /** Samples recorded (capture thread writes, the screen reads): the elapsed time. */
    @Volatile var samples = 0L
        private set

    /** The last frame's peak, for the level meter. */
    @Volatile var peak = 0
        private set

    /** When the last frame arrived (elapsedRealtime), so the screen can say the microphone is silent. */
    @Volatile var lastFrameAt = 0L
        private set

    /** When the current start or resume happened. */
    var startedAt = 0L
        private set

    /** What went wrong last, for the screen. Main thread. */
    var problem = Problem.NONE
        private set

    /** The last recording saved: its file name (for the screen only) and length. */
    var saved: Pair<String, Long>? = null
        private set

    /** Stopped, and the file is being finished on the writer's thread. Main thread. */
    var saving = false
        private set

    /** Bumped on every change the screen should redraw for. */
    @Volatile var version = 0
        private set

    private var app: Context? = null

    fun folder(): File = File(Environment.getExternalStorageDirectory(), RecorderNames.FOLDER)

    fun elapsedMs(): Long = samples * 1000 / MemoWriter.SAMPLE_RATE

    /** Finds out once which format works (MemoWriter.probe), before anybody presses record. */
    fun prepare(context: Context) {
        val scratch = context.applicationContext.cacheDir
        worker.execute { MemoWriter.probe(scratch) }
    }

    private val sink = MicTap.Sink { frame, count, framePeak ->
        val copy = frame.copyOf(count)
        samples += count
        peak = framePeak
        lastFrameAt = SystemClock.elapsedRealtime()
        worker.execute { write(copy) }
    }

    /** Starts a new recording named [base] (or "base (2)"). False when one is already going. */
    fun start(context: Context, base: String): Boolean {
        if (flow.state != RecorderFlow.State.IDLE) return false
        val appContext = context.applicationContext
        app = appContext
        val dir = folder()
        if (!dir.isDirectory && !dir.mkdirs()) {
            problem = Problem.NO_FOLDER
            Log.i(TAG, "rec refused reason=no-folder")
            changed()
            return false
        }
        problem = Problem.NONE
        saved = null
        samples = 0
        peak = 0
        lastFrameAt = 0
        // One sound at a time: music playing would be recorded off the speaker.
        runCatching { com.mammonrn.phoneaikiosk.media.MusicPlayer.quietForVideo(appContext) }
        val scratch = appContext.cacheDir
        worker.execute {
            try {
                val format = MemoWriter.probe(scratch)
                val names = dir.list()?.toList().orEmpty()
                val file = File(dir, RecorderNames.freeBase(base, names) + "." + format.extension)
                writing = file.name
                writer = MemoWriter.open(file, format)
                Log.i(TAG, "rec file open format=${format.extension}")
            } catch (e: Exception) {
                Log.i(TAG, "rec open failed ${e.javaClass.simpleName}")
                writer = null
                writing = null
                main.post { fail(Problem.WRITE_FAILED) }
            }
        }
        flow.start()
        startedAt = SystemClock.elapsedRealtime()
        MicTap.open(sink)
        main.removeCallbacks(renew)
        main.postDelayed(renew, WakePause.RENEW_MS)
        Log.i(TAG, "rec start holding=${flow.holding}")
        changed()
        return true
    }

    fun pause() {
        if (!flow.pause()) return
        MicTap.close(sink)
        Log.i(TAG, "rec pause ms=${elapsedMs()} holding=${flow.holding}")
        changed()
    }

    fun resume() {
        if (!flow.resume()) return
        startedAt = SystemClock.elapsedRealtime()
        MicTap.open(sink)
        Log.i(TAG, "rec resume holding=${flow.holding}")
        changed()
    }

    /**
     * Stops and saves. Safe to call when nothing is recording. [reason] is a
     * word for the log: button, screen-closed, jarvis, no-space, error.
     */
    fun stop(reason: String) {
        if (!flow.stop()) return
        MicTap.close(sink)
        main.removeCallbacks(renew)
        val ms = elapsedMs()
        Log.i(TAG, "rec stop ms=$ms reason=$reason holding=${flow.holding}")
        saving = true
        changed()
        worker.execute {
            val w = writer
            writer = null
            val result = runCatching { w?.finish() ?: 0L }
            val length = result.getOrDefault(0L)
            val name = w?.file?.takeIf { it.exists() }?.name
            writing = null
            Log.i(TAG, "rec saved ms=$length ok=${result.isSuccess && name != null}")
            w?.file?.let { scan(it) }
            main.post {
                saving = false
                if (name != null) saved = name to length
                else if (problem == Problem.NONE && ms > 0) problem = Problem.WRITE_FAILED
                changed()
            }
        }
    }

    /** A Jarvis question (the button) while recording: stop and save; the screen is about to close. */
    override fun quietForJarvis() = stop("jarvis")

    /** Nothing to carry on: the recording was saved when the question began. */
    override fun resumeAfterJarvis() = Unit

    /** Tells the phone's media index about a new, renamed or deleted file. */
    fun scan(file: File) {
        val context = app ?: return
        runCatching { MediaScannerConnection.scanFile(context, arrayOf(file.path), null, null) }
    }

    fun remember(context: Context) {
        app = context.applicationContext
    }

    /** Kiosk-rec thread. */
    private fun write(frame: ShortArray) {
        val w = writer ?: return
        try {
            w.write(frame, frame.size)
        } catch (e: Exception) {
            Log.i(TAG, "rec write failed ${e.javaClass.simpleName}")
            runCatching { w.abandon() }
            writer = null
            writing = null
            main.post { fail(Problem.WRITE_FAILED) }
        }
    }

    private fun fail(why: Problem) {
        problem = why
        stop(if (why == Problem.NO_SPACE) "no-space" else "error")
        changed()
    }

    private val renew = object : Runnable {
        override fun run() {
            if (flow.state == RecorderFlow.State.IDLE) return
            flow.renew()
            if (flow.state == RecorderFlow.State.RECORDING && folder().usableSpace in 1 until MIN_FREE_BYTES) {
                fail(Problem.NO_SPACE)
                return
            }
            main.postDelayed(this, WakePause.RENEW_MS)
        }
    }

    private fun changed() {
        version += 1
    }
}
