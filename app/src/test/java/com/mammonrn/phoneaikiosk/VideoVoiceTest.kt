package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.Track
import com.mammonrn.phoneaikiosk.media.Video
import com.mammonrn.phoneaikiosk.media.VideoVoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** 0.57.0: "เปิดวิดีโอ / หยุดวิดีโอ" on the phone — found, not found, nothing playing. */
class VideoVoiceTest {

    private class FakeDeck(override var hasMedia: Boolean = false, override var playing: Boolean = false,
                           override var hasLast: Boolean = false) : VideoVoice.Deck {
        val did = ArrayList<String>()
        override fun play(videos: List<Video>, start: Int) { did += "play:${videos[start].track.title}"; hasMedia = true; playing = true }
        override fun resume() { did += "resume"; playing = true }
        override fun pause() { did += "pause"; playing = false }
        override fun stop() { did += "stop"; hasMedia = false; playing = false }
    }

    private val library = listOf("งานบวช", "ทะเลหัวหิน", "วันเกิดแม่").map { Video(Track("local:/v/$it.mp4", it)) }

    @Test
    fun `a named video is found and played, even said a little off`() {
        val deck = FakeDeck()
        assertNull(VideoVoice.perform("play", "ทะเล หัวหิน", { library }, deck))
        assertEquals(listOf("play:ทะเลหัวหิน"), deck.did)
        val deck2 = FakeDeck()
        assertNull(VideoVoice.perform("play", "วันเกิดแม", { library }, deck2))      // a letter short
        assertEquals(listOf("play:วันเกิดแม่"), deck2.did)
    }

    @Test
    fun `not found is said as not found, and nothing plays`() {
        val deck = FakeDeck()
        assertEquals("ไม่พบวิดีโอ \"งานแต่ง\" ในเครื่องครับ", VideoVoice.perform("play", "งานแต่ง", { library }, deck))
        assertTrue(deck.did.isEmpty())
        assertEquals(VideoVoice.NO_VIDEOS, VideoVoice.perform("play", "อะไร", { emptyList() }, FakeDeck()))
    }

    @Test
    fun `pause and stop need a video, and do not claim one`() {
        // 0.58.0: a question closes the player, which stops it — so this is true, and said.
        assertEquals(VideoVoice.ALREADY_STOPPED, VideoVoice.perform("pause", "", { library }, FakeDeck()))
        val deck = FakeDeck(hasMedia = true, playing = true)
        assertNull(VideoVoice.perform("pause", "", { library }, deck))
        assertNull(VideoVoice.perform("stop", "", { library }, deck))
        assertEquals(listOf("pause", "stop"), deck.did)
    }

    @Test
    fun `play alone goes on with the paused one, or asks which`() {
        val paused = FakeDeck(hasMedia = true, playing = false)
        assertNull(VideoVoice.perform("play", "", { library }, paused))
        assertEquals(listOf("resume"), paused.did)
        assertEquals(VideoVoice.WHICH_ONE, VideoVoice.perform("play", "", { library }, FakeDeck()))
        assertEquals(VideoVoice.NOTHING_LEFT, VideoVoice.perform("resume", "", { library }, FakeDeck()))
        // The last video, stopped by leaving its screen, opens again from its place.
        val last = FakeDeck(hasLast = true)
        assertNull(VideoVoice.perform("resume", "", { library }, last))
        assertEquals(listOf("resume"), last.did)
    }

    @Test
    fun `every reply fits the 70-character rule`() {
        for (r in listOf(VideoVoice.NO_VIDEOS, VideoVoice.ALREADY_STOPPED, VideoVoice.NOTHING_LEFT,
                         VideoVoice.WHICH_ONE, VideoVoice.NOT_UNDERSTOOD, VideoVoice.notFound("ก".repeat(60)))) {
            assertTrue(r, r.length <= 70)
        }
    }
}
