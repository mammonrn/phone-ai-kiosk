package com.mammonrn.phoneaikiosk.radio

import org.json.JSONArray
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * "ค้นหาสถานีไทย" (0.61.0): the free, key-less Radio Browser directory
 * (https://api.radio-browser.info), for more stations to add to the list.
 *
 * WHAT IS SENT: the country code TH, "hide broken", the order, a count, and
 * the words typed in the search box if any — nothing about the phone or the
 * person. Its own User-Agent, as the directory asks. One request per tap on
 * "ค้นหา"; a server that does not answer is followed by the next mirror, once
 * each, and then the search says it failed. No retries after that.
 *
 * The parsing is pure (JVM tests); [search] is the one network call, run off
 * the main thread by the screen.
 */
object RadioBrowser {

    /** One station as the directory lists it. */
    data class Found(val uuid: String, val name: String, val url: String, val codec: String,
                     val bitrate: Int, val hls: Boolean, val votes: Int, val checkedOk: Boolean) {
        /** An https stream: it plays in this app as it is (DESIGN.md 5ฏ). */
        val secure: Boolean get() = url.lowercase().startsWith("https://")
    }

    /**
     * The directory's servers. Its documentation says to look them up from
     * all.api.radio-browser.info; these three are the long-standing ones, tried
     * in a shuffled order so no one of them takes every search.
     */
    val SERVERS = listOf("de1.api.radio-browser.info", "fi1.api.radio-browser.info", "nl1.api.radio-browser.info")

    const val LIMIT = 100
    const val USER_AGENT = "phone-ai-kiosk-radio/0.61 (+https://github.com/mammonrn/phone-ai-kiosk)"
    private const val TIMEOUT_MS = 10_000

    enum class Order(val key: String) { VOTES("votes"), CLICKS("clickcount") }

    /** The request's path and query on any server. */
    fun query(words: String, order: Order = Order.VOTES, limit: Int = LIMIT): String {
        val q = StringBuilder("/json/stations/search?countrycode=TH&hidebroken=true&order=")
            .append(order.key).append("&reverse=true&limit=").append(limit)
        val w = words.trim()
        if (w.isNotEmpty()) q.append("&name=").append(URLEncoder.encode(w, "UTF-8"))
        return q.toString()
    }

    /**
     * The directory's answer as stations: a playable address (the resolved
     * one when there is one), a name made to fit the list, each address once.
     * Entries without a usable address are left out.
     */
    fun parse(json: String): List<Found> {
        val array = runCatching { JSONArray(json) }.getOrNull() ?: return emptyList()
        val out = ArrayList<Found>()
        val seen = HashSet<String>()
        for (i in 0 until array.length()) {
            val o = array.optJSONObject(i) ?: continue
            val url = o.optString("url_resolved").trim().ifEmpty { o.optString("url").trim() }
            if (RadioBook.checkUrl(url) != null) continue
            val name = RadioBook.shorten(o.optString("name"))
            if (name.isEmpty()) continue
            if (!seen.add(url.lowercase().trimEnd('/'))) continue
            out.add(Found(o.optString("stationuuid"), name, url, o.optString("codec").trim().uppercase(),
                          o.optInt("bitrate", 0).coerceAtLeast(0), o.optInt("hls", 0) == 1,
                          o.optInt("votes", 0), o.optInt("lastcheckok", 0) == 1))
        }
        return out
    }

    /** One search: each server once, in a shuffled order. Throws when none answers. Not on the main thread. */
    fun search(words: String, order: Order = Order.VOTES): List<Found> {
        var last: Exception? = null
        for (server in SERVERS.shuffled()) {
            try {
                return parse(get("https://$server" + query(words, order)))
            } catch (e: IOException) {
                last = e
            }
        }
        throw last ?: IOException("no server")
    }

    private fun get(address: String): String {
        val c = URL(address).openConnection() as HttpURLConnection
        try {
            c.connectTimeout = TIMEOUT_MS
            c.readTimeout = TIMEOUT_MS
            c.setRequestProperty("User-Agent", USER_AGENT)
            c.setRequestProperty("Accept", "application/json")
            if (c.responseCode != 200) throw IOException("http ${c.responseCode}")
            return c.inputStream.use { stream ->
                // A directory answer of 100 stations is ~150 KB; more than 2 MB is not an answer.
                val bytes = stream.readNBytesCompat(2 shl 20)
                String(bytes, Charsets.UTF_8)
            }
        } finally {
            c.disconnect()
        }
    }

    private fun java.io.InputStream.readNBytesCompat(max: Int): ByteArray {
        val out = java.io.ByteArrayOutputStream()
        val buf = ByteArray(16 * 1024)
        while (out.size() < max) {
            val n = read(buf, 0, minOf(buf.size, max - out.size()))
            if (n < 0) break
            out.write(buf, 0, n)
        }
        return out.toByteArray()
    }
}
