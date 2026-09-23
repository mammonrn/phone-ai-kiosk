package com.mammonrn.phoneaikiosk.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.alarm.AlarmBook
import com.mammonrn.phoneaikiosk.alarm.AlarmStore
import com.mammonrn.phoneaikiosk.ui.RetroType
import com.mammonrn.phoneaikiosk.voice.DashboardState

/**
 * The Control Panel: settings, in a 1995 window, inside the kiosk.
 *
 * WHAT IT IS FOR. Everything the voice cannot comfortably say: which days an
 * alarm rings, renaming one, deleting one, ring once or every day. Opened from
 * the taskbar's panel button; closed by the big "กลับหน้าหลัก" button at the
 * bottom, the title bar's X, or Back — three ways home, because a kiosk has no
 * other way out of a screen.
 *
 * STAYS IN THE KIOSK. It is our own activity, so lock task mode allows it, and
 * closing it starts MainActivity again rather than trusting the task stack:
 * the home screen is singleInstance, so this window lives in a task of its
 * own, and "back to the kiosk" is said explicitly.
 *
 * GROWS BY CATEGORY. The first page is the panel's icons, one per category
 * ([CATEGORIES]). A new kind of setting is a new entry there and a page of its
 * own; nothing else here changes. DESIGN.md, "Control Panel", has the rules.
 *
 * Built in code rather than XML because every page is the same few 1995 parts
 * — a title bar, a raised button, a sunken field — composed differently, and
 * the helpers below keep each part defined once.
 */
class SettingsActivity : Activity() {

    private lateinit var pixel: Typeface
    private lateinit var thai: Typeface
    private lateinit var titleText: TextView
    private lateinit var content: FrameLayout

    /** The page on screen, so Back can go up one level before leaving. */
    private var page = Page.HOME

    private enum class Page { HOME, ALARMS, EDIT, SOURCES }

