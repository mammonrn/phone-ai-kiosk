package com.mammonrn.phoneaikiosk.voice

import android.app.ActivityManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Debug
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import org.json.JSONObject
import java.util.Calendar
import java.util.TimeZone

/**
 * Phase 6, the 24–48 hour soak test: the numbers the phone sends every 15
 * minutes (POST /v1/health). The broker keeps them only while Poom has a soak
 * running (server/install/soak.sh), and keeps only the fields it knows.
 *
 * NUMBERS AND OUR OWN STATE WORDS ONLY. Nothing that was heard or said, no
 * place, no calendar, no account, no position: those never enter [sample], so
 * they cannot leave the phone this way. The broker drops anything it does not
 * list as well (server/kiosk_broker/soak.py FIELDS).
 *
 * The counters that must survive a restart (how many times the process, the
 * service and the activity started — the restart evidence itself) live in
 * their own preferences; the rest are read at the moment of the sample.
 */
object SoakProbe {

    const val INTERVAL_MS = 15 * 60_000L

    /** After a send that never reached the VPS: the gap is then the outage plus a minute. */
    const val RETRY_MS = 60_000L

    /** The first sample, a minute after the service starts, so a restart shows quickly. */
    const val FIRST_MS = 60_000L

    private const val PREFS = "soak_probe"
    private const val KEY_ALARM_LATE = "alarm_late_s"
    private const val KEY_ALARM_AT = "alarm_late_at"
    /** A lateness older than this is from an earlier test, not this one. */
    private const val ALARM_MEMORY_MS = 48 * 3_600_000L

    private var processCounted = false

    /** Dashboard refreshes that failed, since the process started. */
    @Volatile var dashboardFailures = 0

    /** Soak samples that never reached the VPS, since the process started. */
    @Volatile var sendFailures = 0

    /** [what] is "service" or "activity". The first call in a process also counts the process. */
    @Synchronized
    fun noteCreate(context: Context, what: String) {
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val edit = prefs.edit()
        if (!processCounted) {
            processCounted = true
            edit.putInt("process_starts", prefs.getInt("process_starts", 0) + 1)
        }
        val key = "${what}_creates"
        edit.putInt(key, prefs.getInt(key, 0) + 1).apply()
    }

    /** When an alarm rings: how far from its minute, kept for the next samples. */
    fun noteAlarmRang(context: Context, hour: Int, minute: Int, nowMs: Long = System.currentTimeMillis()) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit()
            .putLong(KEY_ALARM_LATE, lateSeconds(hour, minute, nowMs, TimeZone.getDefault()))
            .putLong(KEY_ALARM_AT, nowMs)
            .apply()
    }

    /**
     * Seconds after [hour]:[minute] that [nowMs] is — negative if early —
     * taking the nearest occurrence, so a ring at 00:00:30 for 23:59 is 90.
     */
    fun lateSeconds(hour: Int, minute: Int, nowMs: Long, zone: TimeZone): Long {
        val at = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, hour)
            set(Calendar.MINUTE, minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        var late = (nowMs - at.timeInMillis) / 1000
        if (late > 12 * 3600) late -= 24 * 3600
        if (late < -12 * 3600) late += 24 * 3600
        return late
    }

    /** One sample. Reads, never changes anything; well under a second. */
    fun sample(context: Context, stats: VoiceStats, detectorReady: Boolean): JSONObject {
        val now = System.currentTimeMillis()
        val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        val json = JSONObject()

        json.put("uptime_s", (SystemClock.elapsedRealtime() - Process.getStartElapsedRealtime()) / 1000)
        val memory = Debug.MemoryInfo().also { Debug.getMemoryInfo(it) }
        json.put("pss_kb", memory.totalPss)
        memory.getMemoryStat("summary.java-heap")?.toIntOrNull()?.let { json.put("java_kb", it) }
        memory.getMemoryStat("summary.native-heap")?.toIntOrNull()?.let { json.put("native_kb", it) }
        for (key in listOf("process_starts", "service_creates", "activity_creates")) {
            json.put(key, prefs.getInt(key, 0))
        }

        context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))?.let { battery ->
            val level = battery.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
            val scale = battery.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
            if (level >= 0 && scale > 0) json.put("battery_pct", level * 100 / scale)
            val tenths = battery.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, Int.MIN_VALUE)
            if (tenths != Int.MIN_VALUE) json.put("battery_temp_c", tenths / 10.0)
            json.put("plugged", when (battery.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0)) {
                BatteryManager.BATTERY_PLUGGED_AC -> "ac"
                BatteryManager.BATTERY_PLUGGED_USB -> "usb"
                BatteryManager.BATTERY_PLUGGED_WIRELESS -> "wireless"
                else -> "none"
            })
        }
        val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        json.put("thermal", power.currentThermalStatus)
        json.put("screen_on", if (power.isInteractive) "yes" else "no")
        val activity = context.getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        json.put("lock_task", when (activity.lockTaskModeState) {
            ActivityManager.LOCK_TASK_MODE_LOCKED -> "locked"
            ActivityManager.LOCK_TASK_MODE_PINNED -> "pinned"
            else -> "none"
        })

        json.put("mic", VoiceState.mic)
        json.put("detector_ready", if (detectorReady) "yes" else "no")
        for ((key, value) in stats.soakCounts()) json.put(key, value)
        json.put("send_failures", sendFailures)
        json.put("dashboard_failures", dashboardFailures)
        json.put("token", if (VoiceState.hasToken) "yes" else "no")

        com.mammonrn.phoneaikiosk.alarm.AlarmStore.load(context).next(now, TimeZone.getDefault())
            ?.let { json.put("alarm_next_min", (it.second - now) / 60_000) }
        if (now - prefs.getLong(KEY_ALARM_AT, 0L) < ALARM_MEMORY_MS) {
            json.put("alarm_late_s", prefs.getLong(KEY_ALARM_LATE, 0L))
        }
        return json
    }
}
