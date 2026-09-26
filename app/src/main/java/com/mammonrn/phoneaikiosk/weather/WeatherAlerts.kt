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
 *              "line": "⚠ ฝนตกหนักมาก 22 จังหวัด ถึง 27 ก.ย. (กรมอุตุฯ)",
 *              "fetched": epoch seconds or null}],
 *              "updated": epoch seconds or null, "ok": bool,
 *              "sources": {name: {"ok": bool, "updated": epoch|null}}}
 *
 * PURE, no Android in it. A broker older than the block sends no "alerts": that
 * is no warnings, and the weather window's last line is the forecast exactly as
 * before.
 *
 * NO SOURCE NAMES ON THE CARD (Poom, 2026-09-26): a line never carries
 * "(กรมอุตุฯ)" or a "(source time)" tail — the broker's own `line` already
 * drops it, and this file never adds one back. Sources stay listed on the
 * "ที่มาข้อมูล" window, and Jarvis names them when asked. The room that freed
 * goes to the item's own detail (area, time window); the only thing added
 * here is a staleness note, and only when that item's own data is old.
 *
 * A WARNING IS A TRIANGLE, A KIOSK FORECAST IS A DIAMOND: every line built
 * here starts with "⚠" for kind "warning" or "◇" ([KIOSK_FORECAST_MARK]) for
 * kind "forecast", whatever the broker wrote — so each kind differs from the
 * location forecast (which starts with [FORECAST_MARK] while the two take
 * turns) in shape, not only in colour.
 *
 * FRESHNESS IS PER ITEM (2026-09-26, Poom): the top-level "updated"/"ok" only
 * ever follow TMD's CAP fetch, kept for a broker that predates this — an item
 * from สสน. (ThaiWater) that is perfectly fresh was once shown as possibly
 * stale purely because TMD had not answered. Each item's own "fetched" is
 * used for its staleness note; the object's "updated" is the fallback only
 * when an item carries no "fetched" of its own (older broker).
 */
object WeatherAlerts {

    const val WARNING_MARK = "⚠"

    /** The forecast's own mark, only while it takes turns with warnings. */
    const val FORECAST_MARK = "▸"

    /** The kiosk's own forecast (kind "forecast", coming later) — distinct
     *  from [FORECAST_MARK], which stays for the location forecast. */
    const val KIOSK_FORECAST_MARK = "◇"

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
        /**
         * When THIS item's own source was last fetched successfully, epoch
         * seconds; null on a broker old enough not to send it, in which case
         * [line] falls back to the block's overall [Block.updatedS].
         */
        val fetchedS: Long? = null,
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
                val fetchedS = if (o.isNull("fetched") || !o.has("fetched")) null
                               else o.optLong("fetched", -1L).takeIf { it > 0 }
                val item = Item(
                    kind = text(o, "kind"),
                    title = text(o, "title"),
                    areas = text(o, "areas"),
                    untilMs = until(text(o, "until"), zone),
                    source = text(o, "source"),
                    line = text(o, "line"),
                    fetchedS = fetchedS,
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
     * One item as the screen shows it: the broker's own `line` (no source name
     * in it — Poom, 2026-09-26), forced to start with the triangle for kind
     * "warning" or the diamond ([KIOSK_FORECAST_MARK]) for kind "forecast",
     * whatever the broker wrote. A staleness note is appended ONLY when this
     * item's own data is old — over [OLD_AFTER_S] ("· ข้อมูลเมื่อ 3 ชม.ที่แล้ว"),
     * or unknown because its OWN source could not be read ([Item.fetchedS]
     * absent, falling back to [Block.ok]/[Block.updatedS] only on a broker old
     * enough not to send it: "· ข้อมูลอาจไม่เป็นปัจจุบัน"). The note never names
     * a source; sources stay on the "ที่มาข้อมูล" window.
     *
     * Each item's own freshness, not the block's: a fresh สสน. item must not
     * be marked stale merely because TMD's CAP (the block's own "updated")
     * failed on this refresh — that was the bug seen on the phone.
     */
    fun line(item: Item, block: Block, nowMs: Long, zone: ZoneId = BANGKOK): String {
        var body = item.line.ifEmpty { listOf(item.title, item.areas).filter { it.isNotEmpty() }.joinToString(" ") }
        val mark = if (item.kind == "forecast") KIOSK_FORECAST_MARK else WARNING_MARK
        if (!body.startsWith(mark)) body = "$mark $body"

        val fetchedS = item.fetchedS ?: block.updatedS
        val ageS = fetchedS?.let { (nowMs / 1000 - it).coerceAtLeast(0) }
        // No per-item freshness at all (older broker): fall back to the
        // block's own ok, same as before this item ever had a "fetched".
        val old = (item.fetchedS == null && !block.ok) || (ageS != null && ageS > OLD_AFTER_S)
        val note = when {
            old && ageS != null -> "ข้อมูลเมื่อ ${ago(ageS)}"
            old -> "ข้อมูลอาจไม่เป็นปัจจุบัน"
            else -> ""
        }
        return if (note.isEmpty()) body else "$body · $note"
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
