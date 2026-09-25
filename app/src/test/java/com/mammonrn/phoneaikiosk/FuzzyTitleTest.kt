package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.MusicLibrary
import com.mammonrn.phoneaikiosk.media.MusicLibrary.Guess
import com.mammonrn.phoneaikiosk.media.MusicVoice
import com.mammonrn.phoneaikiosk.media.Track
import com.mammonrn.phoneaikiosk.media.Video
import com.mammonrn.phoneaikiosk.media.VideoVoice
import com.mammonrn.phoneaikiosk.voice.DONE_MARK
import com.mammonrn.phoneaikiosk.voice.isDoneWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A title the transcriber heard wrong (0.61.0, Poom). The spoken forms below are
 * REAL: what the broker's speech-to-text returned on the A07 for "เปิดวิดีโอ
 * ทะเลสีชมพู" played through the room (2026-09-25).
 */
class FuzzyTitleTest {

    private fun t(title: String) = Track("local:/v/$title.mp4", title)

    /** Poom's own video titles and song titles, plus the one that was asked for. */
    private val library = listOf(
        "ทะเลสีชมพู", "122 ที่หนึ่งไม่ไหว - ไอน้ำ", "โบวี่ - ยอมจำนนฟ้าดิน", "คู่โจร1", "คู่โจร2",
        "โลกทั้งใบให้นายคนเดียว", "ทะเลใจ", "ดาวเคราะห์สีน้ำเงิน", "เชือกวิเศษ", "ทิ้งไว้กลางทาง", "แพ้ทาง", "ไม่เคย",
    ).map(::t)

    @Test
    fun `the three forms heard on the A07 all find ทะเลสีชมพู`() {
        for (heard in listOf("ทรสีชมพู", "ทรสี ชมพุง", "ทรัล สี ชมพูง")) {
            assertNull("an exact search does not find it: $heard", MusicLibrary.find(library, heard))
            val g = MusicLibrary.guess(library, heard)
            assertTrue("$heard → $g", g is Guess.One && g.track.title == "ทะเลสีชมพู")
        }
    }

    @Test
    fun `nothing alike is still not found`() {
        for (heard in listOf("ไม่มีเรื่องนี้เลย", "วันพระ", "ข่าวเช้า", "ทรสี ชุบพุม")) {
            assertEquals(heard, Guess.None, MusicLibrary.guess(library, heard))
        }
        assertEquals(Guess.None, MusicLibrary.guess(library, "ท"))          // too short to guess from
        assertEquals(Guess.None, MusicLibrary.guess(emptyList(), "ทรสีชมพู"))
    }

    @Test
    fun `two titles about as alike are asked about, not guessed`() {
        // "คู่โจร" is as close to คู่โจร1 as to คู่โจร2.
        val g = MusicLibrary.guess(listOf(t("คู่โจร1"), t("คู่โจร2"), t("ทะเลใจ")), "คู่โจร")
        assertTrue("$g", g is Guess.Two)
        val words = VideoVoice.perform(VideoVoice.PLAY, "คู่โจร", { listOf(t("คู่โจร1"), t("คู่โจร2")).map(::Video) },
            object : VideoVoice.Deck {
                override val hasMedia = false; override val playing = false; override val hasLast = false
                override fun play(videos: List<Video>, start: Int) = Unit
                override fun resume() = Unit; override fun pause() = Unit; override fun stop() = Unit
            })
        // Both are within the exact passes' reach ("คู่โจร" is contained in both): the first is played or asked —
        // either way, never "not found".
        assertFalse(words?.startsWith("ไม่พบ") == true)
    }

    @Test
    fun `a guessed song is played and said by its real title, marked as done`() {
        var played: List<Track> = emptyList()
        val deck = object : MusicVoice.Deck {
            override val hasMedia = false; override val playing = false; override val hasQueue = false; override val volume = 0.5f
            override fun play(tracks: List<Track>) { played = tracks }
            override fun resume() = Unit; override fun pause() = Unit; override fun next() = Unit
            override fun previous() = Unit; override fun stop() = Unit; override fun changeVolume(value: Float) = Unit
        }
        val words = MusicVoice.perform(MusicVoice.PLAY, "ทรสีชมพู", { library }, deck)
        assertEquals("ทะเลสีชมพู", played.single().title)
        assertTrue(isDoneWords(words))
        assertEquals("เปิดเพลง \"ทะเลสีชมพู\" ครับ", words!!.removePrefix(DONE_MARK))
        assertTrue(words.removePrefix(DONE_MARK).length <= 70)
        // Not found stays not found — and is not marked done.
        val miss = MusicVoice.perform(MusicVoice.PLAY, "ข่าวเช้า", { library }, deck)
        assertFalse(isDoneWords(miss))
        assertTrue(miss!!.startsWith("ไม่พบเพลง"))
    }

    @Test
    fun `asking which keeps to 70 characters`() {
        val long = "ชื่อเพลงที่ยาวมากจนต้องตัดให้สั้นลงเพื่อให้อ่านได้บนจอ"
        assertTrue(MusicVoice.which(long, long + "สอง").length <= 70)
    }
}
