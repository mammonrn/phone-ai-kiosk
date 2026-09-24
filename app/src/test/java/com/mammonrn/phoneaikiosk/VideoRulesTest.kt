package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.HeatLadder
import com.mammonrn.phoneaikiosk.media.VideoRules
import com.mammonrn.phoneaikiosk.media.VideoRules.RingZone
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.56.0: the video player's rules — 720p, the ring, the speeds, resuming, and the heat ladder. */
class VideoRulesTest {

    @Test
    fun `up to 720p plays, above it and AV1 are refused with the reason`() {
        assertNull(VideoRules.refusal(1280, 720, "video/avc"))
        assertNull(VideoRules.refusal(720, 1280, "video/hevc"))       // a phone video stands up
        assertEquals("1920×1080", VideoRules.refusal(1920, 1080, "video/avc"))
        assertEquals("AV1", VideoRules.refusal(640, 360, "video/av01"))
        assertTrue(VideoRules.playable("a.MKV") && !VideoRules.playable("a.avi"))
    }

    @Test
    fun `the speed button goes round and says its speed`() {
        assertEquals(1.25f, VideoRules.nextSpeed(1f))
        assertEquals(0.5f, VideoRules.nextSpeed(2f))
        assertEquals(listOf("0.5×", "0.75×", "1.0×", "1.25×", "1.5×", "2.0×"), VideoRules.SPEEDS.map(VideoRules::speedWord))
    }

    @Test
    fun `the ring - play in the middle, skips at nine and three, drag elsewhere`() {
        assertEquals(0f, VideoRules.turn(50f, 0f, 50f, 50f), 1e-6f)          // twelve o'clock
        assertEquals(0.25f, VideoRules.turn(100f, 50f, 50f, 50f), 1e-6f)     // three
        assertEquals(0.75f, VideoRules.turn(0f, 50f, 50f, 50f), 1e-6f)       // nine
        assertEquals(RingZone.CENTRE, VideoRules.zone(10f, 0.3f, 28f, 68f, 8f))
        assertEquals(RingZone.BACK, VideoRules.zone(50f, 0.75f, 28f, 68f, 8f))
        assertEquals(RingZone.FORWARD, VideoRules.zone(72f, 0.25f, 28f, 68f, 8f))   // a little outside still counts
        assertEquals(RingZone.RING, VideoRules.zone(50f, 0.0f, 28f, 68f, 8f))
        assertEquals(RingZone.OUTSIDE, VideoRules.zone(90f, 0.0f, 28f, 68f, 8f))
    }

    @Test
    fun `a video starts where it was left, unless barely begun or nearly done`() {
        assertEquals(0L, VideoRules.resumeAt(3_000, 600_000))
        assertEquals(125_000L, VideoRules.resumeAt(125_000, 600_000))
        assertEquals(0L, VideoRules.resumeAt(590_000, 600_000))
        assertEquals("1:02:03", VideoRules.hms(3_723_000))
        assertEquals("0:00:07", VideoRules.hms(7_900))
    }

    @Test
    fun `the heat ladder climbs at once and comes down one step a minute`() {
        val h = HeatLadder(coolDownMs = 60_000)
        assertEquals(HeatLadder.Step.NORMAL, h.update(1, 0))
        assertEquals(HeatLadder.Step.COOLEST, h.update(3, 1_000))          // severe: straight to 40% brightness
        assertEquals(HeatLadder.Step.COOLEST, h.update(0, 2_000))          // cooled, but wait
        assertEquals(HeatLadder.Step.COOLEST, h.update(0, 61_000))
        assertEquals(HeatLadder.Step.COOLER, h.update(0, 62_000))          // one step down after a minute
        assertEquals(HeatLadder.Step.COOLER, h.update(0, 100_000))
        assertEquals(HeatLadder.Step.NORMAL, h.update(0, 122_000))         // and the next a minute later
        assertEquals(HeatLadder.Step.PAUSE, h.update(4, 123_000))
        assertEquals(HeatLadder.Step.STOP, h.update(6, 124_000))
    }

    @Test
    fun `each step keeps what the one before it did`() {
        val steps = HeatLadder.Step.entries
        for ((a, b) in steps.zipWithNext()) {
            assertTrue((a.maxHeight ?: 0) >= (b.maxHeight ?: 0))
            assertTrue(!a.barsOff || b.barsOff)
            assertTrue((a.brightness ?: 1f) >= (b.brightness ?: 1f))
            assertTrue(!a.pause || b.pause)
        }
        assertEquals(720, HeatLadder.Step.NORMAL.maxHeight)
    }
}
