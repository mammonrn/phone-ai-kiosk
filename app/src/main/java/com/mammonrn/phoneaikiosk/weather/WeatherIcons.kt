package com.mammonrn.phoneaikiosk.weather

import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.voice.DashboardState

/**
 * Which small pixel icon sits beside one of today's weather numbers
 * (DashboardState.weatherStats: rain chance, wind, UV, PM2.5) or beside a
 * สสน. water-situation warning line.
 *
 * PURE — an Int resource id in, an Int resource id out, so the mapping is
 * unit-tested without a phone (WeatherIconsTest), the same reason
 * DashboardState itself carries no Android.
 *
 * DANGER DIFFERS BY SHAPE HERE, NOT COLOUR ALONE (DESIGN.md "6. ไอคอน"): more
 * rays, more raindrops, more streamlines, more particles, or a hazard mark
 * added at the worst level — never the same outline just recoloured.
 *
 * NO NEW ICON COLOUR WAS ADDED for any of this: the gold family for UV is the
 * sun's own colours, navy `#000080` for rain and water is already the wifi
 * and Google Home colour, and the low-battery red `#CC0000` marks the worst
 * PM2.5 level and the worst water level, same as it already marks a low
 * battery (DESIGN.md's icon colour table, "reuse only").
 */
object WeatherIcons {

    /** Beside a value that reads "—" (DashboardState.DASH): never a guessed level. */
    val UNKNOWN = R.drawable.ic_pixel_stat_unknown

    /** Today's rain chance, 0-100. */
    fun rain(percent: Int): Int = when {
        percent < 30 -> R.drawable.ic_pixel_rain_low
        percent < 60 -> R.drawable.ic_pixel_rain_medium
        else -> R.drawable.ic_pixel_rain_high
    }

    /** Wind speed in km/h. */
    fun wind(kmh: Int): Int = when {
        kmh < 15 -> R.drawable.ic_pixel_wind_calm
        kmh < 30 -> R.drawable.ic_pixel_wind_moderate
        else -> R.drawable.ic_pixel_wind_strong
    }

    /** The exact Thai words DashboardState.uvWord returns. An unrecognised word
     *  is treated as "low" — never a level the data did not say. */
    fun uv(word: String): Int = when (word) {
        "ปานกลาง" -> R.drawable.ic_pixel_uv_medium
        "สูง" -> R.drawable.ic_pixel_uv_high
        "สูงมาก" -> R.drawable.ic_pixel_uv_very_high
        "อันตราย" -> R.drawable.ic_pixel_uv_extreme
        else -> R.drawable.ic_pixel_uv_low
    }

    /** The exact Thai words the broker's `pm25_word` sends (DESIGN.md "การ์ดอากาศ",
     *  the PCD 2566 bands). An unrecognised word is treated as the best band —
     *  never worse than the data actually said. */
    fun pm25(word: String): Int = when (word) {
        "ดี" -> R.drawable.ic_pixel_pm25_2
        "ปานกลาง" -> R.drawable.ic_pixel_pm25_3
        "เริ่มมีผลต่อสุขภาพ" -> R.drawable.ic_pixel_pm25_4
        "มีผลต่อสุขภาพ" -> R.drawable.ic_pixel_pm25_5
        else -> R.drawable.ic_pixel_pm25_1
    }

    /**
     * A สสน. water-situation warning's icon, read from the fields
     * WeatherAlerts.Item already carries: its SOURCE and its TITLE.
     *
     * Not "kind" — every alert item's kind is "warning"
     * (server/kiosk_broker/alerts.py `payload_items`); สสน.'s own two labels
     * in the title, "น้ำล้นตลิ่ง" and "น้ำมาก", are what tell the two levels
     * apart, and this reads only THOSE — it assigns no level of its own, the
     * same rule the broker follows for its own `situation_level`.
     *
     * Null for anything else: a TMD or GDACS warning already has its shape
     * from the ⚠ mark every warning line starts with, and does not need an
     * icon here too.
     *
     * (The wiring of this into the alert line itself belongs to whoever owns
     * WeatherAlerts.kt; this is only the icon lookup.)
     */
    fun alertIcon(source: String, title: String): Int? {
        if (source != "สสน.") return null
        return when {
            title.contains("ล้นตลิ่ง") -> R.drawable.ic_pixel_water_severe
            title.contains("น้ำมาก") -> R.drawable.ic_pixel_water_moderate
            else -> R.drawable.ic_pixel_water_neutral
        }
    }

    // ------------------------------------------------------ weatherStats rows

    private const val RAIN_LABEL = "โอกาสฝน"
    private const val WIND_LABEL = "ลม กม./ชม."
    private const val UV_LABEL = "UV"
    private const val PM25_LABEL = "PM2.5 มคก./ลบ.ม."
    private val ICONED_LABELS = setOf(RAIN_LABEL, WIND_LABEL, UV_LABEL, PM25_LABEL)

    /**
     * The icon for one row of DashboardState.weatherStats, read from the same
     * label/value strings already on screen — so the phone does not need a
     * second copy of the parsing DashboardState did to build them.
     *
     * Null for a row this card gives no icon at all (today's high/low), not
     * for a row with no data — that gets [UNKNOWN] instead, so "no icon here"
     * and "no data for this number" stay two different things on screen
     * (ux-ui-design SKILL.md: one empty value must not carry two meanings).
     */
    fun forStat(label: String, value: String): Int? {
        if (value == DashboardState.DASH) {
            return if (label in ICONED_LABELS) UNKNOWN else null
        }
        return when (label) {
            RAIN_LABEL -> leadingInt(value)?.let(::rain)
            WIND_LABEL -> leadingInt(value)?.let(::wind)
            UV_LABEL -> uv(trailingWord(value))
            // A number with no word to read is a data-quality gap, not a level
            // (0 particles is not what this means): the neutral icon, like a
            // failed value, never a guessed severity.
            PM25_LABEL -> trailingWord(value).takeIf { it.isNotEmpty() }?.let(::pm25) ?: UNKNOWN
            else -> null
        }
    }

    private fun leadingInt(value: String): Int? =
        Regex("""^-?\d+""").find(value)?.value?.toIntOrNull()

    /** "8 สูง" -> "สูง", "8.8 ดีมาก" -> "ดีมาก", "8" alone -> "" (no word to read). */
    private fun trailingWord(value: String): String {
        val trimmed = value.trim()
        val lastSpace = trimmed.lastIndexOf(' ')
        if (lastSpace < 0) return ""
        val word = trimmed.substring(lastSpace + 1)
        return if (word.isNotEmpty() && !word[0].isDigit()) word else ""
    }
}
