package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.53.2 (Poom): a card's pages are SLID, not tabbed. The tab row took 44dp
 * of height on the gold card — Jarvis's height. The pages are shown by small
 * squares in the card's title bar instead, which cost no height.
 */
class PagedSlideTest {

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    private val panel = file("src/main/java/com/mammonrn/phoneaikiosk/ui/PagedPanel.kt")
    private val layout = file("src/main/res/layout/activity_main.xml")

    @Test
    fun `the panel adds nothing above its pages - no tab row`() {
        val body = panel.substringAfter("class PagedPanel")
        assertFalse("a tab row is back", "private val tabs" in body || "buildTabs" in body)
        // The only view the panel adds to itself is the content.
        val added = Regex("""\baddView\(content""").findAll(body).count()
        assertTrue(added == 1)
        assertTrue("fun attachIndicator(host: LinearLayout)" in body)
        // Swipe still turns the page.
        assertTrue("override fun onInterceptTouchEvent" in body)
    }

    @Test
    fun `the page squares sit in the title bars, beside the title`() {
        for ((card, dots) in listOf("gold" to "gold_pages_dots", "home" to "home_pages_dots")) {
            val bar = layout.substringAfter("android:id=\"@+id/${card}_titlebar\"").substringBefore("android:id=\"@+id/${card}_badge\"")
            assertTrue("$dots is not in the $card title bar", "android:id=\"@+id/$dots\"" in bar)
        }
        val main = file("src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt")
        assertTrue("commodityPages.attachIndicator(findViewById(R.id.gold_pages_dots))" in main)
    }

    @Test
    fun `news on a page not shown is said in a word, not only a colour`() {
        assertTrue("text = \"ใหม่\"" in panel)
        assertTrue("มีข้อมูลใหม่" in panel)
    }
}
