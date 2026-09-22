package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.DashboardState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the kiosk screen says, including when the data is not there.
 *
 * The interesting behaviour is never "does it draw" — it is what appears when
 * the gold source is down, the weather is fine and the crypto price is four
 * minutes old. That is a question about strings, and this file answers it
 * without a phone in the room.
 */
class DashboardStateTest {

    private val unavailable = "ข้อมูลไม่พร้อม"

    private val everything = """
        {"weather":{"ok":true,"age_seconds":0,"credit":"Open-Meteo (CC BY 4.0)",
                    "temp_c":28.2,"humidity":83,"code":1,"word":"แดดรำไร",
                    "high_c":30.7,"low_c":22.9},
         "gold":{"ok":true,"age_seconds":0,"credit":"x",
                 "ornament_sell":68850.0,"ornament_buy":66491.76,
                 "bar_sell":68050.0,"bar_buy":67850.0,"updated":"22/09/2569"},
         "crypto":{"ok":true,"age_seconds":0,"credit":"Binance",
                   "btc":{"usd":85986.58,"change_pct":1.53},
                   "eth":{"usd":2741.28,"change_pct":-0.63},"quote":"USDT"},
         "place":"แม่สาย เชียงราย"}
    """.trimIndent()

    // ------------------------------------------------------------ a good day

    @Test
    fun `every panel is filled in`() {
        val screen = DashboardState.parse(everything, unavailable)

        assertTrue(screen.weather.text.contains("28.2°C"))
        assertTrue(screen.weather.text.contains("แดดรำไร"))
        assertTrue(screen.gold.text.contains("68,850"))
        assertTrue(screen.crypto.text.contains("$85,987"))
        assertEquals("แม่สาย เชียงราย", screen.place)
        assertFalse(screen.weather.stale)
    }

    @Test
    fun `numbers are grouped and rounded for reading at two metres`() {
        assertEquals("68,850 บ.", DashboardState.baht(68850.0))
        assertEquals("$85,987", DashboardState.dollars(85986.58))
        // Satang and cents are noise on a wall.
        assertEquals("1,000 บ.", DashboardState.baht(999.7))
        assertEquals("$1", DashboardState.dollars(0.9))
    }

    @Test
    fun `a whole number loses its pointless decimal`() {
        assertEquals("28", DashboardState.trim(28.0))
        assertEquals("28.2", DashboardState.trim(28.2))
    }

    @Test
    fun `the direction of a price move is shown with its sign`() {
        val screen = DashboardState.parse(everything, unavailable)
        assertTrue("got: ${screen.crypto.text}", screen.crypto.text.contains("+1.53%"))
        assertTrue("got: ${screen.crypto.text}", screen.crypto.text.contains("-0.63%"))
    }

    // ------------------------------------------- one source down, rest alive

    /** THE RULE THE WHOLE THING EXISTS FOR. */
    @Test
    fun `a failed panel does not take the other panels with it`() {
        val partial = """
            {"weather":{"ok":true,"age_seconds":0,"temp_c":28.2,"humidity":83,
                        "word":"แดดรำไร"},
             "gold":{"ok":false,"age_seconds":0,"error":"OSError"},
             "crypto":{"ok":true,"age_seconds":0,"btc":{"usd":85986.58,"change_pct":1.5}}}
        """.trimIndent()

        val screen = DashboardState.parse(partial, unavailable)
        assertEquals(unavailable, screen.gold.text)
        assertTrue(screen.weather.text.contains("28.2"))
        assertTrue(screen.crypto.text.contains("85,987"))
    }

    @Test
    fun `every panel failing is still a screen and not a crash`() {
        val allDown = """
            {"weather":{"ok":false,"error":"OSError","age_seconds":0},
             "gold":{"ok":false,"error":"OSError","age_seconds":0},
             "crypto":{"ok":false,"error":"OSError","age_seconds":0}}
        """.trimIndent()

        val screen = DashboardState.parse(allDown, unavailable)
        assertEquals(unavailable, screen.weather.text)
        assertEquals(unavailable, screen.gold.text)
        assertEquals(unavailable, screen.crypto.text)
    }

    // --------------------------------------------------------- stale but real

