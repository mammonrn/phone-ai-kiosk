package com.mammonrn.phoneaikiosk.media

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.graphics.drawable.StateListDrawable
import android.util.TypedValue
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.UiScale
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * The video player's floating panel, in the mood of a late-90s DVD player
 * (0.56.0, Poom's reference): brushed silver, a teal read-out, and a big ring
 * with the play button in its middle. Our own drawing; no logo, no picture
 * from the reference (Poom's rule).
 */
object DvdSkin {
    private fun px(c: Context, dp: Int) =
        TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, dp.toFloat(), c.resources.displayMetrics).roundToInt()
    private fun c(ctx: Context, id: Int) = ContextCompat.getColor(ctx, id)

    /** Silver: light at the top, darker below, a dark rim — rounded like the reference's panel. */
    fun panel(ctx: Context): Drawable = GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
        intArrayOf(c(ctx, R.color.dvd_silver_light), c(ctx, R.color.dvd_silver), c(ctx, R.color.dvd_silver_dark))).apply {
        cornerRadius = px(ctx, UiScale.SPACE_L).toFloat()
        setStroke(px(ctx, UiScale.BEVEL), c(ctx, R.color.dvd_silver_dark))
    }

    /** The teal read-out, sunk into the silver. */
    fun lcd(ctx: Context): Drawable = GradientDrawable().apply {
        setColor(c(ctx, R.color.dvd_lcd_bg))
        cornerRadius = px(ctx, UiScale.SPACE_S).toFloat()
        setStroke(px(ctx, UiScale.BEVEL), c(ctx, R.color.dvd_silver_dark))
    }

    /** A small silver button, darker while pressed. */
    fun button(ctx: Context): Drawable = StateListDrawable().apply {
        fun face(top: Int, bottom: Int) = LayerDrawable(arrayOf(GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM,
            intArrayOf(c(ctx, top), c(ctx, bottom))).apply {
            cornerRadius = px(ctx, UiScale.SPACE_S).toFloat()
            setStroke(px(ctx, UiScale.HAIRLINE), c(ctx, R.color.dvd_silver_dark))
        }))
        addState(intArrayOf(android.R.attr.state_pressed), face(R.color.dvd_silver_dark, R.color.dvd_silver))
        addState(intArrayOf(), face(R.color.dvd_silver_light, R.color.dvd_silver))
    }
}

/**
 * The ring: play / pause in the middle, back 10 s at nine o'clock, forward
 * 10 s at three, and the ring itself to drag round for the place in the
 * video (twelve o'clock is the start, all the way round the end). While it
 * is dragged, [onScrub] says where; lifting the finger seeks there.
 * VideoRules.zone decides what a touch is; VideoRulesTest holds it.
 */
