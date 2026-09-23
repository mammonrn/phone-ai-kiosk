package com.mammonrn.phoneaikiosk.ui

import android.content.Context
import android.graphics.Typeface
import android.text.SpannableStringBuilder
import android.text.Spanned
import android.text.style.BackgroundColorSpan
import android.text.style.ForegroundColorSpan
import android.util.AttributeSet
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R
import kotlin.math.abs

/**
 * A card's content in pages, one showing at a time, with a row of 1995
 * property-sheet tabs above it (DESIGN.md, "การ์ดหลายหน้า"). Only the content
 * pages; the card's title bar, frame and place in the stack do not move.
 *
 * MAKING A CARD PAGED is layout only: wrap the pages in a PagedPanel and give
 * each page `android:tag="id|ชื่อแท็บ"`:
 *
 *     <com.mammonrn.phoneaikiosk.ui.PagedPanel ...>
 *         <LinearLayout android:tag="gold|ทอง" .../>
 *         <LinearLayout android:tag="oil|น้ำมัน" .../>
 *     </com.mammonrn.phoneaikiosk.ui.PagedPanel>
 *
 * A third page is a third child. The rules of which page shows are in [Pages].
 *
 * WHAT THE SCREEN PROMISES:
 *  * how many pages and which is showing, at a glance: one tab per page, the
 *    shown one raised and bold; with one page there are no tabs at all;
 *  * the card never changes height when the page changes: pages not shown are
 *    INVISIBLE, so the content keeps the tallest page's height;
 *  * a page with news not yet seen says so on its tab ("ใหม่");
 *  * tabs are the whole width and 40dp tall; a sideways swipe on the content
 *    turns the page too. Nothing else on the dashboard swipes sideways.
 */
class PagedPanel @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null,
) : LinearLayout(context, attrs) {

    /** Called when a finger turns the page, so the card can count it as a touch. */
    var onTurned: ((String) -> Unit)? = null

    lateinit var pages: Pages
        private set

    private val views = LinkedHashMap<String, View>()
    private val titles = HashMap<String, String>()
    private val hidden = HashSet<String>()
    private val tabs = LinearLayout(context).apply { orientation = HORIZONTAL }
    private val content = FrameLayout(context)
    private val tabViews = HashMap<String, TextView>()

    private val density = resources.displayMetrics.density
    private val slop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    private var swiping = false

    init {
        orientation = VERTICAL
    }

    override fun onFinishInflate() {
        super.onFinishInflate()
        val children = (0 until childCount).map { getChildAt(it) }
        removeAllViews()
        for (child in children) {
            val (id, title) = (child.tag as? String)?.split("|", limit = 2)
                ?.takeIf { it.size == 2 } ?: error("each page of a PagedPanel needs tag=\"id|title\"")
            views[id] = child
            titles[id] = title
            content.addView(child, FrameLayout.LayoutParams(
                FrameLayout.LayoutParams.MATCH_PARENT, FrameLayout.LayoutParams.WRAP_CONTENT))
        }
        pages = Pages(views.keys.toList())
        addView(tabs, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT)
            .apply { topMargin = dp(4) })
        buildTabs()
        render()
    }

    /** A page with nothing to show (an older broker, no data) leaves the tabs. */
    fun setPageAvailable(id: String, available: Boolean) {
        val changed = if (available) hidden.remove(id) else hidden.add(id)
        if (!changed) return
        if (!available && pages.current == id) visible().firstOrNull()?.let { pages.show(it) }
        buildTabs()
        render()
    }

    /** This page's news signature; see Pages.news. */
    fun news(id: String, signature: String, nowMs: Long) {
        if (pages.news(id, signature, nowMs)) render()
    }

    private fun visible() = pages.ids.filterNot { it in hidden }

    private fun buildTabs() {
        tabs.removeAllViews()
        tabViews.clear()
        val shown = visible()
        tabs.visibility = if (shown.size > 1) VISIBLE else GONE
        for ((index, id) in shown.withIndex()) {
            val tab = TextView(context).apply {
                gravity = Gravity.CENTER
                typeface = Typeface.create(ResourcesCompat.getFont(context, R.font.plex_thai), Typeface.BOLD)
                setTextSize(TypedValue.COMPLEX_UNIT_PX, resources.getDimension(R.dimen.type_minor))
                isClickable = true
                setOnClickListener { turnTo(id) }
            }
            tabViews[id] = tab
            tabs.addView(tab, LayoutParams(0, dp(40), 1f).apply { if (index > 0) marginStart = dp(4) })
        }
    }

    private fun turnTo(id: String) {
        val now = android.os.SystemClock.elapsedRealtime()
        pages.choose(id, now)
        render()
        onTurned?.invoke(id)
    }

    private fun render() {
        val shown = visible()
        for ((id, view) in views) {
            view.visibility = when {
                id in hidden -> GONE
                id == pages.current -> VISIBLE
                // Not shown but still measured, so the card keeps the tallest
                // page's height and never jumps when the page turns.
                else -> INVISIBLE
            }
        }
        for ((index, id) in shown.withIndex()) {
            val tab = tabViews[id] ?: continue
            val selected = id == pages.current
            val label = SpannableStringBuilder(titles[id])
            if (pages.hasUnseen(id)) {
                label.append("  ")
                val start = label.length
                label.append(" ใหม่ ")
                label.setSpan(BackgroundColorSpan(color(R.color.retro_title)), start, label.length,
                              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
                label.setSpan(ForegroundColorSpan(color(R.color.retro_title_text)), start, label.length,
                              Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
            }
            tab.text = label
            tab.setTextColor(color(if (selected) R.color.retro_text else R.color.retro_dim))
            tab.setBackgroundResource(if (selected) R.drawable.retro_raised else R.drawable.retro_button)
            tab.isSelected = selected
            tab.contentDescription = "หน้า ${index + 1} จาก ${shown.size} ${titles[id]}" +
                (if (selected) " กำลังแสดง" else "") + (if (pages.hasUnseen(id)) " มีข้อมูลใหม่" else "")
            // The shown tab stands 3dp taller, as a 1995 property sheet's does.
            tab.translationY = if (selected) 0f else dp(3).toFloat()
        }
    }

    // A sideways swipe over the content turns the page. Taken only once the
    // finger has clearly gone sideways (twice the touch slop, and more across
    // than down), so a tap still reaches whatever is under it.
    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = track(event)

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onTouchEvent(event: MotionEvent): Boolean {
        // Nothing under the finger took the touch, so it comes here directly:
        // keep following it, so the swipe works over plain text too.
        val claimed = track(event)
        return claimed || (event.actionMasked == MotionEvent.ACTION_DOWN && visible().size > 1)
    }

    private fun track(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> { downX = event.x; downY = event.y; swiping = false }
            MotionEvent.ACTION_MOVE -> {
                val dx = event.x - downX
                val dy = event.y - downY
                if (!swiping && visible().size > 1 && abs(dx) > slop * 2 && abs(dx) > abs(dy) * 1.5f) {
                    swiping = true
                    parent?.requestDisallowInterceptTouchEvent(true)
                }
            }
            MotionEvent.ACTION_UP -> if (swiping) {
                swiping = false
                val shown = visible()
                val at = shown.indexOf(pages.current)
                val next = (at + if (event.x - downX < 0) 1 else -1).coerceIn(0, shown.lastIndex)
                if (next != at) turnTo(shown[next])
                return true
            }
            MotionEvent.ACTION_CANCEL -> swiping = false
        }
        return swiping
    }

    private fun dp(value: Int): Int = (value * density).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(context, id)
}
