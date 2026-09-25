package com.mammonrn.phoneaikiosk.calc

import android.content.Context
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/**
 * The rates on the phone (0.61.0): fetched straight from the free sources
 * (Money.Source, no key, so not through the broker), at most once a day, and
 * kept in the app's files so an offline phone still has the last ones — with
 * their age said. Nothing is ever made up: no table, no numbers.
 *
 * Logs say which source answered and how the others failed — never an amount.
 */
object RateStore {
    private const val TAG = "KioskRates"
    private const val FILE = "rates.json"
    private const val TIMEOUT_MS = 10_000

    /** Debug build only (TEST_RATES): sources made to fail, to see the next one taken. */
    @Volatile var failForTest: Set<Money.Source> = emptySet()

    @Volatile var table: Money.Table? = null
        private set

    /** How the last fetch went, per source, in our own words: "ok", "http 404", "timeout", "off for a test". */
    @Volatile var lastTries: List<Pair<Money.Source, String>> = emptyList()
        private set

    /** The saved table, if there is one (read once per process). */
    fun load(context: Context): Money.Table? {
        table?.let { return it }
        val f = File(context.filesDir, FILE)
        if (!f.exists()) return null
        return runCatching {
            val o = JSONObject(f.readText())
            val source = Money.Source.valueOf(o.getString("source"))
            Money.parse(o.getString("body"), source, o.getLong("fetchedAt"))
        }.getOrNull().also { table = it }
    }

    /**
     * Tries the sources in order and keeps the first good answer. Blocking:
     * call it off the main thread. Returns the table now held (new or old).
     */
    fun fetch(context: Context, now: Long = System.currentTimeMillis()): Money.Table? {
        val tries = ArrayList<Pair<Money.Source, String>>()
        for (s in Money.Source.entries) {
            if (s in failForTest) { tries.add(s to "off for a test"); continue }
            val (body, how) = get(s.url)
            val t = body?.let { Money.parse(it, s, now) }
            if (t == null) { tries.add(s to (if (body == null) how else "unreadable")); continue }
            tries.add(s to "ok")
            runCatching {
                File(context.filesDir, FILE).writeText(JSONObject()
                    .put("source", s.name).put("fetchedAt", now).put("body", body).toString())
            }
            table = t
            break
        }
        lastTries = tries
        Log.i(TAG, "rates fetch " + tries.joinToString(" ") { "${it.first.name.lowercase()}=${it.second.replace(' ', '-')}" })
        return table
    }

    private fun get(url: String): Pair<String?, String> {
        return try {
            val c = URL(url).openConnection() as HttpURLConnection
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", "phone-ai-kiosk (calculator)")
            try {
                if (c.responseCode != 200) null to "http ${c.responseCode}"
                else c.inputStream.bufferedReader().use { it.readText() } to "ok"
            } finally {
                c.disconnect()
            }
        } catch (e: java.net.SocketTimeoutException) {
            null to "timeout"
        } catch (e: Exception) {
            null to e.javaClass.simpleName
        }
    }
}
