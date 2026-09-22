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
         * Whether the broker used its own fallback position because the phone
         * sent none. THAT it happened, never where: the screen and the dump
         * both say "fallback", and neither ever says a coordinate.
         */
        val locationFallback: Boolean = false,
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
            locationFallback = root.optBoolean("location_fallback", false),
        )
    } catch (e: Exception) {
        val panel = Panel(unavailable, false)
        Screen(panel, panel, panel, "")
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
        val seconds = panel?.optInt("age_seconds", 0) ?: 0
        return when {
            seconds < 90 -> ""
            seconds < 3600 -> "  (${seconds / 60} นาทีก่อน)"
            else -> "  (${seconds / 3600} ชม.ก่อน)"
        }
    }

    private fun weatherPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel(unavailable, false)
        val temp = data.optDouble("temp_c", Double.NaN)
        if (temp.isNaN()) return Panel(unavailable, false)

        val word = data.optString("word", "")
        val humidity = data.optInt("humidity", -1)
        val high = data.optDouble("high_c", Double.NaN)
        val low = data.optDouble("low_c", Double.NaN)

        return Panel(buildString {
            append("${trim(temp)}°C  $word")
            if (humidity >= 0) append("\nความชื้น $humidity%")
            if (!high.isNaN() && !low.isNaN()) append("   สูง ${trim(high)}°  ต่ำ ${trim(low)}°")
            append(age(panel))
        }, stale)
    }

    private fun goldPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel(unavailable, false)
        val ornament = data.optDouble("ornament_sell", Double.NaN)
        val bar = data.optDouble("bar_sell", Double.NaN)
        if (ornament.isNaN() && bar.isNaN()) return Panel(unavailable, false)

        return Panel(buildString {
            // Sell prices: the number people mean by "ราคาทอง".
            if (!ornament.isNaN()) {
                append("รูปพรรณ ${baht(ornament)}")
                append(goldMove(data, "ornament_sell_change_pct"))
            }
            if (!bar.isNaN()) {
                if (isNotEmpty()) append("\n")
                append("ทองแท่ง ${baht(bar)}")
                append(goldMove(data, "bar_sell_change_pct"))
            }
            append(age(panel))
        }, stale)
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

    /** The day's move, with a sign, because the direction is most of the point. */
    private fun move(coin: JSONObject): String {
        val change = coin.optDouble("change_pct", Double.NaN)
        if (change.isNaN()) return ""
        val sign = if (change >= 0) "+" else ""
        return "  $sign${percent(change)}%"
    }

    /** 68850.0 -> "68,850" — no decimals, because nobody reads satang at 2 m.
     *  ROUNDED, not truncated: 999.7 baht is 1,000, and a price that reads low
     *  every time is a worse lie than one that is occasionally a baht high. */
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
    ): String = when {
        mic == "no-permission" || mic == "error" -> offline
        tts == "speaking" || tts == "synthesising" || tts == "device-fallback" -> speaking
        chat == "asking" || stt == "sending" -> thinking
        stt == "recording" -> listening
        else -> ready
    }
}
