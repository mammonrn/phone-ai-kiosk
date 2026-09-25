package com.mammonrn.phoneaikiosk.calc

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.text.Editable
import android.text.TextWatcher
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.MATCH
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.WRAP
import com.mammonrn.phoneaikiosk.calc.Solar.Field
import com.mammonrn.phoneaikiosk.calc.Solar.Result
import com.mammonrn.phoneaikiosk.calc.Solar.Rule
import com.mammonrn.phoneaikiosk.ui.PagedPanel
import com.mammonrn.phoneaikiosk.ui.UiScale
import kotlin.math.abs

/**
 * The "โซลาร์" tab of the calculator (0.62.0): seven tools on SLIDES, the
 * same rule as the dashboard's paged cards (DESIGN.md 5, "การ์ดหลายหน้า";
 * Poom: "ใช้แบบสไลด์ตามกฎเดิม ไม่ใช่แท็บปุ่ม"):
 *  * a sideways swipe on the content turns the slide — taken only once the
 *    finger has gone more than twice the touch slop sideways and 1.5 times
 *    more across than down, so taps, typing and scrolling are left alone;
 *  * no row of tabs or page buttons: the only sign of the slides is the row
 *    of small squares in the window's title bar (■ showing, □ the others,
 *    [PagedPanel.SQUARE_DP] each, 4dp apart); a tap on them turns to the next;
 *  * the title bar, the frame and the tab row never move; only the slide does.
 * PagedPanel itself builds its pages from XML only, so this is its rule
 * written again for pages built in code, not a new one.
 *
 * ON-GRID has no battery: its slides are panels, inverter, strings, wire and
 * payback. OFF-GRID adds battery and charge controller. The choice is on the
 * first slide; the squares follow it.
 *
 * The arithmetic and every check are [Solar]; this only reads the fields and
 * says what came back, with the formula under it, in words. What was typed
 * stays when the slide turns or a choice redraws it.
 */
internal class SolarPages(private val a: CalculatorActivity) {

    enum class Slide(val title: Int, val hint: Int, val offGridOnly: Boolean = false) {
        PANELS(R.string.solar_page_panels, R.string.solar_hint_panels),
        BATTERY(R.string.solar_page_battery, R.string.solar_hint_battery, offGridOnly = true),
        INVERTER(R.string.solar_page_inverter, R.string.solar_hint_inverter),
        CONTROLLER(R.string.solar_page_controller, R.string.solar_hint_controller, offGridOnly = true),
        STRINGS(R.string.solar_page_strings, R.string.solar_hint_strings),
        WIRE(R.string.solar_page_wire, R.string.solar_hint_wire),
        PAYBACK(R.string.solar_page_payback, R.string.solar_hint_payback),
    }

    private var offGrid = false
    private var chemistry = Solar.Chemistry.LITHIUM
    private var batteryV = 48
    private var controllerV = 48
    private var mppt = true

    /** What was typed, per slide and field, so a redraw or a turn never loses it. */
    private val typed = HashMap<String, String>()

    private val slides = LinkedHashMap<Slide, View>()

    private fun shown(): List<Slide> = Slide.entries.filter { offGrid || !it.offGridOnly }

    /** The slides: the calculator's one slide component (SlideDeck), shared with the electrical tab. */
    private val deck = SlideDeck(a, { shown() }, { a.getString(it.title) }, { slides.getValue(it) }, Slide.PANELS)

    /** The tab opens: the slide it was on, the squares in the title bar. */
    fun open() {
        if (slides.isEmpty()) for (s in Slide.entries) build(s)
        deck.open()
    }

    /** Builds (or rebuilds, after a choice on it) one slide. */
    private fun build(s: Slide) {
        val page = column()
        page.addView(a.text(a.getString(s.title), UiScale.TEXT_HEADING).apply { typeface = Typeface.create(a.thai, Typeface.BOLD) })
        val list = shown()
        page.addView(a.text(a.getString(R.string.solar_where, list.indexOf(s).coerceAtLeast(0) + 1, list.size), UiScale.TEXT_NOTE, dim = true))
        page.addView(a.text(a.getString(s.hint), UiScale.TEXT_NOTE, dim = true).apply { setPadding(0, a.dp(UiScale.SPACE_XS), 0, 0) })
        when (s) {
            Slide.PANELS -> panels(page)
            Slide.BATTERY -> battery(page)
            Slide.INVERTER -> inverter(page)
            Slide.CONTROLLER -> controller(page)
            Slide.STRINGS -> strings(page)
            Slide.WIRE -> wire(page)
            Slide.PAYBACK -> payback(page)
        }
        page.addView(a.text(a.getString(R.string.solar_estimate), UiScale.TEXT_NOTE).apply {
            typeface = Typeface.create(a.thai, Typeface.BOLD); setPadding(0, a.dp(UiScale.SPACE_M), 0, a.dp(UiScale.SPACE_S))
        })
        slides[s] = ScrollView(a).apply { isFillViewport = false; addView(page) }
    }

