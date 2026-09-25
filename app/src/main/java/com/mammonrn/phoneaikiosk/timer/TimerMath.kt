package com.mammonrn.phoneaikiosk.timer

/**
 * The stopwatch and the countdown as plain values (0.62.0, DESIGN.md 5ณ).
 * No Android in here: TimerTest drives every case with its own clock.
 *
 * EVERY TIME IS elapsedRealtime (ms since boot, counting deep sleep), never
 * the wall clock: a countdown must not jump when the phone sets its clock
 * from the network. The one place the wall clock is used is [Countdown.endWall]
 * / [Stopwatch.startWall], kept only so a reboot (which restarts
 * elapsedRealtime at zero) can be survived — see [Countdown.rebased].
 */

/** The countdown. [setMs] is the length chosen; [leftMs] what is left while paused. */
data class Countdown(
    val setMs: Long = DEFAULT_MS,
    val state: State = State.IDLE,
    /** RUNNING: the elapsedRealtime it ends at. */
    val endAt: Long = 0L,
    /** RUNNING: the same moment on the wall clock, for a reboot only. */
    val endWall: Long = 0L,
    /** PAUSED: what is left. */
    val leftMs: Long = 0L,
    /**
     * IDLE after an end that came while the phone was off more than
     * [LATE_RING_MAX_MS] ago: the wall-clock time it was due, to say so
     * instead of ringing (Poom, 2026-09-25). 0 when there is nothing to say.
     */
    val missedWall: Long = 0L,
) {
    enum class State { IDLE, RUNNING, PAUSED, RINGING }

    /** What is left at [now]: never below zero. */
    fun remaining(now: Long): Long = when (state) {
        State.IDLE -> setMs
        State.RUNNING -> maxOf(0L, endAt - now)
        State.PAUSED -> leftMs
        State.RINGING -> 0L
    }

    /** Due: it was running and its end has come. */
    fun due(now: Long): Boolean = state == State.RUNNING && now >= endAt

    fun canStart(): Boolean = (state == State.IDLE && setMs > 0) || state == State.PAUSED

    /** From IDLE (the set length) or PAUSED (what was left). */
    fun start(now: Long, wallNow: Long): Countdown {
        if (!canStart()) return this
        val left = if (state == State.PAUSED) leftMs else setMs
        return copy(state = State.RUNNING, endAt = now + left, endWall = wallNow + left, leftMs = 0L, missedWall = 0L)
    }

    fun pause(now: Long): Countdown =
        if (state != State.RUNNING) this
        else copy(state = State.PAUSED, leftMs = remaining(now), endAt = 0L, endWall = 0L)

    /** Back to the length that was set, ready to start again (a missed end's note goes too). */
    fun reset(): Countdown = Countdown(setMs = setMs)

    fun ring(): Countdown = copy(state = State.RINGING, endAt = 0L, endWall = 0L, leftMs = 0L, missedWall = 0L)

    /** How long past its end a due countdown is at [now] (0 when not due). */
    fun lateBy(now: Long): Long = if (due(now)) now - endAt else 0L

    /** Due, but too late to ring (the phone was off): ring only within [LATE_RING_MAX_MS]. */
    fun tooLateToRing(now: Long): Boolean = due(now) && lateBy(now) > LATE_RING_MAX_MS

    /** Ended unheard: ready again with the same length, and when it was due kept to be said. */
    fun missed(): Countdown = Countdown(setMs = setMs, missedWall = endWall)

    /** The ringing was stopped (or seen): ready again with the same length. */
    fun acknowledge(): Countdown = if (state == State.RINGING) reset() else this

    /** A new length, only while nothing is counting. Clamped to 0..[MAX_MS]. */
    fun withSet(ms: Long): Countdown =
        if (state != State.IDLE) this else copy(setMs = ms.coerceIn(0L, MAX_MS))

    /**
     * One of the three wheels (hours, minutes, seconds) up or down by one,
     * wrapping within its own range (59 + 1 = 0), the others untouched.
     */
    fun step(wheel: Wheel, up: Boolean): Countdown {
        val parts = split(setMs)
        val (value, range) = when (wheel) {
            Wheel.HOURS -> parts.hours to MAX_HOURS + 1
            Wheel.MINUTES -> parts.minutes to 60
            Wheel.SECONDS -> parts.seconds to 60
        }
        val next = Math.floorMod(value + if (up) 1 else -1, range)
        val moved = when (wheel) {
            Wheel.HOURS -> parts.copy(hours = next)
            Wheel.MINUTES -> parts.copy(minutes = next)
            Wheel.SECONDS -> parts.copy(seconds = next)
        }
        return withSet(moved.totalMs())
    }

    /**
     * After a reboot elapsedRealtime starts again at zero and the old [endAt]
     * means nothing: the end is found again from the wall clock. Anything but
     * RUNNING needs no clock and is kept as it was.
     */
    fun rebased(nowElapsed: Long, nowWall: Long): Countdown =
        if (state != State.RUNNING) this
        else copy(endAt = nowElapsed + (endWall - nowWall))

    enum class Wheel { HOURS, MINUTES, SECONDS }

    data class Parts(val hours: Int, val minutes: Int, val seconds: Int) {
        fun totalMs(): Long = ((hours * 60L + minutes) * 60L + seconds) * 1000L
    }

    companion object {
        const val DEFAULT_MS = 5 * 60_000L
        /** An end that came while the phone was off rings when it is back only within this (Poom: 1 hour). */
        const val LATE_RING_MAX_MS = 60 * 60_000L
        const val MAX_HOURS = 23
        /** 23:59:59, the most the wheels can show. */
        const val MAX_MS = ((MAX_HOURS * 60L + 59) * 60L + 59) * 1000L
        /** The quick lengths, in minutes (Poom's list). */
        val PRESETS_MIN = listOf(1, 3, 5, 10, 15)

        fun split(ms: Long): Parts {
            val s = maxOf(0L, ms) / 1000L
            return Parts((s / 3600).toInt(), ((s / 60) % 60).toInt(), (s % 60).toInt())
        }
    }
}

