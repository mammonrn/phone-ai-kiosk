package com.mammonrn.phoneaikiosk.ui

import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.net.ConnectivityManager
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.LinearLayout
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.voice.JarvisStatus
import com.mammonrn.phoneaikiosk.voice.VoiceService
import com.mammonrn.phoneaikiosk.voice.VoiceState
import com.mammonrn.phoneaikiosk.voice.WakePause

/**
 * Jarvis's state on EVERY screen, in the same place (0.61.0, Poom: it replaces
 * the beep after the wake word). Top right — in an app, just left of its X; on
 * the home screen, at the end of the first window's title bar.
 *
 * SIX SHAPES, not six colours: ○ ready · ● with sound marks listening · •••
 * thinking · a speech bubble speaking · ❚❚ resting · ⊘ not available. Drawn
 * here from squares, like every icon in the app; read out in words.
 *
 * In an app with voice commands it is also that app's Jarvis button, drawn in
 * the app's own look (a raised button: the shape says it can be pressed), and
 * the question carries which screen it came from ([JarvisBadges.screen]) so
 * "ต่อไป" on the music page means the next song. Elsewhere it is flat: a sign,
 * not a button.
 */
class JarvisBadge(context: Context, private val theme: Theme, private val screen: String?) : View(context) {

    /** The app's look: the button's face, its bevel, and the glyph. */
    data class Theme(val face: Int, val light: Int, val dark: Int, val glyph: Int, val listeningFace: Int)

    var status: JarvisStatus = JarvisStatus.READY
        private set

    private val paint = Paint().apply { isAntiAlias = false }
    private val main = Handler(Looper.getMainLooper())
    private val pressable = screen != null
    private val tick = object : Runnable {
        override fun run() {
            refresh()
            main.postDelayed(this, TICK_MS)
        }
    }

    init {
        isClickable = pressable
        isFocusable = pressable
        if (pressable) setOnClickListener {
            VoiceState.turnScreen = screen.orEmpty()
            android.util.Log.i("KioskVoice", "jarvis button pressed screen=$screen")
            VoiceService.start(context, VoiceService.ACTION_BUTTON_LISTEN)
        }
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_YES
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        main.post(tick)
    }

    override fun onDetachedFromWindow() {
        main.removeCallbacks(tick)
        super.onDetachedFromWindow()
    }

    private fun refresh() {
        val cm = context.getSystemService(ConnectivityManager::class.java)
        val online = cm?.activeNetwork != null
        val next = JarvisStatus.of(VoiceState.mic, VoiceState.stt, VoiceState.chat, VoiceState.tts,
            resting = WakePause.reason() != null, online = online, hasToken = VoiceState.hasToken)
        if (next != status || contentDescription == null) {
            status = next
            contentDescription = words(next) + if (pressable) " แตะเพื่อถามจาร์วิส" else ""
            invalidate()
        }
    }

    override fun onDraw(canvas: Canvas) {
        // A pressable badge is a 48dp touch area (the minimum, UiScale.TOUCH)
        // with the 36dp button drawn in its middle (0.63.0, the layout check).
        val box = if (pressable) minOf(width, height, (JarvisBadges.SIZE * resources.displayMetrics.density).toInt())
                  else minOf(width, height)
        canvas.save()
        canvas.translate((width - box) / 2f, (height - box) / 2f)
        drawBox(canvas, box.toFloat(), box.toFloat())
        canvas.restore()
    }

    private fun drawBox(canvas: Canvas, w: Float, h: Float) {
        if (pressable) {
            // A raised 1995 button in the app's colours; gold while listening, like the home card.
            paint.color = if (status == JarvisStatus.LISTENING) theme.listeningFace else theme.face
            canvas.drawRect(0f, 0f, w, h, paint)
            val b = BEVEL * resources.displayMetrics.density
            paint.color = theme.light; canvas.drawRect(0f, 0f, w, b, paint); canvas.drawRect(0f, 0f, b, h, paint)
            paint.color = theme.dark; canvas.drawRect(0f, h - b, w, h, paint); canvas.drawRect(w - b, 0f, w, h, paint)
        }
        val rows = GLYPHS.getValue(status)
        val cell = minOf(w, h) * GLYPH_SHARE / GRID
        val left = (w - cell * GRID) / 2f; val top = (h - cell * GRID) / 2f
        paint.color = theme.glyph
        for ((y, row) in rows.withIndex()) for ((x, ch) in row.withIndex()) if (ch == '#') {
            canvas.drawRect(left + x * cell, top + y * cell, left + (x + 1) * cell, top + (y + 1) * cell, paint)
        }
    }

