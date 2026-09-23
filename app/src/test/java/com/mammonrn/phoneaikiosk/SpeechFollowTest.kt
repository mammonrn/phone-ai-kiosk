package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.SpeechFollow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The Jarvis window following a long answer as it is said (2026-09-23). */
class SpeechFollowTest {

    private val text = "ได้ยิน: อากาศเป็นยังไง\nตอบ: " + "ก".repeat(100)
    private val start = text.indexOf("ตอบ: ") + "ตอบ: ".length

    @Test
    fun `at the start the voice is on the first character of the answer`() {
        assertEquals(start, SpeechFollow.spokenOffset(text, 1_000, 10_000, 1_000))
    }

    @Test
    fun `half way through the audio is half way through the answer`() {
        assertEquals(start + 49, SpeechFollow.spokenOffset(text, 1_000, 10_000, 6_000))
    }

    @Test
    fun `past the end it stays on the last character`() {
        assertEquals(text.length - 1, SpeechFollow.spokenOffset(text, 1_000, 10_000, 99_000))
    }

    @Test
    fun `nothing playing or no answer means nothing to follow`() {
        assertNull(SpeechFollow.spokenOffset(text, 0, 10_000, 5_000))
        assertNull(SpeechFollow.spokenOffset(text, 1_000, 0, 5_000))
        assertNull(SpeechFollow.spokenOffset("ได้ยิน: สวัสดี", 1_000, 10_000, 5_000))
    }

    @Test
    fun `the spoken line sits a third of the way down, never past either end`() {
        assertEquals(400 - 100, SpeechFollow.scrollTarget(400, 300, 1_000))
        assertEquals(0, SpeechFollow.scrollTarget(50, 300, 1_000))       // near the top
        assertEquals(700, SpeechFollow.scrollTarget(990, 300, 1_000))    // near the bottom
        assertEquals(0, SpeechFollow.scrollTarget(200, 300, 250))        // it all fits
    }
}
