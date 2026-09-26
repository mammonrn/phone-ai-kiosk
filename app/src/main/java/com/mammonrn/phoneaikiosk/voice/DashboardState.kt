package com.mammonrn.phoneaikiosk.voice

import org.json.JSONObject

/**
 * Turning the broker's dashboard payload into the words on the screen.
 *
 * PURE, and with no Android in it, because the interesting behaviour is not
 * "does it draw" but "what does it say when the gold API is down and the
 * weather is fine and the crypto price is four minutes old". That is a question
 * about strings, and strings can be tested without a phone.
 *
 * THE RULE THE WHOLE FILE EXISTS FOR: one broken source must never blank the
 * screen. Every panel is read on its own, and a panel that cannot be read says
 * so in its own box while the rest carry on. A kitchen wall that goes dark
 * because a third-party gold scraper timed out is worse than no wall.
 *
 * STALE IS SHOWN, AND SAID. A gold price from six minutes ago is worth far more
 * to somebody glancing at this than an empty box — as long as the box says how
 * old it is. Presenting it silently as current would be the one genuinely bad
 * option, so the age is not optional anywhere in here.
 */
object DashboardState {

    /**
     * What one window shows.
     *
     * [text2] is the SECOND COLUMN, and only the crypto window has one. Four
     * coins stacked in a single column ran off the bottom of a panel sized for
     * two, so they are laid out two and two — and two columns cannot be one
     * string, because the pixel font is monospaced and the Thai font beside it
     * is not, so padding with spaces would not line anything up. Two TextViews
     * side by side is the only honest way to do it. Empty for every other
     * window and for an error, which is why it has a default.
     */
    class Panel(val text: String, val stale: Boolean, val text2: String = "")

    /** Everything the screen needs, already formatted. */
    class Screen(
        val weather: Panel,
        val gold: Panel,
        val crypto: Panel,
        val place: String,
        /** Day or night, for the icon. The WORD is chosen by the broker. */
        val isDay: Boolean = true,
        /**
         * What the gold percentage is measured against, in the words the title
         * bar shows — empty when there is no percentage to explain. A
         * percentage with no stated base is a number pretending to be
         * information, so the two travel together or neither is shown.
         */
        val goldBasis: String = "",
        /**
         * The purity the two gold prices are announced at, "96.5", or empty.
         *
         * The title bar says it as "ความบริสุทธิ์ 96.5%" — the word, not just
         * the number — because the body of the same window carries a second
         * percentage, the price's move, and a reader has to be able to tell
         * the two apart at a glance. Empty when the broker does not send it
         * (one deployed before it did) or when the two products differ, which
         * one line in a title bar could not say honestly.
         */
        val goldPurity: String = "",
        /**
         * Whether the broker used its own fallback position because the phone
         * sent none. THAT it happened, never where: the screen and the dump
         * both say "fallback", and neither ever says a coordinate.
         */
        val locationFallback: Boolean = false,
        /**
         * Sunrise and sunset on the screen's own clock, "6:05 AM", or empty.
         * From the same weather reading, for the same position. Empty when the
         * broker did not send them (an older broker, or Open-Meteo left them
         * out), and the weather window then simply has no sun line.
         */
        val sunrise: String = "",
        val sunset: String = "",
        /**
         * Fuel, under the gold in the commodities window: the three cheapest
         * brands per fuel. Null when the broker sent no oil at all (one older
         * than it), and the window then shows gold only.
         */
        val oil: Panel? = null,
        /**
         * Today's weather numbers as (label, value) cells, in the order the
         * card shows them: high/low, rain chance, wind, UV. A number the model
         * did not give is left out, never shown as a dash or a zero.
         */
        val weatherStats: List<Pair<String, String>> = emptyList(),
        /** The next three days in one sentence, or empty. */
        val outlook: String = "",
        /**
         * The country-wide warnings (weather/WeatherAlerts), which take turns
         * with [outlook] on the window's last line. NONE from a broker that
         * does not send them.
         */
        val alerts: com.mammonrn.phoneaikiosk.weather.WeatherAlerts.Block =
            com.mammonrn.phoneaikiosk.weather.WeatherAlerts.NONE,
    )

