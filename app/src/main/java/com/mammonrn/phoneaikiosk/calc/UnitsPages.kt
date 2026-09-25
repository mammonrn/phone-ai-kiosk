package com.mammonrn.phoneaikiosk.calc

import android.content.Context
import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.LinearLayout
import android.widget.ScrollView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.MATCH
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.WRAP
import com.mammonrn.phoneaikiosk.calc.Units.Kind
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The "หน่วย" tab (0.61.0, Poom): one slide per kind of unit, by the
 * calculator's one slide component (SlideDeck). Type a value, choose its unit,
 * and every other unit of that kind is shown at once — no second choice to make.
 */
internal class UnitsPages(private val a: CalculatorActivity) {

    private val prefs = a.getSharedPreferences("calc_units", Context.MODE_PRIVATE)
    private val deck = SlideDeck(a, { Kind.entries }, { a.getString(kindWord(it)) }, { build(it) }, Kind.LENGTH)

    fun open() = deck.open()

    private fun from(k: Kind): String = prefs.getString("from_${k.name}", Units.UNITS.getValue(k).first().key)!!
    private fun text(k: Kind): String = prefs.getString("value_${k.name}", "1")!!

    private fun build(k: Kind): View {
        val page = column()
        page.addView(a.text(a.getString(kindWord(k)), UiScale.TEXT_HEADING).apply { typeface = Typeface.create(a.thai, Typeface.BOLD) })
        page.addView(a.label(a.getString(R.string.units_value)), gap())
        val field = a.numberField("1").apply { setText(text(k)); a.nameField(this, a.getString(R.string.units_value)) }
        page.addView(field, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)))
        page.addView(a.label(a.getString(R.string.units_from)), gap())
        // The units as toggles, three to a row: the chosen one stays down (navy, bold) — shape and words, not colour only.
        for (row in Units.UNITS.getValue(k).chunked(3)) {
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            for ((n, u) in row.withIndex()) {
                line.addView(a.toggle(a.getString(word(u.key)), u.key == from(k)) {
                    prefs.edit().putString("from_${k.name}", u.key).apply(); deck.render()
                }.apply { maxLines = 2; textSize = UiScale.TEXT_NOTE },
                    LinearLayout.LayoutParams(0, a.dp(UiScale.TOUCH), 1f).apply { if (n > 0) marginStart = a.dp(UiScale.SPACE_XS) })
            }
            repeat(3 - row.size) { line.addView(View(a), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = a.dp(UiScale.SPACE_XS) }) }
            page.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_XS) })
        }
        page.addView(a.label(a.getString(R.string.units_results)), gap())
        val box = column().apply {
            setBackgroundResource(R.drawable.retro_field)
            setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S))
        }
        page.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))
        fun draw() {
            box.removeAllViews()
            val v = field.text.toString().replace(",", "").trim().toDoubleOrNull()
            val rows = v?.let { Units.all(k, it, from(k)) }
            if (rows == null) {
                val why = if (k == Kind.TEMPERATURE && v != null && v.isFinite()) R.string.units_below_zero else R.string.units_bad_value
                box.addView(a.text(a.getString(why), UiScale.TEXT_BASE).apply { setTextColor(a.color(R.color.retro_bad)) })
                return
            }
            for ((u, x) in rows) {
                val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                val mine = u.key == from(k)
                line.addView(a.text(a.getString(word(u.key)), UiScale.TEXT_BASE, dim = mine), LinearLayout.LayoutParams(0, WRAP, 1f))
                line.addView(a.text(show(x), UiScale.TEXT_BASE).apply {
                    gravity = Gravity.END
                    if (!mine) typeface = Typeface.create(a.thai, Typeface.BOLD)
                }, LinearLayout.LayoutParams(0, WRAP, 1f))
                box.addView(line, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)))
            }
        }
        field.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { prefs.edit().putString("value_${k.name}", s?.toString().orEmpty()).apply(); draw() }
        })
        draw()
        if (k == Kind.LENGTH || k == Kind.AREA || k == Kind.MASS) page.addView(a.text(a.getString(R.string.units_note), UiScale.TEXT_NOTE, dim = true), gap())
        return ScrollView(a).apply { isFillViewport = false; addView(page) }
    }

    /** Up to 6 significant digits, separators, never "1.0E-7" for a everyday value. */
    private fun show(x: Double): String = when {
        x == 0.0 -> "0"
        kotlin.math.abs(x) >= 1e12 || kotlin.math.abs(x) < 1e-6 -> String.format(java.util.Locale.US, "%.4e", x)
        kotlin.math.abs(x) >= 1000 -> String.format(java.util.Locale.US, "%,.2f", x).trimEnd('0').trimEnd('.')
        else -> java.math.BigDecimal(x).round(java.math.MathContext(6)).stripTrailingZeros().toPlainString()
    }

    private fun word(key: String): Int = a.resources.getIdentifier("unit_$key", "string", a.packageName)

    private fun gap() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) }

    private fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    companion object {
        fun kindWord(k: Kind) = when (k) {
            Kind.LENGTH -> R.string.units_kind_length
            Kind.AREA -> R.string.units_kind_area
            Kind.MASS -> R.string.units_kind_mass
            Kind.VOLUME -> R.string.units_kind_volume
            Kind.TEMPERATURE -> R.string.units_kind_temperature
            Kind.SPEED -> R.string.units_kind_speed
            Kind.ENERGY -> R.string.units_kind_energy
            Kind.POWER -> R.string.units_kind_power
        }
    }
}
