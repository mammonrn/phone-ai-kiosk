package com.mammonrn.phoneaikiosk.calc

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
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
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The engineering calculator (0.52.0, Poom), opened from the Control Panel.
 *
 * FOUR TABS on one window: "คำนวณ" (the keypad), "ไฟฟ้า" (Ohm's law, series
 * and parallel, the resistor colour code, AC, wire size — [ElectricalPages]),
 * "โซลาร์" (seven solar tools on slides — [SolarPages], 0.62.0) and "ประวัติ"
 * (the last [HISTORY_MAX] results, newest first). A screen of
 * this app, so lock task allows it and KioskScreens closes it on Hey Jarvis;
 * it opens nothing else, asks for no permission and never touches the mic or
 * the speaker.
 *
 * THE KEYPAD types an expression that [CalcEngine] evaluates on "=". The
 * read-out is Press Start 2P, digits and symbols only (DESIGN.md 5จ); errors
 * are said in Thai words, never as NaN. Memory, the angle unit and the
 * history survive a restart (SharedPreferences, [PREFS]).
 */
class CalculatorActivity : Activity() {

    internal lateinit var thai: Typeface
    internal lateinit var pixel: Typeface
    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()

    internal enum class Tab { KEYPAD, ELECTRICAL, SOLAR, HISTORY }
    internal var tab = Tab.KEYPAD

    internal val electrical by lazy { ElectricalPages(this) }
    internal val solar by lazy { SolarPages(this) }

    /** The solar slides' squares (■ □), in the title bar beside the title: they cost no height (DESIGN.md 5จ). */
    internal lateinit var pageDots: LinearLayout

    // ------------------------------------------------------------ state

    private val input = StringBuilder()
    private var ans = 0.0
    private var justEvaluated = false
    private var degrees = true
    private var memory = 0.0
    private val history = ArrayList<Pair<String, String>>()     // expression, result
    private var confirmingClear = false

