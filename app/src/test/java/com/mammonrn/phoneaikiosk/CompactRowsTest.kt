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
    fun `where the songs come from is a flat option row, not tabs again`() {
        // 0.59.0: the source choice moved into the shared "add" page (media/PlaylistPages.kt).
        val pages = listOf(File("src/main/java/com/mammonrn/phoneaikiosk/media/PlaylistPages.kt"),
                           File("app/src/main/java/com/mammonrn/phoneaikiosk/media/PlaylistPages.kt"))
            .first { it.exists() }.readText().replace("\r\n", "\n")
        val sources = pages.substringAfter("private fun drawSources()").substringBefore("\n    }\n")
        assertFalse("choice(" in sources)
        assertTrue("r.option(" in sources)
        val retro = listOf(File("src/main/java/com/mammonrn/phoneaikiosk/ui/Retro.kt"),
                           File("app/src/main/java/com/mammonrn/phoneaikiosk/ui/Retro.kt"))
            .first { it.exists() }.readText().replace("\r\n", "\n")
        val option = retro.substringAfter("fun option(").substringBefore("\n    }\n")
        assertFalse("retro_title" in option || "setBackground" in option)
        assertTrue("minWidth = dp(UiScale.TOUCH)" in option)
    }
}
