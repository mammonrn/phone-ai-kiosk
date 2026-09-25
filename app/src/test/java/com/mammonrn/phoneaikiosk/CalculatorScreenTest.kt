package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The calculator's screen (0.52.0), checked from its source: it stays inside
 * the kiosk, asks for nothing new, leaves the mic and speaker alone, and its
 * icon and words keep DESIGN.md's rules.
 */
class CalculatorScreenTest {

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    private val calc = listOf("CalculatorActivity.kt", "ElectricalPages.kt", "CalcEngine.kt", "Electrical.kt",
                                  "Solar.kt", "SolarPages.kt", "SlideDeck.kt")
        .associateWith { file("src/main/java/com/mammonrn/phoneaikiosk/calc/$it") }

    @Test
    fun `the Control Panel opens it and the manifest keeps it inside the app`() {
        val panel = file("src/main/java/com/mammonrn/phoneaikiosk/settings/SettingsActivity.kt")
        assertTrue("ic_pixel_calculator" in panel && "CalculatorActivity::class.java" in panel)
        val manifest = file("src/main/AndroidManifest.xml")
        val activity = manifest.substringAfter("android:name=\".calc.CalculatorActivity\"").substringBefore("/>")
        assertTrue("android:exported=\"false\"" in activity)
        assertFalse("android:process" in manifest)                  // KioskScreens sees it
    }

    @Test
    fun `it opens nothing but the home screen and never touches the mic or the speaker`() {
        val screen = calc.getValue("CalculatorActivity.kt") + calc.getValue("ElectricalPages.kt") + calc.getValue("SolarPages.kt")
        assertEquals(1, Regex("startActivity\\(").findAll(screen).count())
        assertTrue("Intent(this, MainActivity::class.java)" in screen)
        for (outside in listOf("AudioRecord", "MediaRecorder", "MediaPlayer", "AudioManager", "TextToSpeech",
                               "SoundPool", "ACTION_VIEW", "Uri.parse", "requestPermissions")) {
            assertFalse("the calculator must not use $outside", calc.values.any { outside in it })
        }
    }

    @Test
    fun `the engine is plain Kotlin`() {
        for (name in listOf("CalcEngine.kt", "Electrical.kt", "Solar.kt")) {
            assertFalse("$name imports Android", "import android." in calc.getValue(name))
        }
    }

    @Test
    fun `the icon is 16x16 squares with at most four colours`() {
        val xml = file("src/main/res/drawable/ic_pixel_calculator.xml")
        assertTrue("android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
        assertTrue("Our own pixel art" in xml)
        val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
        assertTrue("${colours.size} colours", colours.size in 1..4)
        for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
            assertFalse("draws curves", Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
        }
    }

    @Test
    fun `its words are formal written Thai`() {
        val strings = file("src/main/res/values/strings.xml")
        val ours = Regex("""<string name="((?:calc|el)_[a-z_]+|window_calculator)">([^<]*)</string>""")
            .findAll(strings).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(ours.size >= 30)
        for ((name, text) in ours) {
            for (spoken in listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว", "ไม่ใส่ก็ได้")) {
                assertFalse("$name is colloquial ($spoken): $text", spoken in text)
            }
        }
    }

    @Test
    fun `the wire page says where its numbers come from and what it does not check`() {
        val strings = file("src/main/res/values/strings.xml")
        val source = Regex("""<string name="el_wire_source">([^<]*)</string>""").find(strings)!!.groupValues[1]
        val ampacity = Regex("""<string name="el_wire_ampacity">([^<]*)</string>""").find(strings)!!.groupValues[1]
        assertTrue("IEC 60228" in source)
        assertTrue("วสท." in ampacity && "พิกัดกระแส" in ampacity)
        assertTrue("IEC 60062" in strings)
    }

    @Test
    fun `the big buttons keep the Control Panel's sizes`() {
        // The keypad's rows share the height left (weight 1 each); how tall
        // that comes out is checked on the A07's screenshot, not here.
        val pages = calc.getValue("CalculatorActivity.kt") + calc.getValue("ElectricalPages.kt")
        // 0.54.0: the sizes are UiScale's names (UiScaleTest); the tool list was 60dp.
        // 0.61.0: the tool list is gone (the tools are slides, Poom); [คำนวณ] [ล้างค่า] keep the 56dp height.
        assertTrue("LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 2f)" in pages)   // คำนวณ
        assertTrue("LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY))" in pages)     // "กลับหน้าหลัก"
    }
}