    companion object {
        private const val TICK_MS = 300L
        private const val BEVEL = 2
        private const val GRID = 12
        /** How much of the badge the 12×12 glyph fills. */
        private const val GLYPH_SHARE = 0.72f

        fun words(s: JarvisStatus): String = when (s) {
            JarvisStatus.READY -> "จาร์วิสพร้อมฟัง"
            JarvisStatus.LISTENING -> "จาร์วิสกำลังฟัง"
            JarvisStatus.THINKING -> "จาร์วิสกำลังคิด"
            JarvisStatus.SPEAKING -> "จาร์วิสกำลังพูด"
            JarvisStatus.RESTING -> "จาร์วิสพักอยู่ กดปุ่มจาร์วิสเพื่อถาม"
            JarvisStatus.UNAVAILABLE -> "จาร์วิสใช้ไม่ได้ตอนนี้"
        }

        /** Each state its own shape (12×12, '#' drawn). */
        val GLYPHS: Map<JarvisStatus, List<String>> = mapOf(
            JarvisStatus.READY to listOf(
                "....####....", "..##....##..", ".#........#.", ".#........#.", "#..........#", "#..........#",
                "#..........#", "#..........#", ".#........#.", ".#........#.", "..##....##..", "....####...."),
            JarvisStatus.LISTENING to listOf(
                "............", ".#........#.", "#..........#", "#...####...#", "#..######..#", "#..######..#",
                "#..######..#", "#..######..#", "#...####...#", "#..........#", ".#........#.", "............"),
            JarvisStatus.THINKING to listOf(
                "............", "............", "............", "............", "............", ".##..##..##.",
                ".##..##..##.", "............", "............", "............", "............", "............"),
            JarvisStatus.SPEAKING to listOf(
                ".##########.", "#..........#", "#.#######..#", "#..........#", "#.#####....#", "#..........#",
                ".####.#####.", "....#.#.....", "....##......", "....#.......", "............", "............"),
            JarvisStatus.RESTING to listOf(
                "............", "............", "..###..###..", "..###..###..", "..###..###..", "..###..###..",
                "..###..###..", "..###..###..", "..###..###..", "..###..###..", "............", "............"),
            JarvisStatus.UNAVAILABLE to listOf(
                "....####....", "..##....##..", ".##.......#.", ".#.#......#.", "#...#......#", "#....#.....#",
                "#.....#....#", "#......#...#", ".#......#.#.", ".#.......##.", "..##....##..", "....####...."),
        )
    }
}

/**
 * Where the badge goes and what it looks like, per screen — added to every
 * screen when it starts (KioskScreens), so a new screen cannot be left without it.
 */
object JarvisBadges {
    private const val TAG_KEY = "jarvis_badge"

    /** The screen's words for the broker when its Jarvis button is pressed; null = a sign only. */
    fun screen(activity: Activity): String? = when (activity.javaClass.simpleName) {
        "MusicActivity" -> "music"
        "VideoActivity" -> "video"
        "NotesActivity" -> "notes"
        "RadioActivity" -> "radio"
        "TimerActivity" -> "timer"
        "SettingsActivity" -> "panel"
        else -> null
    }

    private fun theme(activity: Activity): JarvisBadge.Theme {
        fun c(id: Int) = androidx.core.content.ContextCompat.getColor(activity, id)
        val gold = c(com.mammonrn.phoneaikiosk.R.color.retro_badge)
        return when (activity.javaClass.simpleName) {
            // Winamp's dark metal and gold (the music player's own colours).
            "MusicActivity" -> JarvisBadge.Theme(c(com.mammonrn.phoneaikiosk.R.color.amp_dark), c(com.mammonrn.phoneaikiosk.R.color.amp_light),
                android.graphics.Color.BLACK, c(com.mammonrn.phoneaikiosk.R.color.amp_gold), c(com.mammonrn.phoneaikiosk.R.color.amp_light))
            // PowerDVD's silver with its teal display.
            "VideoActivity" -> JarvisBadge.Theme(c(com.mammonrn.phoneaikiosk.R.color.dvd_silver), c(com.mammonrn.phoneaikiosk.R.color.dvd_silver_light),
                c(com.mammonrn.phoneaikiosk.R.color.dvd_silver_dark), c(com.mammonrn.phoneaikiosk.R.color.dvd_lcd_bg), gold)
            // Norton's blue and cyan.
            "FilesActivity", "DriveActivity" -> JarvisBadge.Theme(NORTON_BLUE, NORTON_LIGHT, android.graphics.Color.BLACK, NORTON_CYAN, gold)
            // The retro grey button — and, as a sign on a navy title bar, white.
            else -> if (screen(activity) != null)
                JarvisBadge.Theme(c(com.mammonrn.phoneaikiosk.R.color.retro_face), c(com.mammonrn.phoneaikiosk.R.color.retro_light),
                    c(com.mammonrn.phoneaikiosk.R.color.retro_shadow), android.graphics.Color.BLACK, gold)
            else JarvisBadge.Theme(0, 0, 0, c(com.mammonrn.phoneaikiosk.R.color.retro_title_text), gold)
        }
    }

