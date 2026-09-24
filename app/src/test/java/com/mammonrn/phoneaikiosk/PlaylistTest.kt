package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.PlayQueue
import com.mammonrn.phoneaikiosk.media.Session
import com.mammonrn.phoneaikiosk.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 0.55.0: the playlist's edits keep the song playing, and the list comes back next time. */
class PlaylistTest {

    private fun t(n: Int, artist: String = "a$n", ms: Long = n * 1000L) = Track("local:/m/$n.flac", "t$n", artist, "", ms)
    private val five = (1..5).map { t(it) }

    @Test
    fun `adding keeps the song playing and joins the end`() {
        val q = PlayQueue().apply { set(five, 2) }
        q.add(listOf(t(6), t(7)))
        assertEquals("t3", q.current!!.title)
        assertEquals(7, q.tracks.size)
        assertEquals(listOf("t3", "t4", "t5", "t6", "t7"), q.upcoming().map { it.title })
        val empty = PlayQueue(); empty.add(listOf(t(1)))
        assertEquals("t1", empty.current!!.title)
    }

    @Test
    fun `removing others keeps the song playing and its place`() {
        val q = PlayQueue().apply { set(five, 2) }
        assertFalse(q.remove(setOf(0, 4)))
        assertEquals(listOf("t2", "t3", "t4"), q.tracks.map { it.title })
        assertEquals("t3", q.current!!.title)
        assertEquals(1, q.currentIndex)
    }

    @Test
    fun `removing the song playing moves to the next one left, or the first at the end`() {
        val q = PlayQueue().apply { set(five, 2) }
        assertTrue(q.remove(setOf(2, 3)))
        assertEquals("t5", q.current!!.title)
        val end = PlayQueue().apply { set(five, 4) }
        assertTrue(end.remove(setOf(4)))
        assertEquals("t1", end.current!!.title)
        val all = PlayQueue().apply { set(five, 0) }
        all.remove(five.indices.toSet())
        assertNull(all.current)
        assertTrue(all.isEmpty)
    }

    @Test
    fun `sorting by artist keeps the song playing, and play follows the new order`() {
        val list = listOf(t(1, "c"), t(2, "a"), t(3, "b"))
        val q = PlayQueue().apply { set(list, 0) }                  // t1 (artist c) playing
        q.sort(compareBy { it.artist })
        assertEquals(listOf("t2", "t3", "t1"), q.tracks.map { it.title })
        assertEquals("t1", q.current!!.title)
        assertNull(q.next(auto = true))                            // t1 is last now
    }

    @Test
    fun `in shuffle a sort changes the list shown, not what plays next`() {
        val q = PlayQueue(Random(7)).apply { set(five, 0); setShuffle(true) }
        val before = q.upcoming().map { it.title }
        q.sort(compareByDescending { it.title })
        assertEquals(before, q.upcoming().map { it.title })
    }

    @Test
    fun `clear empties everything`() {
        val q = PlayQueue().apply { set(five, 1) }
        q.clear()
        assertTrue(q.isEmpty)
        assertNull(q.current)
        assertNull(q.next(auto = false))
    }

    @Test
    fun `a session comes back as it was saved, names with tabs and all`() {
        val odd = Track("nas:Music\\x\ty.flac", "a\tb\\c\nd", "ศิลปิน", "อัลบั้ม", 123_456)
        val s = Session(listOf(t(1), odd), 1, 61_000, true, PlayQueue.Repeat.ONE)
        assertEquals(s, Session.decode(s.encode()))
        assertNull(Session.decode("not a session"))
        assertNull(Session.decode(""))
    }

    @Test
    fun `restore brings the list, the song, shuffle and repeat back`() {
        val q = PlayQueue(Random(3))
        q.restore(five, 3, shuffleOn = true, repeatMode = PlayQueue.Repeat.ALL)
        assertEquals("t4", q.current!!.title)
        assertTrue(q.shuffle)
        assertEquals(PlayQueue.Repeat.ALL, q.repeat)
    }
}
