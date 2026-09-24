package com.mammonrn.phoneaikiosk.camera

import android.app.Activity
import android.content.ContentValues
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
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
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.files.FilesActivity
import com.mammonrn.phoneaikiosk.files.ImageViewerActivity
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * กล้องถ่ายรูป (0.61.0, Poom: "แอปพื้นฐานเหมือนมือถือทั่วไป").
 *
 * Not the "แอปกล้อง" of the house (voice/CameraAppLauncher opens Xiaomi Home
 * for the CCTV cameras); this is the phone's own camera, for a photo. CameraX,
 * which the identity check already brings in, so nothing is added to the APK.
 *
 *  * Back camera first; "สลับกล้อง" goes front/back and says which in words.
 *  * A photo goes to DCIM/Kiosk through MediaStore (no storage permission on
 *    Android 10+), so the file manager shows it and the picture viewer opens it.
 *  * The camera is open only while this screen is in front (its own lifecycle,
 *    as VerifyActivity does): leaving, the screen going off or Hey Jarvis
 *    closing every screen (KioskScreens) lets it go.
 *  * Logs say what happened (bound, lens, saved, failed) — never a file name,
 *    and nothing of the picture.
 */
class CameraActivity : Activity(), LifecycleOwner {

    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry

    private lateinit var r: Retro
    private lateinit var preview: PreviewView
    private lateinit var status: TextView
    private lateinit var shutter: TextView
    private lateinit var flip: TextView
    private lateinit var last: TextView
    private var provider: ProcessCameraProvider? = null
    private var capture: ImageCapture? = null
    private var front = false
    private var busy = false
    private var hasFront = true
    private var hasBack = true
    /** The last photo taken on this screen, to open in the picture viewer. */
    private var lastPath: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        registry.currentState = Lifecycle.State.CREATED
        val thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        r = Retro(this, thai)
        setContentView(buildWindow(thai))
        hideSystemBars()
    }

    override fun onStart() {
        super.onStart()
        registry.currentState = Lifecycle.State.STARTED
        startCamera()
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
        provider?.unbindAll()
        capture = null
        Log.i(TAG, "camera closed")
        super.onStop()
    }

    override fun onDestroy() {
        registry.currentState = Lifecycle.State.DESTROYED
        super.onDestroy()
    }

    // ------------------------------------------------------------ the camera

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            val p = runCatching { future.get() }.getOrNull()
            if (p == null) { say(getString(R.string.camera_failed)); Log.w(TAG, "camera provider failed"); return@addListener }
            if (!registry.currentState.isAtLeast(Lifecycle.State.STARTED)) return@addListener
            provider = p
            hasBack = runCatching { p.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) }.getOrDefault(false)
            hasFront = runCatching { p.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) }.getOrDefault(false)
            if (!hasBack && hasFront) front = true
            bind()
        }, ContextCompat.getMainExecutor(this))
    }

    private fun bind() {
        val p = provider ?: return
        val previewUse = Preview.Builder().build().also { it.setSurfaceProvider(preview.surfaceProvider) }
        val captureUse = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()
        val selector = if (front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        try {
            p.unbindAll()
            p.bindToLifecycle(this, selector, previewUse, captureUse)
            capture = captureUse
            Log.i(TAG, "camera bound lens=${if (front) "front" else "back"}")
            say(null)
        } catch (e: Exception) {
            capture = null
            Log.w(TAG, "camera bind failed: ${e.javaClass.simpleName}")
            say(getString(R.string.camera_failed))
        }
        drawButtons()
    }

    private fun switchLens() {
        if (busy || !(hasFront && hasBack)) return
        front = !front
        bind()
    }

    private fun takePhoto() {
        val c = capture ?: return
        if (busy) return
        busy = true
        drawButtons()
        say(getString(R.string.camera_saving))
        val name = "IMG_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date())
        val values = ContentValues().apply {
            put(MediaStore.MediaColumns.DISPLAY_NAME, "$name.jpg")
            put(MediaStore.MediaColumns.MIME_TYPE, "image/jpeg")
            put(MediaStore.MediaColumns.RELATIVE_PATH, "${Environment.DIRECTORY_DCIM}/$FOLDER")
        }
        val options = ImageCapture.OutputFileOptions.Builder(
            contentResolver, MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values).build()
        c.takePicture(options, ContextCompat.getMainExecutor(this), object : ImageCapture.OnImageSavedCallback {
            override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                busy = false
                val file = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DCIM), "$FOLDER/$name.jpg")
                lastPath = file.absolutePath
                Log.i(TAG, "photo saved lens=${if (front) "front" else "back"} exists=${file.exists()}")
                say(getString(R.string.camera_saved, "DCIM/$FOLDER"))
                drawButtons()
            }

            override fun onError(e: ImageCaptureException) {
                busy = false
                Log.w(TAG, "photo failed code=${e.imageCaptureError}")
                say(getString(R.string.camera_photo_failed))
                drawButtons()
            }
        })
    }

    private fun openLast() {
        val path = lastPath ?: return
        startActivity(Intent(this, ImageViewerActivity::class.java).putExtra(FilesActivity.SINGLE, path))
    }

    // ------------------------------------------------------------ the window

    private fun buildWindow(thai: Typeface): View {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(r.color(R.color.retro_desktop))
            setPadding(r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_camera) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_S), r.dp(UiScale.ICON_S)))
        bar.addView(TextView(this).apply {
            text = getString(R.string.window_camera)
            setTextColor(r.color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = getString(R.string.settings_home)
            isClickable = true
            setOnClickListener { goHome() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(r.dp(UiScale.ICON_M), r.dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP))

        val frame = FrameLayout(this).apply { setBackgroundResource(R.drawable.retro_sunken) }
        preview = PreviewView(this).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            setBackgroundColor(r.color(R.color.retro_dark))
            contentDescription = getString(R.string.camera_preview)
        }
        frame.addView(preview, FrameLayout.LayoutParams(Retro.MATCH, Retro.MATCH))
        window.addView(frame, LinearLayout.LayoutParams(Retro.MATCH, 0, 1f).apply { topMargin = r.dp(UiScale.WINDOW_INSET) })

        status = r.text("", UiScale.TEXT_BASE).apply { visibility = View.GONE; maxLines = 2 }
        window.addView(status, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })

        val row = r.row()
        flip = r.button("") { switchLens() }
        last = r.button(getString(R.string.camera_open_last)) { openLast() }
        row.addView(flip, LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
        row.addView(last, LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f).apply { marginStart = r.dp(UiScale.SPACE_XS) })
        window.addView(row, LinearLayout.LayoutParams(Retro.MATCH, Retro.WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        shutter = r.button(getString(R.string.camera_take), big = true) { takePhoto() }
        window.addView(shutter, LinearLayout.LayoutParams(Retro.MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        drawButtons()
        return root
    }

    private fun drawButtons() {
        if (!::shutter.isInitialized) return
        val lens = getString(if (front) R.string.camera_lens_front else R.string.camera_lens_back)
        flip.text = getString(R.string.camera_switch, lens)
        flip.contentDescription = flip.text
        r.setEnabledButton(flip, hasFront && hasBack && !busy) { switchLens() }
        r.setEnabledButton(shutter, capture != null && !busy) { takePhoto() }
        r.setEnabledButton(last, lastPath != null && !busy) { openLast() }
    }

    private fun say(words: String?) {
        status.text = words.orEmpty()
        status.visibility = if (words == null) View.GONE else View.VISIBLE
    }

    private fun goHome() {
        KioskScreens.leaveAllButHome("camera-home")
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        private const val TAG = "KioskCamera"
        /** DCIM/Kiosk: the phone's photo folder, shown by the file manager. */
        const val FOLDER = "Kiosk"
    }
}
