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
                    "temp_c":28.2,"humidity":83,"code":1,"is_day":1,"word":"แดดรำไร",
                    "high_c":30.7,"low_c":22.9},
         "gold":{"ok":true,"age_seconds":0,"credit":"x",
                 "ornament_sell":68850.0,"ornament_buy":66491.76,
                 "bar_sell":68050.0,"bar_buy":67850.0,"updated":"22/09/2569"},
         "crypto":{"ok":true,"age_seconds":0,"credit":"Binance","quote":"USDT",
                   "coins":[{"symbol":"BTC","usd":85986.58,"change_pct":1.53},
                            {"symbol":"ETH","usd":2741.28,"change_pct":-0.63},
                            {"symbol":"BNB","usd":788.23,"change_pct":-0.98},
                            {"symbol":"XRP","usd":1.5732,"change_pct":5.25}]},
         "place":"เชียงราย","location_fallback":false}
    """.trimIndent()

    // ------------------------------------------------------------ a good day

    @Test
    fun `every panel is filled in`() {
        val screen = DashboardState.parse(everything, unavailable)

        assertTrue(screen.weather.text.contains("28.2°C"))
        assertTrue(screen.weather.text.contains("แดดรำไร"))
        assertTrue(screen.gold.text.contains("68,850"))
        assertTrue(screen.crypto.text.contains("$85,987"))
        assertEquals("เชียงราย", screen.place)
        assertFalse(screen.weather.stale)
        assertFalse(screen.locationFallback)
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
                              screen.crypto.text, screen.crypto.text2,
                              screen.place).joinToString(" ")
        for (word in listOf("Bearer", "token", "key", "http")) {
            assertFalse("$word appeared in: $rendered", rendered.contains(word, ignoreCase = true))
        }
    }

    @Test
    fun `and nothing in it could be a coordinate`() {
        // The phone sends its position and the broker answers with a province
        // name. Nothing comes back that looks like the numbers that went out,
        // because there is nothing the screen could do with them.
        val screen = DashboardState.parse(everything, unavailable)
        val rendered = listOf(screen.weather.text, screen.gold.text,
                              screen.crypto.text, screen.crypto.text2,
                              screen.place).joinToString(" ")
        for (fragment in listOf("20.05", "99.89", "lat", "lon")) {
            assertFalse("$fragment appeared in: $rendered", rendered.contains(fragment))
        }
    }

    // ------------------------------------------------------ four coins, two columns

    @Test
    fun `four coins are dealt two to a column, biggest first`() {
        val screen = DashboardState.parse(everything, unavailable)

        assertTrue("left: ${screen.crypto.text}", screen.crypto.text.startsWith("BTC"))
        assertTrue("left: ${screen.crypto.text}", screen.crypto.text.contains("ETH"))
        assertTrue("right: ${screen.crypto.text2}", screen.crypto.text2.startsWith("BNB"))
        assertTrue("right: ${screen.crypto.text2}", screen.crypto.text2.contains("XRP"))
        // And neither column has strayed into the other's half.
        assertFalse(screen.crypto.text.contains("BNB"))
        assertFalse(screen.crypto.text2.contains("BTC"))
    }

    @Test
    fun `a coin is a symbol with its move, then its price underneath`() {
        val screen = DashboardState.parse(everything, unavailable)
        // One line is 230dp set in the pixel face and half the window is 170dp,
        // which is the whole reason for the break.
        assertTrue("got: ${screen.crypto.text}",
                   screen.crypto.text.startsWith("BTC +1.53%\n$85,987"))
    }

    @Test
    fun `a price is shown at the precision that coin is quoted at`() {
        // Whole dollars are right for Bitcoin and wrong for a $1.57 coin: it
        // would read "$2", which is not a rounding choice but a wrong number.
        assertEquals("$85,987", DashboardState.coinPrice(85986.58))
        assertEquals("$1.57", DashboardState.coinPrice(1.5732))
        assertEquals("$788.23", DashboardState.coinPrice(788.23))
        assertEquals("$0.0998", DashboardState.coinPrice(0.099796))
        assertEquals("—", DashboardState.coinPrice(Double.NaN))
    }

    @Test
    fun `three coins keep the odd one on the left rather than leaving a hole`() {
        val three = """
            {"crypto":{"ok":true,"age_seconds":0,"coins":[
                {"symbol":"BTC","usd":85986.58,"change_pct":1.0},
                {"symbol":"ETH","usd":2741.28,"change_pct":1.0},
                {"symbol":"BNB","usd":788.23,"change_pct":1.0}]}}
        """.trimIndent()
        val screen = DashboardState.parse(three, unavailable)
        assertTrue(screen.crypto.text.contains("BTC"))
        assertTrue(screen.crypto.text.contains("ETH"))
        assertEquals(true, screen.crypto.text2.startsWith("BNB"))
    }

    @Test
    fun `the old two-coin payload still draws while the broker is being deployed`() {
        // The APK lands over adb in seconds; the VPS is a separate step by hand
        // afterwards. In between, this is what the phone is served.
        val old = """
            {"crypto":{"ok":true,"age_seconds":0,
                       "btc":{"usd":85986.58,"change_pct":1.53},
                       "eth":{"usd":2741.28,"change_pct":-0.63}}}
        """.trimIndent()
        val screen = DashboardState.parse(old, unavailable)
        assertTrue("got: ${screen.crypto.text}", screen.crypto.text.contains("BTC"))
        assertTrue("got: ${screen.crypto.text2}", screen.crypto.text2.contains("ETH"))
    }

    @Test
    fun `a coins array with nothing usable in it is unavailable, not blank`() {
        val empty = """{"crypto":{"ok":true,"age_seconds":0,"coins":[]}}"""
        assertEquals(unavailable, DashboardState.parse(empty, unavailable).crypto.text)
        val junk = """{"crypto":{"ok":true,"age_seconds":0,"coins":[{"usd":1.0}]}}"""
        assertEquals(unavailable, DashboardState.parse(junk, unavailable).crypto.text)
    }

    @Test
    fun `the freshness note lands at the end of the left column`() {
        // RetroType finds it with a match anchored to the end of the string, so
        // anywhere else and it is neither dimmed nor shrunk.
        val stale = """
            {"crypto":{"ok":false,"age_seconds":420,"stale":{"coins":[
                {"symbol":"BTC","usd":85986.58,"change_pct":1.0},
                {"symbol":"ETH","usd":2741.28,"change_pct":1.0}]}}}
        """.trimIndent()
        val screen = DashboardState.parse(stale, unavailable)
        assertTrue("got: ${screen.crypto.text}", screen.crypto.text.endsWith("(7 นาทีก่อน)"))
        assertFalse(screen.crypto.text2.contains("ก่อน"))
    }

    // ------------------------------------------------------------- after dark

    @Test
    fun `the night is reported as night so the icon can follow`() {
        // THE BUG. Code 0 is "clear sky", which is a statement about cloud; the
        // screen read it as one about the sun and said "แดดจัด" at 00:15.
        val night = """
            {"weather":{"ok":true,"age_seconds":0,"temp_c":23.5,"humidity":92,
                        "code":0,"is_day":0,"word":"ฟ้าโปร่ง"}}
        """.trimIndent()
        val screen = DashboardState.parse(night, unavailable)
        assertFalse(screen.isDay)
        assertTrue(screen.weather.text.contains("ฟ้าโปร่ง"))
        assertFalse("nothing says แดด after dark", screen.weather.text.contains("แดด"))
    }

    @Test
    fun `daytime is daytime`() {
        assertTrue(DashboardState.parse(everything, unavailable).isDay)
    }

    @Test
    fun `a stale night reading is still a night reading`() {
        // is_day has to be read from whichever block is usable, or the icon
        // flips back to the sun the moment the weather source goes down.
        val stale = """
            {"weather":{"ok":false,"error":"OSError","age_seconds":900,
                        "stale":{"temp_c":23.5,"is_day":0,"word":"ฟ้าโปร่ง"}}}
        """.trimIndent()
        assertFalse(DashboardState.parse(stale, unavailable).isDay)
    }

    @Test
    fun `no weather at all is not a claim about the sky`() {
        // Nothing on screen to contradict, so the default costs nothing.
        assertTrue(DashboardState.parse("""{"weather":{"ok":false}}""", unavailable).isDay)
    }

    // ---------------------------------------------------------- the gold move

    @Test
    fun `the gold move is shown with its sign beside the price it belongs to`() {
        val moved = """
            {"gold":{"ok":true,"age_seconds":0,"ornament_sell":68800.0,
                     "bar_sell":68000.0,"ornament_sell_change_pct":0.15,
                     "bar_sell_change_pct":-0.22,"change_basis":"เทียบครั้งก่อน"}}
        """.trimIndent()
        val screen = DashboardState.parse(moved, unavailable)
        assertTrue("got: ${screen.gold.text}", screen.gold.text.contains("68,800 บ.  +0.15%"))
        assertTrue("got: ${screen.gold.text}", screen.gold.text.contains("68,000 บ.  -0.22%"))
        // And the screen is told what the number is measured against, because a
        // percentage with no stated base is a number pretending to be
        // information.
        assertEquals("เทียบครั้งก่อน", screen.goldBasis)
    }

    @Test
    fun `no move means no percentage and no basis, not a zero`() {
        // The source publishes no previous price, so a freshly installed broker
        // has nothing to compare against. "0.00%" would be a claim with no
        // evidence behind it.
        val screen = DashboardState.parse(everything, unavailable)
        assertFalse("got: ${screen.gold.text}", screen.gold.text.contains("%"))
        assertEquals("", screen.goldBasis)
    }

    @Test
    fun `one price moving does not put a percentage on the other`() {
        val one = """
            {"gold":{"ok":true,"age_seconds":0,"ornament_sell":68800.0,
                     "bar_sell":68000.0,"ornament_sell_change_pct":0.15,
                     "change_basis":"เทียบครั้งก่อน"}}
        """.trimIndent()
        val text = DashboardState.parse(one, unavailable).gold.text
        assertTrue(text.contains("รูปพรรณ 68,800 บ.  +0.15%"))
        assertTrue(text.contains("ทองแท่ง 68,000 บ."))
        assertEquals(1, text.count { it == '%' })
    }

    // ------------------------------------------------------- the gold purity

    private val withPurity = """
        {"gold":{"ok":true,"age_seconds":200,"ornament_sell":68800.0,
                 "bar_sell":68000.0,"ornament_sell_change_pct":0.15,
                 "bar_sell_change_pct":-0.22,"change_basis":"เทียบครั้งก่อน",
                 "ornament_purity_pct":96.5,"bar_purity_pct":96.5}}
    """.trimIndent()

    @Test
    fun `purity reaches the title and never the body`() {
        val screen = DashboardState.parse(withPurity, unavailable)
        assertEquals("96.5", screen.goldPurity)
        // The body's percentages are moves, and only moves.
        assertFalse("got: ${screen.gold.text}", screen.gold.text.contains("96.5"))
    }

    @Test
    fun `a move on screen always comes with what it is measured against`() {
        val text = DashboardState.parse(withPurity, unavailable).gold.text
        val footnote = text.substringAfterLast("\n")
        assertEquals("(+/− เทียบครั้งก่อน · 3 นาทีก่อน)", footnote)
    }

    @Test
    fun `no move means no footnote, and the age stays where it was`() {
        val still = """
            {"gold":{"ok":true,"age_seconds":200,"ornament_sell":68800.0,
                     "bar_sell":68000.0,"ornament_purity_pct":96.5,"bar_purity_pct":96.5}}
        """.trimIndent()
        val text = DashboardState.parse(still, unavailable).gold.text
        assertFalse("got: $text", text.contains("+/−"))
        assertTrue("got: $text", text.endsWith("ทองแท่ง 68,000 บ.  (3 นาทีก่อน)"))
    }

    @Test
    fun `an older broker with no purity leaves the title as it was`() {
        assertEquals("", DashboardState.parse(everything, unavailable).goldPurity)
    }

    @Test
    fun `two different purities are not squeezed into one title`() {
        val mixed = """{"gold":{"ok":true,"ornament_sell":1.0,"bar_sell":2.0,
                        "ornament_purity_pct":96.5,"bar_purity_pct":99.99}}"""
        assertEquals("", DashboardState.parse(mixed, unavailable).goldPurity)
    }

    // -------------------------------------------------------- the place name

    @Test
    fun `an unnamed position is empty here so the screen can word it`() {
        // The broker sends "" when the lookup failed or there was no province
        // to read. MainActivity turns that into "ตำแหน่งปัจจุบัน" — never a
        // coordinate, which is the one thing that must not reach a screen
        // facing a room.
        val nameless = """{"weather":{"ok":true,"temp_c":28.0},"place":""}"""
        assertEquals("", DashboardState.parse(nameless, unavailable).place)
    }

    @Test
    fun `the screen is told when the broker had to fall back`() {
        val fallback = """{"weather":{"ok":true,"temp_c":28.0},"location_fallback":true}"""
        assertTrue(DashboardState.parse(fallback, unavailable).locationFallback)
    }

    // -------------------------------------------------------- card_lines (0.69)

    @Test
    fun `card_lines absent leaves the field null so the old rotation still runs`() {
        assertEquals(null, DashboardState.parse(everything, unavailable).cardLines)
    }

    @Test
    fun `card_lines present is read exactly, worst-first, marks and all`() {
        val json = """{"weather":{"ok":true,"temp_c":28.0},
                       "card_lines":["⚠ ฝนตกหนักมาก 20 จังหวัด ถึง 18:00 น.",
                                     "◇ เชียงรายเสี่ยงน้ำท่วม 27–28 ก.ย."]}"""
        assertEquals(
            listOf("⚠ ฝนตกหนักมาก 20 จังหวัด ถึง 18:00 น.", "◇ เชียงรายเสี่ยงน้ำท่วม 27–28 ก.ย."),
            DashboardState.parse(json, unavailable).cardLines,
        )
    }

    @Test
    fun `card_lines present but empty is an empty list, not null`() {
        val json = """{"weather":{"ok":true,"temp_c":28.0},"card_lines":[]}"""
        assertEquals(emptyList<String>(), DashboardState.parse(json, unavailable).cardLines)
    }

    @Test
    fun `a blank entry in card_lines is dropped rather than shown empty`() {
        val json = """{"weather":{"ok":true,"temp_c":28.0},
                       "card_lines":["▸ บ่ายนี้ฝน 60%", ""]}"""
        assertEquals(listOf("▸ บ่ายนี้ฝน 60%"), DashboardState.parse(json, unavailable).cardLines)
    }
}
