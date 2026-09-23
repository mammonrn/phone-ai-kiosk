package com.mammonrn.phoneaikiosk.alarm

import java.util.Calendar
import java.util.TimeZone
import org.json.JSONArray
import org.json.JSONObject

/**
 * The kiosk's alarms, and every rule about them, without Android.
 *
 * An alarm is a time of day, an optional name, on/off, and WHEN it repeats:
 *   every day (the default, and what a spoken "ปลุกตีห้า" sets — Poom's choice),
 *   chosen days of the week, or once — a one-off switches itself off after it
 *   has rung. The Control Panel (settings/SettingsActivity) edits all of it;
 *   the voice sets time and name and switches on and off.
 *
 * Pure, so it is tested without a phone: AlarmStore keeps it in
 * SharedPreferences and AlarmScheduler hands the next one to AlarmManager.
 */
class AlarmBook(alarms: List<Alarm> = emptyList()) {

    /**
     * [days] is a bit per weekday, bit 0 = Sunday … bit 6 = Saturday — the
     * order of Calendar.DAY_OF_WEEK. It is ignored when [once] is true.
     */
    data class Alarm(val id: Int, val hour: Int, val minute: Int, val label: String,
                     val enabled: Boolean, val days: Int = EVERY_DAY, val once: Boolean = false) {
        val time: String get() = String.format(java.util.Locale.US, "%02d:%02d", hour, minute)
    }

    private val list = alarms.sortedWith(ORDER).toMutableList()

    val alarms: List<Alarm> get() = list.toList()

