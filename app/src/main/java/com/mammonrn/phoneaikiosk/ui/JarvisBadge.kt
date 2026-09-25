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
        val w = width.toFloat(); val h = height.toFloat()
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

    /** Adds the badge over the screen, top right, once. */
    fun attach(activity: Activity) {
        val content = activity.findViewById<ViewGroup>(android.R.id.content) ?: return
        if (content.findViewWithTag<View>(TAG_KEY) != null) return
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d).toInt()
        val home = activity is MainActivity
        val size = dp(if (home) HOME_SIZE else SIZE)
        val badge = JarvisBadge(activity, theme(activity), if (home) null else screen(activity)).apply { tag = TAG_KEY }
        val lp = FrameLayout.LayoutParams(size, size, Gravity.TOP or Gravity.END).apply {
            if (home) { topMargin = dp(HOME_TOP); marginEnd = dp(HOME_END) }
            // An app's window: frame 8 + inset 4, then the 48dp title bar with X at its right.
            else { topMargin = dp(UiScale.FRAME + UiScale.WINDOW_INSET + (UiScale.TOUCH - SIZE) / 2)
                   marginEnd = dp(UiScale.FRAME + UiScale.WINDOW_INSET + UiScale.TOUCH + UiScale.SPACE_XS) }
        }
        content.addView(badge, lp)
    }

    /** In an app: 36dp, inside the 48dp title bar. */
    const val SIZE = 36
    /** On the home screen: at the end of the weather window's title bar. */
    const val HOME_SIZE = 28
    const val HOME_TOP = 12
    const val HOME_END = 12
}
