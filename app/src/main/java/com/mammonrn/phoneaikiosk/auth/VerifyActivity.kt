package com.mammonrn.phoneaikiosk.auth

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * "Is this Poom?" — the face with a blink, or the pattern when the face will
 * not do. Also where Poom enrols the face and sets the pattern, so every screen
 * that touches the camera or the pattern is this one (DESIGN.md, "ยืนยันตัวตน").
 *
 * WHAT IT GUARANTEES, in code rather than in a prompt:
 *  * the camera is on only while this window is on screen — bound to this
 *    activity's lifecycle and unbound the moment the pattern is shown;
 *  * frames are analysed in memory and never written or sent (FaceScanner);
 *  * a pass opens AccessGrant for two minutes, and nothing else does;
 *  * changing an enrolment needs a pass first: asked to enrol a face or set a
 *    pattern while either already exists, it verifies before it lets you.
 *    Deleting needs no pass (Poom: "ลบได้ทันที") — see DESIGN.md for why that
 *    is safe once part 2 ties the broker to one enrolment.
 *
 * Our own activity, so lock task mode allows it, like the Control Panel.
 * A plain Activity with its own lifecycle registry, because CameraX binds to
 * a LifecycleOwner and the rest of the app has no androidx.activity.
 */
class VerifyActivity : Activity(), LifecycleOwner {

    enum class Mode { VERIFY, ENROLL, SET_PATTERN }

    private enum class Stage { FACE, PATTERN, SET_FIRST, SET_CONFIRM, DONE }

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private lateinit var thai: Typeface
    private lateinit var titleText: TextView
    private lateinit var status: TextView
    private lateinit var hint: TextView
    private lateinit var preview: PreviewView
    private lateinit var previewFrame: FrameLayout
    private lateinit var pad: PatternPad
    private lateinit var switchButton: TextView

    private val handler = Handler(Looper.getMainLooper())
    private var analysis: ExecutorService? = null
    private var provider: ProcessCameraProvider? = null
    @Volatile private var scanner: FaceScanner? = null

    /** What the caller asked for, and what is on screen now. */
    private lateinit var target: Mode
    @Volatile private var stage = Stage.FACE
    @Volatile private var mode = Mode.VERIFY
    private var face: AuthStore.Face? = null
    private var hasPattern = false
    private var returnHome = false

    // Face state, touched only on the analysis thread.
    @Volatile private var blink = BlinkCheck()
    private val samples = ArrayList<FloatArray>()
    private var lastSampleAt = 0L
    private var faceStartedAt = 0L
    private var bestScore = -1f

    private var firstPattern: List<Int>? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        target = runCatching { Mode.valueOf(intent.getStringExtra(EXTRA_MODE) ?: "") }
            .getOrDefault(Mode.VERIFY)
        returnHome = intent.getBooleanExtra(EXTRA_RETURN_HOME, false)
        setContentView(buildWindow())
        hideSystemBars()

