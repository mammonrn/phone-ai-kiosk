package com.mammonrn.phoneaikiosk.weather

import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.voice.DashboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The pixel icon beside each of today's weather numbers, and beside a สสน.
 * water-situation warning — PURE, level in, drawable id out (see
 * weather/WeatherIcons.kt).
 */
class WeatherIconsTest {

    // ------------------------------------------------------------ rain / wind

    @Test
    fun `rain chance climbs from one small droplet to two`() {
        assertEquals(R.drawable.ic_pixel_rain_low, WeatherIcons.rain(0))
        assertEquals(R.drawable.ic_pixel_rain_low, WeatherIcons.rain(29))
        assertEquals(R.drawable.ic_pixel_rain_medium, WeatherIcons.rain(30))
        assertEquals(R.drawable.ic_pixel_rain_medium, WeatherIcons.rain(59))
        assertEquals(R.drawable.ic_pixel_rain_high, WeatherIcons.rain(60))
        assertEquals(R.drawable.ic_pixel_rain_high, WeatherIcons.rain(100))
    }

    @Test
    fun `wind speed climbs from one streamline to three`() {
        assertEquals(R.drawable.ic_pixel_wind_calm, WeatherIcons.wind(0))
        assertEquals(R.drawable.ic_pixel_wind_calm, WeatherIcons.wind(14))
        assertEquals(R.drawable.ic_pixel_wind_moderate, WeatherIcons.wind(15))
        assertEquals(R.drawable.ic_pixel_wind_moderate, WeatherIcons.wind(29))
        assertEquals(R.drawable.ic_pixel_wind_strong, WeatherIcons.wind(30))
    }

    // -------------------------------------------------------------------- UV

    @Test
    fun `UV uses the same five words DashboardState uvWord returns`() {
        // 2026-09-26: uvWord's own bands, read back through the icon lookup.
        assertEquals(R.drawable.ic_pixel_uv_low, WeatherIcons.uv(DashboardState.uvWord(0.4)))
        assertEquals(R.drawable.ic_pixel_uv_medium, WeatherIcons.uv(DashboardState.uvWord(4.0)))
        assertEquals(R.drawable.ic_pixel_uv_high, WeatherIcons.uv(DashboardState.uvWord(7.0)))
        assertEquals(R.drawable.ic_pixel_uv_very_high, WeatherIcons.uv(DashboardState.uvWord(10.0)))
        assertEquals(R.drawable.ic_pixel_uv_extreme, WeatherIcons.uv(DashboardState.uvWord(12.0)))
    }

    @Test
    fun `an unrecognised UV word is never shown as worse than low`() {
        assertEquals(R.drawable.ic_pixel_uv_low, WeatherIcons.uv("???"))
    }

    // ------------------------------------------------------------------ PM2.5

    @Test
    fun `PM2_5 uses the PCD 2566 words the broker sends`() {
        assertEquals(R.drawable.ic_pixel_pm25_1, WeatherIcons.pm25("ดีมาก"))
        assertEquals(R.drawable.ic_pixel_pm25_2, WeatherIcons.pm25("ดี"))
        assertEquals(R.drawable.ic_pixel_pm25_3, WeatherIcons.pm25("ปานกลาง"))
        assertEquals(R.drawable.ic_pixel_pm25_4, WeatherIcons.pm25("เริ่มมีผลต่อสุขภาพ"))
        assertEquals(R.drawable.ic_pixel_pm25_5, WeatherIcons.pm25("มีผลต่อสุขภาพ"))
    }

    @Test
    fun `an unrecognised PM2_5 word is never shown as worse than the best band`() {
        assertEquals(R.drawable.ic_pixel_pm25_1, WeatherIcons.pm25(""))
    }

    // ---------------------------------------------------------- weatherStats rows

    @Test
    fun `a stat with a value gets the matching icon`() {
        assertEquals(R.drawable.ic_pixel_rain_high, WeatherIcons.forStat("โอกาสฝน", "72%"))
        assertEquals(R.drawable.ic_pixel_wind_moderate, WeatherIcons.forStat("ลม กม./ชม.", "20"))
        assertEquals(R.drawable.ic_pixel_uv_extreme, WeatherIcons.forStat("UV", "12 อันตราย"))
        assertEquals(R.drawable.ic_pixel_uv_low, WeatherIcons.forStat("UV", "0.4 ต่ำ"))
        assertEquals(R.drawable.ic_pixel_pm25_5,
            WeatherIcons.forStat("PM2.5 มคก./ลบ.ม.", "80 มีผลต่อสุขภาพ"))
    }