    @Test
    fun `a stale value is shown rather than thrown away`() {
        val stale = """
            {"gold":{"ok":false,"error":"OSError","age_seconds":420,
                     "stale":{"ornament_sell":68850.0,"bar_sell":68050.0}}}
        """.trimIndent()

        val screen = DashboardState.parse(stale, unavailable)
        // A price from seven minutes ago beats an empty box on a kitchen wall.
        assertTrue("got: ${screen.gold.text}", screen.gold.text.contains("68,850"))
        assertTrue("it must be marked as old", screen.gold.stale)
    }

    @Test
    fun `and it says how old it is, which is what makes showing it honest`() {
        val stale = """
            {"gold":{"ok":false,"error":"OSError","age_seconds":420,
                     "stale":{"ornament_sell":68850.0}}}
        """.trimIndent()
        assertTrue(DashboardState.parse(stale, unavailable).gold.text.contains("7 นาทีก่อน"))
    }

    @Test
    fun `an hour old is said in hours`() {
        val old = """
            {"crypto":{"ok":false,"age_seconds":7200,
                       "stale":{"btc":{"usd":85986.58,"change_pct":0.0}}}}
        """.trimIndent()
        assertTrue(DashboardState.parse(old, unavailable).crypto.text.contains("2 ชม.ก่อน"))
    }

    @Test
    fun `a fresh value is not cluttered with an age`() {
        val screen = DashboardState.parse(everything, unavailable)
        assertFalse(screen.weather.text.contains("ก่อน"))
        assertFalse(screen.gold.text.contains("ก่อน"))
    }

    // ------------------------------------------------------- malformed input

    @Test
    fun `nonsense is a screen that says so rather than an exception`() {
        for (payload in listOf("", "not json", "[]", "{", "null", "{\"weather\":5}")) {
            val screen = DashboardState.parse(payload, unavailable)
            assertEquals("payload: $payload", unavailable, screen.weather.text)
            assertEquals(unavailable, screen.gold.text)
            assertEquals(unavailable, screen.crypto.text)
        }
    }

    @Test
    fun `a panel missing the numbers it promised is unavailable, not blank`() {
        val empty = """{"gold":{"ok":true,"age_seconds":0}}"""
        assertEquals(unavailable, DashboardState.parse(empty, unavailable).gold.text)
    }

    @Test
    fun `a partial weather panel shows what it has`() {
        val partial = """{"weather":{"ok":true,"age_seconds":0,"temp_c":31.0,"word":"แดดจัด"}}"""
        val text = DashboardState.parse(partial, unavailable).weather.text
        assertTrue(text.contains("31°C"))
        assertTrue(text.contains("แดดจัด"))
        // No humidity and no high/low in the payload, and none invented.
        assertFalse(text.contains("ความชื้น"))
        assertFalse(text.contains("สูง"))
    }

    // ---------------------------------------------------------- jarvis state

    @Test
    fun `the jarvis window says what it is actually doing`() {
        fun state(mic: String, stt: String, chat: String, tts: String) =
            DashboardState.jarvisState(mic, stt, chat, tts,
                                       "ready", "listening", "thinking", "speaking", "offline")

        assertEquals("ready", state("open", "idle", "idle", "idle"))
        assertEquals("listening", state("open", "recording", "idle", "idle"))
        assertEquals("thinking", state("open", "sending", "idle", "idle"))
        assertEquals("thinking", state("open", "ok", "asking", "idle"))
        assertEquals("speaking", state("open", "ok", "ok", "speaking"))
        assertEquals("speaking", state("open", "ok", "ok", "synthesising"))
        // A microphone the app cannot use is the one thing worth shouting about.
        assertEquals("offline", state("no-permission", "idle", "idle", "idle"))
        assertEquals("offline", state("error", "idle", "idle", "idle"))
    }

    @Test
    fun `speaking wins over thinking when both look true`() {
        // The pipeline leaves chat on "ok" while the answer is spoken, so the
        // later stage has to be checked first or the screen lies for the whole
        // of the answer.
        assertEquals(
            "speaking",
            DashboardState.jarvisState("open", "ok", "asking", "speaking",
                                       "ready", "listening", "thinking", "speaking", "offline"),
        )
    }

    // ------------------------------------------------------------- no secrets

    @Test
    fun `nothing in a rendered screen could be a token`() {
        val screen = DashboardState.parse(everything, unavailable)
        val rendered = listOf(screen.weather.text, screen.gold.text,
                              screen.crypto.text, screen.place).joinToString(" ")
        for (word in listOf("Bearer", "token", "key", "http")) {
            assertFalse("$word appeared in: $rendered", rendered.contains(word, ignoreCase = true))
        }
    }
}
