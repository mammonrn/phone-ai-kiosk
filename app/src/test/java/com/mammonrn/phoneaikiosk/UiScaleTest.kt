package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.UiScale
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.54.0 (Poom: "ปรับทุกหน้าในแผงควบคุมตามหลัก UX/UI"): every size on the
 * Control Panel's screens is a name from ui/UiScale, and the names keep the
 * rules — a finger's 48dp, a 4dp grid, text that shrinks with importance and
 * never below 13sp. DESIGN.md section 5ช.
 */
class UiScaleTest {

    private fun file(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }

    private val src = "src/main/java/com/mammonrn/phoneaikiosk"

    /** The Control Panel's screens: the panel's own pages and every screen it opens. */
    private val screens: List<File> by lazy {
        file("$src/settings").listFiles()!!.filter { it.name.endsWith(".kt") } +
            listOf("files/FilesActivity.kt", "calc/CalculatorActivity.kt", "calc/ElectricalPages.kt",
                   "media/MusicActivity.kt", "auth/VerifyActivity.kt").map { file("$src/$it") }
    }

    @Test
    fun `no screen of the Control Panel writes a size as a number`() {
        val literalDp = Regex("""\bdp\(\s*\d""")
        val literalTextSize = Regex("""textSize\s*=\s*(if\s*\([^)]*\)\s*)?\d""")
        val literalTextArg = Regex("""\btext\([^()]*(\([^()]*\))?[^()]*,\s*\d+(\.\d+)?f\b""")
        val bad = mutableListOf<String>()
        for (screen in screens) for ((n, line) in screen.readLines().withIndex()) {
            val code = line.substringBefore("//")
            if (literalDp.containsMatchIn(code) || literalTextSize.containsMatchIn(code) ||
                literalTextArg.containsMatchIn(code) || "setTextSize(" in code) {
                bad += "${screen.name}:${n + 1}: ${line.trim()}"
            }
        }
        assertTrue("sizes must come from UiScale:\n" + bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `a height given to a view is a control's, an icon's or a fixed part's - never a gap`() {
        val height = Regex("""LayoutParams\([^,()]+(?:\([^()]*\))?,\s*(?:a\.)?dp\(UiScale\.(\w+)\)""")
        val allowed = setOf("TOUCH", "PRIMARY", "ICON_BUTTON", "ROW", "ICON_S", "ICON_M", "ICON_L", "ICON_XL",
                            "CAMERA_H", "PATTERN_PAD", "PROGRESS", "DISPLAY_LINE")
        val bad = screens.flatMap { screen ->
            height.findAll(screen.readText()).map { it.groupValues[1] }.filter { it !in allowed }.map { "${screen.name}: $it" }
        }
        assertTrue(bad.joinToString(), bad.isEmpty())
    }

    @Test
    fun `the scale keeps its rules`() {
        // A finger: nothing tapped is under 48dp; the main action is bigger.
        assertTrue(UiScale.TOUCH >= 48)
        assertTrue(UiScale.PRIMARY > UiScale.TOUCH && UiScale.ICON_BUTTON > UiScale.PRIMARY)
        assertTrue(UiScale.ROW >= UiScale.TOUCH && UiScale.SYMBOL_W >= UiScale.TOUCH)
        // Spacing on a 4dp grid, each step larger than the last.
        val spaces = listOf(UiScale.SPACE_XS, UiScale.SPACE_S, UiScale.SPACE_M, UiScale.SPACE_L)
        assertTrue(spaces.all { it % 4 == 0 } && spaces.zipWithNext().all { (a, b) -> b > a })
        assertTrue(UiScale.FRAME % 4 == 0 && UiScale.WINDOW_INSET % 4 == 0)
        // Icons: the 16px art at whole 8dp steps.
        assertEquals(listOf(16, 24, 32, 48), listOf(UiScale.ICON_S, UiScale.ICON_M, UiScale.ICON_L, UiScale.ICON_XL))
        // Text shrinks with importance, and nothing is under 13sp — read at 20 cm.
        val text = listOf(UiScale.TEXT_DISPLAY, UiScale.TEXT_VALUE, UiScale.TEXT_HEADING,
                          UiScale.TEXT_ITEM, UiScale.TEXT_BASE, UiScale.TEXT_NOTE)
        assertTrue(text.zipWithNext().all { (a, b) -> a > b })
        assertTrue(UiScale.TEXT_NOTE >= 13f)
        assertTrue(listOf(UiScale.KEY_TEXT, UiScale.KEY_WORD, UiScale.KEY_SYMBOL, UiScale.KEY_EQUALS).all { it >= 13f })
    }

    @Test
    fun `every word button is at least a finger wide`() {
        for (name in listOf("settings/SettingsActivity.kt", "files/FilesActivity.kt",
                            "calc/CalculatorActivity.kt", "media/MusicActivity.kt")) {
            val body = file("$src/$name").readText().substringAfter("fun button(").substringBefore("\n    }")
            assertTrue(name, "minWidth = dp(UiScale.TOUCH)" in body)
        }
    }

    @Test
    fun `a raised button shows its shadow on the bottom and the right`() {
        // Before 0.54.0 the face covered the shadow: nothing looked raised.
        for (name in listOf("retro_raised", "retro_sunken")) {
            val xml = file("src/main/res/drawable/$name.xml").readText()
            assertTrue(name, "android:left=\"2dp\" android:top=\"2dp\" android:right=\"2dp\" android:bottom=\"2dp\"" in xml)
        }
    }
}
