package com.mammonrn.phoneaikiosk.calc

import android.graphics.Typeface
import android.text.Editable
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.MATCH
import com.mammonrn.phoneaikiosk.calc.CalculatorActivity.Companion.WRAP
import com.mammonrn.phoneaikiosk.calc.Electrical.Band
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The "ไฟฟ้า" tab of the calculator (0.52.0): a list of five tools, each a
 * page of its own. The arithmetic is [Electrical]; this only draws and reads
 * the fields.
 *
 * EVERY VALUE HAS ITS UNIT: each field has its unit buttons beside it (the
 * one in use is navy, and says so to TalkBack), and every result is written
 * with its unit. Inputs are checked on "คำนวณ": what cannot be right stops the
 * sum with a reason in red, what is merely unusual is worked out and flagged
 * "โปรดตรวจ". The message goes away as soon as a field is changed.
 */
internal class ElectricalPages(private val a: CalculatorActivity) {

    enum class Tool { OHM, COMBINE, COLOUR, AC, WIRE }

    /**
     * The tools are slides (Poom 2026-09-25): swiped sideways, ■ □ in the title
     * bar, by the calculator's one slide component (SlideDeck), the same as the
     * solar tab. There is no list of tools any more. Each tool still builds its
     * page as before — nothing of the formulas or results changed.
     */
    private val deck = SlideDeck(a, { Tool.entries }, { a.getString(titleOf(it)) }, { build(it) }, Tool.OHM)

    fun open() = deck.open()

    /** Back closes the calculator, as on the solar tab: there is no list to go back to. */
    fun back(): Boolean = false

    /** The page a tool built last, and whether the deck is asking for it now. */
    private var built: View? = null
    private var building = false

    /** The tool being built: the key its typed values are kept under ("OHM:…", as before). */
    private var tool: Tool = Tool.OHM

    private fun build(t: Tool): View {
        tool = t
        building = true
        try {
            when (t) {
                Tool.OHM -> ohm()
                Tool.COMBINE -> combine()
                Tool.COLOUR -> colour()
                Tool.AC -> ac()
                Tool.WIRE -> wire()
            }
        } finally {
            building = false
        }
        return built!!
    }

    /** A tool's page: given to the deck, or — when a tool redraws itself after a choice — shown again. */
    private fun showPage(view: View) {
        built = view
        if (!building) deck.render()
    }

    // ------------------------------------------------------------ Ohm's law

    private fun ohm() {
        val page = toolPage(R.string.el_ohm, R.string.el_ohm_hint)
        val v = Qty("แรงดัน V", listOf("mV" to 1e-3, "V" to 1.0, "kV" to 1e3), 1)
        val i = Qty("กระแส I", listOf("mA" to 1e-3, "A" to 1.0), 1)
        val r = Qty("ความต้านทาน R", listOf("Ω" to 1.0, "kΩ" to 1e3, "MΩ" to 1e6), 0)
        val p = Qty("กำลัง P", listOf("mW" to 1e-3, "W" to 1.0, "kW" to 1e3), 1)
        val fields = listOf('V' to v, 'I' to i, 'R' to r, 'P' to p)
        fields.forEach { page.addView(it.second.view) }
        val out = Output()
        fields.forEach { it.second.onChange { out.clear() } }
        page.addView(actions(onGo = {
            val values = fields.map { (n, q) -> n to q.value() }
            if (values.any { it.second?.isNaN() == true }) return@actions out.error("กรุณากรอกตัวเลขให้ถูกต้อง")
            if (values.count { it.second != null } != 2) return@actions out.error(a.getString(R.string.el_ohm_two))
            val warnings = ArrayList<String>()
            for ((n, value) in values) {
                val check = value?.let { Electrical.checkOhm(n, it) } ?: continue
                if (check.blocking) return@actions out.error(check.message) else warnings.add(check.message)
            }
            val m = values.toMap()
            val res = Electrical.ohm(m['V'], m['I'], m['R'], m['P'])
                ?: return@actions out.error("ค่าชุดนี้คำนวณไม่ได้ (เช่น กระแสเป็น 0 เมื่อต้องหาความต้านทาน)")
            out.show(listOf(
                "V = " + Electrical.withUnit(res.volts, "V"),
                "I = " + Electrical.withUnit(res.amps, "A"),
                "R = " + Electrical.withUnit(res.ohms, "Ω"),
                "P = " + Electrical.withUnit(res.watts, "W")), warnings,
                // What was worked out is bold; what was typed in is not.
                bold = values.withIndex().filter { it.value.second == null }.map { it.index }.toSet())
        }, onClear = { fields.forEach { it.second.clear() }; out.clear() }))
        page.addView(out.view)
        showPage(scroll(page))
    }