    /**
     * The spoken command: sets (or renames and re-enables) the alarm at
     * [hour]:[minute], every day unless it already had its own days. Null when full.
     */
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
        return add(hour, minute, clean, EVERY_DAY, once = false)
    }

    /** A new alarm from the Control Panel. Null when full or the time is taken. */
    fun add(hour: Int, minute: Int, label: String, days: Int, once: Boolean): Alarm? {
        require(hour in 0..23 && minute in 0..59)
        if (list.size >= MAX_ALARMS || timeTaken(hour, minute, exceptId = null)) return null
        val alarm = Alarm((list.maxOfOrNull { it.id } ?: 0) + 1, hour, minute,
                          label.trim().take(MAX_LABEL_CHARS), true, days and EVERY_DAY, once)
        list += alarm
        list.sortWith(ORDER)
        return alarm
    }

    /** Changes alarm [id]. False when it is gone or another alarm has that time. */
    fun update(id: Int, hour: Int, minute: Int, label: String, days: Int, once: Boolean): Boolean {
        require(hour in 0..23 && minute in 0..59)
        val index = list.indexOfFirst { it.id == id }
        if (index < 0 || timeTaken(hour, minute, exceptId = id)) return false
        list[index] = list[index].copy(hour = hour, minute = minute,
                                       label = label.trim().take(MAX_LABEL_CHARS),
                                       days = days and EVERY_DAY, once = once, enabled = true)
        list.sortWith(ORDER)
        return true
    }

    fun remove(id: Int): Boolean = list.removeAll { it.id == id }

    fun setEnabled(id: Int, enabled: Boolean): Boolean {
        val index = list.indexOfFirst { it.id == id }
        if (index < 0) return false
        list[index] = list[index].copy(enabled = enabled)
        return true
    }

    fun timeTaken(hour: Int, minute: Int, exceptId: Int?): Boolean =
        list.any { it.hour == hour && it.minute == minute && it.id != exceptId }

    /**
     * Switches alarms on or off by "all", a time "06:30", or a name. A name
     * matches when either contains the other, so "ไปทำงาน" finds "ไปทำงานเช้า".
     * Returns how many changed state — 0 means nothing matched or they already
     * were, and Jarvis should say so rather than claim it did something.
     */
    fun enable(target: String, enabled: Boolean): Int {
        var changed = 0
        for ((index, alarm) in list.withIndex()) {
            if (matchesOne(alarm, target.trim()) && alarm.enabled != enabled) {
                list[index] = alarm.copy(enabled = enabled)
                changed++
            }
        }
        return changed
    }

    fun matches(target: String): Boolean = list.any { matchesOne(it, target.trim()) }

    private fun matchesOne(alarm: Alarm, t: String): Boolean =
        t == "all" || t == alarm.time ||
            (t.isNotEmpty() && alarm.label.isNotEmpty() &&
                (alarm.label.contains(t) || t.contains(alarm.label)))

    fun byId(id: Int): Alarm? = list.firstOrNull { it.id == id }

    /** Removes every alarm named exactly [label]; how many went. For tests. */
    fun removeNamed(label: String): Int {
        val before = list.size
        list.removeAll { it.label == label }
        return before - list.size
    }

    /**
     * The next time [alarm] rings strictly after [nowMs], in [zone], or null
     * when it never will (repeating, with no day chosen).
     */
    fun nextRing(alarm: Alarm, nowMs: Long, zone: TimeZone): Long? {
        val at = Calendar.getInstance(zone).apply {
            timeInMillis = nowMs
            set(Calendar.HOUR_OF_DAY, alarm.hour)
            set(Calendar.MINUTE, alarm.minute)
            set(Calendar.SECOND, 0)
            set(Calendar.MILLISECOND, 0)
        }
        for (ahead in 0..7) {
            if (at.timeInMillis > nowMs) {
                val bit = 1 shl (at.get(Calendar.DAY_OF_WEEK) - 1)
                if (alarm.once || alarm.days and bit != 0) return at.timeInMillis
            }
            at.add(Calendar.DAY_OF_YEAR, 1)
        }
        return null
    }

    /** The enabled alarm that rings soonest after [nowMs], with its time. */
    fun next(nowMs: Long, zone: TimeZone): Pair<Alarm, Long>? =
        list.filter { it.enabled }
            .mapNotNull { alarm -> nextRing(alarm, nowMs, zone)?.let { alarm to it } }
            .minByOrNull { it.second }

    fun toJson(): String = JSONArray().apply {
        for (a in list) put(JSONObject().put("id", a.id).put("h", a.hour).put("m", a.minute)
            .put("label", a.label).put("on", a.enabled).put("d", a.days).put("o", a.once))
    }.toString()

    companion object {
        const val MAX_ALARMS = 10
        const val MAX_LABEL_CHARS = 20

        /** Every bit of the week. */
        const val EVERY_DAY = 0x7F

        /** Monday to Friday. */
        const val WEEKDAYS = 0x3E

        /** Saturday and Sunday. */
        const val WEEKEND = 0x41

        /** Short Thai day names, Sunday first — the bit order. */
        val DAY_NAMES = listOf("อา.", "จ.", "อ.", "พ.", "พฤ.", "ศ.", "ส.")

        private val ORDER = compareBy<Alarm>({ it.hour }, { it.minute })

        /** "ทุกวัน", "ครั้งเดียว", "จันทร์–ศุกร์", "เสาร์–อาทิตย์" or "จ. พ. ศ.". */
        fun repeatText(alarm: Alarm): String = when {
            alarm.once -> "ครั้งเดียว"
            alarm.days == EVERY_DAY -> "ทุกวัน"
            alarm.days == WEEKDAYS -> "จันทร์–ศุกร์"
            alarm.days == WEEKEND -> "เสาร์–อาทิตย์"
            alarm.days == 0 -> "ยังไม่ได้เลือกวัน"
            // Monday first, the way a Thai week is read.
            else -> listOf(1, 2, 3, 4, 5, 6, 0).filter { alarm.days and (1 shl it) != 0 }
                .joinToString(" ") { DAY_NAMES[it] }
        }

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
                             o.optString("label", "").take(MAX_LABEL_CHARS), o.optBoolean("on", true),
                             o.optInt("d", EVERY_DAY) and EVERY_DAY, o.optBoolean("o", false))
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