    @Test
    fun `high-low gets no icon at all, not even for missing data`() {
        assertNull(WeatherIcons.forStat("สูง/ต่ำ", "31°/24°"))
        assertNull(WeatherIcons.forStat("สูง/ต่ำ", DashboardState.DASH))
    }

    @Test
    fun `a dash on an iconed stat is the neutral unknown icon, not a guessed level`() {
        for (label in listOf("โอกาสฝน", "ลม กม./ชม.", "UV", "PM2.5 มคก./ลบ.ม.")) {
            assertEquals(label, WeatherIcons.UNKNOWN, WeatherIcons.forStat(label, DashboardState.DASH))
        }
    }

    @Test
    fun `a PM2_5 number with no word attached is unknown, never a guessed level`() {
        // The broker sending a bare number with no pm25_word is not something
        // the phone should ever see, but a parser must not silently guess a
        // severity for it either.
        assertEquals(WeatherIcons.UNKNOWN, WeatherIcons.forStat("PM2.5 มคก./ลบ.ม.", "12"))
    }

    // ------------------------------------------------------- water situations

    @Test
    fun `a สสน overflow warning gets the severe icon, a plain one the moderate icon`() {
        assertEquals(R.drawable.ic_pixel_water_severe,
            WeatherIcons.alertIcon("สสน.", "น้ำล้นตลิ่ง 55 จุด"))
        assertEquals(R.drawable.ic_pixel_water_moderate,
            WeatherIcons.alertIcon("สสน.", "น้ำมาก 12 จุด"))
    }

    @Test
    fun `a TMD or GDACS warning gets no water icon at all`() {
        assertNull(WeatherIcons.alertIcon("กรมอุตุฯ", "ฝนตกหนักมาก"))
        assertNull(WeatherIcons.alertIcon("GDACS", "น้ำท่วม"))
    }

    // ------------------------------------------------------------------ icons

    @Test
    fun `every new icon is a 16x16 square with at most four colours and no curves`() {
        for (name in listOf(
            "rain_low", "rain_medium", "rain_high",
            "wind_calm", "wind_moderate", "wind_strong",
            "uv_low", "uv_medium", "uv_high", "uv_very_high", "uv_extreme",
            "pm25_1", "pm25_2", "pm25_3", "pm25_4", "pm25_5",
            "water_neutral", "water_moderate", "water_severe",
            "stat_unknown",
        )) {
            val xml = file("src/main/res/drawable/ic_pixel_$name.xml")
            assertTrue(name, "android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
            val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml)
                .map { it.groupValues[1].uppercase() }.toSet()
            assertTrue("$name has ${colours.size} colours", colours.size in 1..4)
            for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
                assertTrue("$name draws a curve or diagonal", Regex("[cCsSqQtTaAlL]").find(data) == null)
            }
        }
    }

    /**
     * Every icon colour used above already sits in DESIGN.md's icon colour
     * table for some OTHER icon (navy for wifi/Google Home, the gold family
     * for the sun, battery red) — no new colour was added there, which is
     * the rule that table states ("ใช้ซ้ำเท่านั้น ถ้าจะเพิ่มสีใหม่ต้องลงตารางนี้ก่อน").
     */
    @Test
    fun `no icon colour here is new to the project`() {
        val known = setOf("#FFB000", "#000080", "#CC0000", "#FFFFFF", "#000000", "#808080", "#404040")
        for (name in listOf(
            "rain_low", "rain_medium", "rain_high",
            "wind_calm", "wind_moderate", "wind_strong",
            "uv_low", "uv_medium", "uv_high", "uv_very_high", "uv_extreme",
            "pm25_1", "pm25_2", "pm25_3", "pm25_4", "pm25_5",
            "water_neutral", "water_moderate", "water_severe",
            "stat_unknown",
        )) {
            val xml = file("src/main/res/drawable/ic_pixel_$name.xml")
            val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }
            for (colour in colours) assertTrue("$name uses a new colour $colour", colour in known)
        }
    }

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()
}
