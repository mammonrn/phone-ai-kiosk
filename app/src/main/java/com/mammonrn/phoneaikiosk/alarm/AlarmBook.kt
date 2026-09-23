package com.mammonrn.phoneaikiosk.alarm

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * The kiosk's alarms, and every rule about them, without Android.
 *
 * An alarm is a time of day, an optional name and on/off. It rings EVERY DAY
 * at that time while it is on: "ปลุกตีห้า ไปทำงาน" is set once and switched
 * off with "ปิดปลุกไปทำงาน" when it is not wanted, the way a bedside clock
 * works. Setting the same time again renames it and switches it back on rather
 * than adding a second alarm at the same minute.
 *
 * Pure, so it is tested without a phone: AlarmStore keeps it in
 * SharedPreferences and AlarmScheduler hands the next one to AlarmManager.
 */
class AlarmBook(alarms: List<Alarm> = emptyList()) {

    data class Alarm(val id: Int, val hour: Int, val minute: Int, val label: String,
                     val enabled: Boolean) {
        val time: String get() = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
    }

    private val list = alarms.sortedWith(compareBy({ it.hour }, { it.minute })).toMutableList()

    val alarms: List<Alarm> get() = list.toList()

    /** Sets (or renames and re-enables) the alarm at [hour]:[minute]. Null when full. */
    fun set(hour: Int, minute: Int, label: String): Alarm? {
        require(hour in 0..23 && minute in 0..59)
        val clean = label.trim().take(MAX_LABEL_CHARS)
        val existing = list.indexOfFirst { it.hour == hour && it.minute == minute }
        if (existing >= 0) {
            val updated = list[existing].copy(label = clean.ifEmpty { list[existing].label },
                                              enabled = true)
            list[existing] = updated
            return updated
        }
        if (list.size >= MAX_ALARMS) return null
        val alarm = Alarm((list.maxOfOrNull { it.id } ?: 0) + 1, hour, minute, clean, true)
        list += alarm
        list.sortWith(compareBy({ it.hour }, { it.minute }))
        return alarm
    }

    /**
     * Switches alarms on or off by "all", a time "06:30", or a name. A name
     * matches when either contains the other, so "ไปทำงาน" finds "ไปทำงานเช้า".
     * Returns how many changed state — 0 means nothing matched or they already
     * were, and Jarvis should say so rather than claim it did something.
     */
    fun enable(target: String, enabled: Boolean): Int {
        val t = target.trim()
        var changed = 0
        for ((index, alarm) in list.withIndex()) {
            val match = t == "all" || t == alarm.time ||
                (t.isNotEmpty() && alarm.label.isNotEmpty() &&
                    (alarm.label.contains(t) || t.contains(alarm.label)))
            if (match && alarm.enabled != enabled) {
                list[index] = alarm.copy(enabled = enabled)
                changed++
            }
        }
        return changed
    }

    fun matches(target: String): Boolean {
        val t = target.trim()
        return list.any { t == "all" || t == it.time ||
            (t.isNotEmpty() && it.label.isNotEmpty() && (it.label.contains(t) || t.contains(it.label))) }
    }

    fun byId(id: Int): Alarm? = list.firstOrNull { it.id == id }

    /** Removes every alarm named exactly [label]; how many went. For tests. */
    fun removeNamed(label: String): Int {
        val before = list.size
        list.removeAll { it.label == label }
        return before - list.size
    }

    /** The next time [alarm] rings strictly after [nowMs], in [zone]. */
    fun nextRing(alarm: Alarm, nowMs: Long, zone: TimeZone): Long {
        val at = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        if (at.timeInMillis <= nowMs) at.add(Calendar.DAY_OF_YEAR, 1)
        return at.timeInMillis
    }

    /** The enabled alarm that rings soonest after [nowMs], with its time. */
    fun next(nowMs: Long, zone: TimeZone): Pair<Alarm, Long>? =
        list.filter { it.enabled }
            .map { it to nextRing(it, nowMs, zone) }
            .minByOrNull { it.second }

    fun toJson(): String = JSONArray().apply {
        for (a in list) put(JSONObject().put("id", a.id).put("h", a.hour).put("m", a.minute)
            .put("label", a.label).put("on", a.enabled))
    }.toString()

    companion object {
        const val MAX_ALARMS = 10
        const val MAX_LABEL_CHARS = 20

        /** Reads what toJson wrote. Anything unreadable is skipped, never fatal. */
        fun fromJson(json: String?): AlarmBook {
            if (json.isNullOrBlank()) return AlarmBook()
            val out = ArrayList<Alarm>()
            val array = runCatching { JSONArray(json) }.getOrNull() ?: return AlarmBook()
            for (i in 0 until array.length()) {
                val o = array.optJSONObject(i) ?: continue
                val h = o.optInt("h", -1)
                val m = o.optInt("m", -1)
                if (h !in 0..23 || m !in 0..59) continue
                out += Alarm(o.optInt("id", i + 1), h, m,
                             o.optString("label", "").take(MAX_LABEL_CHARS), o.optBoolean("on", true))
            }
            return AlarmBook(out)
        }

        /** "06:30" -> (6, 30), or null. */
        fun parseTime(text: String): Pair<Int, Int>? {
            val match = Regex("""^(\d{2}):(\d{2})$""").matchEntire(text.trim()) ?: return null
            val h = match.groupValues[1].toInt()
            val m = match.groupValues[2].toInt()
            return if (h in 0..23 && m in 0..59) h to m else null
        }
    }
}
