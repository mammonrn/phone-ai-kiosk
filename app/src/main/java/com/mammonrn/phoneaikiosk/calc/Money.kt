package com.mammonrn.phoneaikiosk.calc

import org.json.JSONObject
import java.util.Currency
import java.util.Locale

/**
 * Currency and metal prices for the calculator's "ราคา" tab (0.61.0, Poom).
 * Plain Kotlin and org.json — MoneyTest drives every formula.
 *
 * WHERE THE NUMBERS COME FROM (CLAUDE.md 3ก: free, no sign-up, once a day,
 * straight from the phone, always with the source and the time; never a guess):
 *  1. fawazahmed0 exchange-api on jsDelivr, then the same data on
 *     currency-api.pages.dev (the fallback its README names);
 *  2. Frankfurter (the European Central Bank's reference rates) when both are
 *     down — fewer currencies and no metals.
 * All three are read as "how many of X one US dollar buys" ([Table.perUsd]);
 * converting between any two goes through that, so no pair needs USD as its
 * own base.
 */
object Money {

    /** A troy ounce in grams (the unit metal prices are quoted in). */
    const val TROY_OUNCE_G = 31.1034768
    /** One baht of gold, the Thai weight (Gold Traders Association). */
    const val BAHT_GOLD_G = 15.244
    /** A day: the rates are fetched at most this often. */
    const val REFRESH_MS = 24L * 60 * 60 * 1000

    enum class Source(val url: String) {
        JSDELIVR("https://cdn.jsdelivr.net/npm/@fawazahmed0/currency-api@latest/v1/currencies/usd.min.json"),
        PAGES("https://latest.currency-api.pages.dev/v1/currencies/usd.min.json"),
        FRANKFURTER("https://api.frankfurter.dev/v1/latest?base=USD"),
    }

    enum class WeightUnit(val grams: Double) {
        OUNCE(TROY_OUNCE_G), GRAM(1.0), KILOGRAM(1000.0), BAHT(BAHT_GOLD_G)
    }

    /** Gold's purity: pure (99.99%) or Thai ornament gold (96.5%). */
    enum class Purity(val share: Double) { PURE(0.9999), THAI(0.965) }

    /**
     * The ten metals by market value, gold first (Poom). Places 1-5 by market
     * capitalisation (assetmarketcap.com, commodities, read 2026-09-25); 6-10
     * by the value of annual mine production (USGS through Visual Capitalist
     * 2023 and Statista 2023) — no free source ranks all ten by one measure;
     * DESIGN.md 5จ says so. [code] is the price's code at the free source, or
     * null when no free, sign-up-free source has it: that metal is listed and
     * says so, and is never given a number.
     */
    enum class Metal(val code: String?) {
        GOLD("xau"), COPPER(null), SILVER("xag"), PLATINUM("xpt"), PALLADIUM("xpd"),
        IRON_ORE(null), ALUMINIUM(null), NICKEL(null), ZINC(null), LITHIUM(null),
    }

    /** Rates as fetched: [perUsd] by lower-case code; [date] is the source's own date for them. */
    class Table(val perUsd: Map<String, Double>, val source: Source, val date: String, val fetchedAt: Long) {
        fun has(code: String) = (perUsd[code.lowercase()] ?: 0.0) > 0.0
    }

    // ------------------------------------------------------------ the sources' answers

    /** fawazahmed0's usd.min.json: {"date":"2026-09-23","usd":{"thb":33.2,"xau":0.000233,…}}. */
    fun parseFawaz(json: String, source: Source, fetchedAt: Long): Table? = runCatching {
        val o = JSONObject(json)
        val rates = o.getJSONObject("usd")
        val map = HashMap<String, Double>()
        for (k in rates.keys()) {
            val v = rates.optDouble(k, Double.NaN)
            if (v.isFinite() && v > 0) map[k.lowercase()] = v
        }
        map["usd"] = 1.0
        Table(map, source, o.getString("date"), fetchedAt).takeIf { map.size > 1 }
    }.getOrNull()

    /** Frankfurter: {"amount":1.0,"base":"USD","date":"2026-09-24","rates":{"THB":33.4,…}}. */
    fun parseFrankfurter(json: String, fetchedAt: Long): Table? = runCatching {
        val o = JSONObject(json)
        if (!o.getString("base").equals("USD", ignoreCase = true)) return null
        val rates = o.getJSONObject("rates")
        val map = HashMap<String, Double>()
        for (k in rates.keys()) {
            val v = rates.optDouble(k, Double.NaN)
            if (v.isFinite() && v > 0) map[k.lowercase()] = v
        }
        map["usd"] = 1.0
        Table(map, Source.FRANKFURTER, o.getString("date"), fetchedAt).takeIf { map.size > 1 }
    }.getOrNull()

    fun parse(json: String, source: Source, fetchedAt: Long): Table? =
        if (source == Source.FRANKFURTER) parseFrankfurter(json, fetchedAt) else parseFawaz(json, source, fetchedAt)

    // ------------------------------------------------------------ the formulas

