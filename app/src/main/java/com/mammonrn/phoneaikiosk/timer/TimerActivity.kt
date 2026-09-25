package com.mammonrn.phoneaikiosk.timer

import android.annotation.SuppressLint
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.text.TextUtils
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.WRAP
import com.mammonrn.phoneaikiosk.ui.ToolWindow
import com.mammonrn.phoneaikiosk.ui.UiScale
import com.mammonrn.phoneaikiosk.voice.VoiceService
import com.mammonrn.phoneaikiosk.voice.VoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * "เวลา" (0.63.0, Poom): นาฬิกาปลุก · จับเวลา · นับถอยหลัง — one app of three pages
 * turned sideways by the calculator's own [SlideDeck] (■ □ □ in the title bar),
 * one icon in the Control Panel's tools folder. Was the stopwatch and countdown
 * of 0.62.0 (DESIGN.md 5ณ); the alarms were a page of the Control Panel.
 *
 * THE ALARMS STAY WHERE THEY LIVE. The page lists them, switches them on and
 * off and deletes them (asked once) through AlarmStore as before; adding and
 * changing one opens the Control Panel's own alarm editor, the one that has
 * always set them, for that alarm alone — and closing it comes back here. The
 * alarms set, their ringing with the screen off and every voice command are
 * the same code as before.
 *
 * THIS SCREEN ONLY DRAWS. The counting is [TimerClock]'s, the process's: X,
 * "กลับหน้าหลัก", Back and Hey Jarvis (KioskScreens closes every screen,
 * DESIGN 11) close the screen and nothing else — the stopwatch keeps
 * counting, the countdown keeps counting and rings at its end, with the
 * screen off too. When it ends, the voice service rings the alarm tone and
 * brings this screen up on its "หมดเวลา" page ([ringIntent]).
 *
 * The one thing leaving does: a countdown that is RINGING when the screen is
 * closed counts as seen — the sound stops, as the stop button would.
 */
class TimerActivity : Activity(), com.mammonrn.phoneaikiosk.calc.SlideHost {

    /** The three pages, in this order. */
    enum class Slide(val title: Int) { ALARMS(R.string.time_slide_alarms), STOPWATCH(R.string.time_slide_stopwatch), COUNTDOWN(R.string.time_slide_countdown) }

    override val activity: Activity get() = this
    override val pageDots: LinearLayout get() = frame.dots
    override fun setPage(view: View) = frame.setPage(view)
    override fun dp(value: Int): Int = r.dp(value)
    override fun color(id: Int): Int = r.color(id)

    private lateinit var deck: com.mammonrn.phoneaikiosk.calc.SlideDeck<Slide>
    /** Asked once to delete, in place, like the Control Panel's alarm page. */
    private var confirmingDelete: Int? = null

    private lateinit var r: Retro
    private lateinit var pixel: Typeface
    private lateinit var frame: ToolWindow

    private val handler = Handler(Looper.getMainLooper())

