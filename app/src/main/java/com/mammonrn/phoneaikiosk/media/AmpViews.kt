package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.media.fx.Eq
import com.mammonrn.phoneaikiosk.media.fx.Spectrum
import com.mammonrn.phoneaikiosk.ui.UiScale
import kotlin.math.ln
import kotlin.math.roundToInt

/**
 * The music player's own parts (0.55.0, Poom: "ใกล้ต้นแบบมากขึ้น"): the dark
 * metal bevels, the bars that move with the music, and the equalizer's eleven
 * sliders. Our own drawing, in the mood of a late-90s player — no skin file,
 * no logo, no picture from the reference is used (Poom's rule).
 */
object AmpSkin {

    private fun px(c: Context, dp: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), c.resources.displayMetrics).roundToInt()

    private fun c(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)

    /** Light on the top and left, dark on the bottom and right, around [face]. */
    fun bevel(ctx: Context, face: Int, raised: Boolean = true): Drawable {
        val light = c(ctx, if (raised) R.color.amp_light else R.color.amp_dark)
        val dark = c(ctx, if (raised) R.color.amp_dark else R.color.amp_light)
        val b = px(ctx, UiScale.BEVEL)
        return LayerDrawable(arrayOf(GradientDrawable().apply { setColor(dark) },
                                     GradientDrawable().apply { setColor(light) },
                                     GradientDrawable().apply { setColor(face) })).apply {
            setLayerInset(1, 0, 0, b, b)
            setLayerInset(2, b, b, b, b)
        }
    }

    /** The player's body. */
    fun body(ctx: Context) = bevel(ctx, c(ctx, R.color.amp_body))

    /** A button: raised, sunk while pressed. */
    fun button(ctx: Context) = StateListDrawable().apply {
        addState(intArrayOf(android.R.attr.state_pressed), bevel(ctx, c(ctx, R.color.amp_face), raised = false))
        addState(intArrayOf(), bevel(ctx, c(ctx, R.color.amp_face)))
    }

    /** A read-out: black, sunk into the body. */
    fun lcd(ctx: Context) = bevel(ctx, c(ctx, R.color.amp_lcd_bg), raised = false)

    /** Motion is kept to a minimum when the phone asks for it (animations off). */
    fun calm(ctx: Context): Boolean =
        Settings.Global.getFloat(ctx.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f
}

/**
 * The bars under the time: what the speaker is playing now, after the
 * equalizer, read from the player's own samples at the position being heard
 * ([com.mammonrn.phoneaikiosk.media.fx.Tap]). Green, then yellow, then orange
 * at the top, in blocks like an LED meter. Still when paused; nothing drawn
 * when stopped.
 */
class SpectrumView(context: Context) : View(context) {

    private var bars = FloatArray(Spectrum.BARS)
    private val paint = Paint()
    private val low = ContextCompat.getColor(context, R.color.retro_lcd)
    private val mid = ContextCompat.getColor(context, R.color.amp_bar_mid)
    private val high = ContextCompat.getColor(context, R.color.amp_bar_high)
    private val off = ContextCompat.getColor(context, R.color.amp_led_off)
    private val calm = AmpSkin.calm(context)
    private var running = false

    private val frame = object : Runnable {
        override fun run() {
            if (!running) return
            step()
            postDelayed(this, if (calm) CALM_MS else FRAME_MS)
        }
    }

    init { contentDescription = context.getString(R.string.music_spectrum) }

    override fun onAttachedToWindow() { super.onAttachedToWindow(); running = true; post(frame) }
    override fun onDetachedFromWindow() { running = false; removeCallbacks(frame); super.onDetachedFromWindow() }

    private fun step() {
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        val fx = MusicPlayer.fx
        val samples = if (playing) fx.tap.at(MusicPlayer.positionMs * 1000) else null
        val next = if (samples != null) Spectrum.bars(samples, fx.tap.sampleRate) else FloatArray(Spectrum.BARS)
        bars = if (calm) next else Spectrum.smooth(bars, next)
        invalidate()
    }

    override fun onDraw(canvas: Canvas) {
        val n = bars.size
        val gap = resources.displayMetrics.density * UiScale.HAIRLINE
        val w = (width - gap * (n - 1)) / n
        val blocks = 8
        val bh = height / blocks.toFloat()
        for (i in 0 until n) {
            val lit = (bars[i] * blocks).roundToInt()
            for (b in 0 until blocks) {
                paint.color = when {
                    b >= lit -> off
                    b >= 7 -> high
                    b >= 5 -> mid
                    else -> low
                }
                val x = i * (w + gap)
                val y = height - (b + 1) * bh
                canvas.drawRect(x, y + gap, x + w, y + bh, paint)
            }
        }
    }

    companion object {
        private const val FRAME_MS = 33L
        private const val CALM_MS = 500L
    }
}

/**
 * The equalizer's eleven sliders: the preamp, a gap, and the ten bands, each
 * with its name under it and +12 / 0 / −12 dB at the left.
 *
 * FOR A FINGER. Eleven sliders side by side are 30dp wide each on this phone
 * — under the 48dp a finger needs. So the whole panel is one touch surface:
 * the column a finger lands in is the slider it holds for the whole gesture,
 * up and down sets it, and the band and its value show while it moves
 * ([onMove]), so a finger that lands one column off sees it at once. Values
 * snap to whole dB; near zero they snap to zero.
 */
