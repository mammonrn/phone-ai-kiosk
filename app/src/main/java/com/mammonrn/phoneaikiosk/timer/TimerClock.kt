package com.mammonrn.phoneaikiosk.timer

import android.app.AlarmManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.SystemClock
import android.provider.Settings
import android.util.Log
import com.mammonrn.phoneaikiosk.voice.VoiceService

/**
 * The stopwatch and the countdown OF THE PROCESS, not of the screen (0.62.0,
 * DESIGN.md 5ณ). The screen ([TimerActivity]) only draws them. Closing the
 * screen — X, "กลับหน้าหลัก", Back, or Hey Jarvis closing every screen
 * (DESIGN 11) — changes nothing here: both keep counting, and the countdown
 * still rings at its end.
 *
 * HOW THE END IS NEVER LOST:
 *  - The times are elapsedRealtime, which keeps counting in deep sleep, so the
 *    screen off or the app in the background makes no difference to the sum.
 *  - The end is handed to AlarmManager twice, as alarm/AlarmScheduler does:
 *    setAlarmClock (the one call Android promises to fire on time in Doze;
 *    USE_EXACT_ALARM is already in the manifest) on the wall clock, and
 *    setExactAndAllowWhileIdle on elapsedRealtime as a backup in case the
 *    wall clock is changed mid-count. Whichever comes first rings; one that
 *    arrives more than a second early (the clock was moved forward) books
 *    both again instead. The screen, while in front, also rings at 00:00.
 *  - Everything is written to SharedPreferences on every change. A killed
 *    process is found again when the kiosk starts (MainActivity calls
 *    [restore]); a reboot, which restarts elapsedRealtime at zero, is noticed
 *    by the boot count and the end is found again from the wall clock
 *    (Countdown.rebased). An end that passed while the phone was off rings as
 *    soon as the kiosk is back.
 *
 * THE RINGING IS THE ALARM'S (VoiceService.startTimerRing): the same tone at
 * the alarm volume through alarm/AlarmRinger, the same "deaf" wake word while
 * it rings (VoiceState.alarmRinging, WakeGate.deaf), the same Jarvis button
 * and home-card stop, the same 10-minute limit. The screen shows "หมดเวลา"
 * until someone stops it.
 *
 * Main thread only. Logs carry states and seconds, nothing else.
 */
object TimerClock {

    const val TAG = "KioskTimer"
    private const val PREFS = "timer"
    private const val REQUEST_CLOCK = 71
    private const val REQUEST_BACKUP = 72
    /** An alarm this early was booked on a wall clock that has since moved. */
    private const val EARLY_MS = 1_000L

    var countdown = Countdown()
        private set
    var stopwatch = Stopwatch()
        private set

    /** Which of the two the screen showed last: it opens there again. */
    var showCountdown = false
        private set

    /** Bumped on every change, so an open screen redraws. */
    @Volatile var version = 0
        private set

    private var loaded = false

    fun load(context: Context) {
        if (loaded) return
        loaded = true
        val p = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val cdState = runCatching { Countdown.State.valueOf(p.getString("cd_state", "IDLE")!!) }
            .getOrDefault(Countdown.State.IDLE)
        countdown = Countdown(
            setMs = p.getLong("cd_set", Countdown.DEFAULT_MS).coerceIn(0L, Countdown.MAX_MS),
            state = cdState,
            endAt = p.getLong("cd_end", 0L),
            endWall = p.getLong("cd_end_wall", 0L),
            leftMs = p.getLong("cd_left", 0L),
        )
        stopwatch = Stopwatch(
            running = p.getBoolean("sw_running", false),
            startedAt = p.getLong("sw_start", 0L),
            startWall = p.getLong("sw_start_wall", 0L),
            banked = p.getLong("sw_banked", 0L),
            laps = p.getString("sw_laps", "").orEmpty().split(',').mapNotNull { it.toLongOrNull() }
                .take(Stopwatch.MAX_LAPS),
        )
        showCountdown = p.getBoolean("mode_countdown", false)
        val now = SystemClock.elapsedRealtime()
        // A reboot: the boot count moved, or the saved elapsedRealtime is in the future.
        val rebooted = p.contains("boot") && (p.getInt("boot", -1) != bootCount(context) || p.getLong("saved_at", 0L) > now)
        if (rebooted) {
            val wall = System.currentTimeMillis()
            countdown = countdown.rebased(now, wall)
            stopwatch = stopwatch.rebased(now, wall)
            Log.i(TAG, "rebased after a reboot countdown=${countdown.state} stopwatch=${stopwatch.running}")
            save(context)
        }
    }

    /**
     * The kiosk has started (boot, update, a killed process): the countdown's
     * alarm is booked again, or it rings now if its end has passed meanwhile.
     */
    fun restore(context: Context) {
        load(context)
        if (countdown.state != Countdown.State.RUNNING) return
        if (!ringIfDue(context, "restore")) book(context)
    }

    // ------------------------------------------------------------ the countdown

    fun setLength(context: Context, ms: Long) = change(context) { countdown = countdown.withSet(ms) }

    fun step(context: Context, wheel: Countdown.Wheel, up: Boolean) = change(context) { countdown = countdown.step(wheel, up) }

    fun startCountdown(context: Context) = change(context) {
        countdown = countdown.start(SystemClock.elapsedRealtime(), System.currentTimeMillis())
        book(context)
        Log.i(TAG, "countdown start s=${countdown.remaining(SystemClock.elapsedRealtime()) / 1000}")
    }

