package com.mammonrn.phoneaikiosk.ui

import android.graphics.Rect
import android.view.MotionEvent
import android.view.TouchDelegate
import android.view.View
import android.view.accessibility.AccessibilityNodeInfo

/**
 * Touch areas bigger than what is drawn (0.64.0, Poom: a folded window on the
 * home screen and the gold/oil page squares must be a 48dp target without
 * changing what the eye sees).
 *
 * Set on a [host] that contains the small views: a touch the host's children do
 * not take (a gap between windows, a spot on a window's body) and that falls in
 * a small view's area goes to that view, as a tap on the view itself. An area
 * is the view's own rectangle grown to at least [UiScale.TOUCH] each way,
 * centred on it, moved inside the host if it would stick out. Where two areas
 * meet, the nearer view's centre wins.
 *
 * Android asks a view's touch delegate before its own click (View.onTouchEvent),
 * and accessibility services read the areas from [getTouchDelegateInfo]; the
 * UI check (scripts/ui-check) measures a view by the area it really answers to.
 */
class TouchAreas(private val host: View) : TouchDelegate(Rect(), host) {

    private val views = ArrayList<View>()
    private var target: View? = null
    private val minPx = (UiScale.TOUCH * host.resources.displayMetrics.density).toInt()

    fun add(view: View) { if (view !in views) views.add(view) }

    /** [view]'s area in the host's coordinates; null when it is not shown. */
    fun areaOf(view: View): Rect? {
        if (!view.isShown || view.width <= 0) return null
        val at = IntArray(2); view.getLocationInWindow(at)
        val home = IntArray(2); host.getLocationInWindow(home)
        val r = Rect(at[0] - home[0], at[1] - home[1], at[0] - home[0] + view.width, at[1] - home[1] + view.height)
        fun grow(lo: Int, hi: Int, limit: Int): Pair<Int, Int> {
            val size = hi - lo
            if (size >= minPx) return lo to hi
            var a = lo - (minPx - size) / 2
            var b = a + minPx
            if (a < 0) { b -= a; a = 0 }
            if (b > limit) { a -= b - limit; b = limit }
            return maxOf(0, a) to b
        }
        val (l, rr) = grow(r.left, r.right, host.width)
        val (t, b) = grow(r.top, r.bottom, host.height)
        return Rect(l, t, rr, b)
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        val x = event.x.toInt(); val y = event.y.toInt()
        if (event.actionMasked == MotionEvent.ACTION_DOWN) {
            target = views.mapNotNull { v -> areaOf(v)?.takeIf { it.contains(x, y) }?.let { v to it } }
                .minByOrNull { (_, a) -> val dx = a.centerX() - x; val dy = a.centerY() - y; dx * dx + dy * dy }?.first
        }
        val v = target ?: return false
        if (event.actionMasked == MotionEvent.ACTION_UP || event.actionMasked == MotionEvent.ACTION_CANCEL) target = null
        // As a tap in the middle of the view itself.
        val copy = MotionEvent.obtain(event)
        copy.setLocation(v.width / 2f, v.height / 2f)
        val taken = v.dispatchTouchEvent(copy)
        copy.recycle()
        return taken
    }

    override fun getTouchDelegateInfo(): AccessibilityNodeInfo.TouchDelegateInfo {
        val map = HashMap<android.graphics.Region, View>()
        for (v in views) areaOf(v)?.let { map[android.graphics.Region(it)] = v }
        return AccessibilityNodeInfo.TouchDelegateInfo(map.ifEmpty { mapOf(android.graphics.Region(0, 0, 0, 0) to host) })
    }

    companion object {
        /** The area [view] answers to on screen: its own, or a bigger one from an ancestor's [TouchAreas]. */
        fun screenArea(view: View): Rect {
            val at = IntArray(2); view.getLocationOnScreen(at)
            val own = Rect(at[0], at[1], at[0] + view.width, at[1] + view.height)
            var p = view.parent as? View
            while (p != null) {
                val d = p.touchDelegate as? TouchAreas
                val a = d?.areaOf(view)
                if (a != null) {
                    val home = IntArray(2); p.getLocationOnScreen(home)
                    a.offset(home[0], home[1])
                    a.union(own)
                    return a
                }
                p = p.parent as? View
            }
            return own
        }
    }
}