    private var exprView: TextView? = null
    private var resultView: TextView? = null
    private var statusView: TextView? = null
    private var angleKey: TextView? = null
    private var shownResult = ""
    private var shownError: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        load()
        setContentView(buildWindow())
        hideSystemBars()
        show(Tab.KEYPAD)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
    }

    override fun onPause() {
        super.onPause()
        save()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** Back: an electrical sub-page → the electrical list; otherwise → the Control Panel. */
    private fun goBack() {
        if (tab == Tab.ELECTRICAL && electrical.back()) return
        finish()
    }

    /** The close button: back where this screen was opened from (ui/Origin, Poom 2026-09-25). */
    private fun closeApp() {
        com.mammonrn.phoneaikiosk.ui.Origin.close(this, "calculator-close")
    }

    private fun goHome() {
        KioskScreens.leaveAllButHome("calculator-home")
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
            setPadding(dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_calculator) },
                    LinearLayout.LayoutParams(dp(UiScale.ICON_S), dp(UiScale.ICON_S)))
        bar.addView(TextView(this).apply {
            text = getString(R.string.window_calculator)
            setTextColor(color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = com.mammonrn.phoneaikiosk.ui.Origin.closeWords(this@CalculatorActivity)
            isClickable = true
            setOnClickListener { closeApp() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        pageDots = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
            visibility = View.GONE
        }
        bar.addView(pageDots, bar.childCount - 1, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, t) in Tab.entries.withIndex()) {
            val label = getString(when (t) {
                Tab.KEYPAD -> R.string.calc_tab_keypad
                Tab.ELECTRICAL -> R.string.calc_tab_electrical
                Tab.SOLAR -> R.string.calc_tab_solar
                Tab.HISTORY -> R.string.calc_tab_history
            })
            val view = TextView(this).apply {
                text = label
                textSize = UiScale.TEXT_BASE
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { show(t) }
            }
            tabs.add(view)
            tabRow.addView(view, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { if (i > 0) marginStart = dp(UiScale.SPACE_S) })
        }
        window.addView(tabRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.WINDOW_INSET) })

        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_S) })
        root.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        return root
    }

    internal fun show(next: Tab) {
        if (next != Tab.HISTORY) confirmingClear = false
        tab = next
        pageDots.visibility = View.GONE           // the solar tab shows its squares again
        for ((i, view) in tabs.withIndex()) {
            val on = Tab.entries[i] == next
            view.typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
            view.setTextColor(color(if (on) R.color.retro_title_text else R.color.retro_text))
            if (on) view.setBackgroundColor(color(R.color.retro_title)) else view.setBackgroundResource(R.drawable.retro_button)
            view.contentDescription = view.text.toString() + if (on) " (เลือกอยู่)" else ""
        }
        when (next) {
            Tab.KEYPAD -> showKeypad()
            Tab.ELECTRICAL -> electrical.open()
            Tab.SOLAR -> solar.open()
            Tab.HISTORY -> showHistory()
        }
    }

    internal fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------ the keypad

    /** One key: its label, and what it does. */
    private class Key(val label: String, val kind: Kind, val insert: String = label, val wide: Int = 1) {
        enum class Kind { DIGIT, OPERATOR, FUNCTION, CLEAR, EQUALS, MEMORY }
    }

    private val rows: List<List<Key>> by lazy {
        val F = Key.Kind.FUNCTION; val D = Key.Kind.DIGIT; val O = Key.Kind.OPERATOR
        val C = Key.Kind.CLEAR; val M = Key.Kind.MEMORY
        listOf(
            listOf(Key("MC", M), Key("MR", M), Key("M+", M), Key("M-", M), Key("DEG", F)),
            listOf(Key("sin", F, "sin("), Key("cos", F, "cos("), Key("tan", F, "tan("), Key("ln", F, "ln("), Key("log", F, "log(")),
            listOf(Key("asin", F, "asin("), Key("acos", F, "acos("), Key("atan", F, "atan("), Key("√", F, "√("), Key("x²", F, "²")),
            listOf(Key("x^y", F, "^"), Key("EXP", F, "E"), Key("(", F), Key(")", F), Key("n!", F, "!")),
            listOf(Key("7", D), Key("8", D), Key("9", D), Key("DEL", C), Key("AC", C)),
            listOf(Key("4", D), Key("5", D), Key("6", D), Key("×", O), Key("÷", O)),
            listOf(Key("1", D), Key("2", D), Key("3", D), Key("+", O), Key("-", O)),
            listOf(Key("0", D), Key(".", D), Key("π", D), Key("e", D), Key("(-)", D, "-")),
            listOf(Key("Ans", D), Key("1/x", F, "^(-1)"), Key("=", Key.Kind.EQUALS, wide = 3)),
        )
    }

    private fun showKeypad() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        // The read-out: what is typed, then the answer (or why there is none).
        val display = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        statusView = text("", UiScale.TEXT_NOTE, dim = true)
        display.addView(statusView)
        exprView = TextView(this).apply {
            typeface = pixel
            textSize = UiScale.TEXT_NOTE
            setTextColor(color(R.color.retro_text))
            gravity = Gravity.END
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.START
            setPadding(0, dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S))
        }
        display.addView(exprView, LinearLayout.LayoutParams(MATCH, WRAP))
        resultView = TextView(this).apply {
            gravity = Gravity.END or Gravity.CENTER_VERTICAL
            maxLines = 1
        }
        display.addView(resultView, LinearLayout.LayoutParams(MATCH, dp(UiScale.DISPLAY_LINE)))
        page.addView(display, LinearLayout.LayoutParams(MATCH, WRAP))

        val pad = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for (row in rows) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            for ((i, key) in row.withIndex()) {
                val view = keyView(key)
                if (key.label == "DEG") angleKey = view
                line.addView(view, LinearLayout.LayoutParams(0, MATCH, key.wide.toFloat())
                    .apply { if (i > 0) marginStart = dp(UiScale.SPACE_XS) })
            }
            pad.addView(line, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        }
        page.addView(pad, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        setPage(page)
        refresh()
    }

    private fun keyView(key: Key) = TextView(this).apply {
        text = key.label
        typeface = pixel
        gravity = Gravity.CENTER
        maxLines = 1
        textSize = if (key.label.length == 1) UiScale.KEY_TEXT else UiScale.KEY_WORD   // "sin", "asin", "DEG"
        // Colour follows Windows 95's calculator, and the label says it too.
        setTextColor(color(when (key.kind) {
            Key.Kind.OPERATOR, Key.Kind.MEMORY -> R.color.retro_title
            Key.Kind.CLEAR -> R.color.retro_bad
            Key.Kind.EQUALS -> R.color.retro_title_text
            else -> R.color.retro_text
        }))
        if (key.kind == Key.Kind.EQUALS) {
            setBackgroundColor(color(R.color.retro_title))
            textSize = UiScale.KEY_EQUALS
        } else setBackgroundResource(R.drawable.retro_button)
        // Press Start 2P draws × and ÷ at half the height of its digits (seen
        // on the A07): these two come from Plex, bold, at the digits' size.
        if (key.label == "×" || key.label == "÷") {
            typeface = Typeface.create(thai, Typeface.BOLD)
            textSize = UiScale.KEY_SYMBOL
        }
        contentDescription = spoken(key)
        isClickable = true
        setOnClickListener { press(key) }
    }

    private fun spoken(key: Key): String = when (key.label) {
        "DEL" -> "ลบตัวสุดท้าย"; "AC" -> "ล้างทั้งหมด"; "MC" -> "ล้างหน่วยความจำ"; "MR" -> "เรียกหน่วยความจำ"
        "M+" -> "บวกเข้าหน่วยความจำ"; "M-" -> "ลบออกจากหน่วยความจำ"; "DEG" -> "สลับหน่วยมุม"
        "√" -> "รากที่สอง"; "x²" -> "ยกกำลังสอง"; "x^y" -> "ยกกำลัง"; "EXP" -> "คูณสิบยกกำลัง"
        "n!" -> "แฟกทอเรียล"; "(-)" -> "เครื่องหมายลบหน้าตัวเลข"; "1/x" -> "หนึ่งส่วน"; "=" -> "เท่ากับ"
        else -> key.label
    }

    private fun press(key: Key) {
        shownError = null
        when (key.kind) {
            Key.Kind.EQUALS -> evaluate()
            Key.Kind.CLEAR -> if (key.label == "AC") { input.clear(); shownResult = ""; justEvaluated = false } else deleteLast()
            Key.Kind.MEMORY -> memoryKey(key.label)
            else -> {
                if (key.label == "DEG") { degrees = !degrees; refresh(); return }
                if (justEvaluated) {
                    input.clear()
                    // An operator carries the answer on: "= then + 2" is "Ans+2".
                    if (key.kind == Key.Kind.OPERATOR || key.insert in listOf("²", "^", "!", "^(-1)")) input.append("Ans")
                    justEvaluated = false
                }
                if (input.length < MAX_INPUT) input.append(key.insert)
            }
        }
        refresh()
    }

    /** DEL takes back what one key typed: "sin(" as a whole, "Ans" as a whole. */
    private fun deleteLast() {
        if (justEvaluated) { input.clear(); justEvaluated = false; shownResult = ""; return }
        val whole = listOf("asin(", "acos(", "atan(", "sin(", "cos(", "tan(", "log(", "ln(", "√(", "^(-1)", "Ans")
            .firstOrNull { input.endsWith(it) }
        if (whole != null) input.setLength(input.length - whole.length)
        else if (input.isNotEmpty()) input.setLength(input.length - 1)
    }

    private fun evaluate() {
        if (input.isEmpty()) return
        when (val r = CalcEngine.evaluate(input.toString(), degrees, ans)) {
            is CalcEngine.Result.Value -> {
                ans = r.value
                shownResult = CalcEngine.format(r.value)
                // What was worked out, with the brackets it closed: "√(256)".
                val open = input.count { it == '(' } - input.count { it == ')' }
                repeat(open.coerceAtLeast(0)) { input.append(')') }
                history.add(0, input.toString() to shownResult)
                while (history.size > HISTORY_MAX) history.removeAt(history.size - 1)
                justEvaluated = true
                save()
            }
            is CalcEngine.Result.Error -> { shownError = reasonText(r.reason); shownResult = "" }
        }
    }

    /** M+ and M-: the answer on screen, or what is typed if it has not been worked out yet. */
    private fun memoryKey(label: String) {
        when (label) {
            "MC" -> memory = 0.0
            "MR" -> {
                if (justEvaluated) { input.clear(); justEvaluated = false }
                val text = CalcEngine.format(memory)
                if (input.length + text.length <= MAX_INPUT) input.append(if (memory < 0) "($text)" else text)
            }
            else -> {
                val value = if (justEvaluated || input.isEmpty()) ans else
                    when (val r = CalcEngine.evaluate(input.toString(), degrees, ans)) {
                        is CalcEngine.Result.Value -> r.value
                        is CalcEngine.Result.Error -> { shownError = reasonText(r.reason); return }
                    }
                memory += if (label == "M+") value else -value
            }
        }
        save()
    }

    internal fun reasonText(reason: CalcEngine.Reason): String = getString(when (reason) {
        CalcEngine.Reason.DIVIDE_BY_ZERO -> R.string.calc_error_divide
        CalcEngine.Reason.DOMAIN -> R.string.calc_error_domain
        CalcEngine.Reason.TOO_BIG -> R.string.calc_error_big
        CalcEngine.Reason.INCOMPLETE -> R.string.calc_error_incomplete
    })

    private fun refresh() {
        exprView?.text = if (justEvaluated) "$input =" else input.toString().ifEmpty { " " }
        resultView?.apply {
            val error = shownError
            if (error != null) {
                text = error
                typeface = Typeface.create(thai, Typeface.BOLD)
                textSize = UiScale.TEXT_ITEM
                setTextColor(color(R.color.retro_bad))
            } else {
                text = shownResult.ifEmpty { if (input.isEmpty()) "0" else "" }
                typeface = pixel
                textSize = UiScale.TEXT_VALUE
                setTextColor(color(R.color.retro_text))
            }
        }
        angleKey?.text = if (degrees) "DEG" else "RAD"
        statusView?.text = buildString {
            append(getString(if (degrees) R.string.calc_angle_degrees else R.string.calc_angle_radians))
            if (memory != 0.0) append("  ·  ").append(getString(R.string.calc_memory, CalcEngine.format(memory)))
        }
    }

    // ------------------------------------------------------------ history

    private fun showHistory() {
        val page = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        if (history.isEmpty()) {
            page.addView(text(getString(R.string.calc_history_empty), UiScale.TEXT_ITEM).apply { setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), 0) })
            setPage(page)
            return
        }
        page.addView(text(getString(R.string.calc_history_hint), UiScale.TEXT_NOTE, dim = true).apply { setPadding(dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS)) })
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        for ((expr, result) in history) {
            list.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.retro_button)
                setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
                minimumHeight = dp(UiScale.ROW)
                isClickable = true
                contentDescription = "$expr เท่ากับ $result"
                setOnClickListener { reuse(result) }
                addView(TextView(context).apply {
                    text = expr; typeface = pixel; textSize = UiScale.TEXT_NOTE
                    setTextColor(color(R.color.retro_dim)); maxLines = 1; ellipsize = TextUtils.TruncateAt.START
                })
                addView(TextView(context).apply {
                    text = "= $result"; typeface = pixel; textSize = UiScale.TEXT_ITEM
                    setTextColor(color(R.color.retro_text)); gravity = Gravity.END; setPadding(0, dp(UiScale.SPACE_XS), 0, 0)
                }, LinearLayout.LayoutParams(MATCH, WRAP))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
        }
        page.addView(ScrollView(this).apply { addView(list) }, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (confirmingClear) {
            page.addView(text(getString(R.string.calc_history_clear_ask), UiScale.TEXT_BASE).apply { setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS)) })
            page.addView(pair(getString(R.string.calc_history_clear_yes), { history.clear(); confirmingClear = false; save(); showHistory() },
                              getString(R.string.cancel), { confirmingClear = false; showHistory() }))
        } else {
            page.addView(button(getString(R.string.calc_history_clear)) { confirmingClear = true; showHistory() },
                         LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_S) })
        }
        setPage(page)
    }

    /** A result from the history goes back into the keypad, ready to use. */
    private fun reuse(result: String) {
        input.clear(); input.append(result)
        justEvaluated = false; shownResult = ""; shownError = null
        show(Tab.KEYPAD)
    }

    // ------------------------------------------------------------ saved state

    private fun load() {
        val prefs = getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        degrees = prefs.getBoolean("degrees", true)
        memory = java.lang.Double.longBitsToDouble(prefs.getLong("memory", 0L))
        ans = java.lang.Double.longBitsToDouble(prefs.getLong("ans", 0L))
        history.clear()
        prefs.getString("history", "")!!.split(RECORD).filter { it.isNotEmpty() }.forEach { line ->
            val parts = line.split(FIELD)
            if (parts.size == 2) history.add(parts[0] to parts[1])
        }
    }

    private fun save() {
        getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putBoolean("degrees", degrees)
            .putLong("memory", java.lang.Double.doubleToRawLongBits(memory))
            .putLong("ans", java.lang.Double.doubleToRawLongBits(ans))
            .putString("history", history.joinToString(RECORD) { it.first + FIELD + it.second })
            .apply()
    }

    // ------------------------------------------------------------ parts

    internal fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    internal fun label(value: String) = text(value, UiScale.TEXT_NOTE).apply { typeface = Typeface.create(thai, Typeface.BOLD) }

    /** A raised 1995 button that sinks while pressed. */
    internal fun button(value: String, big: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = if (big) UiScale.TEXT_HEADING else UiScale.TEXT_BASE
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(R.color.retro_text))
        setBackgroundResource(R.drawable.retro_button)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
        minWidth = dp(UiScale.TOUCH)
        maxLines = 1
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** A button that stays down while chosen. */
    internal fun toggle(value: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(color(if (on) R.color.retro_title_text else R.color.retro_text))
        if (on) setBackgroundColor(color(R.color.retro_title)) else setBackgroundResource(R.drawable.retro_button)
        contentDescription = value + if (on) " (เลือกอยู่)" else ""
        isClickable = true
        setOnClickListener { onClick() }
    }

    internal fun pair(a: String, onA: () -> Unit, b: String, onB: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        addView(button(a) { onA() }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
        addView(button(b) { onB() }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { marginStart = dp(UiScale.SPACE_S) })
    }

    /** A number field: the decimal keyboard, a sign allowed, the white 1995 box. */
    internal fun numberField(hint: String = "") = EditText(this).apply {
        typeface = thai
        textSize = UiScale.TEXT_HEADING
        setSingleLine()
        this.hint = hint
        inputType = InputType.TYPE_CLASS_NUMBER or InputType.TYPE_NUMBER_FLAG_DECIMAL or InputType.TYPE_NUMBER_FLAG_SIGNED
        imeOptions = EditorInfo.IME_ACTION_DONE
        setBackgroundResource(R.drawable.retro_field)
        setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
        setTextColor(color(R.color.retro_text))
        setHintTextColor(color(R.color.retro_dim))
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    internal fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    internal fun color(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        const val PREFS = "calculator"
        const val HISTORY_MAX = 30
        /** Longer than any sum anyone types on a phone, short enough to always evaluate at once. */
        const val MAX_INPUT = 120
        private const val RECORD = "\u001E"
        private const val FIELD = "\u001F"
        internal const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        internal const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
