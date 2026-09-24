package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.MusicVoice
import com.mammonrn.phoneaikiosk.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Spoken music commands on the phone (0.53.0): done, or the honest reason. */
class MusicVoiceTest {

    private class FakeDeck(override var hasQueue: Boolean = false, override var hasMedia: Boolean = false,
                           override var playing: Boolean = false, override var volume: Float = 0.8f) : MusicVoice.Deck {
        val did = ArrayList<String>()
        var played: List<Track> = emptyList()
        override fun play(tracks: List<Track>) { played = tracks; did += "play"; hasQueue = true; hasMedia = true; playing = true }
        override fun resume() { did += "resume"; playing = true }
        override fun pause() { did += "pause"; playing = false }
        override fun next() { did += "next" }
        override fun previous() { did += "previous" }
        override fun stop() { did += "stop"; playing = false; hasMedia = false }
        override fun changeVolume(value: Float) { did += "volume"; volume = value }
    }

    private val library = listOf(
        Track("local:/1", "คิดถึง", "บอดี้สแลม"),
        Track("local:/2", "ความรัก", "บอดี้สแลม"),
        Track("local:/3", "Over the Horizon", "Samsung"),
    )

    @Test
    fun `a song that is there is played and the broker's words stand`() {
        val deck = FakeDeck()
        assertNull(MusicVoice.perform("play", "คิดถึง", { library }, deck))
        assertEquals(listOf("คิดถึง"), deck.played.map { it.title })
        assertNull(MusicVoice.perform("play", "บอดีสแลม", { library }, deck))     // misheard, found
        assertEquals(2, deck.played.size)
    }

    @Test
    fun `a song that is not there is said so, and nothing starts`() {
        val deck = FakeDeck()
        val reply = MusicVoice.perform("play", "ลาวดวงเดือน", { library }, deck)
        assertEquals("ไม่พบเพลง \"ลาวดวงเดือน\" ใน playlist ครับ", reply)
        assertTrue(deck.did.isEmpty())
        assertEquals(MusicVoice.NO_MUSIC, MusicVoice.perform("play", "คิดถึง", { emptyList() }, deck))
        assertTrue(MusicVoice.notFound("ก".repeat(60)).length <= 70)
    }

    @Test
    fun `play alone carries on the list, or plays everything`() {
        val fresh = FakeDeck()
        assertNull(MusicVoice.perform("play", "", { library }, fresh))
        assertEquals(3, fresh.played.size)
        val paused = FakeDeck(hasQueue = true, hasMedia = true)
        assertNull(MusicVoice.perform("resume", "", { library }, paused))
        assertEquals(listOf("resume"), paused.did)
        assertEquals(MusicVoice.NO_MUSIC, MusicVoice.perform("play", "", { emptyList() }, FakeDeck()))
    }

    @Test
    fun `pause, next and previous with nothing to act on say so`() {
        assertEquals(MusicVoice.NOTHING_PLAYING, MusicVoice.perform("pause", "", { library }, FakeDeck()))
        assertEquals(MusicVoice.NOTHING_PLAYING, MusicVoice.perform("stop", "", { library }, FakeDeck()))
        assertEquals(MusicVoice.NO_QUEUE, MusicVoice.perform("next", "", { library }, FakeDeck()))
        val deck = FakeDeck(hasQueue = true, hasMedia = true, playing = true)
        assertNull(MusicVoice.perform("next", "", { library }, deck))
        assertNull(MusicVoice.perform("pause", "", { library }, deck))
        assertEquals(listOf("next", "pause"), deck.did)
    }

    @Test
    fun `volume moves in steps and says when it cannot`() {
        val deck = FakeDeck(volume = 0.9f)
        assertNull(MusicVoice.perform("louder", "", { library }, deck))
        assertEquals(1f, deck.volume, 0f)
        assertEquals(MusicVoice.LOUDEST, MusicVoice.perform("louder", "", { library }, deck))
        assertEquals(MusicVoice.NOT_UNDERSTOOD, MusicVoice.perform("dance", "", { library }, deck))
    }

    @Test
    fun `every reply keeps to 70 characters`() {
        for (reply in listOf(MusicVoice.NO_MUSIC, MusicVoice.NOTHING_PLAYING, MusicVoice.NO_QUEUE,
                             MusicVoice.LOUDEST, MusicVoice.QUIETEST, MusicVoice.NOT_UNDERSTOOD)) {
            assertTrue(reply, reply.length <= 70)
        }
    }
}
