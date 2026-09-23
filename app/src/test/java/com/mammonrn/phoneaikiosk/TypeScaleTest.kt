package com.mammonrn.phoneaikiosk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The type scale by importance (Poom, 2026-09-23; DESIGN.md section 4): the
 * tiers stay in order, and the dashboard's data texts take their size from
 * the tier, not a literal — so crypto cannot quietly grow back past gold.
 */
class TypeScaleTest {

    private fun res(path: String): String {
        val candidates = listOf(File("src/main/res/$path"), File("app/src/main/res/$path"))
        return candidates.first { it.exists() }.readText()
    }

    private fun tiers(): Map<String, Float> =
        Regex("""<dimen name="(type_[a-z]+)">([0-9.]+)sp</dimen>""")
            .findAll(res("values/type_scale.xml"))
            .associate { it.groupValues[1] to it.groupValues[2].toFloat() }

    @Test
    fun `larger means more important, one step per tier`() {
        val t = tiers()
        val order = listOf("type_now", "type_primary", "type_secondary", "type_minor",
                           "type_label", "type_chrome")
        assertEquals(order.toSet(), t.keys)
        for ((higher, lower) in order.zipWithNext()) {
            assertTrue("$higher must be larger than $lower", t.getValue(higher) > t.getValue(lower))
        }
        assertEquals("Jarvis's words stay 15sp (the card must not change)", 15f, t.getValue("type_primary"))
    }

    /** The size a view with this id is given in activity_main.xml. */
    private fun sizeOf(layout: String, id: String): String {
        val start = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("no view $id", start >= 0)
        val end = layout.indexOf("/>", start).let { if (it < 0) layout.length else it }
        return Regex("""android:textSize="([^"]+)"""").find(layout.substring(start, end))
            ?.groupValues?.get(1) ?: "none"
    }

    @Test
    fun `each dashboard text sits in its tier`() {
        val layout = res("layout/activity_main.xml")
        val expected = mapOf(
            "weather_body" to "@dimen/type_now",
            "alarms_body" to "@dimen/type_secondary",
            "crypto_body" to "@dimen/type_minor",
            "crypto_body_right" to "@dimen/type_minor",
            "sunrise_text" to "@dimen/type_minor",
            "sunset_text" to "@dimen/type_minor",
            "weather_outlook" to "@dimen/type_minor",
            "gold_header" to "@dimen/type_label",
            "oil_header" to "@dimen/type_label",
        )
        for ((id, size) in expected) assertEquals(id, size, sizeOf(layout, id))
    }
}
