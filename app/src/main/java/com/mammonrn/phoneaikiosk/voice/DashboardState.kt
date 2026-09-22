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

    /** What one window shows. */
    class Panel(val text: String, val stale: Boolean)

    /** Everything the screen needs, already formatted. */
    class Screen(
        val weather: Panel,
        val gold: Panel,
        val crypto: Panel,
        val place: String,
    )

    /**
     * Reads the payload. Never throws: a malformed reply is a screen that says
     * so, not a crash on the one device nobody can reach a debugger on.
     */
    fun parse(json: String, unavailable: String): Screen = try {
        val root = JSONObject(json)
        Screen(
            weather = weatherPanel(root.optJSONObject("weather"), unavailable),
            gold = goldPanel(root.optJSONObject("gold"), unavailable),
            crypto = cryptoPanel(root.optJSONObject("crypto"), unavailable),
            place = root.optString("place", ""),
        )
    } catch (e: Exception) {
        val panel = Panel(unavailable, false)
        Screen(panel, panel, panel, "")
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
            if (!ornament.isNaN()) append("รูปพรรณ ${baht(ornament)}")
            if (!bar.isNaN()) {
                if (isNotEmpty()) append("\n")
                append("ทองแท่ง ${baht(bar)}")
            }
            append(age(panel))
        }, stale)
    }

    private fun cryptoPanel(panel: JSONObject?, unavailable: String): Panel {
        val (data, stale) = usable(panel) ?: return Panel(unavailable, false)
        val btc = data.optJSONObject("btc")
        val eth = data.optJSONObject("eth")
        if (btc == null && eth == null) return Panel(unavailable, false)

        return Panel(buildString {
            btc?.let { append("BTC  ${dollars(it.optDouble("usd", 0.0))}${move(it)}") }
            eth?.let {
                if (isNotEmpty()) append("\n")
                append("ETH  ${dollars(it.optDouble("usd", 0.0))}${move(it)}")
            }
            append(age(panel))
        }, stale)
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