class EqView(context: Context, private val onChange: (Eq.Settings) -> Unit,
             private val onMove: (String?) -> Unit) : View(context) {

    var settings: Eq.Settings = Eq.Settings()
        set(value) { field = value; contentDescription = describe(value); invalidate() }

    private val d = resources.displayMetrics.density
    private val sp = resources.displayMetrics.scaledDensity
    private val track = Paint().apply { color = ContextCompat.getColor(context, R.color.amp_dark) }
    private val fill = Paint().apply { color = ContextCompat.getColor(context, R.color.amp_gold) }
    private val thumb = Paint().apply { color = ContextCompat.getColor(context, R.color.amp_text) }
    private val thumbEdge = Paint().apply { color = ContextCompat.getColor(context, R.color.amp_dark) }
    private val held = Paint().apply { color = ContextCompat.getColor(context, R.color.amp_select) }
    private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.amp_text)
        textSize = UiScale.TEXT_NOTE * sp
        textAlign = Paint.Align.CENTER
        typeface = Typeface.DEFAULT_BOLD
    }
    private var holding = -1

    /** The dB scale's width at the left. */
    private val scaleW get() = text.measureText("-12") + UiScale.SPACE_XS * d
    private val labelH get() = text.textSize + UiScale.SPACE_XS * d
    private val columns = 1 + Eq.BANDS.size
    private fun colW() = (width - scaleW) / columns
    private fun colX(i: Int) = scaleW + colW() * i + colW() / 2
    private fun top() = UiScale.SPACE_S * d
    private fun bottom() = height - labelH - UiScale.SPACE_XS * d
    private fun yOf(db: Float) = top() + (bottom() - top()) * (Eq.MAX_DB - db) / (2 * Eq.MAX_DB)
    private fun dbAt(y: Float): Float {
        val raw = Eq.MAX_DB - (y - top()) / (bottom() - top()) * 2 * Eq.MAX_DB
        val whole = raw.roundToInt().toFloat()
        return Eq.clamp(if (kotlin.math.abs(raw) < 0.8f) 0f else whole)
    }

    private fun value(i: Int) = if (i == 0) settings.preampDb else settings.gainsDb[i - 1]
    private fun name(i: Int) = if (i == 0) context.getString(R.string.music_eq_preamp) else Eq.LABELS[i - 1]

    override fun onDraw(canvas: Canvas) {
        // The scale.
        text.textAlign = Paint.Align.LEFT
        for (db in listOf(12f, 0f, -12f)) {
            canvas.drawText(if (db > 0) "+12" else if (db < 0) "-12" else "0", 0f, yOf(db) + text.textSize / 3, text)
        }
        text.textAlign = Paint.Align.CENTER
        val tw = UiScale.SPACE_XS * d
        val th = UiScale.SPACE_S * d
        for (i in 0 until columns) {
            val x = colX(i)
            if (i == holding) canvas.drawRect(x - colW() / 2, 0f, x + colW() / 2, height.toFloat(), held)
            canvas.drawRect(x - tw / 2, top(), x + tw / 2, bottom(), track)
            val y = yOf(value(i))
            val zero = yOf(0f)
            if (settings.on) canvas.drawRect(x - tw / 2, minOf(y, zero), x + tw / 2, maxOf(y, zero), fill)
            // The thumb: a raised square.
            val half = colW() * 0.3f
            canvas.drawRect(x - half, y - th, x + half, y + th, thumbEdge)
            canvas.drawRect(x - half + d, y - th + d, x + half - d, y + th - d, thumb)
            canvas.drawText(name(i), x, height - UiScale.SPACE_XS * d, text)
        }
    }

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                if (e.x < scaleW) return false
                holding = ((e.x - scaleW) / colW()).toInt().coerceIn(0, columns - 1)
                parent?.requestDisallowInterceptTouchEvent(true)
                set(e.y)
            }
            MotionEvent.ACTION_MOVE -> if (holding >= 0) set(e.y)
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                holding = -1
                onMove(null)
                invalidate()
            }
        }
        return true
    }

    private fun set(y: Float) {
        val db = dbAt(y)
        if (db != value(holding)) {
            settings = if (holding == 0) settings.withPreamp(db) else settings.withGain(holding - 1, db)
            onChange(settings)
        }
        onMove(name(holding) + " " + (if (db > 0) "+" else "") + db.toInt() + " dB")
        invalidate()
    }

    private fun describe(s: Eq.Settings) = context.getString(R.string.music_eq_describe,
        (if (s.preampDb > 0) "+" else "") + s.preampDb.toInt(),
        Eq.LABELS.indices.joinToString(", ") { Eq.LABELS[it] + " " + s.gainsDb[it].toInt() })
}

/** The equalizer's curve, across the top of its panel: what the ten bands and the preamp make together. */
class EqCurveView(context: Context) : View(context) {
    var settings: Eq.Settings = Eq.Settings()
        set(value) { field = value; invalidate() }
    private val d = resources.displayMetrics.density
    private val line = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.amp_gold); style = Paint.Style.STROKE
        strokeWidth = UiScale.BEVEL * d
    }
    private val grid = Paint().apply { color = ContextCompat.getColor(context, R.color.amp_face) }
    private val path = Path()

    override fun onDraw(canvas: Canvas) {
        canvas.drawRect(0f, height / 2f, width.toFloat(), height / 2f + d, grid)
        path.reset()
        val lo = ln(40.0); val hi = ln(18000.0)
        for (x in 0..width step 2) {
            val f = kotlin.math.exp(lo + (hi - lo) * x / width)
            val db = Eq.totalDb(settings, f).coerceIn(-2.0 * Eq.MAX_DB, 2.0 * Eq.MAX_DB)
            val y = (height / 2f - db / (2 * Eq.MAX_DB) * (height / 2f - d)).toFloat()
            if (x == 0) path.moveTo(0f, y) else path.lineTo(x.toFloat(), y)
        }
        canvas.drawPath(path, line)
    }
}