    private fun rebuild(s: Slide) {
        build(s)
        if (s == deck.current) deck.render()
    }

    // ------------------------------------------------------------ 1. panels

    private fun panels(page: LinearLayout) {
        page.addView(row(listOf(
            a.toggle(a.getString(R.string.solar_grid_on), !offGrid) { setGrid(false) },
            a.toggle(a.getString(R.string.solar_grid_off), offGrid) { setGrid(true) })), gap())
        page.addView(a.text(a.getString(if (offGrid) R.string.solar_grid_off_note else R.string.solar_grid_on_note), UiScale.TEXT_NOTE, dim = true)
            .apply { setPadding(0, a.dp(UiScale.SPACE_XS), 0, 0) })
        val e = In(Slide.PANELS, Field.DAILY_KWH)
        val h = In(Slide.PANELS, Field.SUN_HOURS, "4.5")
        val l = In(Slide.PANELS, Field.LOSS, "20")
        val w = In(Slide.PANELS, Field.PANEL_W, "550")
        form(page, listOf(e, h, l, w)) { out ->
            when (val r = Solar.panels(e.value(), h.value(), l.value(), w.value())) {
                is Result.Bad -> out.error(r.problem)
                is Result.Ok -> out.show(listOf(
                    a.getString(R.string.solar_r_array, Solar.format(r.value.arrayW, 0)),
                    a.getString(R.string.solar_r_panels, Solar.format(w.value()!!), r.value.panels, Solar.format(r.value.installedW)),
                    a.getString(R.string.solar_r_made, Solar.format(r.value.installedKwhPerDay))),
                    R.string.solar_x_panels, emptyList(), R.string.solar_note_panels)
            }
        }
    }

    private fun setGrid(off: Boolean) {
        if (off == offGrid) return
        offGrid = off
        // Every slide says "หน้า n จาก m": all of them change.
        for (s in Slide.entries) build(s)
        deck.render()
    }

    // ------------------------------------------------------------ 2. battery