    /**
     * [amount] of [from] in [to]: amount ÷ (from per USD) × (to per USD).
     * Null when either is not in the table, or the amount is not a finite number ≥ 0.
     */
    fun convert(amount: Double, from: String, to: String, t: Table): Double? {
        if (!amount.isFinite() || amount < 0) return null
        val f = t.perUsd[from.lowercase()] ?: return null
        val g = t.perUsd[to.lowercase()] ?: return null
        if (f <= 0 || g <= 0) return null
        return (amount / f * g).takeIf { it.isFinite() }
    }

    /**
     * The price of [quantity] [unit] of [metal] in [currency]: the source gives
     * troy ounces per US dollar, so one ounce is 1 ÷ that in USD; per gram ÷
     * 31.1034768; × the unit's grams × quantity × purity (gold only) × the
     * currency per USD. Null when the metal has no free price or the currency
     * is not in the table.
     */
    fun metalPrice(metal: Metal, quantity: Double, unit: WeightUnit, currency: String, t: Table,
                   purity: Purity = Purity.PURE): Double? {
        val code = metal.code ?: return null
        if (!quantity.isFinite() || quantity < 0) return null
        val ouncesPerUsd = t.perUsd[code] ?: return null
        val per = t.perUsd[currency.lowercase()] ?: return null
        if (ouncesPerUsd <= 0 || per <= 0) return null
        val usdPerGram = 1.0 / ouncesPerUsd / TROY_OUNCE_G
        val share = if (metal == Metal.GOLD) purity.share else 1.0
        return (usdPerGram * unit.grams * quantity * share * per).takeIf { it.isFinite() }
    }

    // ------------------------------------------------------------ how old

    /** Whole hours since [fetchedAt] (never negative). */
    fun ageHours(fetchedAt: Long, now: Long): Long = ((now - fetchedAt).coerceAtLeast(0)) / (60 * 60 * 1000)

    fun needsRefresh(fetchedAt: Long, now: Long): Boolean = now - fetchedAt >= REFRESH_MS || now < fetchedAt

    // ------------------------------------------------------------ the currencies' names

    /** A currency as the picker shows it: 🇯🇵 ญี่ปุ่น · เยนญี่ปุ่น (JPY). */
    class Named(val code: String, val flag: String, val country: String, val name: String,
                val countryEn: String, val nameEn: String) {
        fun matches(query: String): Boolean {
            val q = query.trim().lowercase()
            if (q.isEmpty()) return true
            return listOf(code, country, name, countryEn, nameEn).any { q in it.lowercase() }
        }
    }

    private val THAI = Locale("th", "TH")

    /**
     * Each ISO currency the table has, with a country for its flag and names in
     * Thai and English from the platform's own locale data (no list typed in
     * here to go out of date). Metals and codes with no country (XAU, XDR…) are
     * left out; the euro is the European Union's.
     */
    fun catalogue(t: Table): List<Named> {
        val country = HashMap<String, String>()
        for (l in Locale.getAvailableLocales()) {
            val cc = l.country
            if (cc.length != 2 || !cc.all { it in 'A'..'Z' }) continue
            val code = runCatching { Currency.getInstance(l)?.currencyCode }.getOrNull() ?: continue
            // The country whose code the currency's starts with wins (USD → US, not Ecuador).
            if (country[code] == null || code.startsWith(cc)) country[code] = cc
        }
        country["EUR"] = "EU"
        val out = ArrayList<Named>()
        for (code in t.perUsd.keys.map { it.uppercase() }.sorted()) {
            if (code.startsWith("X")) continue
            val c = runCatching { Currency.getInstance(code) }.getOrNull() ?: continue
            val cc = country[code] ?: continue
            val thCountry = if (cc == "EU") "สหภาพยุโรป" else Locale("", cc).getDisplayCountry(THAI)
            val enCountry = if (cc == "EU") "European Union" else Locale("", cc).getDisplayCountry(Locale.ENGLISH)
            out.add(Named(code, flag(cc), thCountry, c.getDisplayName(THAI), enCountry, c.getDisplayName(Locale.ENGLISH)))
        }
        return out
    }

    /** A country's flag: its two letters as regional indicator symbols (Poom asked for the flags). */
    fun flag(countryCode: String): String {
        if (countryCode.length != 2) return ""
        val sb = StringBuilder()
        for (ch in countryCode.uppercase()) sb.appendCodePoint(0x1F1E6 + (ch - 'A'))
        return sb.toString()
    }

    /** Pinned first (in pin order), then the rest as they came. */
    fun ordered(all: List<Named>, pins: List<String>): List<Named> {
        val byCode = all.associateBy { it.code }
        val pinned = pins.mapNotNull { byCode[it.uppercase()] }
        val set = pinned.map { it.code }.toSet()
        return pinned + all.filter { it.code !in set }
    }

    /** A price or an amount the way the page shows it: 1,234.56 / 0.004312. */
    fun format(x: Double): String = when {
        !x.isFinite() -> "—"
        x == 0.0 -> "0"
        x >= 1 -> String.format(Locale.US, "%,.2f", x)
        else -> String.format(Locale.US, "%.6f", x).trimEnd('0').trimEnd('.')
    }
}