    /**
     * Reads the payload. Never throws: a malformed reply is a screen that says
     * so, not a crash on the one device nobody can reach a debugger on.
     */
    fun parse(json: String, unavailable: String): Screen = try {
        val root = JSONObject(json)
        val weather = root.optJSONObject("weather")
        val gold = root.optJSONObject("gold")
        Screen(
            weather = weatherPanel(weather, unavailable),
            gold = goldPanel(gold, unavailable),
            crypto = cryptoPanel(root.optJSONObject("crypto"), unavailable),
            place = root.optString("place", ""),
            isDay = isDay(weather),
            goldBasis = usable(gold)?.first?.optString("change_basis", "") ?: "",
            goldPurity = goldPurity(usable(gold)?.first),
            locationFallback = root.optBoolean("location_fallback", false),
            oil = if (root.has("oil")) oilPanel(root.optJSONObject("oil"), unavailable) else null,
            weatherStats = weatherStats(usable(weather)?.first, root.optJSONObject("air")),
            outlook = usable(weather)?.first?.optString("outlook", "")
                ?.takeUnless { it == "null" }.orEmpty(),
            sunrise = clock12(usable(weather)?.first?.optString("sunrise", "") ?: ""),
            sunset = clock12(usable(weather)?.first?.optString("sunset", "") ?: ""),
            alerts = com.mammonrn.phoneaikiosk.weather.WeatherAlerts.parse(root.optJSONObject("alerts")),
        )
    } catch (e: Exception) {
        val panel = Panel(unavailable, false)
        Screen(panel, panel, panel, "")
    }

    /**
     * For the card stack (ui/CardBoard): per window, the SIGNATURE whose change
     * counts as news, and the one-line SUMMARY a folded window shows.
     *
     * WHAT COUNTS AS NEWS is decided here and nowhere else (DESIGN.md, "Cards"):
     *   weather  the whole degree and the sky word — 30.2 to 30.4 is not news
     *   gold     either sell price — the shop announces a few times a day
     *   crypto   BTC's and ETH's day move in whole percent — every coin to the
     *            decimal would make this window news every minute
     * A window with nothing usable is absent: no signature, no news.
     */
    fun cardFacts(json: String): Map<String, Pair<String, String>> {
        val out = HashMap<String, Pair<String, String>>()
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return out
        usable(root.optJSONObject("weather"))?.first?.let { w ->
            val temp = w.optDouble("temp_c", Double.NaN)
            if (!temp.isNaN()) {
                val degrees = Math.round(temp)
                val word = w.optString("word", "")
                out["weather"] = "$degrees|$word" to "$degrees°C $word".trim()
            }
        }
        // The commodities window: gold, and the cheapest diesel and 95 — a
        // change in either is news for the one window they share.
        val oilData = usable(root.optJSONObject("oil"))?.first
        var fuelSignature = ""
        var fuelSummary = ""
        oilData?.optJSONArray("fuels")?.let { fuels ->
            for (i in 0 until fuels.length()) {
                val fuel = fuels.optJSONObject(i) ?: continue
                val low = fuel.optJSONArray("cheapest")?.optJSONObject(0)?.optDouble("price", Double.NaN)
                    ?: continue
                if (low.isNaN()) continue
                fuelSignature += "|${fuel.optString("id")}:$low"
                if (fuelSummary.isEmpty()) fuelSummary = "${fuel.optString("label")} " +
                    String.format(java.util.Locale.US, "%.2f", low)
            }
        }
        usable(root.optJSONObject("gold"))?.first.let { g ->
            val ornament = g?.optDouble("ornament_sell", Double.NaN) ?: Double.NaN
            val bar = g?.optDouble("bar_sell", Double.NaN) ?: Double.NaN
            val gold = when {
                !bar.isNaN() -> "ทองแท่ง ${baht(bar)}"
                !ornament.isNaN() -> "รูปพรรณ ${baht(ornament)}"
                else -> ""
            }
            if (gold.isNotEmpty() || fuelSummary.isNotEmpty()) {
                out["gold"] = "$ornament|$bar$fuelSignature" to
                    listOf(gold, fuelSummary).filter { it.isNotEmpty() }.joinToString(" · ")
            }
        }
        usable(root.optJSONObject("crypto"))?.first?.let { c ->
            val coins = coinList(c)
            if (coins.isNotEmpty()) {
                val signature = coins.filter { it.optString("symbol") in setOf("BTC", "ETH") }
                    .joinToString(",") { coin ->
                        val change = coin.optDouble("change_pct", Double.NaN)
                        "${coin.optString("symbol")}:${if (change.isNaN()) "-" else Math.round(change)}"
                    }
                val first = coins.first()
                out["crypto"] = signature to (first.optString("symbol") + move(first)).trim()
            }
        }
        return out
    }