    fun pauseCountdown(context: Context) = change(context) {
        countdown = countdown.pause(SystemClock.elapsedRealtime())
        book(context)
        Log.i(TAG, "countdown pause left_s=${countdown.leftMs / 1000}")
    }

    fun resetCountdown(context: Context) = change(context) {
        countdown = countdown.reset()
        book(context)
        Log.i(TAG, "countdown reset")
    }

    /**
     * The end has come (the alarm, the screen's tick, or [restore]): the
     * countdown becomes RINGING and the voice service rings. False when it was
     * not due, so nothing happened.
     */
    fun ringIfDue(context: Context, source: String): Boolean {
        load(context)
        if (!countdown.due(SystemClock.elapsedRealtime())) return false
        change(context) { countdown = countdown.ring() }
        book(context)
        Log.i(TAG, "countdown ended source=$source set_s=${countdown.setMs / 1000}")
        VoiceService.ringTimer(context)
        return true
    }

    /** AlarmManager's call ([TimerReceiver]). */
    fun onAlarm(context: Context) {
        load(context)
        if (countdown.state != Countdown.State.RUNNING) return
        if (ringIfDue(context, "alarm")) return
        val left = countdown.endAt - SystemClock.elapsedRealtime()
        if (left > EARLY_MS) {
            Log.i(TAG, "alarm early by s=${left / 1000}; booked again")
            book(context)
        }
    }

    /**
     * Someone saw it end: the stop button here, on the home card, the Jarvis
     * button, or the screen closed while it rang. Back to the set length.
     * The sound itself is stopped by the voice service (which calls this too).
     */
    fun acknowledge(context: Context) {
        load(context)
        if (countdown.state != Countdown.State.RINGING) return
        change(context) { countdown = countdown.acknowledge() }
        Log.i(TAG, "countdown acknowledged")
    }

    // ------------------------------------------------------------ the stopwatch

    fun startStopwatch(context: Context) = change(context) {
        stopwatch = stopwatch.start(SystemClock.elapsedRealtime(), System.currentTimeMillis())
    }

    fun pauseStopwatch(context: Context) = change(context) { stopwatch = stopwatch.pause(SystemClock.elapsedRealtime()) }

    fun resetStopwatch(context: Context) = change(context) { stopwatch = stopwatch.reset() }

    fun lap(context: Context) = change(context) { stopwatch = stopwatch.lap(SystemClock.elapsedRealtime()) }

    fun showMode(context: Context, countdownMode: Boolean) = change(context) { showCountdown = countdownMode }

    // ------------------------------------------------------------ keeping it

    private inline fun change(context: Context, block: () -> Unit) {
        load(context)
        block()
        version += 1
        save(context)
    }

    private fun save(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong("cd_set", countdown.setMs)
            .putString("cd_state", countdown.state.name)
            .putLong("cd_end", countdown.endAt)
            .putLong("cd_end_wall", countdown.endWall)
            .putLong("cd_left", countdown.leftMs)
            .putBoolean("sw_running", stopwatch.running)
            .putLong("sw_start", stopwatch.startedAt)
            .putLong("sw_start_wall", stopwatch.startWall)
            .putLong("sw_banked", stopwatch.banked)
            .putString("sw_laps", stopwatch.laps.joinToString(","))
            .putBoolean("mode_countdown", showCountdown)
            .putInt("boot", bootCount(context))
            .putLong("saved_at", SystemClock.elapsedRealtime())
            .apply()
    }

    private fun bootCount(context: Context): Int =
        runCatching { Settings.Global.getInt(context.contentResolver, Settings.Global.BOOT_COUNT) }.getOrDefault(-1)

    /** The countdown's two alarms: booked while it runs, cancelled otherwise. */
    private fun book(context: Context) {
        val manager = context.getSystemService(AlarmManager::class.java) ?: return
        val clock = pending(context, REQUEST_CLOCK)
        val backup = pending(context, REQUEST_BACKUP)
        if (countdown.state != Countdown.State.RUNNING) {
            manager.cancel(clock)
            manager.cancel(backup)
            return
        }
        val left = maxOf(0L, countdown.endAt - SystemClock.elapsedRealtime())
        val show = PendingIntent.getActivity(context, REQUEST_CLOCK, Intent(context, TimerActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        try {
            manager.setAlarmClock(AlarmManager.AlarmClockInfo(System.currentTimeMillis() + left, show), clock)
        } catch (e: SecurityException) {
            Log.w(TAG, "alarm clock refused: ${e.javaClass.simpleName}")
        }
        try {
            manager.setExactAndAllowWhileIdle(AlarmManager.ELAPSED_REALTIME_WAKEUP, countdown.endAt, backup)
        } catch (e: SecurityException) {
            Log.w(TAG, "exact alarm refused: ${e.javaClass.simpleName}")
        }
        Log.i(TAG, "countdown booked in_s=${left / 1000}")
    }

    private fun pending(context: Context, request: Int): PendingIntent =
        PendingIntent.getBroadcast(context, request, Intent(context, TimerReceiver::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
}

/** AlarmManager's call at the countdown's end. Not exported: only our own PendingIntent reaches it. */
class TimerReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        TimerClock.onAlarm(context)
    }
}
