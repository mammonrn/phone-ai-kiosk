package com.mammonrn.phoneaikiosk.radio

import org.json.JSONArray
import org.json.JSONObject

/**
 * One station on the list (0.61.0, radio). [id] is ours, made when the
 * station is added; it is what a log line may carry, never the name or the URL.
 */
data class Station(val id: String, val name: String, val url: String, val favourite: Boolean = false)

/**
 * The radio's station list: seeded with the Thai hit stations that were
 * measured to play (tools/radio/RESULTS.md), and the person's to edit — add by
 * name and URL, rename, remove, star. Removing a station takes it off this list
 * only; no file of anybody's is deleted, so there is no identity check
 * (DESIGN.md 5ฏ).
 *
 * NO ANDROID IN HERE (org.json is on the unit tests' classpath), so the JVM
 * tests drive every edit. Every edit returns a new book or a reason in words.
 */
class RadioBook(val stations: List<Station>) {

    /** Why an edit was refused, in words for the screen. */
    enum class Refusal { NAME_EMPTY, NAME_LONG, URL_BAD, URL_TOO_LONG, DUPLICATE, FULL, MISSING }

    /** An edit's outcome: the new book, or why not. */
    class Result private constructor(val book: RadioBook?, val refusal: Refusal?) {
        val ok: Boolean get() = book != null
        companion object {
            fun ok(book: RadioBook) = Result(book, null)
            fun no(why: Refusal) = Result(null, why)
        }
    }

    /** What the screen lists: starred stations first, each group in the order it was added. */
    fun ordered(): List<Station> = stations.filter { it.favourite } + stations.filterNot { it.favourite }

    fun find(id: String): Station? = stations.firstOrNull { it.id == id }

    /** The station's place on the screen's list, 1-based: what a log line says instead of its name. */
    fun number(id: String): Int = ordered().indexOfFirst { it.id == id } + 1

    fun has(url: String): Boolean = stations.any { sameUrl(it.url, url) }

    fun add(name: String, url: String, id: String): Result {
        val n = cleanName(name)
        val u = url.trim()
        checkName(n)?.let { return Result.no(it) }
        checkUrl(u)?.let { return Result.no(it) }
        if (has(u)) return Result.no(Refusal.DUPLICATE)
        if (stations.size >= MAX_STATIONS) return Result.no(Refusal.FULL)
        return Result.ok(RadioBook(stations + Station(id, n, u)))
    }

    /** A new name, a new URL, or both; the star and the place stay. */
    fun edit(id: String, name: String, url: String): Result {
        val old = find(id) ?: return Result.no(Refusal.MISSING)
        val n = cleanName(name)
        val u = url.trim()
        checkName(n)?.let { return Result.no(it) }
        checkUrl(u)?.let { return Result.no(it) }
        if (stations.any { it.id != id && sameUrl(it.url, u) }) return Result.no(Refusal.DUPLICATE)
        return Result.ok(RadioBook(stations.map { if (it.id == id) old.copy(name = n, url = u) else it }))
    }

    fun remove(id: String): Result {
        if (find(id) == null) return Result.no(Refusal.MISSING)
        return Result.ok(RadioBook(stations.filterNot { it.id == id }))
    }

    fun star(id: String, on: Boolean): Result {
        if (find(id) == null) return Result.no(Refusal.MISSING)
        return Result.ok(RadioBook(stations.map { if (it.id == id) it.copy(favourite = on) else it }))
    }

    fun encode(): String {
        val list = JSONArray()
        for (s in stations) list.put(JSONObject().put("id", s.id).put("name", s.name).put("url", s.url)
                                         .put("fav", s.favourite))
        return JSONObject().put("version", 1).put("stations", list).toString(1)
    }

    companion object {
        const val MAX_STATIONS = 200
        const val MAX_NAME = 40
        const val MAX_URL = 2048

        /**
         * The list from its file. Entries that are broken (no name, no usable
         * URL, a repeated id) are skipped, not fatal: one bad line never loses
         * the rest of the list. Null when the text is not a list at all.
         */
        fun decode(text: String): RadioBook? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val list = root.optJSONArray("stations") ?: return null
            val out = ArrayList<Station>()
            val ids = HashSet<String>()
            for (i in 0 until list.length()) {
                val o = list.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                val name = cleanName(o.optString("name"))
                val url = o.optString("url").trim()
                if (id.isEmpty() || !ids.add(id) || checkName(name) != null || checkUrl(url) != null) continue
                if (out.any { sameUrl(it.url, url) }) continue
                out.add(Station(id, name, url, o.optBoolean("fav", false)))
            }
            return RadioBook(out)
        }

