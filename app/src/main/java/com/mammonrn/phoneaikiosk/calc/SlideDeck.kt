package com.mammonrn.phoneaikiosk.calc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.drawable.GradientDrawable
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.PagedPanel
import com.mammonrn.phoneaikiosk.ui.UiScale
import kotlin.math.abs

/**
 * The calculator's slides: ONE component for every tab whose tools are pages
 * turned sideways (the solar tab, 0.61.0; the electrical tab since Poom asked
 * for it the same way, 2026-09-25 — "ใช้ component ชิ้นเดียวกัน").
 *
 *  * a sideways swipe over the page turns it, by PagedPanel's rule (more than
 *    twice the touch slop, 1.5 times more across than down); anything less
 *    reaches what is under the finger, so fields and buttons still work;
 *  * ■ □ □ in the window's title bar (the activity's pageDots) say which page
 *    of how many — a shape, not only a colour — and a tap on them goes on;
 *  * the tab row and the title bar never move; only the page does.
 *
 * [keys] is asked each time (the solar tab has more slides off-grid); [title]
 * names a slide for the squares' read-out; [view] gives the slide's view,
 * built by the tab's own code — this class knows nothing of formulas.
 */
internal class SlideDeck<K>(
    private val a: CalculatorActivity,
    private val keys: () -> List<K>,
    private val title: (K) -> String,
    private val view: (K) -> View,
    first: K,
) {
    var current: K = first
        private set

    private val frame = SlideFrame(a) { step -> turn(step) }

    /** The tab opens: the slide it was on, the squares in the title bar. */
    fun open() {
        a.setPage(frame)
        render()
    }

    /** Shows [key] (a choice on a slide can change which slides there are). */
    fun show(key: K) {
        current = key
        render()
    }

    fun turn(step: Int) {
        val list = keys()
        val next = (list.indexOf(current) + step).coerceIn(0, list.lastIndex)
        if (list[next] == current) return
        current = list[next]
        a.currentFocus?.clearFocus()
        render()
    }

    /** The current slide, drawn again (after a choice on it rebuilt it). */
    fun render() {
        val list = keys()
        if (current !in list) current = list.first()
        frame.removeAllViews()
        frame.addView(view(current), FrameLayout.LayoutParams(MATCH, MATCH))
        dots()
    }

    private fun dots() {
        val host = a.pageDots
        host.removeAllViews()
        val list = keys()
        host.visibility = View.VISIBLE
        val said = ArrayList<String>()
        for ((n, k) in list.withIndex()) {
            val on = k == current
            host.addView(View(a).apply {
                background = GradientDrawable().apply {
                    if (on) setColor(a.color(R.color.retro_title_text))
                    setStroke(a.dp(UiScale.HAIRLINE), a.color(R.color.retro_title_text))
                }
                importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
            }, LinearLayout.LayoutParams(a.dp(PagedPanel.SQUARE_DP), a.dp(PagedPanel.SQUARE_DP)).apply { if (n > 0) marginStart = a.dp(UiScale.SPACE_XS) })
            val name = title(k)
            said.add(if (on) a.getString(R.string.solar_dot_showing, name) else name)
        }
        host.contentDescription = a.getString(R.string.solar_dots, list.indexOf(current) + 1, list.size, said.joinToString(", "))
        host.isClickable = true
        host.setOnClickListener {
            val l = keys()
            current = l[(l.indexOf(current) + 1) % l.size]
            render()
        }
    }

    /**
     * The slide's frame: a sideways swipe over it turns the slide, by
     * PagedPanel's rule (more than twice the touch slop, 1.5 times more across
     * than down). Anything less reaches what is under the finger.
     */
    private class SlideFrame(context: Context, private val onTurn: (Int) -> Unit) : FrameLayout(context) {
        private val slop = ViewConfiguration.get(context).scaledTouchSlop
        private var downX = 0f
        private var downY = 0f
        private var swiping = false

        override fun onInterceptTouchEvent(event: MotionEvent): Boolean = track(event)

        @SuppressLint("ClickableViewAccessibility")
        override fun onTouchEvent(event: MotionEvent): Boolean =
            track(event) || event.actionMasked == MotionEvent.ACTION_DOWN

        private fun track(event: MotionEvent): Boolean {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; swiping = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = event.x - downX
                    val dy = event.y - downY
                    if (!swiping && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                        swiping = true
                        parent?.requestDisallowInterceptTouchEvent(true)
                    }
                }
                MotionEvent.ACTION_UP -> if (swiping) {
                    swiping = false
                    onTurn(if (event.x - downX < 0) 1 else -1)
                    return true
                }
                MotionEvent.ACTION_CANCEL -> swiping = false
            }
            return swiping
        }
    }
}
