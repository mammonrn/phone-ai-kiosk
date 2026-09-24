package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.PlayQueue
import com.mammonrn.phoneaikiosk.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
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
}