    /**
     * "06:05" -> "6:05 AM", "18:13" -> "6:13 PM": the taskbar clock's format,
     * so every time on the screen reads the same way. Empty for anything that
     * is not a 24-hour HH:MM, including the "null" optString makes of a null.
     */
    fun clock12(hhmm: String): String {
        val match = Regex("""^(\d{2}):(\d{2})$""").matchEntire(hhmm) ?: return ""
        val hour = match.groupValues[1].toInt()
        val minute = match.groupValues[2]
        if (hour > 23 || minute.toInt() > 59) return ""
        val half = if (hour < 12) "AM" else "PM"
        val twelve = when (val h = hour % 12) { 0 -> 12; else -> h }
        return "$twelve:$minute $half"
    }

    /**
     * Day or night, read from whichever block of the weather panel is usable.
     *
     * Defaults to day only when there is no weather at all — with no panel
     * there is no word on screen either, so nothing can contradict it. When
     * there IS a reading, `is_day` comes with it: the broker puts it there
     * precisely so the icon and the word cannot disagree.
     */
    private fun isDay(panel: JSONObject?): Boolean {
        val data = usable(panel)?.first ?: return true
        return data.optInt("is_day", 1) != 0
    }

    /**
     * The numbers to render, whether they are fresh or the last good ones.
     *
     * Returns null when there is nothing at all to show. `ok` and a `stale`
     * block are two different states and both carry usable numbers, which is
     * why this is one function rather than a branch at every call site.
     */
    private fun usable(panel: JSONObject?): Pair<JSONObject, Boolean>? {
        if (panel == null) return null
        if (panel.optBoolean("ok", false)) return panel to false
        val stale = panel.optJSONObject("stale") ?: return null
        return stale to true
    }

    private fun age(panel: JSONObject?): String {
        val words = ageWords(panel)
        return if (words.isEmpty()) "" else "  ($words)"
    }

    /** "3 นาทีก่อน", "2 ชม.ก่อน", or empty when it is fresh. */
    private fun ageWords(panel: JSONObject?): String {
        val seconds = panel?.optInt("age_seconds", 0) ?: 0
        return when {
            seconds < 90 -> ""
            seconds < 3600 -> "${seconds / 60} นาทีก่อน"
            else -> "${seconds / 3600} ชม.ก่อน"
        }
    }

    /** Both products' purity when they agree, else empty. See [Screen.goldPurity]. */
    private fun goldPurity(data: JSONObject?): String {
        if (data == null) return ""
        val ornament = data.optDouble("ornament_purity_pct", Double.NaN)
        val bar = data.optDouble("bar_purity_pct", Double.NaN)
        val known = listOf(ornament, bar).filterNot { it.isNaN() }
        if (known.isEmpty() || known.any { it != known[0] }) return ""
        return percent(known[0])
    }

    private fun weatherPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel(unavailable, false)
        val temp = data.optDouble("temp_c", Double.NaN)
        if (temp.isNaN()) return Panel(unavailable, false)

