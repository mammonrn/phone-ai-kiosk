package com.mammonrn.phoneaikiosk.files

import android.app.Activity
import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageDecoder
import android.graphics.Matrix
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import android.view.GestureDetector
import android.view.Gravity
import android.view.MotionEvent
import android.view.ScaleGestureDetector
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.io.File
import java.util.concurrent.Executors

/**
 * The picture viewer (0.59.0, Poom: "ตัวดูรูปใหม่ แบบง่าย ขยายสองนิ้วได้ ทีละรูป
 * ไม่ปัดไปรูปถัดไป"). One picture from the file manager, on black: two fingers
 * zoom it, one finger moves it while zoomed, a double tap goes back to whole.
 * No next or previous — a swipe moves the picture, never to another one.
 *
 * A screen of this app, so lock task stays on; "ปิด" goes back to the folder.
 * Decoded on a worker at no more than twice the screen's size, so a 50 MP
 * photo does not run the phone out of memory. HEIC and the first frame of a
 * GIF come through ImageDecoder like the rest.
 */
class ImageViewerActivity : Activity() {

    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-picture") }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        val r = Retro(this, thai)
        val file = File(intent.getStringExtra(FilesActivity.SINGLE).orEmpty())
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }

        val root = FrameLayout(this).apply { setBackgroundColor(r.color(R.color.retro_dark)) }
        val picture = ZoomView(this)
        root.addView(picture, FrameLayout.LayoutParams(Retro.MATCH, Retro.MATCH))
        val note = r.text(getString(R.string.picture_loading), UiScale.TEXT_BASE).apply {
            setTextColor(r.color(R.color.retro_light)); gravity = Gravity.CENTER
        }
        root.addView(note, FrameLayout.LayoutParams(Retro.MATCH, Retro.WRAP, Gravity.CENTER))
        val bar = r.row().apply {
            setBackgroundColor(r.color(R.color.video_scrim))
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        }
        bar.addView(TextView(this).apply {
            text = file.name; typeface = Typeface.create(thai, Typeface.BOLD); textSize = UiScale.TEXT_BASE
            setTextColor(r.color(R.color.retro_light)); maxLines = 2; ellipsize = TextUtils.TruncateAt.MIDDLE
        }, LinearLayout.LayoutParams(0, Retro.WRAP, 1f))
        bar.addView(r.button(getString(R.string.picture_close)) { finish() },
                    LinearLayout.LayoutParams(Retro.WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_S) })
        root.addView(bar, FrameLayout.LayoutParams(Retro.MATCH, Retro.WRAP, Gravity.TOP))
        setContentView(root)
        hideSystemBars()

        val longSide = maxOf(resources.displayMetrics.widthPixels, resources.displayMetrics.heightPixels) * 2
        worker.execute {
            val bitmap = runCatching {
                ImageDecoder.decodeBitmap(ImageDecoder.createSource(file)) { decoder, info, _ ->
                    val big = maxOf(info.size.width, info.size.height)
                    var sample = 1
                    while (big / sample > longSide) sample *= 2
                    decoder.setTargetSampleSize(sample)
                }
            }
            runOnUiThread {
                if (isDestroyed) return@runOnUiThread
                bitmap.onSuccess {
                    Log.i(TAG, "picture shown")
                    picture.show(it)
                    note.visibility = android.view.View.GONE
                }.onFailure {
                    Log.i(TAG, "picture failed ${it.javaClass.simpleName}")
                    note.text = getString(R.string.picture_failed)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = finish()

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        /** `logcat -s KioskFiles:I` — shown or failed, never the picture's name. */
        private const val TAG = FilesActivity.TAG
    }
}

/**
 * One picture that two fingers zoom (whole … [MAX_ZOOM] × whole) and one
 * finger moves, never past its edges; a double tap goes back to whole.
 */
class ZoomView(context: Context) : ImageView(context) {

    private val m = Matrix()
    private var fit = 1f
    private var scale = 1f
    private var bitmapW = 0
    private var bitmapH = 0

    private val pinch = ScaleGestureDetector(context, object : ScaleGestureDetector.SimpleOnScaleGestureListener() {
        override fun onScale(d: ScaleGestureDetector): Boolean {
            val next = (scale * d.scaleFactor).coerceIn(fit, fit * MAX_ZOOM)
            val f = next / scale
            scale = next
            m.postScale(f, f, d.focusX, d.focusY)
            keepInside()
            return true
        }
    })

    private val gestures = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onScroll(e1: MotionEvent?, e2: MotionEvent, dx: Float, dy: Float): Boolean {
            m.postTranslate(-dx, -dy)
            keepInside()
            return true
        }

        override fun onDoubleTap(e: MotionEvent): Boolean {
            if (scale > fit * 1.01f) whole() else {
                val f = 2f
                scale *= f
                m.postScale(f, f, e.x, e.y)
                keepInside()
            }
            return true
        }
    })

    init {
        scaleType = ScaleType.MATRIX
    }

    fun show(bitmap: Bitmap) {
        bitmapW = bitmap.width
        bitmapH = bitmap.height
        setImageBitmap(bitmap)
        if (width > 0) whole() else post { whole() }
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (bitmapW > 0) whole()
    }

    /** The whole picture, centred. */
    private fun whole() {
        if (bitmapW == 0 || width == 0) return
        fit = minOf(width.toFloat() / bitmapW, height.toFloat() / bitmapH)
        scale = fit
        m.reset()
        m.postScale(fit, fit)
        m.postTranslate((width - bitmapW * fit) / 2, (height - bitmapH * fit) / 2)
        imageMatrix = m
    }

    /** A picture smaller than the screen stays centred; a bigger one never shows past its edge. */
    private fun keepInside() {
        val v = FloatArray(9).also(m::getValues)
        val w = bitmapW * scale
        val h = bitmapH * scale
        val x = v[Matrix.MTRANS_X]
        val y = v[Matrix.MTRANS_Y]
        val nx = if (w <= width) (width - w) / 2 else x.coerceIn(width - w, 0f)
        val ny = if (h <= height) (height - h) / 2 else y.coerceIn(height - h, 0f)
        m.postTranslate(nx - x, ny - y)
        imageMatrix = m
    }

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        pinch.onTouchEvent(event)
        if (!pinch.isInProgress) gestures.onTouchEvent(event)
        return true
    }

    companion object {
        const val MAX_ZOOM = 8f
    }
}
