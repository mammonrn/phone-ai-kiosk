package com.mammonrn.phoneaikiosk.calc

import android.content.Context
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.MATCH
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.WRAP
import com.mammonrn.phoneaikiosk.calc.Money.Metal
import com.mammonrn.phoneaikiosk.calc.Money.Purity
import com.mammonrn.phoneaikiosk.calc.Money.WeightUnit
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The "ราคา" tab (0.61.0, Poom): two slides of the calculator's one slide
 * component (SlideDeck) — แปลงค่าเงิน and โลหะและแร่.
 *
 * Every number on it comes from the table RateStore holds, and the source,
 * the source's own date and when it was fetched are always under it. No table
 * (never fetched and offline) means no numbers, and says so; an old table is
 * used and its age is said.
 */
internal class RatesPages(private val a: CalculatorActivity) {

    enum class Slide(val title: Int) { CURRENCY(R.string.money_slide_currency), METALS(R.string.money_slide_metals) }

    private val prefs = a.getSharedPreferences("calc_money", Context.MODE_PRIVATE)
    private val main = Handler(Looper.getMainLooper())
    private val deck = SlideDeck(a, { Slide.entries }, { a.getString(it.title) }, { build(it) }, Slide.CURRENCY)

    private var fetching = false
    private var fetchFailed = false
    /** Which currency the picker is choosing, or null when it is closed. */
    private var picking: String? = null
    private var query = ""

    private var from: String get() = prefs.getString("from", "USD")!!; set(v) { prefs.edit().putString("from", v).apply() }
    private var to: String get() = prefs.getString("to", "THB")!!; set(v) { prefs.edit().putString("to", v).apply() }
    private var metalCurrency: String get() = prefs.getString("metal_cur", "THB")!!; set(v) { prefs.edit().putString("metal_cur", v).apply() }
    private var amountText: String get() = prefs.getString("amount", "1")!!; set(v) { prefs.edit().putString("amount", v).apply() }
    private var qtyText: String get() = prefs.getString("qty", "1")!!; set(v) { prefs.edit().putString("qty", v).apply() }
    private var unit: WeightUnit
        get() = runCatching { WeightUnit.valueOf(prefs.getString("unit", "BAHT")!!) }.getOrDefault(WeightUnit.BAHT)
        set(v) { prefs.edit().putString("unit", v.name).apply() }
    private var purity: Purity
        get() = runCatching { Purity.valueOf(prefs.getString("purity", "THAI")!!) }.getOrDefault(Purity.THAI)
        set(v) { prefs.edit().putString("purity", v.name).apply() }
    private var pins: List<String>
        get() = prefs.getString("pins", "THB,USD")!!.split(',').filter { it.isNotBlank() }
        set(v) { prefs.edit().putString("pins", v.joinToString(",")).apply() }

    fun open() {
        RateStore.load(a)
        deck.open()
        val t = RateStore.table
        if (t == null || Money.needsRefresh(t.fetchedAt, System.currentTimeMillis())) refresh()
    }

    /** Fetches now (the button, or a day gone by); the page says it is fetching and never waits on it. */
    private fun refresh() {
        if (fetching) return
        fetching = true
        fetchFailed = false
        deck.render()
        val app = a.applicationContext
        worker.execute {
            val before = RateStore.table?.fetchedAt
            val t = RateStore.fetch(app)
            main.post {
                fetching = false
                fetchFailed = t == null || t.fetchedAt == before
                if (!a.isFinishing && a.tab == CalculatorActivity.Tab.PRICES) deck.render()
            }
        }
    }

    private fun build(s: Slide): View {
        val page = column()
        page.addView(a.text(a.getString(s.title), UiScale.TEXT_HEADING).apply { typeface = Typeface.create(a.thai, Typeface.BOLD) })
        val who = picking
        if (who != null) picker(page, who) else when (s) {
            Slide.CURRENCY -> currency(page)
            Slide.METALS -> metals(page)
        }
        return ScrollView(a).apply { isFillViewport = false; addView(page) }
    }

    // ------------------------------------------------------------ the currency slide

