package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.JarvisBadge
import com.mammonrn.phoneaikiosk.voice.JarvisStatus
import com.mammonrn.phoneaikiosk.voice.JarvisStatus.Companion.of
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The badge on every screen (0.61.0): one state, and each state its own shape. */
class JarvisStatusTest {

    @Test
    fun `the states in the home card's order`() {
        assertEquals(JarvisStatus.READY, of("open", "idle", "idle", "idle", resting = false, online = true, hasToken = true))
        assertEquals(JarvisStatus.LISTENING, of("open", "recording", "idle", "idle", false, true, true))
        assertEquals(JarvisStatus.THINKING, of("open", "sending", "idle", "idle", false, true, true))
        assertEquals(JarvisStatus.THINKING, of("open", "idle", "asking", "idle", false, true, true))
        assertEquals(JarvisStatus.SPEAKING, of("open", "idle", "idle", "speaking", false, true, true))
        assertEquals(JarvisStatus.RESTING, of("open", "idle", "idle", "idle", resting = true, online = true, hasToken = true))
        // A question asked with the button while resting still shows what Jarvis is doing.
        assertEquals(JarvisStatus.LISTENING, of("open", "recording", "idle", "idle", resting = true, online = true, hasToken = true))
        assertEquals(JarvisStatus.UNAVAILABLE, of("no-permission", "idle", "idle", "idle", false, true, true))
        assertEquals(JarvisStatus.UNAVAILABLE, of("open", "idle", "idle", "idle", false, online = false, hasToken = true))
        assertEquals(JarvisStatus.UNAVAILABLE, of("open", "idle", "idle", "idle", false, online = true, hasToken = false))
    }

    @Test
    fun `six different shapes, each 12 by 12, and words for each`() {
        val shapes = JarvisBadge.GLYPHS
        assertEquals(JarvisStatus.entries.toSet(), shapes.keys)
        for ((s, rows) in shapes) {
            assertEquals(s.name, 12, rows.size)
            assertTrue(s.name, rows.all { it.length == 12 && it.all { c -> c == '#' || c == '.' } })
            assertTrue(JarvisBadge.words(s).startsWith("จาร์วิส"))
        }
        assertEquals("no two states share a shape", shapes.size, shapes.values.toSet().size)
    }
}
