package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.MusicLibrary
import com.mammonrn.phoneaikiosk.media.Track
import com.mammonrn.phoneaikiosk.media.Video
import com.mammonrn.phoneaikiosk.media.VideoVoice
import com.mammonrn.phoneaikiosk.voice.DONE_MARK
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Parts of one story (0.63.0, Poom): "เปิดวิดีโอ คู่โจร" with คู่โจร1 and คู่โจร2 in
 * the playlist opens the part being watched, else the first, and says which.
 * Titles alike that are not parts of one story are still asked about.
 */
class SequelTest {

    private fun v(title: String) = Video(Track("local:/v/$title.mp4", title))

    private val list = listOf(v("122 ที่หนึ่งไม่ไหว - ไอน้ำ"), v("คู่โจร1"), v("คู่โจร2"), v("โลกทั้งใบให้นายคนเดียว"))

    private class Deck(override val last: Video? = null, val left: Set<String> = emptySet()) : VideoVoice.Deck {
        var opened: Video? = null
        override val hasMedia = false
        override val playing = false
        override val hasLast = last != null
        override fun play(videos: List<Video>, start: Int) { opened = videos[start] }
        override fun resume() {}
        override fun pause() {}
        override fun stop() {}
        override fun inProgress(video: Video) = video.track.title in left
    }

    @Test fun `a part number is read from the end of a title, never a year`() {
        assertEquals(MusicLibrary.key("คู่โจร") to 1, MusicLibrary.sequel("คู่โจร1"))
        assertEquals("toystory" to 2, MusicLibrary.sequel("Toy Story 2"))
        assertEquals(MusicLibrary.key("ฟ้าทะลายโจร") to 3, MusicLibrary.sequel("ฟ้าทะลายโจร ภาค 3"))
        assertEquals(MusicLibrary.key("บ้านผีสิง") to 2, MusicLibrary.sequel("บ้านผีสิง ตอนที่ ๒"))
        assertNull(MusicLibrary.sequel("ทะเลสีชมพู"))
        assertNull(MusicLibrary.sequel("คอนเสิร์ต 2024"))
    }

    private fun open(deck: Deck, query: String = "คู่โจร"): String? =
        VideoVoice.perform(VideoVoice.PLAY, query, { list }, deck)

    @Test fun `none watched opens the first part and says its title`() {
        val deck = Deck()
        assertEquals(DONE_MARK + "เปิดวิดีโอ \"คู่โจร1\" ครับ", open(deck))
        assertEquals("คู่โจร1", deck.opened?.track?.title)
    }

    @Test fun `the part left part-way is opened`() {
        val deck = Deck(left = setOf("คู่โจร2"))
        assertEquals(DONE_MARK + "เปิดวิดีโอ \"คู่โจร2\" ครับ", open(deck))
    }

    @Test fun `of two left part-way the later part, and the one watched last above all`() {
        assertEquals("คู่โจร2", Deck(left = setOf("คู่โจร1", "คู่โจร2")).also { open(it) }.opened?.track?.title)
        val deck = Deck(last = list[1], left = setOf("คู่โจร1", "คู่โจร2"))
        open(deck)
        assertEquals("คู่โจร1", deck.opened?.track?.title)
    }

    @Test fun `a part asked by its number is that part`() {
        val deck = Deck(left = setOf("คู่โจร1"))
        open(deck, "คู่โจร2")
        assertEquals("คู่โจร2", deck.opened?.track?.title)
    }

    @Test fun `titles alike that are not parts of one story are not taken as parts`() {
        val tracks = listOf(Track("a", "ทะเลสีชมพู"), Track("b", "ทะเลสีคราม"))
        assertTrue(MusicLibrary.parts(tracks, "ทะเลสี").isEmpty())
        val mixed = listOf(Track("a", "คู่โจร1"), Track("b", "คู่หู2"))
        assertTrue(MusicLibrary.parts(mixed, "คู่").isEmpty())
    }
}
