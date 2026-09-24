package com.mammonrn.phoneaikiosk.ui

import android.content.Context
import android.graphics.drawable.GradientDrawable
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
 * A card's content in pages, one showing at a time, turned by a SIDEWAYS
 * SWIPE (DESIGN.md, "การ์ดหลายหน้า"). Only the content pages move; the card's
 * title bar, frame and place in the stack do not.
 *
 * NO TABS (0.53.2, Poom: "สั่งไว้ตั้งแต่แรกว่าให้ทำเป็นแบบสไลด์"). The row of
 * 1995 property-sheet tabs took 44dp of height on the gold card — height that
 * came out of Jarvis. Now the only sign of pages is a row of small squares in
 * the card's own title bar ([attachIndicator]): ■ the page showing, □ the
 * others, and "ใหม่" after a page with news not yet seen. Nothing is added to
 * the card's height.
 *
 * MAKING A CARD PAGED is layout plus one call: wrap the pages in a PagedPanel,
 * give each page `android:tag="id|ชื่อหน้า"`, put an empty LinearLayout in the
 * card's title bar, and hand it to [attachIndicator]:
 *
 *     <com.mammonrn.phoneaikiosk.ui.PagedPanel ...>
 *         <LinearLayout android:tag="gold|ทอง" .../>
 *         <LinearLayout android:tag="oil|น้ำมัน" .../>
 *     </com.mammonrn.phoneaikiosk.ui.PagedPanel>
 *
 * A third page is a third child. The rules of which page shows are in [Pages].
 *
 * WHAT THE SCREEN PROMISES:
 *  * how many pages and which is showing, at a glance, from the squares; with
 *    one page there are none;
 *  * the card never changes height when the page changes: pages not shown are
 *    INVISIBLE, so the content keeps the tallest page's height;
 *  * a page with news not yet seen says "ใหม่" after its square — a word, not
 *    only a colour;
 *  * a sideways swipe on the content turns the page; a tap on the squares
 *    turns to the next one. Nothing else on the dashboard swipes sideways.
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
    private val content = FrameLayout(context)
    private var indicator: LinearLayout? = null

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
        addView(content, LayoutParams(LayoutParams.MATCH_PARENT, LayoutParams.WRAP_CONTENT))
        render()
    }

    /**
     * Where the page squares go: an empty LinearLayout in the card's title bar,
     * so showing the pages costs no height. Tapping them turns to the next page.
     */
    fun attachIndicator(host: LinearLayout) {
        indicator = host.apply {
            orientation = HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            setPadding(dp(4), 0, dp(4), 0)
            setOnClickListener {
                val shown = visible()
                if (shown.size > 1) turnTo(shown[(shown.indexOf(pages.current) + 1) % shown.size])
            }
        }
        render()
    }

    /** A page with nothing to show (an older broker, no data) leaves the squares. */
    fun setPageAvailable(id: String, available: Boolean) {
        val changed = if (available) hidden.remove(id) else hidden.add(id)
        if (!changed) return
        if (!available && pages.current == id) visible().firstOrNull()?.let { pages.show(it) }
        render()
    }

    /** This page's news signature; see Pages.news. */
    fun news(id: String, signature: String, nowMs: Long) {
        if (pages.news(id, signature, nowMs)) render()
    }

    private fun visible() = pages.ids.filterNot { it in hidden }

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
        val host = indicator ?: return
        host.removeAllViews()
        host.visibility = if (shown.size > 1) VISIBLE else GONE
        if (shown.size <= 1) return
        val said = ArrayList<String>()
        for ((index, id) in shown.withIndex()) {
            val selected = id == pages.current
            host.addView(View(context).apply {
                background = GradientDrawable().apply {
                    // ■ the page showing, □ the others: a shape, not only a colour.
                    if (selected) setColor(color(R.color.retro_title_text))
                    setStroke(dp(1), color(R.color.retro_title_text))
                }
            }, LayoutParams(dp(SQUARE_DP), dp(SQUARE_DP)).apply { if (index > 0) marginStart = dp(4) })
            val fresh = pages.hasUnseen(id)
            if (fresh) host.addView(TextView(context).apply {
                text = "ใหม่"
                typeface = ResourcesCompat.getFont(context, R.font.plex_thai)
                setTextSize(TypedValue.COMPLEX_UNIT_SP, 10f)
                setTextColor(color(R.color.retro_badge))
                setPadding(dp(2), 0, 0, 0)
            })
            said.add(titles[id] + (if (selected) " กำลังแสดง" else "") + (if (fresh) " มีข้อมูลใหม่" else ""))
        }
        host.contentDescription = "หน้า ${shown.indexOf(pages.current) + 1} จาก ${shown.size}: " +
            said.joinToString(", ") + " แตะเพื่อเปลี่ยนหน้า หรือปัดซ้ายขวา"
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

    companion object {
        /** A page square's side: small enough for the title bar's one 11sp line. */
        const val SQUARE_DP = 7
    }
}
