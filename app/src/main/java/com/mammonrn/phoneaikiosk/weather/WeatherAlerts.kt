package com.mammonrn.phoneaikiosk.weather

import org.json.JSONObject
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime

/**
 * The Thai Meteorological Department's country-wide warnings, as the broker
 * sends them in /v1/dashboard:
 *
 *   "alerts": {"items": [{"kind": "warning", "title": …, "areas": …,
 *              "until": ISO-8601 or null, "source": "กรมอุตุฯ",
 *              "line": "⚠ ฝนตกหนักมาก 22 จังหวัด ถึง 27 ก.ย. (กรมอุตุฯ)"}],
 *              "updated": epoch seconds or null, "ok": bool}
 *
 * PURE, no Android in it. A broker older than the block sends no "alerts": that
 * is no warnings, and the weather window's last line is the forecast exactly as
 * before.
 *
 * A WARNING IS A TRIANGLE: every line built here starts with "⚠", whatever the
 * broker wrote, so a warning and the forecast (which starts with [FORECAST_MARK]
 * while the two take turns) differ in shape, not only in colour.
 */
object WeatherAlerts {

    const val WARNING_MARK = "⚠"

    /** The forecast's own mark, only while it takes turns with warnings. */
    const val FORECAST_MARK = "▸"

    /** Older than this and the line says how old, not the time it came. */
    const val OLD_AFTER_S = 6 * 60 * 60L

    data class Item(
        val kind: String,
        val title: String,
        val areas: String,
        /** When it ends, epoch ms; null when the broker did not say or it could not be read. */
        val untilMs: Long?,
        val source: String,
        val line: String,
    )

    data class Block(val items: List<Item>, val updatedS: Long?, val ok: Boolean)

    val NONE = Block(emptyList(), null, true)

    /**
     * Debug builds only (TEST_WEATHER_ALERTS over adb, and the UI walk): a sample
     * "alerts" block used instead of the broker's, to see the card with warnings
     * on the A07. Null in release; nothing in main code sets it.
     */
    @Volatile var override: String? = null

    /** The block, or [NONE]. Never throws. */
    fun parse(alerts: JSONObject?, zone: ZoneId = BANGKOK): Block {
        if (alerts == null) return NONE
        return try {
            val list = alerts.optJSONArray("items")
            val items = ArrayList<Item>()
            for (i in 0 until (list?.length() ?: 0)) {
                val o = list!!.optJSONObject(i) ?: continue
                val item = Item(
                    kind = text(o, "kind"),
                    title = text(o, "title"),
                    areas = text(o, "areas"),
                    untilMs = until(text(o, "until"), zone),
                    source = text(o, "source"),
                    line = text(o, "line"),
                )
                if (item.line.isNotEmpty() || item.title.isNotEmpty()) items.add(item)
            }
            val updated = if (alerts.isNull("updated") || !alerts.has("updated")) null
                          else alerts.optLong("updated", -1L).takeIf { it > 0 }
            Block(items, updated, alerts.optBoolean("ok", false))
        } catch (e: Exception) {
            NONE
        }
    }

    /** Parses the "alerts" block of a whole dashboard payload; [NONE] when it has none. */
    fun fromDashboard(json: String): Block =
        runCatching { parse(JSONObject(json).optJSONObject("alerts")) }.getOrDefault(NONE)

    private fun text(o: JSONObject, key: String): String =
        if (o.isNull(key)) "" else o.optString(key, "").trim()

    /**
     * "2026-09-27T18:00:00+07:00", "2026-09-27T18:00:00" (Thai time) or
     * "2026-09-27" (to the end of that day). Null for anything else: a warning
     * whose end cannot be read stays until the broker drops it.
     */
    fun until(iso: String, zone: ZoneId = BANGKOK): Long? {
        if (iso.isEmpty()) return null
        runCatching { return OffsetDateTime.parse(iso).toInstant().toEpochMilli() }
        runCatching { return ZonedDateTime.parse(iso).toInstant().toEpochMilli() }
        runCatching { return LocalDateTime.parse(iso).atZone(zone).toInstant().toEpochMilli() }
        runCatching { return LocalDate.parse(iso).plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli() }
        return null
    }

    /** The warnings still running at [nowMs]: one whose end has passed is dropped here too. */
    fun live(block: Block, nowMs: Long): List<Item> =
        block.items.filter { it.untilMs == null || it.untilMs > nowMs }

