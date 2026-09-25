package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.calc.Solar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The calculator's solar tab (0.62.0), checked from its source: slides, not
 * tabs (Poom: "ใช้แบบสไลด์ตามกฎเดิม ไม่ใช่แท็บปุ่ม"); every field has a name
 * to be blamed by; every rule has words; the words are formal; the
 * "estimate" note is on every slide.
 */
class SolarScreenTest {

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    private val pages = file("src/main/java/com/mammonrn/phoneaikiosk/calc/SolarPages.kt")
    private val activity = file("src/main/java/com/mammonrn/phoneaikiosk/calc/CalculatorActivity.kt")
    private val strings = file("src/main/res/values/strings_solar.xml")

    private fun string(name: String): String =
        Regex("""<string name="$name"[^>]*>([^<]*)</string>""").find(strings)?.groupValues?.get(1)
            ?: throw AssertionError("no string $name")

    @Test
    fun `the tools are slides turned by a swipe, with squares in the title bar - no tab row`() {
        val body = pages.substringAfter("internal class SolarPages")
        // The swipe and the squares are the calculator's one slide component, shared with the electrical tab.
        val deck = file("src/main/java/com/mammonrn/phoneaikiosk/calc/SlideDeck.kt")
        assertTrue("SlideDeck(" in body)
        assertTrue("override fun onInterceptTouchEvent" in deck)
        assertTrue("slop * 2" in deck && "abs(dy) * 1.5f" in deck)          // PagedPanel's rule
        assertTrue("PagedPanel.SQUARE_DP" in deck)                          // the same squares
        assertFalse("a tab row is back", "private val tabs" in body || "fun list()" in body)
        // The squares live in the window's title bar, and only while on the solar tab.
        assertTrue("bar.addView(pageDots" in activity)
        assertTrue("pageDots.visibility = View.GONE" in activity)
        assertTrue("Tab.SOLAR -> solar.open()" in activity)
    }

    /** Poom 2026-09-25: the electrical tab's tools are slides too, by the same component, not a copy. */
    @Test
    fun `the electrical tools are slides of the same deck, with no list of buttons and no second swipe code`() {
        val el = file("src/main/java/com/mammonrn/phoneaikiosk/calc/ElectricalPages.kt")
        assertTrue("SlideDeck(" in el)
        assertFalse("fun list()" in el || "R.string.el_back" in el)
        for (other in listOf(el, pages)) assertFalse("a second swipe", "onInterceptTouchEvent" in other || "scaledTouchSlop" in other)
    }

    @Test
    fun `every field has a label and a name, and every rule has words`() {
        for (f in Solar.Field.entries) {
            assertTrue("no label for $f", "Field.$f -> R.string.solar_" in pages)
        }
        // labelOf and nameOf: each field twice.
        for (f in Solar.Field.entries) {
            assertEquals("$f", 2, Regex("""Field\.$f -> R\.string\.solar_""").findAll(pages).count())
        }
        for (r in Solar.Rule.entries) assertTrue("no words for $r", "Rule.$r ->" in pages)
        for (name in Regex("""R\.string\.(solar_[a-z_]+)""").findAll(pages).map { it.groupValues[1] }.toSet()) {
            string(name)
        }
        assertEquals("กรุณากรอก%1\$s", string("solar_e_missing"))
        assertEquals("%1\$s ต้องมากกว่า 0", string("solar_e_not_positive"))
        assertEquals("ชั่วโมงแดด", string("solar_n_sun_hours"))
    }

    @Test
    fun `the estimate note and a formula go with the answers`() {
        assertEquals("ผลเป็นค่าประมาณ การติดตั้งจริงควรให้ช่างไฟฟ้าตรวจ", string("solar_estimate"))
        for (x in listOf("panels", "battery", "inverter", "mppt", "pwm", "strings", "wire", "payback")) {
            assertTrue("solar_x_$x", string("solar_x_$x").startsWith("สูตร"))
        }
        // The assumptions are said on the screen, not only in the code.
        assertTrue("25%" in string("solar_x_inverter"))
        assertTrue("0.0175" in string("solar_x_wire") && "2 ×" in string("solar_x_wire"))
        assertTrue("90%" in string("solar_note_battery") && "50%" in string("solar_note_battery"))
        assertTrue("4.2" in string("solar_note_payback"))
    }

    @Test
    fun `its words are formal written Thai`() {
        val all = Regex("""<string name="([a-z_]+)"[^>]*>([^<]*)</string>""").findAll(strings).toList()
        assertTrue(all.size >= 80)
        for (m in all) for (spoken in listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว", "ไม่ใส่ก็ได้")) {
            assertFalse("${m.groupValues[1]} is colloquial ($spoken)", spoken in m.groupValues[2])
        }
        // A % in a string that takes values is written %%.
        for (m in all.filter { "%1$" in it.groupValues[2] }) {
            assertFalse(m.groupValues[1], Regex("""%(?![0-9]\$|%)""").containsMatchIn(m.groupValues[2].replace("%%", "")))
        }
    }
}
