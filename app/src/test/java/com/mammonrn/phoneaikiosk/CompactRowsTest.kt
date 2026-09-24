package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.54.1 (Poom approved three proposals): the lights page puts a name, its
 * "อนุญาตให้สั่ง" and its "ตั้งชื่อ" on one row; the music library's source is
 * an option row, not a second row of navy tabs; "ออฟไลน์" is written once.
 */
class CompactRowsTest {

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText().replace("\r\n", "\n")

    private val lights = file("src/main/java/com/mammonrn/phoneaikiosk/settings/LightsPage.kt")
    private val music = file("src/main/java/com/mammonrn/phoneaikiosk/media/MusicActivity.kt")

    @Test
    fun `a light's name, allow and rename share one row of 48dp controls`() {
        val item = lights.substringAfter("private fun item(").substringBefore("\n    }\n")
        val row = item.substringAfter("block.addView(nameRow(").substringBefore("warning(block, clash)")
        assertTrue("allowBox(" in row && "R.string.lights_rename" in row)
        assertTrue(row.split("a.dp(UiScale.TOUCH)").size - 1 == 2)
        assertTrue("minimumHeight = a.dp(UiScale.TOUCH)" in lights)
        // The old three-row layout is gone.
        assertFalse("private fun nameLine(" in lights)
    }

    @Test
    fun `offline is said once - on the device's line, not again on each light`() {
        val item = lights.substringAfter("private fun item(").substringBefore("\n    }\n")
        assertTrue("if (active && online)" in item)
        val signal = file("src/main/java/com/mammonrn/phoneaikiosk/home/HomeSettings.kt")
            .substringAfter("fun signalLine(").substringBefore("\n    }")
        assertFalse("ออฟไลน์" in signal)
    }

    @Test
    fun `the music source is a flat option row under the tabs, not tabs again`() {
        val library = music.substringAfter("private fun showLibrary()").substringBefore("\n    }\n")
        assertFalse("choice(" in library)
        assertTrue("option(getString(R.string.music_local)" in library && "option(getString(R.string.music_nas)" in library)
        val option = music.substringAfter("private fun option(").substringBefore("\n    }\n")
        assertFalse("retro_title" in option || "setBackground" in option)
        assertTrue("minWidth = dp(UiScale.TOUCH)" in option)
    }
}