    // ------------------------------------------------------------ series / parallel

    private var part = Electrical.Part.RESISTOR

    private fun combine() {
        val page = toolPage(R.string.el_combine, R.string.el_combine_hint)
        page.addView(row(Electrical.Part.entries.map { p ->
            a.toggle(p.thai, p == part) {
                // Another kind of part: other units, so the values typed for the last one go.
                if (p != part) { typed.keys.removeAll { it.startsWith("${Tool.COMBINE}:") }; picked.keys.removeAll { it.startsWith("${Tool.COMBINE}:") } }
                part = p; combine()
            }
        }))
        val units = when (part) {
            Electrical.Part.RESISTOR -> listOf("Ω" to 1.0, "kΩ" to 1e3, "MΩ" to 1e6)
            Electrical.Part.CAPACITOR -> listOf("pF" to 1e-12, "nF" to 1e-9, "µF" to 1e-6)
            Electrical.Part.INDUCTOR -> listOf("µH" to 1e-6, "mH" to 1e-3, "H" to 1.0)
        }
        val fields = (1..MAX_PARTS).map { n -> Qty("ตัวที่ $n", units, if (part == Electrical.Part.RESISTOR) 0 else 2) }
        val out = Output()
        fields.forEachIndexed { n, q ->
            q.onChange { out.clear() }
            if (n >= 3) q.view.visibility = View.GONE                     // three to start with
            page.addView(q.view)
        }
        page.addView(a.button(a.getString(R.string.el_add_part)) {
            fields.firstOrNull { it.view.visibility == View.GONE }?.view?.visibility = View.VISIBLE
        }, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_XS) })
        page.addView(actions(onGo = {
            val given = fields.mapNotNull { it.value() }
            if (given.any { it.isNaN() }) return@actions out.error("กรุณากรอกตัวเลขให้ถูกต้อง")
            if (given.size < 2) return@actions out.error("กรุณากรอกค่าอย่างน้อย 2 ตัว")
            if (given.any { it <= 0 }) return@actions out.error("ค่าทุกตัวต้องมากกว่า 0")
            val (series, parallel) = Electrical.combine(part, given) ?: return@actions out.error("คำนวณไม่ได้")
            out.show(listOf("ต่ออนุกรม = " + Electrical.withUnit(series, part.unit),
                            "ต่อขนาน = " + Electrical.withUnit(parallel, part.unit)), emptyList(), bold = setOf(0, 1))
        }, onClear = { fields.forEach { it.clear() }; out.clear() }))
        page.addView(out.view)
        showPage(scroll(page))
    }

    // ------------------------------------------------------------ colour code

    private var decoding = true
    private var bandCount = 4
    /** Digits at 0-2, the multiplier at 3, the tolerance at 4: 4.7 kΩ ±5 % to start. */
    private val chosen = mutableListOf(Band.YELLOW, Band.VIOLET, Band.BLACK, Band.RED, Band.GOLD)
    private var picking: Int? = null
    private var tolerance = 5.0

    private fun colour() {
        val page = toolPage(R.string.el_colour, R.string.el_colour_source)
        page.addView(row(listOf(
            a.toggle("อ่านจากสี", decoding) { decoding = true; picking = null; colour() },
            a.toggle("หาสีจากค่า", !decoding) { decoding = false; picking = null; colour() })))
        page.addView(row(listOf(
            a.toggle("4 แถบ", bandCount == 4) { bandCount = 4; picking = null; colour() },
            a.toggle("5 แถบ", bandCount == 5) { bandCount = 5; picking = null; colour() })), gap())
        if (decoding) decode(page) else encode(page)
        showPage(scroll(page))
    }

    private fun roleOf(index: Int): String {
        val digits = bandCount - 2
        return when {
            index < digits -> "แถบที่ ${index + 1}: หลักที่ ${index + 1}"
            index == digits -> "แถบที่ ${index + 1}: ตัวคูณ"
            else -> "แถบที่ ${index + 1}: ค่าความคลาดเคลื่อน"
        }
    }

    /** Which colours a band at [index] may take (IEC 60062). */
    private fun allowed(index: Int): List<Band> {
        val digits = bandCount - 2
        return when {
            index < digits -> Band.entries.filter { it.digit != null && !(index == 0 && it == Band.BLACK) }
            index == digits -> Band.entries.filter { it.exponent != null }
            else -> Band.entries.filter { it.tolerance != null }
        }
    }

    /** chosen[] holds digits at 0-2, multiplier at 3, tolerance at 4, whatever the count. */
    private fun slot(index: Int): Int {
        val digits = bandCount - 2
        return when {
            index < digits -> index
            index == digits -> 3
            else -> 4
        }
    }

    private fun decode(page: LinearLayout) {
        val current = (0 until bandCount).map { chosen[slot(it)] }
        for (index in 0 until bandCount) {
            val band = current[index]
            page.addView(a.label(roleOf(index)).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS)) })
            page.addView(swatchButton(band, picking == index) { picking = if (picking == index) null else index; colour() },
                         LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)))
            if (picking == index) page.addView(palette(allowed(index)) { b -> chosen[slot(index)] = b; picking = null; colour() })
        }
        val reading = Electrical.read(current)
        page.addView(Output().apply {
            if (reading == null) error("สีชุดนี้ไม่ใช่รหัสที่ถูกต้อง")
            else {
                val lo = reading.ohms * (1 - reading.tolerance / 100)
                val hi = reading.ohms * (1 + reading.tolerance / 100)
                show(listOf(Electrical.withUnit(reading.ohms, "Ω") + " ±" + Electrical.plain(reading.tolerance) + "%",
                            "ช่วงค่าจริง " + Electrical.withUnit(lo, "Ω") + " ถึง " + Electrical.withUnit(hi, "Ω")), emptyList())
            }
        }.view)
    }

    private fun encode(page: LinearLayout) {
        val value = Qty("ค่าความต้านทาน", listOf("Ω" to 1.0, "kΩ" to 1e3, "MΩ" to 1e6), 1)
        page.addView(value.view)
        page.addView(a.label("ค่าความคลาดเคลื่อน").apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS)) })
        page.addView(row(listOf(1.0, 2.0, 5.0, 10.0).map { t ->
            a.toggle("±" + Electrical.plain(t) + "%", tolerance == t) { tolerance = t; colour() }
        }))
        val out = Output()
        value.onChange { out.clear() }
        page.addView(actions(onGo = {
            val ohms = value.value() ?: return@actions out.error("กรุณากรอกค่าความต้านทาน")
            if (ohms.isNaN() || ohms <= 0) return@actions out.error("ค่าความต้านทานต้องมากกว่า 0")
            val code = Electrical.encode(ohms, bandCount - 2, tolerance)
                ?: return@actions out.error("ค่านี้อยู่นอกช่วงที่รหัสสี $bandCount แถบแสดงได้")
            val lines = code.bands.mapIndexed { i, b -> roleOf(i) + " = " + b.thai }
            val warn = if (code.exact) emptyList() else
                listOf("ค่านี้ต้องใช้มากกว่า ${bandCount - 2} หลัก จึงปัดเป็น " + Electrical.withUnit(code.shown, "Ω"))
            out.show(listOf("ได้ " + Electrical.withUnit(code.shown, "Ω") + " ±" + Electrical.plain(tolerance) + "%") + lines, warn)
            out.swatches(code.bands)
        }, onClear = { value.clear(); out.clear() }))
        page.addView(out.view)
    }

    private fun swatchButton(band: Band, open: Boolean, onClick: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.retro_button)
        setPadding(a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_S), 0)
        isClickable = true
        contentDescription = "สี" + band.thai + if (open) " (กำลังเลือก)" else " แตะเพื่อเปลี่ยน"
        setOnClickListener { onClick() }
        addView(swatch(band), LinearLayout.LayoutParams(a.dp(UiScale.ICON_L), a.dp(UiScale.ICON_L)))
        addView(a.text(band.thai, UiScale.TEXT_ITEM).apply { setPadding(a.dp(UiScale.SPACE_S), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(a.text(if (open) "▲" else "▼", UiScale.TEXT_BASE))
    }

    private fun swatch(band: Band) = View(a).apply {
        // A dark edge round every swatch: white and silver would vanish on the grey face.
        background = android.graphics.drawable.GradientDrawable().apply {
            setColor(0xFF000000.toInt() or band.rgb)
            setStroke(a.dp(UiScale.BEVEL), a.color(R.color.retro_dark))
        }
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
    }

    private fun palette(options: List<Band>, onPick: (Band) -> Unit): View {
        val grid = column()
        for (line in options.chunked(3)) {
            grid.addView(row(line.map { b ->
                LinearLayout(a).apply {
                    orientation = LinearLayout.HORIZONTAL
                    gravity = Gravity.CENTER_VERTICAL
                    setBackgroundResource(R.drawable.retro_button)
                    setPadding(a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS), 0)
                    isClickable = true
                    contentDescription = "เลือกสี" + b.thai
                    setOnClickListener { onPick(b) }
                    addView(swatch(b), LinearLayout.LayoutParams(a.dp(UiScale.ICON_M), a.dp(UiScale.ICON_M)))
                    addView(a.text(b.thai, UiScale.TEXT_NOTE).apply { setPadding(a.dp(UiScale.SPACE_S), 0, 0, 0); maxLines = 1 })
                }
            } + List(3 - line.size) { View(a) }), LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_XS) })
        }
        return grid.apply { setPadding(0, 0, 0, a.dp(UiScale.SPACE_S)) }
    }

    // ------------------------------------------------------------ AC

    private var threePhase = false

    private fun ac() {
        val page = toolPage(R.string.el_ac, R.string.el_ac_hint)
        page.addView(row(listOf(
            a.toggle("1 เฟส", !threePhase) { threePhase = false; ac() },
            a.toggle("3 เฟส", threePhase) { threePhase = true; ac() })))
        val v = Qty(if (threePhase) "แรงดันระหว่างสาย V" else "แรงดัน V", listOf("V" to 1.0, "kV" to 1e3), 0,
                    if (threePhase) "400" else "230")
        val i = Qty("กระแส I", listOf("mA" to 1e-3, "A" to 1.0), 1)
        val pf = Qty("ตัวประกอบกำลัง PF (0 ถึง 1)", emptyList(), 0, "1")
        listOf(v, i, pf).forEach { page.addView(it.view) }
        val out = Output()
        listOf(v, i, pf).forEach { q -> q.onChange { out.clear() } }
        page.addView(actions(onGo = {
            val vv = v.value(); val ii = i.value(); val pp = pf.value()
            if (vv == null || ii == null || pp == null) return@actions out.error("กรุณากรอกแรงดัน กระแส และตัวประกอบกำลัง")
            if (vv.isNaN() || ii.isNaN() || pp.isNaN()) return@actions out.error("กรุณากรอกตัวเลขให้ถูกต้อง")
            if (vv <= 0) return@actions out.error("แรงดันต้องมากกว่า 0")
            if (ii < 0) return@actions out.error("กระแสต้องไม่ติดลบ")
            if (pp < 0 || pp > 1) return@actions out.error("ตัวประกอบกำลังต้องอยู่ระหว่าง 0 ถึง 1")
            val warn = ArrayList<String>()
            if (vv > 1000) warn.add("แรงดันเกิน 1 kV สูงกว่าระบบแรงต่ำ กรุณาตรวจหน่วย")
            if (ii > 5000) warn.add("กระแสเกิน 5 kA ผิดปกติ กรุณาตรวจหน่วย")
            val p = Electrical.acPower(vv, ii, pp, threePhase)!!
            out.show(listOf("กำลังจริง P = " + Electrical.withUnit(p.watts, "W"),
                            "กำลังปรากฏ S = " + Electrical.withUnit(p.va, "VA"),
                            "กำลังรีแอกทีฟ Q = " + Electrical.withUnit(p.vars, "var")), warn)
        }, onClear = { listOf(v, i, pf).forEach { it.clear() }; out.clear() }))
        page.addView(out.view)

        // Reactance: whichever of L and C is filled in.
        page.addView(a.label(a.getString(R.string.el_reactance)).apply { setPadding(0, a.dp(UiScale.SPACE_M), 0, a.dp(UiScale.SPACE_XS)) })
        val f = Qty("ความถี่ f", listOf("Hz" to 1.0), 0, "50")
        val l = Qty("ความเหนี่ยวนำ L", listOf("µH" to 1e-6, "mH" to 1e-3, "H" to 1.0), 1)
        val c = Qty("ความจุ C", listOf("nF" to 1e-9, "µF" to 1e-6, "mF" to 1e-3), 1)
        listOf(f, l, c).forEach { page.addView(it.view) }
        val out2 = Output()
        listOf(f, l, c).forEach { q -> q.onChange { out2.clear() } }
        page.addView(actions(onGo = {
            val ff = f.value() ?: return@actions out2.error("กรุณากรอกความถี่")
            if (ff.isNaN() || ff <= 0) return@actions out2.error("ความถี่ต้องมากกว่า 0")
            val ll = l.value(); val cc = c.value()
            if (ll == null && cc == null) return@actions out2.error("กรุณากรอก L หรือ C อย่างน้อยหนึ่งค่า")
            if (ll?.isNaN() == true || cc?.isNaN() == true) return@actions out2.error("กรุณากรอกตัวเลขให้ถูกต้อง")
            if ((ll != null && ll <= 0) || (cc != null && cc <= 0)) return@actions out2.error("L และ C ต้องมากกว่า 0")
            val lines = ArrayList<String>()
            ll?.let { lines.add("X_L = " + Electrical.withUnit(Electrical.inductiveReactance(ff, it)!!, "Ω")) }
            cc?.let { lines.add("X_C = " + Electrical.withUnit(Electrical.capacitiveReactance(ff, it)!!, "Ω")) }
            out2.show(lines, if (ff != 50.0 && ff != 60.0) listOf("ไฟบ้านในไทยเป็น 50 Hz") else emptyList())
        }, onClear = { listOf(l, c).forEach { it.clear() }; out2.clear() }))
        page.addView(out2.view)
        showPage(scroll(page))
    }

    // ------------------------------------------------------------ wire size

    private var wireThree = false

    private fun wire() {
        val page = toolPage(R.string.el_wire, R.string.el_wire_hint)
        page.addView(row(listOf(
            a.toggle("1 เฟส", !wireThree) { wireThree = false; wire() },
            a.toggle("3 เฟส", wireThree) { wireThree = true; wire() })))
        val v = Qty(if (wireThree) "แรงดันระหว่างสาย" else "แรงดัน", listOf("V" to 1.0), 0, if (wireThree) "400" else "230")
        val i = Qty("กระแสของโหลด", listOf("A" to 1.0), 0)
        val len = Qty("ความยาวสาย (ระยะทางเดียว)", listOf("m" to 1.0), 0)
        val limit = Qty("แรงดันตกที่ยอมได้", listOf("%" to 1.0), 0, "3")
        val fields = listOf(v, i, len, limit)
        fields.forEach { page.addView(it.view) }
        val out = Output()
        fields.forEach { q -> q.onChange { out.clear() } }
        page.addView(actions(onGo = {
            val vv = v.value(); val ii = i.value(); val ll = len.value(); val lim = limit.value()
            if (listOf(vv, ii, ll, lim).any { it == null }) return@actions out.error("กรุณากรอกให้ครบทุกช่อง")
            if (listOf(vv, ii, ll, lim).any { it!!.isNaN() }) return@actions out.error("กรุณากรอกตัวเลขให้ถูกต้อง")
            if (vv!! <= 0 || ii!! <= 0 || ll!! <= 0) return@actions out.error("แรงดัน กระแส และความยาวต้องมากกว่า 0")
            if (lim!! <= 0 || lim > 20) return@actions out.error("แรงดันตกที่ยอมได้ต้องอยู่ระหว่าง 0 ถึง 20%")
            val warn = ArrayList<String>()
            if (ii > 400) warn.add("กระแสเกิน 400 A ตารางนี้มีสายถึง 120 ตร.มม. เท่านั้น")
            if (ll > 1000) warn.add("สายยาวเกิน 1 กม. ผิดปกติสำหรับไฟบ้าน กรุณาตรวจหน่วย")
            val drops = Electrical.drops(vv, ii, ll, wireThree)!!
            val best = Electrical.smallestFor(drops, lim)
            val lines = ArrayList<String>()
            if (best == null) lines.add("ไม่มีขนาดใดถึง 120 ตร.มม. ที่แรงดันตกไม่เกิน ${Electrical.plain(lim)}%")
            else lines.add("เล็กที่สุดที่ตกไม่เกิน ${Electrical.plain(lim)}%: ${Electrical.plain(best.mm2)} ตร.มม.")
            val from = drops.indexOf(best).takeIf { it >= 0 }?.minus(1)?.coerceAtLeast(0) ?: (drops.size - 4)
            for (d in drops.drop(from).take(4)) {
                lines.add("${Electrical.plain(d.mm2)} ตร.มม.: ตก ${Electrical.plain(d.volts, 3)} V (${Electrical.plain(d.percent, 3)}%)")
            }
            // The one thing this page does not check goes with every answer, not only at the foot.
            warn.add(a.getString(R.string.el_wire_ampacity_short))
            out.show(lines, warn)
        }, onClear = { listOf(i, len).forEach { it.clear() }; out.clear() }))
        page.addView(out.view)
        page.addView(a.text(a.getString(R.string.el_wire_source), UiScale.TEXT_NOTE, dim = true).apply { setPadding(0, a.dp(UiScale.SPACE_S), 0, 0) })
        page.addView(a.text(a.getString(R.string.el_wire_ampacity), UiScale.TEXT_NOTE).apply {
            setTextColor(a.color(R.color.retro_bad)); setPadding(0, a.dp(UiScale.SPACE_S), 0, 0)
        })
        showPage(scroll(page))
    }

    // ------------------------------------------------------------ parts

    /**
     * What was typed, and the unit picked, per field ("tool:label"). A toggle
     * on the page (±5 %, 3 เฟส, ตัวเก็บประจุ) draws the page again; what was
     * typed must still be there (ux-ui-design: never clear what was entered).
     */
    private val typed = HashMap<String, String>()
    private val picked = HashMap<String, Int>()

    /** A number with a unit: the label, the field, and the unit buttons. */
    private inner class Qty(label: String, private val units: List<Pair<String, Double>>, start: Int,
                            initial: String = "") {
        private val key = "$tool:$label"
        private var unit = picked[key] ?: start
        val field: EditText = a.numberField().apply {
            setText(typed[key] ?: initial)
            a.nameField(this, label)            // read by the screen reader (0.63.0 UI check)
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { typed[key] = s?.toString().orEmpty() }
            })
        }
        private val unitViews = ArrayList<TextView>()
        val view: LinearLayout = column().apply {
            addView(a.label(label + if (units.size == 1) " (${units[0].first})" else "").apply {
                setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_XS))
            })
            val line = LinearLayout(a).apply { orientation = LinearLayout.HORIZONTAL }
            line.addView(field, LinearLayout.LayoutParams(0, a.dp(UiScale.TOUCH), 1f))
            if (units.size > 1) for ((n, u) in units.withIndex()) {
                val t = unitButton(u.first, n == unit) { pick(n) }
                unitViews.add(t)
                line.addView(t, LinearLayout.LayoutParams(a.dp(UiScale.TOUCH), a.dp(UiScale.TOUCH)).apply { marginStart = a.dp(UiScale.SPACE_XS) })
            }
            addView(line, LinearLayout.LayoutParams(MATCH, WRAP))
        }

        private fun pick(n: Int) {
            unit = n
            picked[key] = n
            for ((k, t) in unitViews.withIndex()) styleUnit(t, units[k].first, k == n)
            changed?.invoke()
        }

        private var changed: (() -> Unit)? = null

        fun onChange(block: () -> Unit) {
            changed = block
            field.addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, st: Int, c: Int, af: Int) = Unit
                override fun onTextChanged(s: CharSequence?, st: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) = block()
            })
        }

        /** Null when empty, NaN when not a number, else the value in base units. */
        fun value(): Double? {
            val text = field.text.toString().trim().replace(',', '.')
            if (text.isEmpty()) return null
            val number = text.toDoubleOrNull() ?: return Double.NaN
            return number * (units.getOrNull(unit)?.second ?: 1.0)
        }

        fun clear() = field.setText("")
    }

    private fun unitButton(value: String, on: Boolean, onClick: () -> Unit) = TextView(a).apply {
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
        styleUnit(this, value, on)
    }

    private fun styleUnit(t: TextView, value: String, on: Boolean) = t.apply {
        text = value
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(a.thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(a.color(if (on) R.color.retro_title_text else R.color.retro_text))
        if (on) setBackgroundColor(a.color(R.color.retro_title)) else setBackgroundResource(R.drawable.retro_button)
        contentDescription = "หน่วย $value" + if (on) " (เลือกอยู่)" else ""
    }

    /** The answer box: results in black, then warnings, or one error in red. */
    private inner class Output {
        val view: LinearLayout = column().apply {
            setBackgroundResource(R.drawable.retro_field)
            setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S))
            visibility = View.GONE
        }

        fun clear() { view.removeAllViews(); view.visibility = View.GONE }

        fun error(message: String) {
            clear()
            view.addView(a.text("ผิดพลาด: $message", UiScale.TEXT_BASE).apply { setTextColor(a.color(R.color.retro_bad)) })
            view.visibility = View.VISIBLE
        }

        /** [bold]: which lines are the answer (the first, unless said otherwise). */
        fun show(lines: List<String>, warnings: List<String>, bold: Set<Int> = setOf(0)) {
            clear()
            for ((n, line) in lines.withIndex()) view.addView(a.text(line, if (n in bold) UiScale.TEXT_HEADING else UiScale.TEXT_ITEM).apply {
                if (n in bold) typeface = Typeface.create(a.thai, Typeface.BOLD)
                setPadding(0, if (n == 0) 0 else a.dp(UiScale.SPACE_XS), 0, 0)
            })
            for (w in warnings) view.addView(a.text("โปรดตรวจ: $w", UiScale.TEXT_NOTE).apply {
                setTextColor(a.color(R.color.retro_bad)); setPadding(0, a.dp(UiScale.SPACE_S), 0, 0)
            })
            view.visibility = View.VISIBLE
        }

        /** The bands drawn as a resistor body, each with its name under it. */
        fun swatches(bands: List<Band>) {
            view.addView(row(bands.map { b ->
                column().apply {
                    gravity = Gravity.CENTER_HORIZONTAL
                    addView(swatch(b), LinearLayout.LayoutParams(a.dp(UiScale.ICON_L), a.dp(UiScale.ICON_L)))
                    addView(a.text(b.thai, UiScale.TEXT_NOTE).apply { gravity = Gravity.CENTER })
                }
            }, WRAP), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        }
    }

    private fun toolPage(title: Int, hint: Int) = column().apply {
        addView(a.text(a.getString(title), UiScale.TEXT_HEADING).apply { typeface = Typeface.create(a.thai, Typeface.BOLD) })
        addView(a.text(a.getString(hint), UiScale.TEXT_NOTE, dim = true).apply { setPadding(0, a.dp(UiScale.SPACE_XS), 0, a.dp(UiScale.SPACE_XS)) })
    }

    private fun actions(onGo: () -> Unit, onClear: () -> Unit) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        setPadding(0, a.dp(UiScale.SPACE_S), 0, a.dp(UiScale.SPACE_S))
        addView(a.button(a.getString(R.string.el_calculate), big = true) { onGo() }, LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 2f))
        addView(a.button(a.getString(R.string.el_clear)) { onClear() },
                LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 1f).apply { marginStart = a.dp(UiScale.SPACE_S) })
    }

    private fun row(views: List<View>, height: Int = a.dp(UiScale.TOUCH)) = LinearLayout(a).apply {
        orientation = LinearLayout.HORIZONTAL
        for ((n, v) in views.withIndex()) addView(v, LinearLayout.LayoutParams(0, height, 1f).apply { if (n > 0) marginStart = a.dp(UiScale.SPACE_S) })
    }

    private fun gap() = LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) }

    private fun column() = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }

    private fun scroll(view: View) = ScrollView(a).apply { isFillViewport = false; addView(view) }

    companion object {
        const val MAX_PARTS = 8

        fun titleOf(t: Tool): Int = when (t) {
            Tool.OHM -> R.string.el_ohm
            Tool.COMBINE -> R.string.el_combine
            Tool.COLOUR -> R.string.el_colour
            Tool.AC -> R.string.el_ac
            Tool.WIRE -> R.string.el_wire
        }
    }
}
