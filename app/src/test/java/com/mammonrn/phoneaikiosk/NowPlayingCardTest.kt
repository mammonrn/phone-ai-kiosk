package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.NowPlayingCard
import com.mammonrn.phoneaikiosk.media.NowPlayingCard.Playback
import com.mammonrn.phoneaikiosk.media.NowPlayingCard.Source
import com.mammonrn.phoneaikiosk.media.NowPlayingCard.State
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The shared home-card's own appear/disappear rule (Poom): shown while
 * playing, shown up to ten minutes paused, gone after that or once stopped —
 * and, since music and radio share one card but never play at once, whichever
 * is ACTUALLY playing wins over one merely paused within its hold.
 */
class NowPlayingCardTest {

    private val stopped = State(Playback.STOPPED, null)

    @Test
    fun `playing always shows, whatever the clock says`() {
        assertTrue(NowPlayingCard.visible(State(Playback.PLAYING, null), nowMs = 0))
        assertTrue(NowPlayingCard.visible(State(Playback.PLAYING, null), nowMs = Long.MAX_VALUE / 2))
    }

    @Test
    fun `stopped never shows`() {
        assertFalse(NowPlayingCard.visible(stopped, nowMs = 0))
    }

    @Test
    fun `paused shows right up to the ten-minute hold`() {
        val paused = State(Playback.PAUSED, pausedAtMs = 0L)
        assertTrue(NowPlayingCard.visible(paused, nowMs = NowPlayingCard.PAUSE_HOLD_MS - 1))
        assertTrue(NowPlayingCard.visible(paused, nowMs = NowPlayingCard.PAUSE_HOLD_MS))
    }

    @Test
    fun `paused past the ten-minute hold is gone`() {
        val paused = State(Playback.PAUSED, pausedAtMs = 0L)
        assertFalse(NowPlayingCard.visible(paused, nowMs = NowPlayingCard.PAUSE_HOLD_MS + 1))
    }

    @Test
    fun `9m59s paused shows, 10m01s paused does not`() {
        val pausedAt = 1_000_000L
        val paused = State(Playback.PAUSED, pausedAtMs = pausedAt)
        assertTrue(NowPlayingCard.visible(paused, nowMs = pausedAt + 9 * 60_000L + 59_000L))
        assertFalse(NowPlayingCard.visible(paused, nowMs = pausedAt + 10 * 60_000L + 1_000L))
    }

    @Test
    fun `a paused source with no tracked start time is treated as just paused`() {
        // pausedAtMs null: the caller has not started counting yet (the first
        // tick it sees PAUSED) — showing it is the safe default, not hiding it.
        assertTrue(NowPlayingCard.visible(State(Playback.PAUSED, null), nowMs = 999_999_999L))
    }

    // ------------------------------------------------------------ pick()

    @Test
    fun `music playing picks music over a radio merely paused within its hold`() {
        val music = State(Playback.PLAYING, null)
        val radio = State(Playback.PAUSED, pausedAtMs = 0L)
        assertEquals(Source.MUSIC, NowPlayingCard.pick(music, radio, nowMs = 1_000L))
    }

    @Test
    fun `switching to radio while music is still within its pause hold shows radio, not music`() {
        // Starting the radio pauses (never stops) the music: for a while both
        // could independently qualify by visible(); the one really playing —
        // the radio — must be what the shared card shows.
        val music = State(Playback.PAUSED, pausedAtMs = 0L)
        val radio = State(Playback.PLAYING, null)
        assertEquals(Source.RADIO, NowPlayingCard.pick(music, radio, nowMs = 5_000L))
    }

    @Test
    fun `both stopped shows neither`() {
        assertNull(NowPlayingCard.pick(stopped, stopped, nowMs = 0))
    }

    @Test
    fun `a paused source past its hold is not picked once nothing else is playing`() {
        val music = State(Playback.PAUSED, pausedAtMs = 0L)
        assertNull(NowPlayingCard.pick(music, stopped, nowMs = NowPlayingCard.PAUSE_HOLD_MS + 1))
    }

    // ------------------------------------------------------------ pausedAt()

    @Test
    fun `pausedAt starts counting the moment playback is first paused`() {
        assertEquals(100L, NowPlayingCard.pausedAt(Playback.PAUSED, previous = null, nowMs = 100L))
    }

    @Test
    fun `pausedAt holds its start time steady while still paused`() {
        assertEquals(100L, NowPlayingCard.pausedAt(Playback.PAUSED, previous = 100L, nowMs = 500L))
    }

    @Test
    fun `pausedAt clears the moment playback is not paused`() {
        assertNull(NowPlayingCard.pausedAt(Playback.PLAYING, previous = 100L, nowMs = 500L))
        assertNull(NowPlayingCard.pausedAt(Playback.STOPPED, previous = 100L, nowMs = 500L))
    }
}