    private class Category(val icon: Int, val label: Int, val open: (SettingsActivity) -> Unit)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        setContentView(buildWindow())
        hideSystemBars()
        showHome()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        when (page) {
            Page.EDIT -> showAlarms()
            Page.ALARMS, Page.SOURCES -> showHome()
            Page.HOME -> goHome()
        }
    }

    /** Back to the kiosk screen, said explicitly — see the class notes. */
    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------ the window

    private fun buildWindow(): View {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.retro_desktop))
            setPadding(dp(7), dp(7), dp(7), dp(7))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(dp(5), dp(5), dp(5), dp(5))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Title bar: icon, title, and a close button big enough for a thumb.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(6), dp(3), dp(3), dp(3))
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_control_panel) },
                    LinearLayout.LayoutParams(dp(16), dp(16)))
        titleText = TextView(this).apply {
            setTextColor(color(R.color.retro_title_text))
            textSize = 13f
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(6), 0, 0, 0)
            maxLines = 1
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = getString(R.string.settings_close)
            isClickable = true
            setOnClickListener { goHome() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(20), dp(20), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(44), dp(40)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(5) })

        // The way home, always at the bottom, the biggest thing on the page.
        root.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, dp(56)).apply { topMargin = dp(7) })
        return root
    }

    // ---------------------------------------------------------------- home

    private fun showHome() {
        page = Page.HOME
        titleText.text = getString(R.string.settings_title)
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(10), dp(10), dp(10), dp(10))
        }
        for (category in CATEGORIES) {
            grid.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setBackgroundResource(R.drawable.retro_button)
                setPadding(dp(8), dp(10), dp(8), dp(10))
                setOnClickListener { category.open(this@SettingsActivity) }
                addView(ImageView(context).apply { setImageResource(category.icon) },
                        LinearLayout.LayoutParams(dp(48), dp(48)))
                addView(text(getString(category.label), 14f).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(6), 0, 0)
                })
            }, LinearLayout.LayoutParams(dp(112), WRAP).apply { marginEnd = dp(10) })
        }
        setPage(LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(text(getString(R.string.settings_hint), 12f, dim = true).apply {
                setPadding(dp(2), dp(8), dp(2), 0)
            })
        })
    }

    // -------------------------------------------------------------- alarms

    private var confirmingDelete: Int? = null

    private fun showAlarms() {
        page = Page.ALARMS
        titleText.text = getString(R.string.window_alarms)
        val book = AlarmStore.load(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        list.addView(button(getString(R.string.settings_back_to_panel)) { showHome() },
                     LinearLayout.LayoutParams(WRAP, dp(48)))

        if (book.alarms.isEmpty()) {
            // An empty page is an invitation: what to press, or what to say.
            list.addView(text(getString(R.string.alarms_empty), 14f).apply {
                setPadding(dp(4), dp(16), dp(4), dp(16))
            })
        }
        for (alarm in book.alarms) list.addView(alarmRow(book, alarm), LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dp(8) })

        val full = book.alarms.size >= AlarmBook.MAX_ALARMS
        list.addView(button(getString(if (full) R.string.alarms_full else R.string.alarm_add),
                            big = true, enabled = !full) { showEdit(null) },
                     LinearLayout.LayoutParams(MATCH, dp(56)).apply { topMargin = dp(12) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    /**
     * Where each number on the screen comes from, with its licence — the
     * attribution Open-Meteo (CC BY 4.0), CAMS and OpenStreetMap (ODbL) ask
     * for. Read-only: a heading and a line per source.
     */
    private fun showSources() {
        page = Page.SOURCES
        titleText.text = getString(R.string.window_sources)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(button(getString(R.string.settings_back_to_panel)) { showHome() },
                     LinearLayout.LayoutParams(WRAP, dp(48)))
        for (entry in resources.getStringArray(R.array.data_sources)) {
            val (heading, detail) = entry.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            list.addView(label(heading), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(12) })
            list.addView(text(detail, 13f))
        }
        setPage(ScrollView(this).apply { addView(list) })
    }

    /** One alarm: tick box, time, name, when it repeats, and edit / delete. */
    private fun alarmRow(book: AlarmBook, alarm: AlarmBook.Alarm): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The tick box is the on/off switch: a 48dp target with the word beside it.
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = getString(if (alarm.enabled) R.string.alarm_on else R.string.alarm_off)
            setOnClickListener {
                book.setEnabled(alarm.id, !alarm.enabled)
                AlarmStore.save(this@SettingsActivity, book)
                showAlarms()
            }
            addView(ImageView(context).apply {
                setImageResource(if (alarm.enabled) R.drawable.ic_pixel_check_on else R.drawable.ic_pixel_check_off)
            }, LinearLayout.LayoutParams(dp(32), dp(32)))
            addView(text(getString(if (alarm.enabled) R.string.alarm_on else R.string.alarm_off), 13f).apply {
                setPadding(dp(4), 0, dp(10), 0)
            })
        }, LinearLayout.LayoutParams(WRAP, dp(48)))
        top.addView(text(RetroType.pixelify(DashboardState.clock12(alarm.time), pixel), 20f, dim = !alarm.enabled),
                    LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(text(alarm.label, 15f, dim = !alarm.enabled).apply {
            setPadding(dp(10), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(top)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(6), 0, 0)
        }
        if (confirmingDelete == alarm.id) {
            // Deleting cannot be undone, so it asks once, in place.
            bottom.addView(text(getString(R.string.alarm_delete_confirm), 13f), LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(button(getString(R.string.alarm_delete)) {
                book.remove(alarm.id)
                AlarmStore.save(this, book)
                confirmingDelete = null
                showAlarms()
            }, LinearLayout.LayoutParams(dp(72), dp(48)))
            bottom.addView(button(getString(R.string.cancel)) {
                confirmingDelete = null
                showAlarms()
            }, LinearLayout.LayoutParams(dp(80), dp(48)).apply { marginStart = dp(6) })
        } else {
            bottom.addView(text(AlarmBook.repeatText(alarm), 13f, dim = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(button(getString(R.string.edit)) { showEdit(alarm) },
                           LinearLayout.LayoutParams(dp(72), dp(48)))
            bottom.addView(button(getString(R.string.alarm_delete)) {
                confirmingDelete = alarm.id
                showAlarms()
            }, LinearLayout.LayoutParams(dp(64), dp(48)).apply { marginStart = dp(6) })
        }
        box.addView(bottom)
        return box
    }

    // ---------------------------------------------------------------- edit

    private fun showEdit(alarm: AlarmBook.Alarm?) {
        page = Page.EDIT
        titleText.text = getString(if (alarm == null) R.string.alarm_add else R.string.alarm_edit_title)
        var hour = alarm?.hour ?: 6
        var minute = alarm?.minute ?: 0
        var days = alarm?.days ?: AlarmBook.EVERY_DAY
        var mode = when {
            alarm == null -> Repeat.DAILY
            alarm.once -> Repeat.ONCE
            alarm.days == AlarmBook.EVERY_DAY -> Repeat.DAILY
            else -> Repeat.DAYS
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
        }
        val error = text("", 13f).apply {
            setTextColor(color(R.color.retro_bad))
            visibility = View.GONE
        }

        // Time: the big display and two spinners, Win95 style — ▲ / ▼ buttons
        // a thumb can hit, repeating while held.
        form.addView(label(getString(R.string.alarm_time)))
        val shown = text("", 26f).apply { gravity = Gravity.CENTER }
        fun refreshTime() {
            shown.text = RetroType.pixelify(DashboardState.clock12(
                String.format(java.util.Locale.US, "%02d:%02d", hour, minute)), pixel)
        }
        refreshTime()
        form.addView(shown, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(spinner(getString(R.string.alarm_hour),
                up = { hour = (hour + 1) % 24; refreshTime() },
                down = { hour = (hour + 23) % 24; refreshTime() }))
            addView(spinner(getString(R.string.alarm_minute),
                up = { minute = (minute + 1) % 60; refreshTime() },
                down = { minute = (minute + 59) % 60; refreshTime() }),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(24) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })

        // Name.
        form.addView(label(getString(R.string.alarm_name)), LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dp(14) })
        val name = EditText(this).apply {
            setText(alarm?.label.orEmpty())
            hint = getString(R.string.alarm_name_hint)
            filters = arrayOf(InputFilter.LengthFilter(AlarmBook.MAX_LABEL_CHARS))
            typeface = thai
            textSize = 16f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setTextColor(color(R.color.retro_text))
        }
        form.addView(name, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(4) })

        // Repeat: three big choices, then the days when "เลือกวัน".
        form.addView(label(getString(R.string.alarm_repeat)), LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dp(14) })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderRepeat() {
            modeRow.removeAllViews()
            for (choice in Repeat.values()) {
                modeRow.addView(toggle(getString(choice.label), choice == mode) {
                    mode = choice
                    if (choice == Repeat.DAYS && days == AlarmBook.EVERY_DAY) days = AlarmBook.WEEKDAYS
                    renderRepeat()
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(4) })
            }
            dayRow.removeAllViews()
            dayRow.visibility = if (mode == Repeat.DAYS) View.VISIBLE else View.GONE
            // Monday first, the way a Thai week is read.
            for (index in listOf(1, 2, 3, 4, 5, 6, 0)) {
                val bit = 1 shl index
                dayRow.addView(toggle(AlarmBook.DAY_NAMES[index], days and bit != 0) {
                    days = days xor bit
                    renderRepeat()
                }, LinearLayout.LayoutParams(0, dp(48), 1f).apply { marginEnd = dp(3) })
            }
        }
        renderRepeat()
        form.addView(modeRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(4) })
        form.addView(dayRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(6) })
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(8) })

        // Save / cancel.
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.save), big = true) {
                val chosenDays = when (mode) {
                    Repeat.DAILY -> AlarmBook.EVERY_DAY
                    Repeat.DAYS -> days
                    Repeat.ONCE -> days
                }
                if (mode == Repeat.DAYS && chosenDays == 0) {
                    error.text = getString(R.string.alarm_pick_a_day)
                    error.visibility = View.VISIBLE
                    return@button
                }
                val book = AlarmStore.load(this@SettingsActivity)
                val label = name.text.toString()
                val ok = if (alarm == null) {
                    book.add(hour, minute, label, chosenDays, mode == Repeat.ONCE) != null
                } else {
                    book.update(alarm.id, hour, minute, label, chosenDays, mode == Repeat.ONCE)
                }
                if (!ok) {
                    error.text = getString(if (book.timeTaken(hour, minute, alarm?.id))
                        R.string.alarm_time_taken else R.string.alarms_full)
                    error.visibility = View.VISIBLE
                    return@button
                }
                AlarmStore.save(this@SettingsActivity, book)
                showAlarms()
            }, LinearLayout.LayoutParams(0, dp(56), 1f))
            addView(button(getString(R.string.cancel), big = true) { showAlarms() },
                    LinearLayout.LayoutParams(0, dp(56), 1f).apply { marginStart = dp(8) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(14) })

        setPage(ScrollView(this).apply { addView(form) })
    }

    private enum class Repeat(val label: Int) {
        DAILY(R.string.alarm_daily), DAYS(R.string.alarm_some_days), ONCE(R.string.alarm_once)
    }

    // ------------------------------------------------------------- the parts

    private fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    private fun label(value: String) = text(value, 13f).apply {
        typeface = Typeface.create(thai, Typeface.BOLD)
    }

    /** A raised 1995 button that sinks while pressed. */
    private fun button(value: String, big: Boolean = false, enabled: Boolean = true,
                       onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = if (big) 16f else 14f
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(if (enabled) R.color.retro_text else R.color.retro_dim))
        setBackgroundResource(R.drawable.retro_button)
        setPadding(dp(12), 0, dp(12), 0)
        isClickable = enabled
        isEnabled = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    /** A button that stays down while chosen: the day and repeat choices. */
    private fun toggle(value: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = 14f
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(color(if (on) R.color.retro_title_text else R.color.retro_text))
        if (on) setBackgroundColor(color(R.color.retro_title))
        else setBackgroundResource(R.drawable.retro_button)
        contentDescription = value + if (on) " (เลือกอยู่)" else ""
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** "ชั่วโมง ▲ ▼": two 48dp buttons that repeat while held. */
    private fun spinner(name: String, up: () -> Unit, down: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(text(name, 13f).apply { gravity = Gravity.CENTER })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(repeating("▲", up), LinearLayout.LayoutParams(dp(64), dp(48)))
            addView(repeating("▼", down), LinearLayout.LayoutParams(dp(64), dp(48)).apply { marginStart = dp(6) })
        })
    }

    private val repeatHandler = Handler(Looper.getMainLooper())

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun repeating(symbol: String, step: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = 18f
        gravity = Gravity.CENTER
        setTextColor(color(R.color.retro_text))
        setBackgroundResource(R.drawable.retro_button)
        isClickable = true
        val again = object : Runnable {
            override fun run() {
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

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REPEAT_START_MS = 450L
        private const val REPEAT_MS = 110L

        /**
         * The panel's categories, in order. ADD A SETTING HERE: an icon, a
         * name, and the page it opens (DESIGN.md, "Control Panel").
         */
        private val CATEGORIES = listOf(
            Category(R.drawable.ic_pixel_alarm_clock, R.string.window_alarms) { it.showAlarms() },
            Category(R.drawable.ic_pixel_sources, R.string.window_sources) { it.showSources() },
        )
    }
}
