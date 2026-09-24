package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.PlayQueue
import com.mammonrn.phoneaikiosk.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/**
 * 0.60.0, Poom on the A07: shuffle on, a playlist of several songs, one song
 * ended and the player stopped. The log showed 8 songs added to an empty list
 * with shuffle on, one skipped (ALAC), one played for 4:16, then "stopped".
 *
 * The cause: the place to start was wherever the first song landed in the
 * shuffled order, so only the tail of the order was left to play; a tap on a
 * song did the same. With shuffle on, every song is played once before the end
 * (repeat off) — whatever way the list was made or the song chosen.
 */
class ShuffleEndTest {

    private fun songs(n: Int) = (1..n).map { Track("local:/m/$it.mp3", "เพลง $it") }

    /** Plays to the end as the service does (auto = a song finished by itself). */
    private fun playAll(q: PlayQueue): List<Track> {
        val heard = ArrayList<Track>()
        var t = q.current
        while (t != null && heard.size < 100) { heard += t; t = q.next(auto = true) }
        return heard
    }

    @Test
    fun `songs added to an empty list with shuffle on all play before it stops`() {
        for (seed in 0 until 50) {
            val q = PlayQueue(Random(seed))
            q.setShuffle(true)
            q.add(songs(8))
            val heard = playAll(q)
            assertEquals("seed $seed", 8, heard.size)
            assertEquals("seed $seed", 8, heard.toSet().size)
        }
    }

    @Test
    fun `a song tapped with shuffle on is followed by every other song`() {
        for (seed in 0 until 50) {
            val q = PlayQueue(Random(seed))
            q.set(songs(8), 0)
            q.setShuffle(true)
            q.jumpTo(5)
            assertEquals(songs(8)[5], q.current)
            val heard = playAll(q)
            assertEquals("seed $seed", 8, heard.toSet().size)
        }
    }

    @Test
    fun `every pair of shuffle and repeat ends the way it should`() {
        for (shuffle in listOf(false, true)) for (repeat in PlayQueue.Repeat.entries) {
            val q = PlayQueue(Random(3))
            q.set(songs(4), 0)
            q.setShuffle(shuffle)
            q.repeat = repeat
            val first = q.current
            var t = first
            val heard = ArrayList<Track>()
            repeat(9) { if (t != null) { heard += t!!; t = q.next(auto = true) } }
            when (repeat) {
                PlayQueue.Repeat.OFF -> { assertEquals("$shuffle $repeat", 4, heard.size); assertEquals(4, heard.toSet().size) }
                PlayQueue.Repeat.ALL -> { assertEquals("$shuffle $repeat", 9, heard.size); assertEquals(4, heard.take(4).toSet().size) }
                PlayQueue.Repeat.ONE -> assertEquals("$shuffle $repeat", List(9) { first }, heard)
            }
        }
    }

    @Test
    fun `a song added while one plays is still to come, shuffle on or off`() {
        for (shuffle in listOf(false, true)) {
            val q = PlayQueue(Random(9))
            q.set(songs(3), 0)
            q.setShuffle(shuffle)
            q.next(auto = true)                 // one song done
            val extra = Track("local:/m/new.mp3", "ใหม่")
            q.add(listOf(extra))
            val rest = playAll(q)
            assertEquals("shuffle=$shuffle", true, extra in rest)
            assertNull(q.next(auto = true))
        }
    }

    /** 0.61.0, Poom: repeat ALL with shuffle makes a new order every round, not the same one again. */
    @Test
    fun `repeat all with shuffle plays every song each round in a new order`() {
        var sameRounds = 0
        for (seed in 0 until 50) {
            val q = PlayQueue(Random(seed))
            q.set(songs(6), 0)
            q.setShuffle(true)
            q.repeat = PlayQueue.Repeat.ALL
            val heard = ArrayList<Track>()
            var t = q.current
            repeat(18) { heard += t!!; t = q.next(auto = true) }
            val rounds = heard.chunked(6)
            for (r in rounds) assertEquals("seed $seed", 6, r.toSet().size)
            for (i in 1 until rounds.size) assertNotEquals("seed $seed round $i opens with the song just heard",
                rounds[i - 1].last(), rounds[i].first())
            if (rounds[0] == rounds[1]) sameRounds++
        }
        // 6 songs: two rounds alike by chance is about 1 in 600 per seed.
        assertTrue("same order in $sameRounds of 50", sameRounds <= 1)
    }

    @Test
    fun `a song added while one plays stays in every later round of repeat all with shuffle`() {
        val q = PlayQueue(Random(4))
        q.set(songs(3), 0)
        q.setShuffle(true)
        q.repeat = PlayQueue.Repeat.ALL
        q.next(auto = true)
        val extra = Track("local:/m/new.mp3", "ใหม่")
        q.add(listOf(extra))
        val heard = ArrayList<Track>()
        var t = q.current
        repeat(12) { heard += t!!; t = q.next(auto = true) }
        // After the first round finishes, each later round of 4 has the new song.
        val firstRoundLeft = 3                  // 2 left of the first round + the added one
        heard.drop(firstRoundLeft).chunked(4).filter { it.size == 4 }.forEach {
            assertEquals(4, it.toSet().size)
            assertTrue(extra in it)
        }
    }

    @Test
    fun `next by hand at the end with shuffle starts a new order too`() {
        val q = PlayQueue(Random(1))
        q.set(songs(5), 0)
        q.setShuffle(true)
        val round1 = (0 until 5).map { val c = q.current!!; if (it < 4) q.next(auto = false); c }
        val round2 = (0 until 5).map { q.next(auto = false)!! }
        assertEquals(5, round2.toSet().size)
        assertNotEquals(round1.last(), round2.first())
    }
}