    /**
     * One warning as the screen shows it: the broker's line, starting with the
     * triangle, its source followed by the time it was read ("(กรมอุตุฯ 2:30 PM)",
     * the clock the rest of the screen uses) — or by how old it is when the
     * broker could not read the source this time ([Block.ok] false) or it is
     * over [OLD_AFTER_S] old ("(กรมอุตุฯ · ข้อมูลเมื่อ 3 ชม.ที่แล้ว)").
     */
    fun line(item: Item, block: Block, nowMs: Long, zone: ZoneId = BANGKOK): String {
        var body = item.line.ifEmpty { listOf(item.title, item.areas).filter { it.isNotEmpty() }.joinToString(" ") }
        if (!body.startsWith(WARNING_MARK)) body = "$WARNING_MARK $body"
        val source = item.source
        // The broker ends its line with "(source)"; the note goes inside it.
        val tail = if (source.isNotEmpty()) "($source)" else ""
        if (tail.isNotEmpty() && body.endsWith(tail)) body = body.removeSuffix(tail).trimEnd()

        val ageS = block.updatedS?.let { (nowMs / 1000 - it).coerceAtLeast(0) }
        val old = !block.ok || (ageS != null && ageS > OLD_AFTER_S)
        val note = when {
            old && ageS != null -> "ข้อมูลเมื่อ ${ago(ageS)}"
            old -> "ข้อมูลอาจไม่เป็นปัจจุบัน"
            block.updatedS != null -> clock(block.updatedS, zone)
            else -> ""
        }
        val inside = when {
            source.isEmpty() -> note
            note.isEmpty() -> source
            old -> "$source · $note"
            else -> "$source $note"
        }
        return if (inside.isEmpty()) body else "$body ($inside)"
    }

    /** "5 นาทีที่แล้ว", "3 ชม.ที่แล้ว", "2 วันที่แล้ว". */
    fun ago(seconds: Long): String = when {
        seconds < 3600 -> "${maxOf(1, seconds / 60)} นาทีที่แล้ว"
        seconds < 48 * 3600 -> "${seconds / 3600} ชม.ที่แล้ว"
        else -> "${seconds / 86400} วันที่แล้ว"
    }

    /** Epoch seconds on the screen's 12-hour clock, "2:30 PM". */
    fun clock(epochS: Long, zone: ZoneId = BANGKOK): String {
        val t = java.time.Instant.ofEpochSecond(epochS).atZone(zone)
        val h = t.hour % 12
        return "${if (h == 0) 12 else h}:${String.format(java.util.Locale.US, "%02d", t.minute)} ${if (t.hour < 12) "AM" else "PM"}"
    }

    /**
     * Everything the weather window's last line takes turns with: the forecast
     * first (marked while it has company), then each live warning. Only the
     * forecast, unmarked and as before, when there is no warning; empty when
     * there is neither.
     */
    fun lines(outlook: String, block: Block, nowMs: Long, zone: ZoneId = BANGKOK): List<String> {
        val warnings = live(block, nowMs).map { line(it, block, nowMs, zone) }
        if (warnings.isEmpty()) return if (outlook.isEmpty()) emptyList() else listOf(outlook)
        val forecast = if (outlook.isEmpty()) emptyList() else listOf("$FORECAST_MARK $outlook")
        return forecast + warnings
    }

    val BANGKOK: ZoneId = ZoneId.of("Asia/Bangkok")
}

/**
 * Which of the weather window's lines is showing (PURE): each for [periodMs],
 * in order, round and round; a tap shows the next at once and gives it a full
 * period. A new set of lines (another count) starts again from the first.
 */
class LineRotation(private val periodMs: Long = PERIOD_MS) {

    private var anchorMs = 0L
    private var anchorIndex = 0
    private var count = -1

    /** The index to show at [nowMs] of [lines] lines. */
    fun current(lines: Int, nowMs: Long): Int {
        if (lines <= 1) { reset(lines, nowMs); return 0 }
        if (lines != count) reset(lines, nowMs)
        val steps = ((nowMs - anchorMs).coerceAtLeast(0) / periodMs).toInt()
        return (anchorIndex + steps) % lines
    }

    /** A tap: the next line now, and a full period for it. Returns its index. */
    fun advance(lines: Int, nowMs: Long): Int {
        if (lines <= 1) return 0
        val next = (current(lines, nowMs) + 1) % lines
        anchorIndex = next
        anchorMs = nowMs
        return next
    }

    private fun reset(lines: Int, nowMs: Long) {
        count = lines
        anchorIndex = 0
        anchorMs = nowMs
    }

    companion object {
        /** Six seconds a line: long enough to read two lines of Thai at a glance. */
        const val PERIOD_MS = 6_000L
    }
}
