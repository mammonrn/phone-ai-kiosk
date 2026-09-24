package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.MusicLibrary
import com.mammonrn.phoneaikiosk.media.MusicLibrary.By
import com.mammonrn.phoneaikiosk.media.PlayQueue
import com.mammonrn.phoneaikiosk.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** The music player's queue and its search by voice (0.53.0). */
class MusicLibraryTest {

    private fun t(n: Int, title: String = "เพลง $n", artist: String = "", album: String = "") =
        Track("local:/Music/$n.flac", title, artist, album)

    // ------------------------------------------------------------ formats

    @Test
    fun `the six formats Poom chose play, and the three he left out say why`() {
        for (name in listOf("a.flac", "b.WAV", "c.mp3", "d.m4a", "e.aac", "f.opus", "g.ogg")) {
            assertTrue(name, MusicLibrary.playable(name))
        }
        assertEquals("DSD", MusicLibrary.unsupportedReason("x.dsf"))
        assertEquals("APE", MusicLibrary.unsupportedReason("x.ape"))
        assertEquals("WavPack", MusicLibrary.unsupportedReason("x.wv"))
        assertFalse(MusicLibrary.playable("x.dsf"))
        assertFalse(MusicLibrary.playable("notes.txt"))
        assertEquals("Over the Horizon", MusicLibrary.titleFromFile("Over the Horizon.m4a"))
    }

    // ------------------------------------------------------------ search

    private val library = listOf(
        Track("local:/1", "คิดถึง", "บอดี้สแลม", "ดัม-มะ-ชา-ติ"),
        Track("local:/2", "ความรัก", "บอดี้สแลม", "ดัม-มะ-ชา-ติ"),
        Track("local:/3", "ขอบฟ้า", "ปาล์มมี่", "Palmy"),
        Track("local:/4", "Over the Horizon", "Samsung", ""),
        Track("nas:Music\\5.mp3", "ภูชี้ฟ้า", "", "เชียงราย"),
    )

    @Test
    fun `a title is found as said, spaced or not, any case`() {
        assertEquals(listOf("local:/4"), MusicLibrary.find(library, "over the horizon")!!.tracks.map { it.id })
        assertEquals(listOf("local:/4"), MusicLibrary.find(library, "OverTheHorizon")!!.tracks.map { it.id })
        assertEquals(By.TITLE, MusicLibrary.find(library, "คิดถึง")!!.by)
    }

    @Test
    fun `an artist gives all their songs`() {
        val found = MusicLibrary.find(library, "บอดี้สแลม")!!
        assertEquals(By.ARTIST, found.by)
        assertEquals(listOf("ความรัก", "คิดถึง"), found.tracks.map { it.title })
    }

    @Test
    fun `a transcriber's slips are forgiven, a different song is not`() {
        // Tone marks dropped or swapped, as Whisper does.
        assertEquals("ภูชี้ฟ้า", MusicLibrary.find(library, "ภูชีฟ้า")!!.name)
        assertEquals("บอดี้สแลม", MusicLibrary.find(library, "บอดีสแลม")!!.name)
        assertEquals("ปาล์มมี่", MusicLibrary.find(library, "ปาลมมี")!!.name)
        // One letter off in a long name.
        assertEquals("Over the Horizon", MusicLibrary.find(library, "over the horizen")!!.name)
        // Not in the library: nothing, never the nearest thing.
        assertNull(MusicLibrary.find(library, "ลาวดวงเดือน"))
        assertNull(MusicLibrary.find(library, "ก"))
    }

    @Test
    fun `the key keeps Thai vowels and drops tones and punctuation`() {
        assertEquals("คดถง".length + 2, MusicLibrary.key("คิดถึง").length)     // ิ and ึ kept
        assertEquals(MusicLibrary.key("ภูชี้ฟ้า"), MusicLibrary.key("ภูชีฟา"))
        assertEquals("dammachati", MusicLibrary.key("Dam-ma-cha-ti"))
    }

    // ------------------------------------------------------------ the queue

    @Test
    fun `plays on and stops at the end, or wraps with repeat all`() {
        val q = PlayQueue()
        q.set(listOf(t(1), t(2), t(3)))
        assertEquals("เพลง 1", q.current!!.title)
        assertEquals("เพลง 2", q.next(auto = true)!!.title)
        assertEquals("เพลง 3", q.next(auto = true)!!.title)
        assertNull(q.next(auto = true))                                    // the end
        q.repeat = PlayQueue.Repeat.ALL
        assertEquals("เพลง 1", q.next(auto = true)!!.title)
    }

    @Test
    fun `repeat one plays the same track, but next by hand moves on`() {
        val q = PlayQueue()
        q.set(listOf(t(1), t(2)))
        q.repeat = PlayQueue.Repeat.ONE
        assertEquals("เพลง 1", q.next(auto = true)!!.title)
        assertEquals("เพลง 2", q.next(auto = false)!!.title)
        assertEquals("เพลง 1", q.next(auto = false)!!.title)                // by hand, wraps
        assertEquals("เพลง 2", q.previous()!!.title)                        // wraps back
    }

    @Test
    fun `shuffle keeps the playing track and covers every track once`() {
        val q = PlayQueue(Random(7))
        q.set((1..10).map { t(it) }, start = 3)
        assertEquals("เพลง 4", q.current!!.title)
        q.setShuffle(true)
        assertEquals("เพลง 4", q.current!!.title)
        val seen = mutableListOf(q.current!!.title)
        while (true) seen.add(q.next(auto = true)?.title ?: break)
        assertEquals(10, seen.size)
        assertEquals(10, seen.toSet().size)
        q.setShuffle(false)
        assertEquals(seen.last(), q.current!!.title)                         // off keeps the track too
    }

    @Test
    fun `repeat cycles as Winamp's button does, and a tap jumps`() {
        val q = PlayQueue()
        q.set(listOf(t(1), t(2), t(3)))
        assertEquals(PlayQueue.Repeat.ALL, q.cycleRepeat())
        assertEquals(PlayQueue.Repeat.ONE, q.cycleRepeat())
        assertEquals(PlayQueue.Repeat.OFF, q.cycleRepeat())
        assertEquals("เพลง 3", q.jumpTo(2)!!.title)
        assertEquals(2, q.currentIndex)
        assertNull(PlayQueue().next(auto = false))
    }
}
