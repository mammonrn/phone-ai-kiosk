package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.timer.Countdown
import com.mammonrn.phoneaikiosk.timer.Stopwatch
import com.mammonrn.phoneaikiosk.timer.TimerText
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** The stopwatch's and the countdown's sums and words (0.62.0, DESIGN.md 5ณ). */
class TimerTest {

    private val wall = 1_700_000_000_000L

    // ------------------------------------------------------------ words

    @Test
    fun `the stopwatch reads in tenths, truncated, with hours only from an hour`() {
        assertEquals("00:00.0", TimerText.stopwatch(0))
        assertEquals("00:00.9", TimerText.stopwatch(999))
        assertEquals("00:01.0", TimerText.stopwatch(1_000))
        assertEquals("05:07.3", TimerText.stopwatch(5 * 60_000L + 7_300))
        assertEquals("59:59.9", TimerText.stopwatch(3_600_000L - 1))
        assertEquals("1:00:00.0", TimerText.stopwatch(3_600_000L))
        assertEquals("12:03:04.5", TimerText.stopwatch(12 * 3_600_000L + 3 * 60_000L + 4_500))
        assertEquals("00:00.0", TimerText.stopwatch(-5))
    }

    @Test
    fun `the countdown rounds up, so it shows 00-00 only at the end`() {
        assertEquals("00:00", TimerText.countdown(0))
        assertEquals("00:01", TimerText.countdown(1))
        assertEquals("00:01", TimerText.countdown(1_000))
        assertEquals("00:02", TimerText.countdown(1_001))
        assertEquals("05:00", TimerText.countdown(5 * 60_000L))
        assertEquals("04:59", TimerText.countdown(5 * 60_000L - 1_000))
        assertEquals("1:00:00", TimerText.countdown(3_600_000L))
        assertEquals("23:59:59", TimerText.countdown(Countdown.MAX_MS))
    }

    @Test
    fun `lengths in Thai words`() {
        assertEquals("5 นาที", TimerText.thai(5 * 60_000L))
        assertEquals("1 ชั่วโมง 5 นาที 30 วินาที", TimerText.thai(3_600_000L + 5 * 60_000L + 30_000))
        assertEquals("2 ชั่วโมง", TimerText.thai(2 * 3_600_000L))
        assertEquals("0 วินาที", TimerText.thai(0))
        assertEquals("45 วินาที", TimerText.thai(44_200))
    }

    // ------------------------------------------------------------ the countdown

    @Test
    fun `start, run, pause, resume and end`() {
        var cd = Countdown().withSet(60_000)
        assertEquals(60_000, cd.remaining(0))
        cd = cd.start(now = 1_000, wallNow = wall)
        assertEquals(Countdown.State.RUNNING, cd.state)
        assertEquals(61_000, cd.endAt)
        assertEquals(wall + 60_000, cd.endWall)
        assertEquals(40_000, cd.remaining(21_000))
        cd = cd.pause(21_000)
        assertEquals(Countdown.State.PAUSED, cd.state)
        // Paused time does not count.
        assertEquals(40_000, cd.remaining(500_000))
        cd = cd.start(now = 500_000, wallNow = wall + 499_000)
        assertEquals(540_000, cd.endAt)
        assertFalse(cd.due(539_999))
        assertTrue(cd.due(540_000))
        assertEquals(0, cd.remaining(600_000))
        cd = cd.ring()
        assertEquals(Countdown.State.RINGING, cd.state)
        assertFalse(cd.due(700_000))
        // Seen: ready again with the same length.
        cd = cd.acknowledge()
        assertEquals(Countdown.State.IDLE, cd.state)
        assertEquals(60_000, cd.setMs)
    }

    @Test
    fun `the screen off changes nothing - the end is a moment, not a count of ticks`() {
        // Started, then nothing at all happens for 10 minutes (screen off, no redraws):
        // at the next look the countdown is exactly as far along as the clock is.
        val cd = Countdown().withSet(15 * 60_000L).start(now = 100_000, wallNow = wall)
        assertEquals(5 * 60_000L, cd.remaining(100_000 + 10 * 60_000L))
        assertTrue(cd.due(100_000 + 15 * 60_000L))
        assertEquals("00:00", TimerText.countdown(cd.remaining(100_000 + 20 * 60_000L)))
    }

    @Test
    fun `after a reboot the end is found again from the wall clock`() {
        // Started 3 minutes into the old boot with 10 minutes to go.
        val cd = Countdown().withSet(10 * 60_000L).start(now = 180_000, wallNow = wall)
        // The phone restarted; 4 minutes of wall time later elapsedRealtime reads 30 s.
        val after = cd.rebased(nowElapsed = 30_000, nowWall = wall + 4 * 60_000L)
        assertEquals(6 * 60_000L, after.remaining(30_000))
        // An end that passed while the phone was off is due at once.
        val late = cd.rebased(nowElapsed = 30_000, nowWall = wall + 11 * 60_000L)
        assertTrue(late.due(30_000))
        // Nothing to rebase when it is not running.
        val paused = cd.pause(240_000)
        assertEquals(paused, paused.rebased(5, wall + 999_999))
    }