    // The live parts, refreshed ten times a second without rebuilding the page.
    private var digits: TextView? = null
    private var stateWord: TextView? = null
    private var endsAt: TextView? = null
    private var otherLine: TextView? = null
    private var drawnKey = ""
    private var drawnSound: Boolean? = null
    /** The setter's three numbers, by wheel, updated in place. */
    private val wheelValues = HashMap<Countdown.Wheel, TextView>()
    /** The quick lengths' buttons, by minutes, marked in place. */
    private val presetButtons = LinkedHashMap<Int, TextView>()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        r = Retro(this, thai)
        TimerClock.load(this)
        frame = ToolWindow(this, r, R.drawable.ic_pixel_alarm_clock, onClose = { closeApp() }, onHome = { goHome() })
        setContentView(frame.root)
        val first = when (intent?.getStringExtra(EXTRA_SLIDE)) {
            "alarms" -> Slide.ALARMS
            "stopwatch" -> Slide.STOPWATCH
            "countdown" -> Slide.COUNTDOWN
            else -> if (TimerClock.showCountdown) Slide.COUNTDOWN else Slide.STOPWATCH
        }
        deck = com.mammonrn.phoneaikiosk.calc.SlideDeck(this, { Slide.entries.toList() }, { getString(it.title) }, { slidePage(it) }, first)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { finish() }
        }
        ToolWindow.hideSystemBars(this)
        takeRing(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        takeRing(intent)
    }

    override fun onStart() {
        super.onStart()
        drawnKey = ""
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        ToolWindow.hideSystemBars(this)
    }

    override fun onPause() {
        // Leaving for good while it rings = seen: the sound stops (X, Back, home, Jarvis).
        if (isFinishing) stopRinging("screen-closed")
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = finish()

    /** The close button: back where this screen was opened from (ui/Origin). */
    private fun closeApp() = Origin.close(this, "timer-close")

    private fun goHome() = ToolWindow.goHome(this, "timer-home")

    /**
     * Brought up by the countdown's end. When the voice service was not
     * running, nothing is ringing yet: it is started from here, the
     * foreground, which a microphone service needs (VoiceService.ringTimer).
     */
    private fun takeRing(intent: Intent?) {
        if (intent?.getBooleanExtra(EXTRA_RING, false) != true) return
        intent.removeExtra(EXTRA_RING)
        TimerClock.showMode(this, countdownMode = true)
        if (::deck.isInitialized) deck.show(Slide.COUNTDOWN)
        if (TimerClock.countdown.state == Countdown.State.RINGING && VoiceState.alarmRinging.isEmpty()) {
            VoiceService.start(this, VoiceService.ACTION_TIMER_RING)
        }
    }

    private fun stopRinging(reason: String) {
        if (TimerClock.countdown.state != Countdown.State.RINGING) return
        TimerClock.acknowledge(this)
        // The same stop as the home card's button: the tone, the deaf wake word, the card.
        if (VoiceState.alarmRinging.isNotEmpty()) VoiceService.start(this, VoiceService.ACTION_ALARM_STOP)
        android.util.Log.i(TimerClock.TAG, "ring stopped from the screen reason=$reason")
    }

    // ------------------------------------------------------------ drawing

    private val tick = object : Runnable {
        override fun run() {
            // The screen in front rings at 00:00 itself; the alarm then finds it done.
            TimerClock.ringIfDue(this@TimerActivity, "screen")
            // Rebuilt only when what is ON the page changes; a wheel step changes
            // a number in place, so a ▲ held down is never taken from under the finger.
            if (layoutKey() != drawnKey) draw() else refresh()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** What decides which parts the page has. Anything else is a number, refreshed in place. */
    private fun layoutKey(): String {
        val cd = TimerClock.countdown
        val sw = TimerClock.stopwatch
        return "${deck.current}|${TimerClock.showCountdown}|${cd.state}|${cd.canStart()}|" +
            "${sw.running}|${sw.isClear}|${sw.laps.size}|${VoiceState.alarmRinging.isNotEmpty()}|" +
            "${VoiceState.alarmsVersion}|$confirmingDelete"
    }

    private fun draw() {
        drawnKey = layoutKey()
        drawnSound = VoiceState.alarmRinging.isNotEmpty()
        digits = null; stateWord = null; endsAt = null; otherLine = null
        wheelValues.clear()
        presetButtons.clear()
        frame.title.text = getString(R.string.window_time)
        val ringing = TimerClock.countdown.state == Countdown.State.RINGING
        // A ringing end keeps the screen lit until someone sees it.
        if (ringing && drawnSound == true) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        if (ringing) {
            // The one thing to do while it rings: no pages to turn.
            frame.dots.visibility = View.GONE
            val page = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
            drawRinging(page)
            frame.setPage(ScrollView(this).apply { addView(page) })
        } else {
            deck.open()
        }
        refresh()
    }

    /** One of the three pages, built by [deck] (a turn builds the next one). */
    private fun slidePage(slide: Slide): View {
        digits = null; stateWord = null; endsAt = null; otherLine = null
        wheelValues.clear()
        presetButtons.clear()
        val page = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
        page.addView(r.bold(getString(slide.title), UiScale.TEXT_HEADING), LinearLayout.LayoutParams(MATCH, WRAP))
        if (slide == Slide.ALARMS) {
            drawAlarms(page)
        } else {
            // Which of the two the voice and the home card read as "shown" (TimerClock).
            val countdown = slide == Slide.COUNTDOWN
            if (TimerClock.showCountdown != countdown) TimerClock.showMode(this, countdown)
            otherLine = r.text("", UiScale.TEXT_NOTE, dim = true).apply { visibility = View.GONE }
            page.addView(otherLine, LinearLayout.LayoutParams(MATCH, WRAP))
            page.addView(lcd(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            if (countdown) drawCountdown(page) else drawStopwatch(page)
        }
        drawnKey = layoutKey()
        return ScrollView(this).apply { addView(page) }
    }

    // ------------------------------------------------------------ the alarms (0.63.0)

    /** The alarms as the Control Panel listed them: on/off, time, name, repeat; change, delete, add. */
    private fun drawAlarms(page: LinearLayout) {
        val book = com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(this)
        if (book.alarms.isEmpty()) {
            page.addView(r.text(getString(R.string.alarms_empty), UiScale.TEXT_BASE),
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        }
        for (alarm in book.alarms) page.addView(alarmRow(book, alarm),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        val full = book.alarms.size >= com.mammonrn.phoneaikiosk.alarm.AlarmBook.MAX_ALARMS
        page.addView(r.button(getString(if (full) R.string.alarms_full else R.string.alarm_add), big = true, enabled = !full) {
            editAlarm(-1)
        }, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
    }

    private fun alarmRow(book: com.mammonrn.phoneaikiosk.alarm.AlarmBook,
                         alarm: com.mammonrn.phoneaikiosk.alarm.AlarmBook.Alarm): View = r.column().apply {
        setBackgroundResource(R.drawable.retro_sunken)
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        val top = r.row().apply { gravity = Gravity.CENTER_VERTICAL }
        // The tick box is the on/off switch: a 48dp target with the word beside it.
        top.addView(r.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = getString(if (alarm.enabled) R.string.alarm_on else R.string.alarm_off)
            setOnClickListener {
                book.setEnabled(alarm.id, !alarm.enabled)
                com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(this@TimerActivity, book)
                drawnKey = ""
            }
            addView(ImageView(context).apply {
                setImageResource(if (alarm.enabled) R.drawable.ic_pixel_check_on else R.drawable.ic_pixel_check_off)
            }, LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
            addView(r.text(getString(if (alarm.enabled) R.string.alarm_on else R.string.alarm_off), UiScale.TEXT_NOTE).apply {
                setPadding(r.dp(UiScale.SPACE_XS), 0, r.dp(UiScale.SPACE_S), 0)
            })
        }, LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
        top.addView(r.text(com.mammonrn.phoneaikiosk.ui.RetroType.pixelify(
            com.mammonrn.phoneaikiosk.voice.DashboardState.clock12(alarm.time), pixel), UiScale.TEXT_VALUE, dim = !alarm.enabled),
            LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(r.text(alarm.label, UiScale.TEXT_ITEM, dim = !alarm.enabled).apply {
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(top)
        val bottom = r.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, r.dp(UiScale.SPACE_S), 0, 0)
        }
        if (confirmingDelete == alarm.id) {
            // Deleting cannot be undone, so it asks once, in place.
            bottom.addView(r.text(getString(R.string.alarm_delete_confirm), UiScale.TEXT_NOTE), LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(r.button(getString(R.string.alarm_delete)) {
                book.remove(alarm.id)
                com.mammonrn.phoneaikiosk.alarm.AlarmStore.save(this@TimerActivity, book)
                confirmingDelete = null
            }, LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
            bottom.addView(r.button(getString(R.string.cancel)) { confirmingDelete = null },
                LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_S) })
        } else {
            bottom.addView(r.text(com.mammonrn.phoneaikiosk.alarm.AlarmBook.repeatText(alarm), UiScale.TEXT_NOTE, dim = true),
                LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(r.button(getString(R.string.edit)) { editAlarm(alarm.id) },
                LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
            bottom.addView(r.button(getString(R.string.alarm_delete)) { confirmingDelete = alarm.id },
                LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_S) })
        }
        addView(bottom)
    }

    /** The Control Panel's alarm editor, for this alarm alone (-1: a new one); it comes back here. */
    private fun editAlarm(id: Int) {
        startActivity(Intent(this, com.mammonrn.phoneaikiosk.settings.SettingsActivity::class.java)
            .putExtra(com.mammonrn.phoneaikiosk.settings.SettingsActivity.EXTRA_EDIT_ALARM, id))
    }

    /** The read-out: green Press Start 2P digits on black, the state in words under them. */
    private fun lcd(): View = r.column().apply {
        setBackgroundColor(r.color(R.color.retro_dark))
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_M))
        digits = TextView(context).apply {
            typeface = pixel
            textSize = UiScale.LCD_DIGITS
            setTextColor(r.color(R.color.retro_lcd))
            gravity = Gravity.CENTER
            maxLines = 1
        }
        addView(digits, LinearLayout.LayoutParams(MATCH, WRAP))
        stateWord = r.bold("", UiScale.TEXT_HEADING).apply {
            setTextColor(r.color(R.color.retro_lcd))
            gravity = Gravity.CENTER
        }
        addView(stateWord, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        endsAt = r.text("", UiScale.TEXT_NOTE).apply {
            setTextColor(r.color(R.color.retro_lcd_dim))
            gravity = Gravity.CENTER
            visibility = View.GONE
        }
        addView(endsAt, LinearLayout.LayoutParams(MATCH, WRAP))
    }

    /** The numbers, the state word and the other mode's line: ten times a second, nothing rebuilt. */
    private fun refresh() {
        val now = SystemClock.elapsedRealtime()
        val cd = TimerClock.countdown
        val sw = TimerClock.stopwatch
        val shown: String
        val word: Int
        if (TimerClock.showCountdown) {
            shown = TimerText.countdown(cd.remaining(now))
            word = when (cd.state) {
                Countdown.State.IDLE -> R.string.timer_cd_set
                Countdown.State.RUNNING -> R.string.timer_cd_running
                Countdown.State.PAUSED -> R.string.timer_cd_paused
                Countdown.State.RINGING -> R.string.timer_ring_title
            }
            endsAt?.let {
                val running = cd.state == Countdown.State.RUNNING
                // Poom (2026-09-25): an end that came while the phone was off more than an hour
                // ago is not rung; this line says when it was due instead.
                val missed = cd.state == Countdown.State.IDLE && cd.missedWall > 0
                it.visibility = if (running || missed) View.VISIBLE else View.GONE
                if (running) it.text = getString(R.string.timer_cd_ends_at, clock(System.currentTimeMillis() + cd.remaining(now)))
                else if (missed) it.text = getString(R.string.timer_cd_missed,
                    java.text.SimpleDateFormat("d MMM HH:mm", java.util.Locale("th", "TH")).format(java.util.Date(cd.missedWall)))
            }
            digits?.contentDescription = getString(R.string.timer_digits_left, TimerText.thai(cd.remaining(now)))
        } else {
            shown = TimerText.stopwatch(sw.elapsed(now))
            word = when {
                sw.running -> R.string.timer_sw_running
                sw.isClear -> R.string.timer_sw_ready
                else -> R.string.timer_sw_paused
            }
            digits?.contentDescription = getString(R.string.timer_digits_counted, TimerText.thai(sw.elapsed(now)))
        }
        digits?.let { if (it.text.toString() != shown) it.text = shown }
        if (wheelValues.isNotEmpty()) {
            val parts = Countdown.split(cd.setMs)
            wheelValues[Countdown.Wheel.HOURS]?.text = "%02d".format(parts.hours)
            wheelValues[Countdown.Wheel.MINUTES]?.text = "%02d".format(parts.minutes)
            wheelValues[Countdown.Wheel.SECONDS]?.text = "%02d".format(parts.seconds)
        }
        // The quick length that matches the set one is marked by shape ("[5]"), not colour.
        for ((minutes, button) in presetButtons) {
            val chosen = cd.setMs == minutes * 60_000L
            val label = if (chosen) "[$minutes]" else minutes.toString()
            if (button.text.toString() != label) {
                button.text = label
                button.contentDescription = getString(R.string.timer_preset_desc, minutes) + if (chosen) " (เลือกอยู่)" else ""
            }
        }
        stateWord?.setText(word)
        // The mode not shown, when it is counting: nothing is hidden by switching.
        otherLine?.let { line ->
            val text = when {
                TimerClock.showCountdown && sw.running ->
                    getString(R.string.timer_other_stopwatch, TimerText.stopwatch(sw.elapsed(now)))
                !TimerClock.showCountdown && cd.state == Countdown.State.RUNNING ->
                    getString(R.string.timer_other_countdown, TimerText.countdown(cd.remaining(now)))
                !TimerClock.showCountdown && cd.state == Countdown.State.PAUSED ->
                    getString(R.string.timer_other_countdown_paused, TimerText.countdown(cd.remaining(now)))
                else -> null
            }
            line.visibility = if (text == null) View.GONE else View.VISIBLE
            if (text != null && line.text.toString() != text) line.text = text
        }
    }

    // ------------------------------------------------------------ the stopwatch

    private fun drawStopwatch(page: LinearLayout) {
        val sw = TimerClock.stopwatch
        val buttons = r.row()
        fun add(word: String, enabled: Boolean = true, action: () -> Unit) {
            buttons.addView(r.button(word, big = true, enabled = enabled) { action() },
                LinearLayout.LayoutParams(0, r.dp(UiScale.PRIMARY), 1f).apply {
                    if (buttons.childCount > 0) marginStart = r.dp(UiScale.SPACE_S)
                })
        }
        when {
            sw.running -> {
                add(getString(if (sw.laps.size >= Stopwatch.MAX_LAPS) R.string.timer_laps_full else R.string.timer_lap),
                    enabled = sw.canLap()) { TimerClock.lap(this) }
                add(getString(R.string.timer_pause)) { TimerClock.pauseStopwatch(this) }
            }
            sw.isClear -> add(getString(R.string.timer_start)) { TimerClock.startStopwatch(this) }
            else -> {
                add(getString(R.string.timer_resume)) { TimerClock.startStopwatch(this) }
                add(getString(R.string.timer_reset)) { TimerClock.resetStopwatch(this) }
            }
        }
        page.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        page.addView(r.text(getString(R.string.timer_note_stopwatch), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })

        // The laps, newest first.
        page.addView(r.label(getString(R.string.timer_laps_title, sw.laps.size)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
        val rows = sw.lapRows()
        if (rows.isEmpty()) {
            page.addView(r.text(getString(R.string.timer_laps_empty), UiScale.TEXT_BASE, dim = true),
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        for (row in rows) page.addView(lapRow(row), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
    }

    /** "รอบ 3   00:12.3   รวม 00:45.6   เร็วสุด": the mark is a word, never only a colour. */
    private fun lapRow(row: Stopwatch.LapRow): View = r.row().apply {
        setBackgroundResource(R.drawable.retro_field)
        minimumHeight = r.dp(UiScale.TOUCH)
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_XS))
        addView(r.text(getString(R.string.timer_lap_row, row.number), UiScale.TEXT_BASE),
                LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(r.bold(TimerText.stopwatch(row.split), UiScale.TEXT_ITEM), LinearLayout.LayoutParams(0, WRAP, 1.2f))
        addView(r.text(getString(R.string.timer_lap_total, TimerText.stopwatch(row.total)), UiScale.TEXT_NOTE, dim = true),
                LinearLayout.LayoutParams(0, WRAP, 1.6f))
        val mark = when (row.mark) {
            Stopwatch.Mark.FASTEST -> getString(R.string.timer_lap_fastest)
            Stopwatch.Mark.SLOWEST -> getString(R.string.timer_lap_slowest)
            Stopwatch.Mark.NONE -> ""
        }
        addView(r.bold(mark, UiScale.TEXT_NOTE).apply { gravity = Gravity.END }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    // ------------------------------------------------------------ the countdown

    private fun drawCountdown(page: LinearLayout) {
        val cd = TimerClock.countdown
        if (cd.state == Countdown.State.IDLE) drawSetter(page)
        val buttons = r.row()
        fun add(word: String, enabled: Boolean = true, action: () -> Unit) {
            buttons.addView(r.button(word, big = true, enabled = enabled) { action() },
                LinearLayout.LayoutParams(0, r.dp(UiScale.PRIMARY), 1f).apply {
                    if (buttons.childCount > 0) marginStart = r.dp(UiScale.SPACE_S)
                })
        }
        when (cd.state) {
            Countdown.State.IDLE -> add(getString(R.string.timer_cd_start), enabled = cd.canStart()) { TimerClock.startCountdown(this) }
            Countdown.State.RUNNING -> {
                add(getString(R.string.timer_pause)) { TimerClock.pauseCountdown(this) }
                add(getString(R.string.timer_cd_cancel)) { TimerClock.resetCountdown(this) }
            }
            Countdown.State.PAUSED -> {
                add(getString(R.string.timer_cd_resume)) { TimerClock.startCountdown(this) }
                add(getString(R.string.timer_cd_cancel)) { TimerClock.resetCountdown(this) }
            }
            Countdown.State.RINGING -> Unit
        }
        page.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        if (cd.state == Countdown.State.IDLE && !cd.canStart()) {
            page.addView(r.text(getString(R.string.timer_cd_zero), UiScale.TEXT_NOTE).apply { setTextColor(r.color(R.color.retro_bad)) },
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        page.addView(r.text(getString(R.string.timer_note_countdown), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    /** Hours, minutes, seconds: ▲ over the value over ▼, repeating while held; then the quick lengths. */
    private fun drawSetter(page: LinearLayout) {
        val parts = Countdown.split(TimerClock.countdown.setMs)
        val wheels = r.row().apply { gravity = Gravity.CENTER }
        for ((i, wheel) in Countdown.Wheel.values().withIndex()) {
            val (name, value) = when (wheel) {
                Countdown.Wheel.HOURS -> getString(R.string.timer_hours) to parts.hours
                Countdown.Wheel.MINUTES -> getString(R.string.timer_minutes) to parts.minutes
                Countdown.Wheel.SECONDS -> getString(R.string.timer_seconds) to parts.seconds
            }
            wheels.addView(r.column().apply {
                gravity = Gravity.CENTER_HORIZONTAL
                addView(r.text(name, UiScale.TEXT_NOTE).apply { gravity = Gravity.CENTER })
                addView(repeating("▲", getString(R.string.timer_up_desc, name)) { TimerClock.step(this@TimerActivity, wheel, up = true) },
                        LinearLayout.LayoutParams(r.dp(UiScale.SYMBOL_W), r.dp(UiScale.TOUCH)))
                addView(r.bold("%02d".format(value), UiScale.TEXT_VALUE).apply {
                    gravity = Gravity.CENTER
                    wheelValues[wheel] = this
                }, LinearLayout.LayoutParams(MATCH, WRAP))
                addView(repeating("▼", getString(R.string.timer_down_desc, name)) { TimerClock.step(this@TimerActivity, wheel, up = false) },
                        LinearLayout.LayoutParams(r.dp(UiScale.SYMBOL_W), r.dp(UiScale.TOUCH)))
            }, LinearLayout.LayoutParams(WRAP, WRAP).apply { if (i > 0) marginStart = r.dp(UiScale.SPACE_L) })
        }
        page.addView(wheels, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })

        page.addView(r.label(getString(R.string.timer_presets)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        val presets = r.row()
        for ((i, minutes) in Countdown.PRESETS_MIN.withIndex()) {
            presets.addView(r.button(minutes.toString()) { TimerClock.setLength(this, minutes * 60_000L) }.also {
                presetButtons[minutes] = it
                it.contentDescription = getString(R.string.timer_preset_desc, minutes)
            }, LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f).apply { if (i > 0) marginStart = r.dp(UiScale.SPACE_XS) })
        }
        page.addView(presets, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
    }

    /** The ringing end: what ended, in words, and the one thing to do. */
    private fun drawRinging(page: LinearLayout) {
        val sound = VoiceState.alarmRinging.isNotEmpty()
        page.addView(r.column().apply {
            setBackgroundColor(r.color(R.color.retro_dark))
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_L), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_L))
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_bell) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_XL), r.dp(UiScale.ICON_XL)))
            addView(TextView(context).apply {
                typeface = pixel
                textSize = UiScale.LCD_DIGITS
                setTextColor(r.color(R.color.retro_lcd))
                gravity = Gravity.CENTER
                text = TimerText.countdown(0)
                contentDescription = getString(R.string.timer_ring_title)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            addView(r.bold(getString(R.string.timer_ring_title), UiScale.TEXT_DISPLAY).apply {
                setTextColor(r.color(R.color.retro_lcd))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            addView(r.text(getString(R.string.timer_ring_detail, TimerText.thai(TimerClock.countdown.setMs)), UiScale.TEXT_BASE).apply {
                setTextColor(r.color(R.color.retro_lcd))
                gravity = Gravity.CENTER
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        page.addView(r.button(getString(if (sound) R.string.timer_ring_stop else R.string.timer_ring_ok), big = true) {
            stopRinging("button")
        }, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.ICON_BUTTON)).apply { topMargin = r.dp(UiScale.SPACE_M) })
        page.addView(r.text(getString(if (sound) R.string.timer_ring_note else R.string.timer_ring_quiet), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    // ------------------------------------------------------------ parts

    private val repeatHandler = Handler(Looper.getMainLooper())

    /** ▲ / ▼, 64 × 48dp, repeating while held — the alarm page's spinner buttons. */
    @SuppressLint("ClickableViewAccessibility")
    private fun repeating(symbol: String, description: String, step: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = UiScale.TEXT_VALUE
        gravity = Gravity.CENTER
        setTextColor(r.color(R.color.retro_text))
        setBackgroundResource(R.drawable.retro_button)
        contentDescription = description
        isClickable = true
        val again = object : Runnable {
            override fun run() {
                // A page rebuilt under the finger (the length reached 0) ends the repeat.
                if (!isAttachedToWindow) return
                step()
                repeatHandler.postDelayed(this, REPEAT_MS)
            }
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    step()
                    repeatHandler.postDelayed(again, REPEAT_START_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    repeatHandler.removeCallbacks(again)
                }
            }
            true
        }
    }

    /** A wall-clock time as the taskbar shows it: "2:35 PM". */
    private fun clock(wall: Long): String = SimpleDateFormat("h:mm a", Locale.US).format(Date(wall))

    companion object {
        private const val TICK_MS = 100L
        private const val REPEAT_START_MS = 450L
        private const val REPEAT_MS = 110L
        const val EXTRA_RING = "com.mammonrn.phoneaikiosk.TIMER_RING"
        /** The page to open on: "alarms", "stopwatch" or "countdown". */
        const val EXTRA_SLIDE = "com.mammonrn.phoneaikiosk.TIME_SLIDE"

        /** This screen on its "หมดเวลา" page, over whatever is in front. */
        /** The screen to the front with no sound: a countdown missed while the phone was off. */
        fun showIntent(context: Context): Intent = Intent(context, TimerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)

        fun ringIntent(context: Context): Intent = Intent(context, TimerActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)
            .putExtra(EXTRA_RING, true)
    }
}