/** The stopwatch: what was banked before the last start, plus the run since. */
data class Stopwatch(
    val running: Boolean = false,
    /** RUNNING: the elapsedRealtime of the last start. */
    val startedAt: Long = 0L,
    /** RUNNING: the same moment on the wall clock, for a reboot only. */
    val startWall: Long = 0L,
    /** Time counted before the last start. */
    val banked: Long = 0L,
    /** The running total at each lap, oldest first. */
    val laps: List<Long> = emptyList(),
) {
    fun elapsed(now: Long): Long = banked + if (running) maxOf(0L, now - startedAt) else 0L

    /** Nothing counted and not running: the "ready" state. */
    val isClear: Boolean get() = !running && banked == 0L && laps.isEmpty()

    fun start(now: Long, wallNow: Long): Stopwatch =
        if (running) this else copy(running = true, startedAt = now, startWall = wallNow)

    fun pause(now: Long): Stopwatch =
        if (!running) this else copy(running = false, banked = elapsed(now), startedAt = 0L, startWall = 0L)

    fun reset(): Stopwatch = Stopwatch()

    fun canLap(): Boolean = running && laps.size < MAX_LAPS

    fun lap(now: Long): Stopwatch = if (!canLap()) this else copy(laps = laps + elapsed(now))

    /** After a reboot: the start found again from the wall clock (see [Countdown.rebased]). */
    fun rebased(nowElapsed: Long, nowWall: Long): Stopwatch =
        if (!running) this else copy(startedAt = nowElapsed - maxOf(0L, nowWall - startWall))

    /** One lap row: its number (1 = first), its own time, the total at its end, and a mark. */
    data class LapRow(val number: Int, val split: Long, val total: Long, val mark: Mark)

    enum class Mark { NONE, FASTEST, SLOWEST }

    /**
     * The laps, NEWEST FIRST, each with its own time. With two laps or more the
     * fastest and the slowest are marked (in words on screen, not colour);
     * all laps equal marks nothing.
     */
    fun lapRows(): List<LapRow> {
        val splits = laps.mapIndexed { i, total -> total - if (i == 0) 0L else laps[i - 1] }
        val fast = splits.minOrNull()
        val slow = splits.maxOrNull()
        val marking = splits.size >= 2 && fast != slow
        return splits.mapIndexed { i, split ->
            val mark = when {
                !marking -> Mark.NONE
                split == fast && splits.indexOf(fast) == i -> Mark.FASTEST
                split == slow && splits.indexOf(slow) == i -> Mark.SLOWEST
                else -> Mark.NONE
            }
            LapRow(i + 1, split, laps[i], mark)
        }.reversed()
    }

    companion object {
        const val MAX_LAPS = 99
    }
}

/** How the times read on screen and aloud. */
object TimerText {

    /**
     * The stopwatch: "05:07.3" under an hour, "1:05:07.3" from an hour up, in
     * tenths (the screen redraws ten times a second). Truncated, as every
     * stopwatch does: 0.99 s is "00:00.9", not "00:01.0".
     */
    fun stopwatch(ms: Long): String {
        val t = maxOf(0L, ms) / 100
        val tenth = t % 10
        val s = t / 10
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d.%d".format(h, m, sec, tenth)
               else "%02d:%02d.%d".format(m, sec, tenth)
    }

    /**
     * The countdown: "04:59" / "1:00:00", in whole seconds ROUNDED UP, so the
     * display reads 00:01 through the last second and shows 00:00 only at the
     * end — the moment the sound starts.
     */
    fun countdown(ms: Long): String {
        val s = (maxOf(0L, ms) + 999) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        return if (h > 0) "%d:%02d:%02d".format(h, m, sec) else "%02d:%02d".format(m, sec)
    }

    /** Thai words for a length, for the screen reader and the ringing line: "1 ชั่วโมง 5 นาที 30 วินาที". */
    fun thai(ms: Long): String {
        val s = (maxOf(0L, ms) + 999) / 1000
        val h = s / 3600
        val m = (s / 60) % 60
        val sec = s % 60
        val parts = buildList {
            if (h > 0) add("$h ชั่วโมง")
            if (m > 0) add("$m นาที")
            if (sec > 0 || (h == 0L && m == 0L)) add("$sec วินาที")
        }
        return parts.joinToString(" ")
    }
}
