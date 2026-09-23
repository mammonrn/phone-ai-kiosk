package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.RetroType
import com.mammonrn.phoneaikiosk.voice.DashboardState
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Sunrise and sunset in the weather window, and an icon per coin (2026-09-23).
 */
class SunAndCoinIconTest {

    @Test
    fun `sun times use the taskbar clock's format`() {
        assertEquals("6:05 AM", DashboardState.clock12("06:05"))
        assertEquals("6:13 PM", DashboardState.clock12("18:13"))
        assertEquals("12:00 PM", DashboardState.clock12("12:00"))
        assertEquals("12:30 AM", DashboardState.clock12("00:30"))
    }

    @Test
    fun `anything that is not HH colon MM is no time at all`() {
        for (odd in listOf("", "null", "6:05", "24:00", "06:60", "2026-09-23T06:05", "ab:cd")) {
            assertEquals(odd, "", DashboardState.clock12(odd))
        }
    }

    @Test
    fun `sun times reach the screen from the weather panel`() {
        val json = """{"weather":{"ok":true,"temp_c":28.4,"humidity":70,"word":"แดดจัด",
            "is_day":1,"sunrise":"06:05","sunset":"18:13"}}"""
        val screen = DashboardState.parse(json, "-")
        assertEquals("6:05 AM", screen.sunrise)
        assertEquals("6:13 PM", screen.sunset)
    }

    @Test
    fun `an older broker without sun times leaves them empty and the rest intact`() {
        val json = """{"weather":{"ok":true,"temp_c":28.4,"humidity":70,"word":"แดดจัด","is_day":1}}"""
        val screen = DashboardState.parse(json, "-")
        assertEquals("", screen.sunrise)
        assertEquals("", screen.sunset)
        assertEquals(true, screen.weather.text.startsWith("28.4°C"))
    }

    @Test
    fun `a sun time of null is empty, not the word null`() {
        val json = """{"weather":{"ok":true,"temp_c":28.4,"sunrise":null,"sunset":null}}"""
        assertEquals("", DashboardState.parse(json, "-").sunrise)
    }

    @Test
    fun `each coin line is found, prices and notes are not`() {
        val column = "BTC +1.81%\n\$87,102\n\nETH +1.78%\n\$2,783  (3 นาทีก่อน)"
        assertEquals(listOf(0 to "BTC", 20 to "ETH"), RetroType.coinTickers(column))
    }

    @Test
    fun `a coin with no move yet and a new coin are still found`() {
        assertEquals(listOf(0 to "SOL"), RetroType.coinTickers("SOL\n\$150"))
    }
}