    private fun currency(page: LinearLayout) {
        val t = RateStore.table
        page.addView(a.label(a.getString(R.string.money_amount)), gap())
        val amount = a.numberField("1").apply { setText(amountText); contentDescription = a.getString(R.string.money_amount) }
        page.addView(amount, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)))
        page.addView(a.label(a.getString(R.string.money_from)), gap())
        page.addView(chooser(from, "from"), LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)))
        page.addView(a.button(a.getString(R.string.money_swap)) { val f = from; from = to; to = f; deck.render() },
                     LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_S) })
        page.addView(a.label(a.getString(R.string.money_to)), gap())
        page.addView(chooser(to, "to"), LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)))
        val answer = box()
        page.addView(answer, gap())
        fun draw() {
            answer.removeAllViews()
            val x = amount.text.toString().replace(",", "").trim().toDoubleOrNull()
            when {
                t == null -> answer.addView(a.text(a.getString(R.string.money_no_numbers), UiScale.TEXT_BASE))
                x == null || !x.isFinite() || x < 0 -> answer.addView(a.text(a.getString(R.string.money_bad_amount), UiScale.TEXT_BASE).apply {
                    setTextColor(a.color(R.color.retro_bad)) })
                else -> {
                    val r = Money.convert(x, from, to, t)
                    val one = Money.convert(1.0, from, to, t)
                    if (r == null || one == null) answer.addView(a.text(a.getString(R.string.money_not_at_source, from, to), UiScale.TEXT_BASE))
                    else {
                        answer.addView(a.text("${Money.format(x)} $from = ${Money.format(r)} $to", UiScale.TEXT_HEADING).apply {
                            typeface = Typeface.create(a.thai, Typeface.BOLD) })
                        answer.addView(a.text("1 $from = ${Money.format(one)} $to", UiScale.TEXT_BASE))
                        answer.addView(a.text(a.getString(R.string.money_formula), UiScale.TEXT_NOTE, dim = true))
                    }
                }
            }
        }
        amount.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { amountText = s?.toString().orEmpty(); draw() }
        })
        draw()
        status(page)
    }

    /** The big button that shows a currency and opens the picker: 🇹🇭 ไทย · บาท (THB). */
    private fun chooser(code: String, which: String) = a.button(words(code)) { picking = which; query = ""; deck.render() }.apply {
        maxLines = 2
        contentDescription = a.getString(R.string.money_choose_desc, words(code))
    }

    private fun words(code: String): String {
        val t = RateStore.table ?: return code
        val n = Money.catalogue(t).firstOrNull { it.code == code } ?: return code
        return "${n.flag} ${n.country} · ${n.name} (${n.code})"
    }

    // ------------------------------------------------------------ the picker

    private fun picker(page: LinearLayout, which: String) {
        val t = RateStore.table
        page.addView(a.text(a.getString(when (which) {
            "from" -> R.string.money_pick_from
            "to" -> R.string.money_pick_to
            else -> R.string.money_pick_metal
        }), UiScale.TEXT_BASE, dim = true))
        val search = EditText(a).apply {
            typeface = a.thai; textSize = UiScale.TEXT_BASE; setSingleLine()
            hint = a.getString(R.string.money_search_hint)
            setBackgroundResource(R.drawable.retro_field)
            setPadding(a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_S), 0)
            setText(query)
        }
        page.addView(search, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)).apply { topMargin = a.dp(UiScale.SPACE_S) })
        page.addView(a.button(a.getString(R.string.cancel)) { picking = null; deck.render() },
                     LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_S) })
        val list = column()
        page.addView(list, gap())
        if (t == null) { list.addView(a.text(a.getString(R.string.money_no_numbers), UiScale.TEXT_BASE)); return }
        val all = Money.catalogue(t)
        fun fill() {
            list.removeAllViews()
            val shown = Money.ordered(all, pins).filter { it.matches(query) }
            if (shown.isEmpty()) list.addView(a.text(a.getString(R.string.money_none_found), UiScale.TEXT_BASE, dim = true))
            val pinned = pins.toSet()
            for (n in shown.take(MAX_ROWS)) {
                val on = n.code in pinned
                val row = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
                row.addView(a.button(if (on) "★" else "☆") {
                    pins = if (on) pins - n.code else pins + n.code
                    fill()
                }.apply { contentDescription = a.getString(if (on) R.string.money_unpin else R.string.money_pin, n.code) },
                    LinearLayout.LayoutParams(a.dp(UiScale.TOUCH), a.dp(UiScale.PRIMARY)))
                row.addView(a.button("${n.flag} ${n.country} · ${n.name} (${n.code})") {
                    when (which) { "from" -> from = n.code; "to" -> to = n.code; else -> metalCurrency = n.code }
                    picking = null
                    deck.render()
                }.apply { maxLines = 2; gravity = Gravity.CENTER_VERTICAL or Gravity.START; textSize = UiScale.TEXT_BASE
                    typeface = if (on) Typeface.create(a.thai, Typeface.BOLD) else a.thai },
                    LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 1f).apply { marginStart = a.dp(UiScale.SPACE_XS) })
                list.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_XS) })
            }
            if (shown.size > MAX_ROWS) list.addView(a.text(a.getString(R.string.money_more, shown.size - MAX_ROWS), UiScale.TEXT_NOTE, dim = true))
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { query = s?.toString().orEmpty(); fill() }
        })
        fill()
    }

    // ------------------------------------------------------------ the metals slide

    private fun metals(page: LinearLayout) {
        val t = RateStore.table
        page.addView(a.label(a.getString(R.string.money_quantity)), gap())
        val qty = a.numberField("1").apply { setText(qtyText); contentDescription = a.getString(R.string.money_quantity) }
        page.addView(qty, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)))
        page.addView(row(WeightUnit.entries.map { u -> a.toggle(a.getString(unitWord(u)), u == unit) { unit = u; deck.render() } }), gap())
        page.addView(a.label(a.getString(R.string.money_gold_purity)), gap())
        page.addView(row(Purity.entries.map { p ->
            a.toggle(a.getString(if (p == Purity.PURE) R.string.money_purity_pure else R.string.money_purity_thai), p == purity) { purity = p; deck.render() } }))
        page.addView(a.label(a.getString(R.string.money_show_in)), gap())
        page.addView(chooser(metalCurrency, "metal"), LinearLayout.LayoutParams(MATCH, a.dp(UiScale.PRIMARY)))
        val list = column()
        page.addView(list, gap())
        fun draw() {
            list.removeAllViews()
            val q = qty.text.toString().replace(",", "").trim().toDoubleOrNull()
            if (q == null || !q.isFinite() || q < 0) {
                list.addView(a.text(a.getString(R.string.money_bad_quantity), UiScale.TEXT_BASE).apply { setTextColor(a.color(R.color.retro_bad)) })
            }
            for ((i, m) in Metal.entries.withIndex()) {
                val price = if (t == null || q == null) null else Money.metalPrice(m, q, unit, metalCurrency, t, purity)
                val words = when {
                    m.code == null -> a.getString(R.string.money_no_free_source)
                    t == null -> a.getString(R.string.money_no_numbers_short)
                    !t.has(m.code) -> a.getString(R.string.money_source_has_no_metals)
                    price == null -> "—"
                    else -> "${Money.format(price)} $metalCurrency"
                }
                val line = LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.retro_sunken)
                    setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_XS), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_XS))
                    minimumHeight = a.dp(UiScale.PRIMARY)
                }
                line.addView(ImageView(a).apply { setImageResource(iconOf(m)); importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO },
                             LinearLayout.LayoutParams(a.dp(UiScale.ICON_M), a.dp(UiScale.ICON_M)))
                val name = a.getString(nameOf(m)) + if (m == Metal.GOLD) " " + a.getString(
                    if (purity == Purity.PURE) R.string.money_purity_pure else R.string.money_purity_thai) else ""
                line.addView(a.text("${i + 1}. $name", UiScale.TEXT_BASE).apply { typeface = Typeface.create(a.thai, Typeface.BOLD) },
                             LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = a.dp(UiScale.SPACE_S) })
                line.addView(a.text(words, if (price != null) UiScale.TEXT_BASE else UiScale.TEXT_NOTE, dim = price == null).apply { gravity = Gravity.END },
                             LinearLayout.LayoutParams(0, WRAP, 1f))
                line.contentDescription = "$name $words"
                list.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_XS) })
            }
            list.addView(a.text(a.getString(R.string.money_metal_note, a.getString(unitWord(unit))), UiScale.TEXT_NOTE, dim = true), gap())
        }
        qty.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
            override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) { qtyText = s?.toString().orEmpty(); draw() }
        })
        draw()
        status(page)
    }

    // ------------------------------------------------------------ where from, and how old

    private fun status(page: LinearLayout) {
        val t = RateStore.table
        val now = System.currentTimeMillis()
        val lines = ArrayList<String>()
        if (fetching) lines.add(a.getString(R.string.money_fetching))
        if (t == null) {
            if (!fetching) lines.add(a.getString(R.string.money_nothing_yet))
        } else {
            lines.add(a.getString(R.string.money_source, a.getString(sourceName(t.source)), t.date))
            val hours = Money.ageHours(t.fetchedAt, now)
            val age = when {
                hours < 1 -> a.getString(R.string.money_age_now)
                hours < 48 -> a.getString(R.string.money_age_hours, hours)
                else -> a.getString(R.string.money_age_days, hours / 24)
            }
            lines.add(a.getString(R.string.money_fetched, SimpleDateFormat("d MMM HH:mm", Locale("th", "TH")).format(Date(t.fetchedAt)), age))
            if (fetchFailed && Money.needsRefresh(t.fetchedAt, now)) lines.add(a.getString(R.string.money_offline_old))
            else if (fetchFailed) lines.add(a.getString(R.string.money_refresh_failed))
        }
        val box = box()
        for ((i, l) in lines.withIndex()) box.addView(a.text(l, UiScale.TEXT_NOTE, dim = i > 0 || t != null).apply {
            if (fetchFailed && l == lines.last()) setTextColor(a.color(R.color.retro_bad)) })
        page.addView(box, gap())
        page.addView(a.button(a.getString(if (fetching) R.string.money_fetching_button else R.string.money_refresh)) { refresh() },
                     LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_S) })
    }

    // ------------------------------------------------------------ small parts

    private fun box() = column().apply {
        setBackgroundResource(R.drawable.retro_field)
        setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S))
    }

    private fun row(views: List<TextView>) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((n, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, a.dp(UiScale.TOUCH), 1f).apply { if (n > 0) marginStart = a.dp(UiScale.SPACE_XS) })
    }

    private fun gap() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) }

    private fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    companion object {
        /** Rows in the picker before "and N more — type to find". */
        const val MAX_ROWS = 40
        private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-rates") }

        fun unitWord(u: WeightUnit) = when (u) {
            WeightUnit.OUNCE -> R.string.money_unit_ounce
            WeightUnit.GRAM -> R.string.money_unit_gram
            WeightUnit.KILOGRAM -> R.string.money_unit_kilogram
            WeightUnit.BAHT -> R.string.money_unit_baht
        }

        fun sourceName(s: Money.Source) = when (s) {
            Money.Source.JSDELIVR -> R.string.money_src_jsdelivr
            Money.Source.PAGES -> R.string.money_src_pages
            Money.Source.FRANKFURTER -> R.string.money_src_frankfurter
        }

        fun nameOf(m: Metal) = when (m) {
            Metal.GOLD -> R.string.metal_gold
            Metal.COPPER -> R.string.metal_copper
            Metal.SILVER -> R.string.metal_silver
            Metal.PLATINUM -> R.string.metal_platinum
            Metal.PALLADIUM -> R.string.metal_palladium
            Metal.IRON_ORE -> R.string.metal_iron_ore
            Metal.ALUMINIUM -> R.string.metal_aluminium
            Metal.NICKEL -> R.string.metal_nickel
            Metal.ZINC -> R.string.metal_zinc
            Metal.LITHIUM -> R.string.metal_lithium
        }

        fun iconOf(m: Metal) = when (m) {
            Metal.GOLD -> R.drawable.ic_metal_gold
            Metal.COPPER -> R.drawable.ic_metal_copper
            Metal.SILVER -> R.drawable.ic_metal_silver
            Metal.PLATINUM -> R.drawable.ic_metal_platinum
            Metal.PALLADIUM -> R.drawable.ic_metal_palladium
            Metal.IRON_ORE -> R.drawable.ic_metal_iron_ore
            Metal.ALUMINIUM -> R.drawable.ic_metal_aluminium
            Metal.NICKEL -> R.drawable.ic_metal_nickel
            Metal.ZINC -> R.drawable.ic_metal_zinc
            Metal.LITHIUM -> R.drawable.ic_metal_lithium
        }
    }
}