    @Test
    fun `nothing to count cannot start, and the length changes only while idle`() {
        val zero = Countdown().withSet(0)
        assertFalse(zero.canStart())
        assertEquals(zero, zero.start(0, wall))
        val running = Countdown().withSet(60_000).start(0, wall)
        assertEquals(running, running.withSet(5_000))
        assertEquals(Countdown.MAX_MS, Countdown().withSet(Long.MAX_VALUE).setMs)
        assertEquals(0, Countdown().withSet(-5).setMs)
    }

    @Test
    fun `each wheel steps within its own range and wraps`() {
        var cd = Countdown().withSet(0)
        cd = cd.step(Countdown.Wheel.SECONDS, up = false)
        assertEquals(59_000, cd.setMs)
        cd = cd.step(Countdown.Wheel.SECONDS, up = true)
        assertEquals(0, cd.setMs)
        cd = cd.step(Countdown.Wheel.MINUTES, up = false)
        assertEquals(59 * 60_000L, cd.setMs)
        cd = cd.step(Countdown.Wheel.HOURS, up = false)
        assertEquals(Countdown.Parts(23, 59, 0), Countdown.split(cd.setMs))
        cd = cd.step(Countdown.Wheel.HOURS, up = true)
        assertEquals(Countdown.Parts(0, 59, 0), Countdown.split(cd.setMs))
    }

    @Test
    fun `the presets are Poom's list`() {
        assertEquals(listOf(1, 3, 5, 10, 15), Countdown.PRESETS_MIN)
        assertEquals(5 * 60_000L, Countdown.DEFAULT_MS)
    }

    @Test
    fun `reset goes back to the set length from any state`() {
        val cd = Countdown().withSet(90_000).start(0, wall).pause(30_000)
        assertEquals(Countdown(setMs = 90_000), cd.reset())
    }

    // ------------------------------------------------------------ the stopwatch

    @Test
    fun `the stopwatch banks time across pauses`() {
        var sw = Stopwatch()
        assertTrue(sw.isClear)
        sw = sw.start(1_000, wall)
        assertEquals(4_000, sw.elapsed(5_000))
        sw = sw.pause(5_000)
        assertEquals(4_000, sw.elapsed(99_000))
        assertFalse(sw.isClear)
        sw = sw.start(100_000, wall)
        assertEquals(6_000, sw.elapsed(102_000))
        assertTrue(sw.reset().isClear)
    }

    @Test
    fun `laps are newest first with their own time, fastest and slowest marked`() {
        var sw = Stopwatch().start(0, wall)
        sw = sw.lap(10_000)   // lap 1: 10 s
        sw = sw.lap(15_000)   // lap 2: 5 s
        sw = sw.lap(27_000)   // lap 3: 12 s
        val rows = sw.lapRows()
        assertEquals(listOf(3, 2, 1), rows.map { it.number })
        assertEquals(listOf(12_000L, 5_000L, 10_000L), rows.map { it.split })
        assertEquals(listOf(27_000L, 15_000L, 10_000L), rows.map { it.total })
        assertEquals(listOf(Stopwatch.Mark.SLOWEST, Stopwatch.Mark.FASTEST, Stopwatch.Mark.NONE), rows.map { it.mark })
    }

    @Test
    fun `one lap, or all laps equal, marks nothing`() {
        val one = Stopwatch().start(0, wall).lap(1_000)
        assertEquals(Stopwatch.Mark.NONE, one.lapRows().single().mark)
        val equal = Stopwatch().start(0, wall).lap(1_000).lap(2_000)
        assertTrue(equal.lapRows().all { it.mark == Stopwatch.Mark.NONE })
    }

    @Test
    fun `laps only while running, and no more than 99`() {
        val paused = Stopwatch().start(0, wall).pause(5_000)
        assertEquals(paused, paused.lap(6_000))
        var sw = Stopwatch().start(0, wall)
        repeat(120) { sw = sw.lap(it * 1_000L + 1) }
        assertEquals(Stopwatch.MAX_LAPS, sw.laps.size)
        assertFalse(sw.canLap())
    }

    @Test
    fun `a running stopwatch survives a reboot by the wall clock`() {
        val sw = Stopwatch().start(500_000, wall).rebased(nowElapsed = 20_000, nowWall = wall + 60_000)
        assertEquals(60_000, sw.elapsed(20_000))
    }
}