    private fun battery(page: LinearLayout) {
        page.addView(a.label(a.getString(R.string.solar_chemistry)).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS)) })
        val dod = In(Slide.BATTERY, Field.DOD, Solar.format(chemistry.defaultDod))
        page.addView(row(listOf(
            a.toggle(a.getString(R.string.solar_lithium), chemistry == Solar.Chemistry.LITHIUM) { setChemistry(Solar.Chemistry.LITHIUM) },
            a.toggle(a.getString(R.string.solar_lead_acid), chemistry == Solar.Chemistry.LEAD_ACID) { setChemistry(Solar.Chemistry.LEAD_ACID) })))
        page.addView(a.label(a.getString(R.string.solar_system_v)).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS)) })
        page.addView(row(VOLTS.map { v -> a.toggle(a.getString(R.string.solar_volts, v), v == batteryV) { batteryV = v; rebuild(Slide.BATTERY) } }))
        val e = In(Slide.BATTERY, Field.DAILY_KWH)
        val d = In(Slide.BATTERY, Field.DAYS, "1")
        val eff = In(Slide.BATTERY, Field.EFFICIENCY, Solar.format(Solar.DEFAULT_EFFICIENCY))
        form(page, listOf(e, d, dod, eff)) { out ->
            when (val r = Solar.battery(e.value(), d.value(), dod.value(), eff.value(), batteryV.toDouble())) {
                is Result.Bad -> out.error(r.problem)
                is Result.Ok -> out.show(listOf(
                    a.getString(R.string.solar_r_battery_ah, Solar.format(r.value.ah, 1), batteryV.toString()),
                    a.getString(R.string.solar_r_battery_wh, Solar.format(r.value.wh, 0), Solar.format(r.value.wh / 1000))),
                    R.string.solar_x_battery, emptyList(), R.string.solar_note_battery)
            }
        }
    }

    /** Another chemistry: its own depth of discharge goes in the field (the user can still change it). */
    private fun setChemistry(c: Solar.Chemistry) {
        if (c == chemistry) return
        chemistry = c
        typed[key(Slide.BATTERY, Field.DOD)] = Solar.format(c.defaultDod)
        rebuild(Slide.BATTERY)
    }

    // ------------------------------------------------------------ 3. inverter

    private fun inverter(page: LinearLayout) {
        val run = In(Slide.INVERTER, Field.RUNNING_W)
        val peak = In(Slide.INVERTER, Field.PEAK_W)
        form(page, listOf(run, peak)) { out ->
            when (val r = Solar.inverter(run.value(), peak.value())) {
                is Result.Bad -> out.error(r.problem)
                is Result.Ok -> out.show(listOf(
                    a.getString(R.string.solar_r_inv_continuous, Solar.format(r.value.continuousW, 0)),
                    a.getString(R.string.solar_r_inv_surge, Solar.format(r.value.surgeW, 0))),
                    R.string.solar_x_inverter,
                    if (peak.value() == null) listOf(a.getString(R.string.solar_w_no_peak)) else emptyList())
            }
        }
    }

    // ------------------------------------------------------------ 4. charge controller

    private fun controller(page: LinearLayout) {
        page.addView(row(listOf(
            a.toggle(a.getString(R.string.solar_mppt), mppt) { if (!mppt) { mppt = true; rebuild(Slide.CONTROLLER) } },
            a.toggle(a.getString(R.string.solar_pwm), !mppt) { if (mppt) { mppt = false; rebuild(Slide.CONTROLLER) } })), gap())
        if (mppt) {
            page.addView(a.label(a.getString(R.string.solar_system_v)).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS)) })
            page.addView(row(VOLTS.map { v -> a.toggle(a.getString(R.string.solar_volts, v), v == controllerV) { controllerV = v; rebuild(Slide.CONTROLLER) } }))
            val w = In(Slide.CONTROLLER, Field.ARRAY_W)
            form(page, listOf(w)) { out ->
                when (val r = Solar.mppt(w.value(), controllerV.toDouble())) {
                    is Result.Bad -> out.error(r.problem)
                    is Result.Ok -> out.show(listOf(a.getString(R.string.solar_r_controller, a.getString(R.string.solar_mppt), Solar.format(r.value, 1))),
                                             R.string.solar_x_mppt, emptyList(), R.string.solar_note_mppt)
                }
            }
        } else {
            val isc = In(Slide.CONTROLLER, Field.ISC)
            val n = In(Slide.CONTROLLER, Field.PARALLEL, "1")
            form(page, listOf(isc, n)) { out ->
                when (val r = Solar.pwm(isc.value(), n.value())) {
                    is Result.Bad -> out.error(r.problem)
                    is Result.Ok -> out.show(listOf(a.getString(R.string.solar_r_controller, a.getString(R.string.solar_pwm), Solar.format(r.value, 1))),
                                             R.string.solar_x_pwm, emptyList(), R.string.solar_note_pwm)
                }
            }
        }
    }

    // ------------------------------------------------------------ 5. strings

    private fun strings(page: LinearLayout) {
        val voc = In(Slide.STRINGS, Field.VOC)
        val vmp = In(Slide.STRINGS, Field.VMP)
        val isc = In(Slide.STRINGS, Field.ISC, label = R.string.solar_l_isc_optional)
        val cv = In(Slide.STRINGS, Field.COEF_VOC, Solar.format(Solar.DEFAULT_COEF_VOC))
        val cm = In(Slide.STRINGS, Field.COEF_VMP, Solar.format(Solar.DEFAULT_COEF_VMP))
        val tMin = In(Slide.STRINGS, Field.MIN_TEMP, Solar.format(Solar.DEFAULT_MIN_C))
        val tCell = In(Slide.STRINGS, Field.CELL_TEMP, Solar.format(Solar.DEFAULT_CELL_C))
        val maxV = In(Slide.STRINGS, Field.MAX_V)
        val minV = In(Slide.STRINGS, Field.MPPT_MIN)
        form(page, listOf(voc, vmp, isc, cv, cm, tMin, tCell, maxV, minV)) { out ->
            val r = Solar.strings(voc.value(), vmp.value(), cv.value(), cm.value(), tMin.value(), tCell.value(), maxV.value(), minV.value())
            if (r is Result.Bad) return@form out.error(r.problem)
            val s = (r as Result.Ok).value
            // Isc is only for the note under the answer; empty is fine, wrong is not.
            val iscValue = isc.value()?.let { v ->
                when (val c = Solar.check(Field.ISC, v)) { is Result.Bad -> return@form out.error(c.problem); is Result.Ok -> c.value }
            }
            val lines = ArrayList<String>()
            lines.add(when {
                s.maxSeries < 1 -> a.getString(R.string.solar_r_series_zero, Solar.format(s.vocCold), Solar.format(maxV.value()!!))
                !s.fits -> a.getString(R.string.solar_r_series_none, s.minSeries, s.maxSeries)
                else -> a.getString(R.string.solar_r_series, s.minSeries, s.maxSeries)
            })
            if (s.maxSeries >= 1) lines.add(a.getString(R.string.solar_r_voc_cold, Solar.format(s.vocCold), s.maxSeries, Solar.format(s.vocCold * s.maxSeries)))
            lines.add(a.getString(R.string.solar_r_vmp_hot, Solar.format(s.vmpHot), s.minSeries, Solar.format(s.vmpHot * s.minSeries)))
            if (iscValue != null) lines.add(a.getString(R.string.solar_r_string_current, Solar.format(iscValue * Solar.CONTROLLER_SAFETY)))
            out.show(lines, R.string.solar_x_strings, emptyList(), R.string.solar_note_strings, answerIsBad = !s.fits)
        }
    }

    // ------------------------------------------------------------ 6. wire

    private fun wire(page: LinearLayout) {
        val i = In(Slide.WIRE, Field.CURRENT)
        val l = In(Slide.WIRE, Field.LENGTH)
        val v = In(Slide.WIRE, Field.VOLTAGE)
        val d = In(Slide.WIRE, Field.DROP, "3")
        form(page, listOf(i, l, v, d)) { out ->
            when (val r = Solar.wire(i.value(), l.value(), v.value(), d.value())) {
                is Result.Bad -> out.error(r.problem)
                is Result.Ok -> {
                    val w = r.value
                    val lines = if (w.chosenMm2 != null) listOf(
                        a.getString(R.string.solar_r_wire, Solar.format(w.chosenMm2)),
                        a.getString(R.string.solar_r_wire_drop, Solar.format(w.dropV), Solar.format(w.dropPct)),
                        a.getString(R.string.solar_r_wire_needed, Solar.format(w.neededMm2)))
                    else listOf(
                        a.getString(R.string.solar_r_wire_none, Solar.format(d.value()!!)),
                        a.getString(R.string.solar_r_wire_largest, Solar.format(w.dropV), Solar.format(w.dropPct)))
                    // The one thing this does not check goes with every answer (as on the ไฟฟ้า tab).
                    out.show(lines, R.string.solar_x_wire, listOf(a.getString(R.string.el_wire_ampacity_short)), answerIsBad = w.chosenMm2 == null)
                }
            }
        }
    }

    // ------------------------------------------------------------ 7. payback

    private fun payback(page: LinearLayout) {
        val cost = In(Slide.PAYBACK, Field.COST)
        val price = In(Slide.PAYBACK, Field.PRICE, Solar.format(Solar.DEFAULT_PRICE))
        val kwh = In(Slide.PAYBACK, Field.PRODUCED_KWH)
        form(page, listOf(cost, price, kwh)) { out ->
            when (val r = Solar.payback(cost.value(), price.value(), kwh.value())) {
                is Result.Bad -> out.error(r.problem)
                is Result.Ok -> out.show(listOf(
                    a.getString(R.string.solar_r_years, Solar.format(r.value.years, 1)),
                    a.getString(R.string.solar_r_month, Solar.format(r.value.perMonth, 0)),
                    a.getString(R.string.solar_r_year, Solar.format(r.value.perYear, 0))),
                    R.string.solar_x_payback,
                    if (r.value.years > LONG_PAYBACK_YEARS) listOf(a.getString(R.string.solar_w_long_payback)) else emptyList(),
                    R.string.solar_note_payback)
            }
        }
    }

    // ------------------------------------------------------------ parts

    private fun key(s: Slide, f: Field) = "$s:$f"

    /** One number field: its label with the unit, the white box, and what was typed kept. */
    private inner class In(slide: Slide, val field: Field, initial: String = "", label: Int = labelOf(field)) {
        private val k = key(slide, field)
        val default = initial
        var changed: (() -> Unit)? = null
        val box: EditText = a.numberField().apply {
            setText(typed[k] ?: initial)
            contentDescription = a.getString(label)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { typed[k] = s?.toString().orEmpty(); changed?.invoke() }
            })
        }
        val view: LinearLayout = column().apply {
            addView(a.label(a.getString(label)).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS)) })
            addView(box, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)))
        }

        /** Null: empty. NaN: not a number. Solar says which, in its own words. */
        fun value(): Double? = Solar.parse(box.text.toString())

        /** Back to how the slide first showed it: the default, or empty. */
        fun reset() = box.setText(default)
    }

    /** The fields, [คำนวณ] [ล้างค่า], and the answer box under them. */
    private fun form(page: LinearLayout, inputs: List<In>, go: (Output) -> Unit) {
        inputs.forEach { page.addView(it.view) }
        val out = Output()
        // The message goes the moment anything is changed (ux-ui-design, Poom 2026-09-24).
        inputs.forEach { it.changed = { out.clear() } }
        page.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, a.dp(UiScale.SPACE_L), 0, a.dp(UiScale.SPACE_S))
            addView(a.button(a.getString(R.string.el_calculate), big = true) { a.currentFocus?.clearFocus(); go(out) },
                    LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 2f))
            addView(a.button(a.getString(R.string.el_clear)) { inputs.forEach { it.reset() }; out.clear() },
                    LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 1f).apply { marginStart = a.dp(UiScale.SPACE_S) })
        })
        page.addView(out.view)
    }

    /** The answer box: the answer in bold, the rest, the formula, notes; or one error in red. */
    private inner class Output {
        val view: LinearLayout = column().apply {
            setBackgroundResource(R.drawable.retro_field)
            setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S))
            visibility = View.GONE
        }

        fun clear() { view.removeAllViews(); view.visibility = View.GONE }

        fun error(p: Solar.Problem) {
            clear()
            view.addView(a.text(a.getString(R.string.solar_error, say(p)), UiScale.TEXT_BASE).apply { setTextColor(a.color(R.color.retro_bad)) })
            view.visibility = View.VISIBLE
        }

        /** [answerIsBad]: the answer is "nothing fits" — red, and said so in its words. */
        fun show(lines: List<String>, formula: Int, warnings: List<String>, note: Int? = null, answerIsBad: Boolean = false) {
            clear()
            for ((n, line) in lines.withIndex()) view.addView(a.text(line, if (n == 0) UiScale.TEXT_HEADING else UiScale.TEXT_ITEM).apply {
                if (n == 0) typeface = Typeface.create(a.thai, Typeface.BOLD)
                if (n == 0 && answerIsBad) setTextColor(a.color(R.color.retro_bad))
                setPadding(0, if (n == 0) 0 else a.dp(UiScale.SPACE_XS), 0, 0)
            })
            view.addView(a.text(a.getString(formula), UiScale.TEXT_NOTE, dim = true).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, 0) })
            for (w in warnings) view.addView(a.text(a.getString(R.string.solar_check, w), UiScale.TEXT_NOTE).apply {
                setTextColor(a.color(R.color.retro_bad)); setPadding(0, a.dp(UiScale.SPACE_S), 0, 0)
            })
            if (note != null) view.addView(a.text(a.getString(note), UiScale.TEXT_NOTE, dim = true).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, 0) })
            view.visibility = View.VISIBLE
        }
    }

    /** A problem from [Solar] in plain Thai: which field, and why. */
    private fun say(p: Solar.Problem): String {
        val name = a.getString(nameOf(p.field))
        val limit = p.limit?.let { Solar.format(it) }.orEmpty()
        return when (p.rule) {
            Rule.MISSING -> a.getString(R.string.solar_e_missing, name)
            Rule.NOT_NUMBER -> a.getString(R.string.solar_e_not_number, name)
            Rule.NOT_POSITIVE -> a.getString(R.string.solar_e_not_positive, name)
            Rule.NEGATIVE -> a.getString(R.string.solar_e_negative, name)
            Rule.BELOW -> a.getString(R.string.solar_e_below, name, limit)
            Rule.ABOVE -> a.getString(R.string.solar_e_above, name, limit)
            Rule.NOT_WHOLE -> a.getString(R.string.solar_e_not_whole, name)
            Rule.NOT_NEGATIVE -> a.getString(R.string.solar_e_not_negative, name)
            Rule.VMP_NOT_BELOW_VOC -> a.getString(R.string.solar_e_vmp)
            Rule.MPPT_NOT_BELOW_MAX -> a.getString(R.string.solar_e_mppt)
            Rule.CELL_NOT_ABOVE_MIN -> a.getString(R.string.solar_e_cell)
            Rule.PEAK_BELOW_RUNNING -> a.getString(R.string.solar_e_peak)
        }
    }

    private fun row(views: List<View>) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((n, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, a.dp(UiScale.TOUCH), 1f).apply { if (n > 0) marginStart = a.dp(UiScale.SPACE_S) })
    }

    private fun gap() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) }

    private fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    companion object {
        /** The battery systems sold for homes. */
        val VOLTS = listOf(12, 24, 48)

        /** Past a panel's usual 25-year warranty, the page says so. */
        const val LONG_PAYBACK_YEARS = 25.0

        fun labelOf(f: Field): Int = when (f) {
            Field.DAILY_KWH -> R.string.solar_l_daily_kwh
            Field.SUN_HOURS -> R.string.solar_l_sun_hours
            Field.LOSS -> R.string.solar_l_loss
            Field.PANEL_W -> R.string.solar_l_panel_w
            Field.DAYS -> R.string.solar_l_days
            Field.DOD -> R.string.solar_l_dod
            Field.EFFICIENCY -> R.string.solar_l_efficiency
            Field.BATTERY_V -> R.string.solar_system_v
            Field.RUNNING_W -> R.string.solar_l_running_w
            Field.PEAK_W -> R.string.solar_l_peak_w
            Field.ARRAY_W -> R.string.solar_l_array_w
            Field.ISC -> R.string.solar_l_isc
            Field.PARALLEL -> R.string.solar_l_parallel
            Field.VOC -> R.string.solar_l_voc
            Field.VMP -> R.string.solar_l_vmp
            Field.COEF_VOC -> R.string.solar_l_coef_voc
            Field.COEF_VMP -> R.string.solar_l_coef_vmp
            Field.MIN_TEMP -> R.string.solar_l_min_temp
            Field.CELL_TEMP -> R.string.solar_l_cell_temp
            Field.MAX_V -> R.string.solar_l_max_v
            Field.MPPT_MIN -> R.string.solar_l_mppt_min
            Field.CURRENT -> R.string.solar_l_current
            Field.LENGTH -> R.string.solar_l_length
            Field.VOLTAGE -> R.string.solar_l_voltage
            Field.DROP -> R.string.solar_l_drop
            Field.COST -> R.string.solar_l_cost
            Field.PRICE -> R.string.solar_l_price
            Field.PRODUCED_KWH -> R.string.solar_l_produced
        }

        fun nameOf(f: Field): Int = when (f) {
            Field.DAILY_KWH -> R.string.solar_n_daily_kwh
            Field.SUN_HOURS -> R.string.solar_n_sun_hours
            Field.LOSS -> R.string.solar_n_loss
            Field.PANEL_W -> R.string.solar_n_panel_w
            Field.DAYS -> R.string.solar_n_days
            Field.DOD -> R.string.solar_n_dod
            Field.EFFICIENCY -> R.string.solar_n_efficiency
            Field.BATTERY_V -> R.string.solar_n_battery_v
            Field.RUNNING_W -> R.string.solar_n_running_w
            Field.PEAK_W -> R.string.solar_n_peak_w
            Field.ARRAY_W -> R.string.solar_n_array_w
            Field.ISC -> R.string.solar_n_isc
            Field.PARALLEL -> R.string.solar_n_parallel
            Field.VOC -> R.string.solar_n_voc
            Field.VMP -> R.string.solar_n_vmp
            Field.COEF_VOC -> R.string.solar_n_coef_voc
            Field.COEF_VMP -> R.string.solar_n_coef_vmp
            Field.MIN_TEMP -> R.string.solar_n_min_temp
            Field.CELL_TEMP -> R.string.solar_n_cell_temp
            Field.MAX_V -> R.string.solar_n_max_v
            Field.MPPT_MIN -> R.string.solar_n_mppt_min
            Field.CURRENT -> R.string.solar_n_current
            Field.LENGTH -> R.string.solar_n_length
            Field.VOLTAGE -> R.string.solar_n_voltage
            Field.DROP -> R.string.solar_n_drop
            Field.COST -> R.string.solar_n_cost
            Field.PRICE -> R.string.solar_n_price
            Field.PRODUCED_KWH -> R.string.solar_n_produced
        }
    }
}
