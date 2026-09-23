package com.mammonrn.phoneaikiosk.auth

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import android.view.View
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.R
import kotlin.math.abs
import kotlin.math.min

/**
 * A 3×3 grid of dots to drag a pattern across, drawn in the kiosk's 1995 look:
 * square dots on the grey face, the chosen ones filled navy and joined by a
 * navy line, red for a moment when the pattern was wrong.
 *
 * Skipping over a dot takes it too (0 → 2 passes 1), as Android's own pattern
 * lock does, so the pattern is what the finger visibly crossed. The dots are
 * reported once the finger lifts ([onPattern]); nothing is kept here after
 * [clear]. Accessibility: the grid announces itself and how many dots are
 * chosen; the pattern itself is never spoken.
 */
class PatternPad(context: Context) : View(context) {

    var onPattern: ((List<Int>) -> Unit)? = null

    /** Not accepting input — during a lockout, or while a result shows. */
    var locked = false
        set(value) { field = value; invalidate() }

    private val chosen = ArrayList<Int>()
    private var fingerX = -1f
    private var fingerY = -1f
    private var wrong = false

    private val dp = resources.displayMetrics.density
    private val face = Paint().apply { color = ContextCompat.getColor(context, R.color.retro_face) }
    private val light = Paint().apply { color = ContextCompat.getColor(context, R.color.retro_light) }
    private val shadow = Paint().apply { color = ContextCompat.getColor(context, R.color.retro_shadow) }
    private val dark = Paint().apply { color = ContextCompat.getColor(context, R.color.retro_dark) }
    private val on = Paint().apply { color = ContextCompat.getColor(context, R.color.retro_title) }
    private val bad = Paint().apply { color = ContextCompat.getColor(context, R.color.retro_bad) }
    private val line = Paint().apply {
        strokeWidth = 6 * dp
        strokeCap = Paint.Cap.SQUARE
    }

    init {
        contentDescription = context.getString(R.string.auth_pattern_pad)
        isFocusable = true
    }

    fun clear() {
        chosen.clear()
        wrong = false
        fingerX = -1f
        invalidate()
    }

    /** Shows the last pattern in red, as the answer to a wrong one. */
    fun showWrong() {
        wrong = true
        fingerX = -1f
        invalidate()
    }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        val side = if (MeasureSpec.getMode(heightMeasureSpec) == MeasureSpec.UNSPECIFIED) w else min(w, h)
        setMeasuredDimension(side, side)
    }

    private fun cell() = min(width, height) / 3f
    private fun centre(dot: Int): Pair<Float, Float> =
        Pair((dot % 3 + 0.5f) * cell(), (dot / 3 + 0.5f) * cell())

    private fun hit(x: Float, y: Float): Int? {
        val c = cell()
        val col = (x / c).toInt()
        val row = (y / c).toInt()
        if (col !in 0..2 || row !in 0..2) return null
        val (cx, cy) = centre(row * 3 + col)
        // Only near the dot, so a diagonal drag does not catch the corners.
        return if (abs(x - cx) < c * 0.32f && abs(y - cy) < c * 0.32f) row * 3 + col else null
    }

    private fun add(dot: Int) {
        if (dot in chosen) return
        chosen.lastOrNull()?.let { last ->
            val r1 = last / 3; val c1 = last % 3; val r2 = dot / 3; val c2 = dot % 3
            if ((r1 + r2) % 2 == 0 && (c1 + c2) % 2 == 0) {
                val middle = ((r1 + r2) / 2) * 3 + (c1 + c2) / 2
                if (middle !in chosen) chosen += middle
            }
        }
        chosen += dot
        announceForAccessibility(context.getString(R.string.auth_pattern_dots, chosen.size))
    }

    @SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (locked) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                parent?.requestDisallowInterceptTouchEvent(true)
                clear()
                hit(event.x, event.y)?.let { add(it) }
            }
            MotionEvent.ACTION_MOVE -> hit(event.x, event.y)?.let { add(it) }
            MotionEvent.ACTION_UP -> {
                fingerX = -1f
                invalidate()
                val dots = chosen.toList()
                if (dots.isNotEmpty()) onPattern?.invoke(dots)
                return true
            }
            MotionEvent.ACTION_CANCEL -> clear()
        }
        fingerX = event.x
        fingerY = event.y
        invalidate()
        return true
    }

    override fun onDraw(canvas: Canvas) {
        val c = cell()
        val half = c * 0.16f
        val edge = 2 * dp
        val stroke = if (wrong) bad else on
        line.color = stroke.color
        for (i in 1 until chosen.size) {
            val (x1, y1) = centre(chosen[i - 1])
            val (x2, y2) = centre(chosen[i])
            canvas.drawLine(x1, y1, x2, y2, line)
        }
        if (fingerX >= 0 && chosen.isNotEmpty() && !wrong) {
            val (x, y) = centre(chosen.last())
            canvas.drawLine(x, y, fingerX, fingerY, line)
        }
        for (dot in 0 until 9) {
            val (x, y) = centre(dot)
            // A raised square dot: light top-left, dark bottom-right.
            canvas.drawRect(x - half, y - half, x + half, y + half, dark)
            canvas.drawRect(x - half, y - half, x + half - edge, y + half - edge, light)
            canvas.drawRect(x - half + edge, y - half + edge, x + half - edge, y + half - edge, shadow)
            canvas.drawRect(x - half + edge, y - half + edge, x + half - 2 * edge, y + half - 2 * edge, face)
            if (dot in chosen) {
                canvas.drawRect(x - half + 2 * edge, y - half + 2 * edge,
                                x + half - 2 * edge, y + half - 2 * edge, stroke)
            }
        }
    }
}