class RingView(context: Context,
               private val onPlay: () -> Unit,
               private val onSkip: (Long) -> Unit,
               private val onScrub: (Float?) -> Unit,
               private val onSeek: (Float) -> Unit) : View(context) {

    /** Where the video is, 0..1; and whether it plays (for the middle's picture). */
    var progress = 0f
        set(value) { field = value.coerceIn(0f, 1f); if (scrub == null) invalidate() }
    var playing = false
        set(value) { field = value; invalidate() }

    private val d = resources.displayMetrics.density
    private val silver = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dvd_silver) }
    private val rim = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dvd_silver_dark); style = Paint.Style.STROKE; strokeWidth = UiScale.BEVEL * d
    }
    private val lcd = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dvd_lcd_bg) }
    private val arc = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = ContextCompat.getColor(context, R.color.dvd_lcd_text); style = Paint.Style.STROKE
        strokeWidth = UiScale.SPACE_XS * d; strokeCap = Paint.Cap.ROUND
    }
    private val glyph = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.retro_dark) }
    private val light = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = ContextCompat.getColor(context, R.color.dvd_lcd_text) }
    private var scrub: Float? = null
    private var downZone = VideoRules.RingZone.OUTSIDE
    private var dragging = false
    private var downX = 0f; private var downY = 0f
    private val slop = ViewConfiguration.get(context).scaledTouchSlop

    init { isClickable = true; contentDescription = context.getString(R.string.video_ring) }

    private val cx get() = width / 2f
    private val cy get() = height / 2f
    private val outer get() = min(width, height) / 2f - UiScale.BEVEL * d
    private val inner get() = outer * 0.45f

    override fun onDraw(canvas: Canvas) {
        canvas.drawCircle(cx, cy, outer, silver)
        canvas.drawCircle(cx, cy, outer, rim)
        // The place in the video, as an arc round the ring.
        val r = (outer + inner) / 2
        val box = RectF(cx - r, cy - r, cx + r, cy + r)
        canvas.drawArc(box, -90f, 360f * (scrub ?: progress), false, arc)
        // The skips, at nine and three o'clock.
        val s = inner * 0.28f
        drawDouble(canvas, cx - r, cy, s, back = true)
        drawDouble(canvas, cx + r, cy, s, back = false)
        // The middle: the play button, teal, with its picture.
        canvas.drawCircle(cx, cy, inner, lcd)
        canvas.drawCircle(cx, cy, inner, rim)
        val g = inner * 0.4f
        if (playing) {
            canvas.drawRect(cx - g * 0.8f, cy - g, cx - g * 0.25f, cy + g, light)
            canvas.drawRect(cx + g * 0.25f, cy - g, cx + g * 0.8f, cy + g, light)
        } else {
            val p = Path().apply { moveTo(cx - g * 0.6f, cy - g); lineTo(cx + g, cy); lineTo(cx - g * 0.6f, cy + g); close() }
            canvas.drawPath(p, light)
        }
    }

    private fun drawDouble(canvas: Canvas, x: Float, y: Float, s: Float, back: Boolean) {
        val dir = if (back) -1 else 1
        for (k in 0..1) {
            val ox = x + dir * (k * s - s / 2)
            val p = Path().apply {
                moveTo(ox - dir * s / 2, y - s / 2); lineTo(ox + dir * s / 2, y); lineTo(ox - dir * s / 2, y + s / 2); close()
            }
            canvas.drawPath(p, glyph)
        }
    }

    private fun zoneAt(x: Float, y: Float) =
        VideoRules.zone(hypot(x - cx, y - cy), VideoRules.turn(x, y, cx, cy), inner, outer, UiScale.SPACE_L * d)

    override fun onTouchEvent(e: MotionEvent): Boolean {
        when (e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                downZone = zoneAt(e.x, e.y); downX = e.x; downY = e.y; dragging = false
                if (downZone == VideoRules.RingZone.OUTSIDE) return false
                parent?.requestDisallowInterceptTouchEvent(true)
                isPressed = true
            }
            MotionEvent.ACTION_MOVE -> {
                if (downZone == VideoRules.RingZone.CENTRE) return true
                if (!dragging && hypot(e.x - downX, e.y - downY) > slop) dragging = true
                if (dragging) {
                    scrub = VideoRules.turn(e.x, e.y, cx, cy)
                    onScrub(scrub)
                    invalidate()
                }
            }
            MotionEvent.ACTION_UP -> {
                isPressed = false
                val s = scrub
                when {
                    dragging && s != null -> onSeek(s)
                    downZone == VideoRules.RingZone.CENTRE -> onPlay()
                    downZone == VideoRules.RingZone.BACK -> onSkip(-VideoRules.SKIP_MS)
                    downZone == VideoRules.RingZone.FORWARD -> onSkip(VideoRules.SKIP_MS)
                }
                scrub = null; dragging = false
                onScrub(null)
                invalidate()
                if (!dragging) performClick()
            }
            MotionEvent.ACTION_CANCEL -> { isPressed = false; scrub = null; dragging = false; onScrub(null); invalidate() }
        }
        return true
    }

    override fun performClick(): Boolean = super.performClick()
}