        face = AuthStore.loadFace(this)
        hasPattern = AuthStore.hasPattern(this)
        val enrolled = face != null || hasPattern
        when {
            // Changing what recognises Poom needs Poom first.
            target != Mode.VERIFY && enrolled -> {
                hint.text = getString(R.string.auth_verify_first)
                startVerify()
            }
            target == Mode.ENROLL -> startEnroll()
            target == Mode.SET_PATTERN -> startSetPattern()
            !enrolled -> finishWith(OUTCOME_NOTHING_ENROLLED, getString(R.string.auth_nothing_enrolled))
            else -> startVerify()
        }
        handler.postDelayed(idleTimeout, IDLE_MS)
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
    }

    override fun onResume() {
        super.onResume()
        registry.currentState = Lifecycle.State.RESUMED
        hideSystemBars()
    }

    override fun onPause() {
        registry.currentState = Lifecycle.State.STARTED
        super.onPause()
    }

    override fun onStop() {
        registry.currentState = Lifecycle.State.CREATED
        super.onStop()
        // Off screen means no camera, whatever else is going on.
        if (!isFinishing) finishWith(OUTCOME_CANCELLED, null)
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        handler.removeCallbacksAndMessages(null)
        stopCamera()
        // The scanner is used on the analysis thread, so it is closed there,
        // after any frame still in flight.
        val s = scanner
        val ex = analysis
        if (ex != null) {
            runCatching { ex.execute { s?.close() } }
            ex.shutdown()
        } else {
            s?.close()
        }
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = finishWith(OUTCOME_CANCELLED, null)

    // ------------------------------------------------------------- stages

    private fun startVerify() {
        mode = Mode.VERIFY
        titleText.text = getString(R.string.auth_title_verify)
        if (face != null) startFace() else startPattern(null)
    }

    private fun startEnroll() {
        mode = Mode.ENROLL
        titleText.text = getString(R.string.auth_title_enroll)
        hint.text = getString(R.string.auth_privacy)
        startFace()
    }

    private fun startSetPattern() {
        mode = Mode.SET_PATTERN
        titleText.text = getString(R.string.auth_title_set_pattern)
        stopCamera()
        stage = Stage.SET_FIRST
        firstPattern = null
        showPad()
        status.text = getString(R.string.auth_pattern_draw_new)
        switchButton.visibility = View.GONE
    }

    private fun startFace() {
        stage = Stage.FACE
        blink = BlinkCheck()
        synchronized(samples) { samples.clear() }
        bestScore = -1f
        faceStartedAt = SystemClock.elapsedRealtime()
        pad.visibility = View.GONE
        previewFrame.visibility = View.VISIBLE
        status.text = getString(R.string.auth_starting)
        switchButton.text = getString(R.string.auth_use_pattern)
        switchButton.visibility = if (mode == Mode.VERIFY && hasPattern) View.VISIBLE else View.GONE
        switchButton.setOnClickListener { startPattern(null) }
        startCamera()
        if (mode == Mode.VERIFY) handler.postDelayed(faceTimeout, FACE_TIMEOUT_MS)
    }

    private fun startPattern(reason: String?) {
        handler.removeCallbacks(faceTimeout)
        stopCamera()
        if (!hasPattern) {
            finishWith(OUTCOME_FAILED, reason ?: getString(R.string.auth_failed))
            return
        }
        stage = Stage.PATTERN
        showPad()
        switchButton.text = getString(R.string.auth_use_face)
        switchButton.visibility = if (face != null) View.VISIBLE else View.GONE
        switchButton.setOnClickListener { startFace() }
        showPatternPrompt(reason)
    }

    private fun showPad() {
        previewFrame.visibility = View.GONE
        pad.visibility = View.VISIBLE
        pad.clear()
    }

    private fun showPatternPrompt(reason: String?) {
        val wait = PatternLock.waitMs(AuthStore.attempts(this), System.currentTimeMillis())
        if (wait > 0) {
            pad.locked = true
            status.text = getString(R.string.auth_pattern_locked, waitWords(wait))
            handler.postDelayed({ if (stage == Stage.PATTERN) showPatternPrompt(null) }, 1000)
        } else {
            pad.locked = false
            status.text = reason ?: getString(R.string.auth_draw_pattern)
        }
    }

    private val faceTimeout = Runnable {
        if (stage == Stage.FACE && mode == Mode.VERIFY) {
            log("face timeout best=%.2f".format(bestScore))
            startPattern(getString(R.string.auth_not_recognised_pattern))
        }
    }

    private val idleTimeout = Runnable { finishWith(OUTCOME_CANCELLED, null) }

    // ------------------------------------------------------------- pattern

    private fun onPattern(dots: List<Int>) {
        handler.removeCallbacks(idleTimeout)
        handler.postDelayed(idleTimeout, IDLE_MS)
        when (stage) {
            Stage.PATTERN -> checkPattern(dots)
            Stage.SET_FIRST -> {
                if (!PatternLock.isValid(dots)) {
                    pad.showWrong(); status.text = getString(R.string.auth_pattern_too_short); return
                }
                firstPattern = dots
                pad.clear()
                stage = Stage.SET_CONFIRM
                status.text = getString(R.string.auth_pattern_confirm)
            }
            Stage.SET_CONFIRM -> {
                if (dots != firstPattern) {
                    pad.showWrong()
                    firstPattern = null
                    stage = Stage.SET_FIRST
                    status.text = getString(R.string.auth_pattern_mismatch)
                    return
                }
                AuthStore.setPattern(this, dots)
                log("pattern set")
                finishWith(OUTCOME_PATTERN_SET, getString(R.string.auth_pattern_saved))
            }
            else -> Unit
        }
    }

    private fun checkPattern(dots: List<Int>) {
        val now = System.currentTimeMillis()
        val attempts = AuthStore.attempts(this)
        if (PatternLock.waitMs(attempts, now) > 0) { showPatternPrompt(null); return }
        val record = AuthStore.loadPattern(this)
        val right = record != null && PatternLock.matches(dots, record.salt, record.hash, record.iterations)
        val after = PatternLock.afterTry(attempts, right, now)
        AuthStore.saveAttempts(this, after)
        if (right) {
            log("pass method=pattern")
            passed(AccessGrant.Method.PATTERN)
            return
        }
        log("pattern wrong failures=${after.failures}")
        pad.showWrong()
        val left = PatternLock.FREE_TRIES - after.failures
        if (left > 0) status.text = getString(R.string.auth_pattern_wrong, left)
        else handler.postDelayed({ if (stage == Stage.PATTERN) { pad.clear(); showPatternPrompt(null) } }, 800)
    }

    // ---------------------------------------------------------------- face

    private fun startCamera() {
        if (analysis == null) analysis = Executors.newSingleThreadExecutor()
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val cameraProvider = runCatching { future.get() }.getOrNull()
            if (cameraProvider == null || stage != Stage.FACE) {
                if (cameraProvider == null) cameraFailed()
                return@addListener
            }
            provider = cameraProvider
            val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
            val analysisUse = ImageAnalysis.Builder()
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
            analysisUse.setAnalyzer(analysis!!) { proxy -> analyse(proxy) }
            try {
                cameraProvider.unbindAll()
                cameraProvider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA,
                                               previewUse, analysisUse)
                status.text = getString(R.string.auth_find_face)
            } catch (e: Exception) {
                log("camera bind failed: ${e.javaClass.simpleName}")
                cameraFailed()
            }
        }, ContextCompat.getMainExecutor(this))
    }

    private fun stopCamera() {
        runCatching { provider?.unbindAll() }
    }

    private fun cameraFailed() {
        if (mode == Mode.VERIFY) startPattern(getString(R.string.auth_camera_unavailable))
        else finishWith(OUTCOME_FAILED, getString(R.string.auth_camera_unavailable))
    }

    /** On the analysis thread. The frame is recycled before this returns. */
    private fun analyse(proxy: ImageProxy) {
        var frame: Bitmap? = null
        var upright: Bitmap? = null
        try {
            if (stage != Stage.FACE) return
            val s = scanner ?: FaceEmbedder.fromAssets(this)?.let { FaceScanner(it) }?.also { scanner = it }
            if (s == null) { handler.post { cameraFailed() }; return }
            frame = proxy.toBitmap()
            val rotation = proxy.imageInfo.rotationDegrees
            upright = if (rotation == 0) frame else Bitmap.createBitmap(
                frame, 0, 0, frame.width, frame.height,
                Matrix().apply { postRotate(rotation.toFloat()) }, true)
            val seen = s.scan(upright)
            if (mode == Mode.ENROLL) enrolFrame(seen) else verifyFrame(seen)
        } catch (e: Exception) {
            log("frame failed: ${e.javaClass.simpleName}")
        } finally {
            if (upright != null && upright !== frame) upright.recycle()
            frame?.recycle()
            proxy.close()
        }
    }

    private fun verifyFrame(seen: FaceScanner.Seen) {
        val templates = face?.templates ?: return
        val matches = seen.embedding?.let {
            val score = FaceMath.bestScore(it, templates)
            if (score > bestScore) bestScore = score
            score >= MATCH_THRESHOLD
        }
        val step = blink.feed(BlinkCheck.Frame(seen.faces, seen.trackingId, seen.leftOpen,
                                               seen.rightOpen, matches))
        handler.post {
            if (stage != Stage.FACE) return@post
            when (step) {
                BlinkCheck.Step.PASSED -> {
                    log("pass method=face best=%.2f".format(bestScore))
                    passed(AccessGrant.Method.FACE)
                }
                BlinkCheck.Step.NOT_RECOGNISED -> {
                    log("face not recognised best=%.2f".format(bestScore))
                    startPattern(getString(if (hasPattern) R.string.auth_not_recognised_pattern
                                           else R.string.auth_not_recognised))
                }
                else -> status.text = stepText(step)
            }
        }
    }

    private fun enrolFrame(seen: FaceScanner.Seen) {
        val now = SystemClock.elapsedRealtime()
        val embedding = seen.embedding
        val first = synchronized(samples) { samples.firstOrNull() }
        val matches = embedding?.let { first == null || FaceMath.cosine(it, first) >= ENROLL_CONSISTENCY }
        if (embedding != null && matches == true && now - lastSampleAt >= SAMPLE_GAP_MS) {
            synchronized(samples) { if (samples.size < ENROLL_SAMPLES) samples += embedding }
            lastSampleAt = now
        }
        val step = blink.feed(BlinkCheck.Frame(seen.faces, seen.trackingId, seen.leftOpen,
                                               seen.rightOpen, matches))
        val count = synchronized(samples) { samples.size }
        handler.post {
            if (stage != Stage.FACE) return@post
            when {
                step == BlinkCheck.Step.NOT_RECOGNISED -> {
                    log("enrol restarted: inconsistent faces")
                    status.text = getString(R.string.auth_enroll_restart)
                    blink = BlinkCheck()
                    synchronized(samples) { samples.clear() }
                }
                count >= ENROLL_SAMPLES && step == BlinkCheck.Step.PASSED -> saveEnrolment()
                count < ENROLL_SAMPLES && seen.faces == 1 ->
                    status.text = getString(R.string.auth_enroll_hold, count, ENROLL_SAMPLES)
                count >= ENROLL_SAMPLES -> status.text = getString(R.string.auth_enroll_blink)
                else -> status.text = stepText(step)
            }
        }
    }

    private fun saveEnrolment() {
        stage = Stage.DONE
        stopCamera()
        val templates = synchronized(samples) { samples.toList() }
        val record = AuthStore.Face(AuthStore.newEnrollmentId(), System.currentTimeMillis(), templates)
        runCatching { AuthStore.saveFace(this, record) }.onFailure {
            log("enrol save failed: ${it.javaClass.simpleName}")
            finishWith(OUTCOME_FAILED, getString(R.string.auth_failed))
            return
        }
        log("enrolled templates=${templates.size}")
        finishWith(OUTCOME_ENROLLED, getString(R.string.auth_enroll_saved))
    }

    private fun stepText(step: BlinkCheck.Step): String = getString(when (step) {
        BlinkCheck.Step.FIND_FACE -> R.string.auth_find_face
        BlinkCheck.Step.LOOK -> R.string.auth_look
        BlinkCheck.Step.BLINK -> R.string.auth_blink
        BlinkCheck.Step.REOPEN -> R.string.auth_reopen
        BlinkCheck.Step.PASSED -> R.string.auth_passed
        BlinkCheck.Step.NOT_RECOGNISED -> R.string.auth_not_recognised
    })

    // ------------------------------------------------------------- endings

    private fun passed(how: AccessGrant.Method) {
        stage = Stage.DONE
        handler.removeCallbacks(faceTimeout)
        stopCamera()
        AccessGrant.open(how)
        // Asked to change an enrolment: the pass was the way in, not the end.
        when (target) {
            Mode.ENROLL -> { hint.text = getString(R.string.auth_privacy); startEnroll() }
            Mode.SET_PATTERN -> startSetPattern()
            Mode.VERIFY -> finishWith(OUTCOME_PASSED, getString(R.string.auth_passed))
        }
    }

    private var finishing = false

    private fun finishWith(outcome: String, message: String?) {
        if (finishing) return
        finishing = true
        stage = Stage.DONE
        handler.removeCallbacks(faceTimeout)
        handler.removeCallbacks(idleTimeout)
        stopCamera()
        log("outcome=$outcome target=${target.name.lowercase()}")
        setResult(if (outcome == OUTCOME_PASSED || outcome == OUTCOME_ENROLLED ||
                      outcome == OUTCOME_PATTERN_SET) RESULT_OK else RESULT_CANCELED,
                  Intent().putExtra(EXTRA_OUTCOME, outcome))
        val close = {
            if (returnHome) {
                startActivity(Intent(this, MainActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
            }
            finish()
        }
        if (message == null || isFinishing) { close(); return }
        // Long enough to read the result, short enough not to be in the way.
        status.text = message
        previewFrame.visibility = View.GONE
        pad.visibility = View.GONE
        switchButton.visibility = View.GONE
        handler.postDelayed({ close() }, RESULT_SHOW_MS)
    }

    // --------------------------------------------------------------- window

    private fun buildWindow(): View {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finishWith(OUTCOME_CANCELLED, null) }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.retro_desktop))
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(6), dp(8), dp(6), dp(8))
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_face) },
                    LinearLayout.LayoutParams(dp(16), dp(16)))
        titleText = TextView(this).apply {
            setTextColor(color(R.color.retro_title_text))
            textSize = 13f
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
            maxLines = 1
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        window.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(5) })

        status = TextView(this).apply {
            textSize = 16f
            typeface = Typeface.create(thai, Typeface.BOLD)
            setTextColor(color(R.color.retro_text))
            gravity = Gravity.CENTER
            minLines = 2
        }
        body.addView(status, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT))

        // The camera's view of you, so you can see you are in frame. Shown,
        // never saved: the preview is a live surface, not a picture.
        preview = PreviewView(this).apply { scaleType = PreviewView.ScaleType.FILL_CENTER }
        previewFrame = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(dp(3), dp(3), dp(3), dp(3))
            addView(preview, FrameLayout.LayoutParams(MATCH, MATCH))
        }
        body.addView(previewFrame, LinearLayout.LayoutParams(dp(240), dp(320)).apply { topMargin = dp(10) })

        pad = PatternPad(this).apply {
            visibility = View.GONE
            onPattern = { dots -> this@VerifyActivity.onPattern(dots) }
        }
        body.addView(pad, LinearLayout.LayoutParams(dp(280), dp(280)).apply { topMargin = dp(10) })

        hint = TextView(this).apply {
            textSize = 13f
            typeface = thai
            setTextColor(color(R.color.retro_dim))
            gravity = Gravity.CENTER
        }
        body.addView(hint, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(10) })

        val buttons = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        switchButton = button("") {}.apply { visibility = View.GONE }
        buttons.addView(switchButton, LinearLayout.LayoutParams(0, dp(56), 1f))
        buttons.addView(button(getString(R.string.auth_cancel)) { finishWith(OUTCOME_CANCELLED, null) },
                        LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(7) })
        root.addView(buttons, LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(7) })
        return root
    }

    private fun button(value: String, onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = 16f
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(R.color.retro_text))
        setBackgroundResource(R.drawable.retro_button)
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun waitWords(ms: Long): String {
        val seconds = (ms + 999) / 1000
        return if (seconds < 60) "$seconds วินาที" else "${(seconds + 59) / 60} นาที"
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    /** Outcomes and reasons only — never an image, a number from a face, or a pattern. */
    private fun log(message: String) = Log.i(TAG, message)

    companion object {
        const val TAG = "KioskAuth"
        const val EXTRA_MODE = "mode"
        const val EXTRA_RETURN_HOME = "return_home"
        const val EXTRA_OUTCOME = "outcome"

        const val OUTCOME_PASSED = "passed"
        const val OUTCOME_FAILED = "failed"
        const val OUTCOME_CANCELLED = "cancelled"
        const val OUTCOME_ENROLLED = "enrolled"
        const val OUTCOME_PATTERN_SET = "pattern_set"
        const val OUTCOME_NOTHING_ENROLLED = "nothing_enrolled"

        /**
         * SFace cosine at or above which a face is Poom's. OpenCV's own figure
         * is 0.363 (sface.py, tuned on LFW); this is set a little stricter
         * because a false accept here reads someone's email.
         */
        const val MATCH_THRESHOLD = 0.40f
        /** During enrolment, each capture must look like the first one. */
        const val ENROLL_CONSISTENCY = 0.50f
        const val ENROLL_SAMPLES = 5
        const val SAMPLE_GAP_MS = 300L
        const val FACE_TIMEOUT_MS = 20_000L
        const val IDLE_MS = 90_000L
        const val RESULT_SHOW_MS = 1_500L
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT

        fun intent(context: Context, mode: Mode, returnHome: Boolean = false): Intent =
            Intent(context, VerifyActivity::class.java)
                .putExtra(EXTRA_MODE, mode.name)
                .putExtra(EXTRA_RETURN_HOME, returnHome)
    }
}