        // The headline only: now, and the sky. Today's numbers are a table of
        // their own (weatherStats) and the next days a sentence (outlook), so
        // each can be laid out for what it is (2026-09-23, Poom: temperature,
        // rain, wind, UV — today only; humidity is not among them).
        val word = data.optString("word", "")
        return Panel("${trim(temp)}°C  $word" + age(panel), stale)
    }

    private fun goldPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel(unavailable, false)
        val ornament = data.optDouble("ornament_sell", Double.NaN)
        val bar = data.optDouble("bar_sell", Double.NaN)
        if (ornament.isNaN() && bar.isNaN()) return Panel(unavailable, false)

        val ornamentMove = goldMove(data, "ornament_sell_change_pct")
        val barMove = goldMove(data, "bar_sell_change_pct")
        val basis = data.optString("change_basis", "")

        return Panel(buildString {
            // Sell prices: the number people mean by "ราคาทอง".
            if (!ornament.isNaN()) {
                append("รูปพรรณ ${baht(ornament)}")
                append(ornamentMove)
            }
            if (!bar.isNaN()) {
                if (isNotEmpty()) append("\n")
                append("ทองแท่ง ${baht(bar)}")
                append(barMove)
            }
            // TWO PERCENTAGES LIVE IN THIS WINDOW and they must never be read
            // as one another. Purity is in the title, spelt out as a word. The
            // move is always signed, and whenever one is on screen this
            // footnote says what it is measured against, in the broker's own
            // words — so "+0.37%" can only be a move and "96.5%" only purity.
            // It ends in ")" on purpose: RetroType dims a trailing
            // parenthesis as a footnote, which is what this is.
            val movement = ornamentMove.isNotEmpty() || barMove.isNotEmpty()
            if (movement && basis.isNotEmpty()) {
                val note = listOf("+/− $basis", ageWords(panel)).filter { it.isNotEmpty() }
                append("\n(${note.joinToString(" · ")})")
            } else {
                append(age(panel))
            }
        }, stale)
    }

    /**
     * The fuel lines: "ดีเซล 40.69 ปตท. บางจาก คาลเท็กซ์", one per fuel, and a
     * footnote saying these are Bangkok's prices and when they were announced.
     * Brands at the same price share it; a dearer one gets its own after "·".
     */
    private fun oilPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel("น้ำมัน: $unavailable", false)
        val fuels = data.optJSONArray("fuels") ?: return Panel("น้ำมัน: $unavailable", false)
        val lines = ArrayList<String>()
        for (i in 0 until fuels.length()) {
            val fuel = fuels.optJSONObject(i) ?: continue
            val line = fuelLine(fuel.optString("label"), fuel.optJSONArray("cheapest"))
            if (line.isNotEmpty()) lines += line
        }
        if (lines.isEmpty()) return Panel("น้ำมัน: $unavailable", false)
        val note = listOf("ราคา" + data.optString("area", "กรุงเทพฯ"), data.optString("date", ""),
                          ageWords(panel)).filter { it.isNotEmpty() }.joinToString(" · ")
        return Panel(lines.joinToString("\n") + "\n($note)", stale)
    }

    /** "โซฮอล์ 95 39.90 PT · 39.94 ปตท. บางจาก" from the cheapest list, in price order. */
    fun fuelLine(label: String, cheapest: org.json.JSONArray?): String {
        if (label.isEmpty() || cheapest == null || cheapest.length() == 0) return ""
        val groups = LinkedHashMap<String, MutableList<String>>()
        for (i in 0 until cheapest.length()) {
            val offer = cheapest.optJSONObject(i) ?: continue
            val price = offer.optDouble("price", Double.NaN)
            val brand = offer.optString("brand")
            if (price.isNaN() || brand.isEmpty()) continue
            groups.getOrPut(String.format(java.util.Locale.US, "%.2f", price)) { ArrayList() } += brand
        }
        if (groups.isEmpty()) return ""
        return label + " " + groups.entries.joinToString(" · ") { (price, brands) ->
            "$price ${brands.joinToString(" ")}"
        }
    }

    /**
     * The move on a gold price, or nothing at all.
     *
     * NOTHING AT ALL is the important half. The source publishes no previous
     * price — /latest is its only endpoint and it carries four prices and a
     * timestamp — so the broker measures against the last announcement it
     * watched go by, and until it has seen one change there is nothing to
     * measure. "0.00%" would be a claim with no evidence behind it, so the
     * absence is passed through rather than filled in.
     */
    private fun goldMove(data: JSONObject, key: String): String {
        if (!data.has(key)) return ""
        val change = data.optDouble(key, Double.NaN)
        if (change.isNaN()) return ""
        val sign = if (change >= 0) "+" else ""
        return "  $sign${percent(change)}%"
    }

    /**
     * Four coins, split into two columns of two.
     *
     * The order is the broker's, which is market capitalisation with the
     * dollar-pegged taken out, so the left column holds the two biggest.
     *
     * TWO LINES PER COIN, because "BTC  $86,509  +0.67%" is about 230dp set in
     * the pixel face and half of this window is 170dp. Splitting the symbol and
     * its move off from the price is what makes four coins fit without
     * shrinking the type — which is the thing Poom asked not to touch.
     */
    private fun cryptoPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel(unavailable, false)
        val coins = coinList(data)
        if (coins.isEmpty()) return Panel(unavailable, false)

        val left = StringBuilder()
        val right = StringBuilder()
        // Odd counts keep the extra coin on the left, so a three-coin day reads
        // top-left, bottom-left, top-right rather than leaving a hole.
        val inLeft = (coins.size + 1) / 2
        for ((index, coin) in coins.withIndex()) {
            val column = if (index < inLeft) left else right
            if (column.isNotEmpty()) column.append("\n\n")
            column.append(coin.optString("symbol", "?"))
            column.append(move(coin))
            column.append("\n")
            column.append(coinPrice(coin.optDouble("usd", Double.NaN)))
        }
        // The freshness note goes under the left column, where it reads as the
        // footnote it is. It has to be LAST in the string it lands in: the
        // renderer finds it with a match anchored to the end. See ui/RetroType.
        left.append(age(panel))
        return Panel(left.toString(), stale, right.toString())
    }

    /**
     * The coins to show, newest payload shape first.
     *
     * The `btc`/`eth` branch is for the minutes between installing this APK and
     * deploying the broker that goes with it — the phone updates over adb in
     * seconds and the VPS is a separate step, by hand, afterwards. Without it
     * the crypto window reads "ข้อมูลไม่พร้อม" in between, which looks exactly
     * like a broken kiosk rather than a half-finished deploy.
     */
    private fun coinList(data: JSONObject): List<JSONObject> {
        val array = data.optJSONArray("coins")
        if (array != null) {
            val out = ArrayList<JSONObject>(array.length())
            for (index in 0 until array.length()) {
                val coin = array.optJSONObject(index) ?: continue
                if (coin.optString("symbol").isNotEmpty()) out.add(coin)
            }
            return out
        }
        val out = ArrayList<JSONObject>(2)
        for (key in listOf("btc", "eth")) {
            data.optJSONObject(key)?.let { out.add(it.put("symbol", key.uppercase())) }
        }
        return out
    }

    /**
     * The day's move, with a sign, because the direction is most of the point.
     *
     * ONE space, not two. Poom found the crypto window too loose, and the
     * double space between "BTC" and its move was part of it: on a two-line
     * coin the symbol and its move are one thing and should read as one.
     */
    private fun move(coin: JSONObject): String {
        val change = coin.optDouble("change_pct", Double.NaN)
        if (change.isNaN()) return ""
        val sign = if (change >= 0) "+" else ""
        return " $sign${percent(change)}%"
    }

    /** 68850.0 -> "68,850" — no decimals, because nobody reads satang at 2 m.
     *  ROUNDED, not truncated: 999.7 baht is 1,000, and a price that reads low
     *  every time is a worse lie than one that is occasionally a baht high. */
    // ------------------------------------------------------ today's weather

    fun weatherStats(data: JSONObject?, air: JSONObject? = null): List<Pair<String, String>> {
        val out = ArrayList<Pair<String, String>>()
        val dust = pm25(air)
        if (data == null) return if (dust == null) out else listOf(dust)
        val high = data.optDouble("high_c", Double.NaN)
        val low = data.optDouble("low_c", Double.NaN)
        if (!high.isNaN() && !low.isNaN()) out += "สูง/ต่ำ" to "${Math.round(high)}°/${Math.round(low)}°"
        if (data.has("rain_chance") && !data.isNull("rain_chance")) {
            out += "โอกาสฝน" to "${data.optInt("rain_chance")}%"
        }
        if (data.has("wind_kmh") && !data.isNull("wind_kmh")) {
            out += "ลม กม./ชม." to "${data.optInt("wind_kmh")}"
        }
        val uv = data.optDouble("uv", Double.NaN)
        if (!uv.isNaN()) out += "UV" to "${Math.round(uv)} ${uvWord(uv)}"
        if (dust != null) out += dust
        return out
    }

    /** Older than this, PM2.5 is not shown as now: it is an hourly reading. */
    const val MAX_PM25_AGE_SECONDS = 3 * 3600

    /**
     * PM2.5 from the broker's `air` panel (Open-Meteo air quality, CAMS):
     * "PM2.5 มคก./ลบ.ม." to "8.8 ดีมาก" — the value and the PCD level word,
     * never a colour alone. Null (so no cell at all) when the panel is absent
     * (an older broker), failed with nothing kept, or too old to be "now".
     */
    fun pm25(air: JSONObject?): Pair<String, String>? {
        val (data, _) = usable(air) ?: return null
        if ((air?.optInt("age_seconds", 0) ?: 0) > MAX_PM25_AGE_SECONDS) return null
        val value = data.optDouble("pm25", Double.NaN)
        if (value.isNaN() || value < 0) return null
        val word = data.optString("pm25_word", "").takeUnless { it == "null" }.orEmpty()
        val number = if (value == Math.floor(value)) value.toLong().toString()
                     else String.format(java.util.Locale.US, "%.1f", value)
        return "PM2.5 มคก./ลบ.ม." to "$number $word".trim()
    }

    /** The WHO UV index bands, in Thai — the same words Jarvis is given. */
    fun uvWord(uv: Double): String = when {
        uv < 3 -> "ต่ำ"
        uv < 6 -> "ปานกลาง"
        uv < 8 -> "สูง"
        uv < 11 -> "สูงมาก"
        else -> "อันตราย"
    }

    /**
     * The prices behind each price window's news (ui/MoveTracker): gold's two
     * sell prices with the cheapest of each fuel for the commodities window,
     * each coin's dollar price for crypto. Only usable numbers.
     */
    fun cardPrices(json: String): Map<String, Map<String, Double>> {
        val root = runCatching { JSONObject(json) }.getOrNull() ?: return emptyMap()
        val out = HashMap<String, Map<String, Double>>()
        val commodities = HashMap<String, Double>()
        usable(root.optJSONObject("gold"))?.first?.let { g ->
            for (key in listOf("ornament_sell", "bar_sell")) {
                val v = g.optDouble(key, Double.NaN)
                if (!v.isNaN()) commodities[key] = v
            }
        }
        usable(root.optJSONObject("oil"))?.first?.optJSONArray("fuels")?.let { fuels ->
            for (i in 0 until fuels.length()) {
                val fuel = fuels.optJSONObject(i) ?: continue
                val low = fuel.optJSONArray("cheapest")?.optJSONObject(0)?.optDouble("price", Double.NaN)
                if (low != null && !low.isNaN()) commodities["oil:" + fuel.optString("id")] = low
            }
        }
        if (commodities.isNotEmpty()) out["gold"] = commodities
        usable(root.optJSONObject("crypto"))?.first?.let { c ->
            val coins = HashMap<String, Double>()
            for (coin in coinList(c)) {
                val usd = coin.optDouble("usd", Double.NaN)
                if (!usd.isNaN()) coins[coin.optString("symbol")] = usd
            }
            if (coins.isNotEmpty()) out["crypto"] = coins
        }
        return out
    }

    // ------------------------------------------------ the commodities table

    /** One table row: what it is, its price, and what follows the price. */
    class Row(val label: String, val price: String, val extra: String)

    /**
     * The commodities window as a TABLE, not a paragraph (2026-09-23).
     *
     * On the A07 the fuel lines were one string each, so each price started
     * wherever its label ended — "ดีเซล 40.69" and "โซฮอล์ 95 39.94" did not
     * line up, and "95" ran into "39.94" in the pixel face. Rows of cells fix
     * both: MainActivity lays them out in columns with the prices
     * right-aligned. Each section carries a one-line HEADER instead of a
     * footnote: the unit, what the move is measured against, that fuel is
     * Bangkok's price and the date — said once, where it explains the rows.
     *
     * [oil] is null when the broker sent no fuel at all (an older broker).
     */
    class Commodities(val goldHeader: String, val gold: List<Row>,
                      val oilHeader: String?, val oil: List<Row>?)

    fun commodities(json: String, unavailable: String): Commodities {
        val root = runCatching { JSONObject(json) }.getOrNull()
            ?: return Commodities("ทองคำ: $unavailable", emptyList(), null, null)

        val goldPanel = root.optJSONObject("gold")
        val goldData = usable(goldPanel)?.first
        val gold = ArrayList<Row>()
        var goldHeader = "ทองคำ: $unavailable"
        if (goldData != null) {
            for ((label, key) in listOf("รูปพรรณ" to "ornament_sell", "ทองแท่ง" to "bar_sell")) {
                val price = goldData.optDouble(key, Double.NaN)
                if (!price.isNaN()) gold += Row(label, group(Math.round(price)),
                                                goldMove(goldData, "${key}_change_pct").trim())
            }
            if (gold.isNotEmpty()) {
                val basis = goldData.optString("change_basis", "")
                goldHeader = listOf("ทองคำ บาทละ",
                                    if (basis.isNotEmpty() && gold.any { it.extra.isNotEmpty() }) "+/− $basis" else "",
                                    ageWords(goldPanel)).filter { it.isNotEmpty() }.joinToString(" · ")
            }
        }

        if (!root.has("oil")) return Commodities(goldHeader, gold, null, null)
        val oilPanel = root.optJSONObject("oil")
        val oilData = usable(oilPanel)?.first
        val fuels = oilData?.optJSONArray("fuels")
        val oil = ArrayList<Row>()
        if (fuels != null) {
            for (i in 0 until fuels.length()) {
                val fuel = fuels.optJSONObject(i) ?: continue
                oilRow(fuel.optString("label"), fuel.optJSONArray("cheapest"))?.let { oil += it }
            }
        }
        if (oil.isEmpty()) return Commodities(goldHeader, gold, "น้ำมัน: $unavailable", emptyList())
        val area = oilData?.optString("area", "กรุงเทพฯ") ?: "กรุงเทพฯ"
        val oilHeader = listOf("น้ำมันถูกสุด บาท/ลิตร",
                               "ราคา$area ${com.mammonrn.phoneaikiosk.ui.ScreenDate.fromThai(oilData?.optString("date", "") ?: "")}".trim(),
                               // The announcement date already says when these
                               // prices are from; the fetch's age is added only
                               // when the source is down (stale), where it
                               // matters — with both, the header ran off the
                               // line on the A07 (0.35.0).
                               if (usable(oilPanel)?.second == true) ageWords(oilPanel) else "")
            .filter { it.isNotEmpty() }.joinToString(" · ")
        return Commodities(goldHeader, gold, oilHeader, oil)
    }

    /**
     * "ดีเซล | 40.69 | ปตท. บางจาก เชลล์": the cheapest price in the price
     * column, the brands selling at it after it, and a dearer brand of the top
     * three as "PT +0.04" — so the column is always the lowest price and what
     * follows says who, and by how much the rest are dearer.
     */
    fun oilRow(label: String, cheapest: org.json.JSONArray?): Row? {
        if (label.isEmpty() || cheapest == null) return null
        val offers = (0 until cheapest.length()).mapNotNull { i ->
            val o = cheapest.optJSONObject(i) ?: return@mapNotNull null
            val price = o.optDouble("price", Double.NaN)
            val brand = o.optString("brand")
            if (price.isNaN() || brand.isEmpty()) null else brand to price
        }
        if (offers.isEmpty()) return null
        val low = offers.minOf { it.second }
        val same = offers.filter { it.second == low }.map { it.first }
        val dearer = offers.filter { it.second != low }.map { (brand, price) ->
            "$brand +" + String.format(java.util.Locale.US, "%.2f", price - low)
        }
        return Row(label, String.format(java.util.Locale.US, "%.2f", low),
                   (same + dearer).joinToString(" "))
    }

    private val THAI_MONTHS = listOf(
        "มกราคม" to "ม.ค.", "กุมภาพันธ์" to "ก.พ.", "มีนาคม" to "มี.ค.", "เมษายน" to "เม.ย.",
        "พฤษภาคม" to "พ.ค.", "มิถุนายน" to "มิ.ย.", "กรกฎาคม" to "ก.ค.", "สิงหาคม" to "ส.ค.",
        "กันยายน" to "ก.ย.", "ตุลาคม" to "ต.ค.", "พฤศจิกายน" to "พ.ย.", "ธันวาคม" to "ธ.ค.",
    )

    /** "23 กันยายน 2569" -> "23 ก.ย.", the taskbar's own form; anything else as it came. */
    fun shortThaiDate(date: String): String {
        val parts = date.trim().split(Regex("\\s+"))
        if (parts.size >= 2) THAI_MONTHS.firstOrNull { it.first == parts[1] }?.let {
            return "${parts[0]} ${it.second}"
        }
        return date.trim()
    }

    fun baht(value: Double): String = group(Math.round(value)) + " บ."

    /** 85986.58 -> "$85,987" for the same reason. */
    fun dollars(value: Double): String = "$" + group(Math.round(value))

    /**
     * A coin price at the precision that coin is actually quoted at.
     *
     * [dollars] rounds to whole dollars, which was right while the screen showed
     * Bitcoin and Ether and is wrong the moment it does not: of the top four on
     * the day this was written, one is $1.57. Whole dollars would print that as
     * "$2", which is not a rounding choice but a wrong number, and a coin under
     * a dollar as "$0". So: no decimals above a thousand, two below it, four
     * below a dollar. Nobody reads cents on Bitcoin at arm's length and
     * everybody needs them on XRP.
     */
    fun coinPrice(value: Double): String {
        if (value.isNaN()) return "—"
        val magnitude = Math.abs(value)
        return "$" + when {
            magnitude >= 1000 -> group(Math.round(value))
            magnitude >= 1 -> String.format(java.util.Locale.US, "%.2f", value)
            else -> String.format(java.util.Locale.US, "%.4f", value)
        }
    }

    private fun group(value: Long): String {
        val digits = value.toString()
        val sign = if (digits.startsWith("-")) "-" else ""
        val body = digits.removePrefix("-")
        val out = StringBuilder()
        for ((index, character) in body.withIndex()) {
            if (index > 0 && (body.length - index) % 3 == 0) out.append(',')
            out.append(character)
        }
        return sign + out
    }

    /**
     * A percentage as precisely as the source gave it and no further: 1.53 ->
     * "1.53", 1.50 -> "1.5", 2.0 -> "2".
     *
     * Not [trim], which rounds to one decimal — right for a temperature, wrong
     * here, where it would turn a 1.53% move into "1.5%" and quietly disagree
     * with every other screen showing the same number.
     */
    fun percent(value: Double): String {
        val text = String.format(java.util.Locale.US, "%.2f", value)
            .trimEnd('0').trimEnd('.')
        // "%.2f" of a tiny negative is "-0.00", and "-0%" is not a thing.
        return if (text == "-0") "0" else text
    }

    /** 28.0 -> "28", 28.2 -> "28.2". A trailing ".0" is noise on a wall. */
    fun trim(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) value.toLong().toString()
        else String.format(java.util.Locale.US, "%.1f", value)

    /**
     * What the title bar of the Jarvis window says.
     *
     * Driven by the states the voice pipeline already reports, so there is one
     * source of truth for "what is it doing" rather than two that drift.
     */
    fun jarvisState(
        mic: String,
        stt: String,
        chat: String,
        tts: String,
        ready: String,
        listening: String,
        thinking: String,
        speaking: String,
        offline: String,
        // Jarvis resting while media plays (WakePause): shown where "ready"
        // would be. A question asked with the button still reads listening /
        // thinking / speaking, because that is what Jarvis is doing then.
        resting: String? = null,
    ): String = when {
        mic == "no-permission" || mic == "error" -> offline
        tts == "speaking" || tts == "synthesising" || tts == "device-fallback" -> speaking
        chat == "asking" || stt == "sending" -> thinking
        stt == "recording" -> listening
        resting != null -> resting
        else -> ready
    }
}