        /** One line, no runs of spaces. */
        fun cleanName(name: String): String = name.replace(Regex("\\s+"), " ").trim()

        /**
         * A directory's name made to fit [MAX_NAME]: cut at the last space that
         * fits, else just before [MAX_NAME] — never between a Thai consonant and
         * its vowel or tone mark (DESIGN.md 7).
         */
        fun shorten(name: String): String {
            val n = cleanName(name)
            if (n.length <= MAX_NAME) return n
            val space = n.lastIndexOf(' ', MAX_NAME)
            if (space > MAX_NAME / 2) return n.substring(0, space).trim()
            var end = MAX_NAME
            while (end > 1 && isThaiMark(n[end])) end -= 1
            return n.substring(0, end).trim()
        }

        private fun isThaiMark(c: Char): Boolean =
            c == 'ั' || c in 'ิ'..'ฺ' || c in '็'..'๎'

        private fun checkName(name: String): Refusal? = when {
            name.isEmpty() -> Refusal.NAME_EMPTY
            name.length > MAX_NAME -> Refusal.NAME_LONG
            else -> null
        }

        /** An http(s) address with a host; nothing else can be a stream. */
        fun checkUrl(url: String): Refusal? {
            if (url.length > MAX_URL) return Refusal.URL_TOO_LONG
            val m = Regex("^(https?)://([^/?#\\s:@]+)(:\\d{1,5})?([/?#]\\S*)?$", RegexOption.IGNORE_CASE).matchEntire(url)
                ?: return Refusal.URL_BAD
            val host = m.groupValues[2]
            if ('.' !in host && host.lowercase() != "localhost") return Refusal.URL_BAD
            return null
        }

        /** The same stream written twice: case of the scheme and host, and a trailing slash, do not count. */
        fun sameUrl(a: String, b: String): Boolean = normal(a) == normal(b)

        private fun normal(url: String): String {
            val u = url.trim()
            val cut = u.indexOf("://")
            if (cut < 0) return u
            val rest = u.substring(cut + 3)
            val slash = rest.indexOfAny(charArrayOf('/', '?', '#')).let { if (it < 0) rest.length else it }
            return (u.substring(0, cut).lowercase() + "://" + rest.substring(0, slash).lowercase() +
                rest.substring(slash)).trimEnd('/')
        }
    }
}

/** What a stream address is, from its path: how the player is told to open it. */
object StreamKind {

    enum class Kind { HLS, PLAYLIST, DIRECT }

    fun of(url: String): Kind {
        val path = url.substringBefore('#').substringBefore('?').lowercase()
        return when {
            path.endsWith(".m3u8") -> Kind.HLS
            path.endsWith(".pls") || path.endsWith(".m3u") -> Kind.PLAYLIST
            else -> Kind.DIRECT
        }
    }

    /** An http:// stream (not encrypted). */
    fun plain(url: String): Boolean = url.trim().lowercase().startsWith("http://")

    /**
     * The hosts allowed to send http (Poom, 2026-09-25): ONLY the domains of the
     * app's own station list, the same names as res/xml/network_security_config.xml
     * (RadioBookTest holds the two together). Any other http station — one added
     * by hand or found in the search — still cannot play.
     */
    val PLAIN_ALLOWED_HOSTS = setOf("media.login.in.th")

    fun host(url: String): String =
        url.trim().substringAfter("://").substringBefore('/').substringBefore('?').substringBefore(':').lowercase()

    /** An http stream Android will refuse in this app: http, and not one of [PLAIN_ALLOWED_HOSTS]. */
    fun plainBlocked(url: String): Boolean = plain(url) && host(url) !in PLAIN_ALLOWED_HOSTS

    /**
     * The first stream address in a .pls or .m3u playlist's text ("File1=…"
     * lines, or bare lines), or null. An HLS playlist is not this: it is
     * given to the player whole.
     */
    fun firstInPlaylist(text: String): String? {
        for (raw in text.lineSequence()) {
            val line = raw.trim()
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("[")) continue
            val value = if (Regex("^File\\d+=", RegexOption.IGNORE_CASE).containsMatchIn(line)) line.substringAfter('=').trim()
                        else line
            if (RadioBook.checkUrl(value) == null) return value
        }
        return null
    }
}