    private const val NORTON_BLUE = 0xFF0000AA.toInt()
    private const val NORTON_LIGHT = 0xFF5555FF.toInt()
    private const val NORTON_CYAN = 0xFF55FFFF.toInt()

    /**
     * Shows or hides the screen's badge (0.63.0, Poom: on a full-screen video it
     * goes with the controls, so nothing sits on the picture while it plays).
     */
    fun show(activity: Activity, shown: Boolean) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        content.findViewWithTag<View>(TAG_KEY)?.visibility = if (shown) View.VISIBLE else View.GONE
    }

    /**
     * Adds the badge to the screen, once.
     *
     * IN AN APP IT IS PART OF THE TITLE BAR (0.63.0): put in the row that holds
     * the window's X, just before the X, so the row makes room for it. Laid
     * OVER the screen instead (0.61.0) it covered whatever sat by the X — the
     * calculator's page squares, found by Poom on the A07. The X is found by
     * where it is (the clickable view in the top-right corner), since every
     * screen draws its own title bar. A screen that rebuilds its title bar
     * (the video player's pages) gets the badge again on its next layout. A
     * screen with no such row keeps the badge over it, and says so in the log
     * (`badge placed … mode=overlay`) for tools/layout/check_layout.py.
     *
     * The home screen keeps its fixed place at the end of the weather window's
     * title bar, which leaves room for it.
     */
    fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG_KEY) != null) return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val home = activity is MainActivity
        val badge = JarvisBadge(activity, theme(activity), if (home) null else screen(activity)).apply { tag = TAG_KEY }
        if (home) {
            val size = dp(HOME_SIZE)
            content.addView(badge, FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
                topMargin = dp(HOME_TOP); marginEnd = dp(HOME_END)
            })
            return
        }
        overlay(content, badge, ::dp)
        val name = activity.javaClass.simpleName
        content.viewTreeObserver.addOnGlobalLayoutListener {
            // Placed already and still on screen: nothing to do. Its row thrown
            // away with a rebuilt title bar: placed again.
            if (badge.parent !== content && badge.isAttachedToWindow) return@addOnGlobalLayoutListener
            val close = findClose(content, dp(CORNER_DP)) ?: return@addOnGlobalLayoutListener
            val row = close.parent as? LinearLayout ?: return@addOnGlobalLayoutListener
            if (row.orientation != LinearLayout.HORIZONTAL || badge.parent === row) return@addOnGlobalLayoutListener
            (badge.parent as? ViewGroup)?.removeView(badge)
            row.addView(badge, row.indexOfChild(close), LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)).apply {
                gravity = Gravity.CENTER_VERTICAL
            })
            android.util.Log.i("KioskUi", "badge placed screen=$name mode=titlebar")
        }
        android.util.Log.i("KioskUi", "badge placed screen=$name mode=overlay-until-layout")
    }

    /** Over the screen by the X's usual place, until (or unless) the title bar takes it. */
    private fun overlay(content: ViewGroup, badge: View, dp: (Int) -> Int) {
        content.addView(badge, FrameLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH), Gravity.TOP or Gravity.END).apply {
            // An app's window: frame 8 + inset 4, then the 48dp title bar with X at its right.
            topMargin = dp(UiScale.FRAME + UiScale.WINDOW_INSET)
            marginEnd = dp(UiScale.FRAME + UiScale.WINDOW_INSET + UiScale.TOUCH)
        })
    }

    /**
     * The window's X: the smallest shown, clickable view whose top-right corner
     * is within [corner] px of the screen's top-right corner (below the frame).
     */
    private fun findClose(content: ViewGroup, corner: Int): View? {
        val width = content.width.takeIf { it > 0 } ?: return null
        val at = IntArray(2)
        content.getLocationInWindow(at)
        val (left0, top0) = at[0] to at[1]
        var best: View? = null
        var bestArea = Int.MAX_VALUE
        fun walk(v: View) {
            if (!v.isShown || v.tag == TAG_KEY) return
            if (v.isClickable && v.width > 0) {
                v.getLocationInWindow(at)
                val right = at[0] - left0 + v.width
                val top = at[1] - top0
                val area = v.width * v.height
                if (width - right <= corner && top <= corner && area < bestArea) { best = v; bestArea = area }
            }
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        walk(content)
        return best
    }

    /** How near the top-right corner the X has to be: frame, inset and a little. */
    private const val CORNER_DP = 40

    /** In an app: 36dp, inside the 48dp title bar. */
    const val SIZE = 36
    /** On the home screen: at the end of the weather window's title bar. */
    const val HOME_SIZE = 28
    const val HOME_TOP = 12
    const val HOME_END = 12
}
