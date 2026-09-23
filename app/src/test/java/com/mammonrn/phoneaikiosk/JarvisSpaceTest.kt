package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertTrue
import java.io.File
import org.junit.Test

/**
 * The space rule (0.42.0, Poom, DESIGN.md "แบ่งพื้นที่"): the windows above
 * Jarvis are as tall as their content, and Jarvis takes the rest — never less
 * than 156dp, always directly above the taskbar.
 */
class JarvisSpaceTest {

    private val layout: String = listOf(File("src/main/res/layout/activity_main.xml"),
                                        File("app/src/main/res/layout/activity_main.xml"))
        .first { it.exists() }.readText()

    private fun element(id: String): String {
        val start = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("no $id in activity_main.xml", start >= 0)
        val open = layout.lastIndexOf('<', start)
        return layout.substring(open, layout.indexOf('>', start))
    }

    @Test
    fun `the card stack is as tall as its content`() {
        val stack = element("card_stack")
        assertTrue(stack, stack.contains("android:layout_height=\"wrap_content\""))
        assertTrue(stack, !stack.contains("layout_weight"))
    }

    @Test
    fun `jarvis takes the rest and never less than 156dp`() {
        val jarvis = element("card_jarvis")
        assertTrue(jarvis, jarvis.contains("android:layout_height=\"0dp\""))
        assertTrue(jarvis, jarvis.contains("android:layout_weight=\"1\""))
        assertTrue(jarvis, jarvis.contains("android:minHeight=\"156dp\""))
    }
}
