package com.mammonrn.phoneaikiosk.media

/** A named list a person made (0.59.0), of songs or of videos. Its items are what the players play. */
data class Playlist(val id: String, val name: String, val kind: Kind, val items: List<Track>) {
    enum class Kind { MUSIC, VIDEO }
}

/**
 * EVERY PLAYLIST (0.59.0, Poom: "เลิกดึงไฟล์สื่อทั้งเครื่อง แสดงเฉพาะที่ผู้ใช้เลือกเอง").
 * The players show only what is in here; voice search looks only in here.
 * Plain Kotlin — PlaylistBookTest — and kept by PlaylistStore in the app's
 * own files as text: never in a log.
 *
 *  * Several lists for each kind, each with a name that is not blank, not
 *    taken by another list of the same kind, and at most [MAX_NAME] long.
 *  * Adding what a list already holds adds nothing twice (a folder added again).
 *  * One list of each kind is "current": the one the player shows and plays.
 *  * [migrate]: the music player's list from before 0.59.0 becomes the
 *    playlist "รายการเดิม" — whole, in its order — so an update loses nothing.
 */
class PlaylistBook(lists: List<Playlist> = emptyList(), current: Map<Playlist.Kind, String> = emptyMap()) {

    var lists: List<Playlist> = lists
        private set
    private val current = HashMap(current)

    fun of(kind: Playlist.Kind): List<Playlist> = lists.filter { it.kind == kind }
    fun get(id: String?): Playlist? = lists.firstOrNull { it.id == id }

    /** The list the player of [kind] shows: the one chosen last, else the first. */
    fun current(kind: Playlist.Kind): Playlist? = get(current[kind]) ?: of(kind).firstOrNull()

    fun setCurrent(kind: Playlist.Kind, id: String?) {
        if (id == null) current.remove(kind) else if (get(id)?.kind == kind) current[kind] = id
    }

    enum class Problem { EMPTY, TOO_LONG, TAKEN }

    /** Why [name] cannot name a list of [kind] (other than [self]), or null. */
    fun nameProblem(kind: Playlist.Kind, name: String, self: String? = null): Problem? {
        val n = name.trim()
        if (n.isEmpty()) return Problem.EMPTY
        if (n.length > MAX_NAME) return Problem.TOO_LONG
        if (of(kind).any { it.id != self && it.name.trim().equals(n, ignoreCase = true) }) return Problem.TAKEN
        return null
    }

    /** "รายการที่ 1", "รายการที่ 2"…: the first free one, offered in the name box. */
    fun suggestName(kind: Playlist.Kind): String {
        var n = of(kind).size + 1
        while (nameProblem(kind, "$SUGGEST$n") != null) n += 1
        return "$SUGGEST$n"
    }

    /**
     * A new list. The caller checked [nameProblem]; it becomes the current one.
     * [asIs]: the items exactly as given, a song twice if it was there twice
     * (the migrated list must be the old one, whole).
     */
    fun create(kind: Playlist.Kind, name: String, items: List<Track> = emptyList(), asIs: Boolean = false): Playlist {
        val id = "p" + ((lists.mapNotNull { it.id.removePrefix("p").toIntOrNull() }.maxOrNull() ?: 0) + 1)
        val list = Playlist(id, name.trim(), kind, if (asIs) items else distinct(items))
        lists = lists + list
        current[kind] = id
        return list
    }

    fun rename(id: String, name: String) = edit(id) { it.copy(name = name.trim()) }

    /** Removes the list only — never a file. The current one falls to the first left. */
    fun delete(id: String) {
        val gone = get(id) ?: return
        lists = lists.filterNot { it.id == id }
        if (current[gone.kind] == id) current.remove(gone.kind)
    }

    /** Appends what the list does not already hold. Returns how many were new. */
    fun add(id: String, items: List<Track>): Int {
        val list = get(id) ?: return 0
        val have = list.items.mapTo(HashSet()) { it.id }
        val fresh = distinct(items).filter { it.id !in have }
        edit(id) { it.copy(items = it.items + fresh) }
        return fresh.size
    }

    /** The list as the player now has it (after a removal, a sort, a clear). */
    fun setItems(id: String, items: List<Track>) = edit(id) { it.copy(items = items) }

    /** Every item of [kind], each once: what voice search looks through. */
    fun searchable(kind: Playlist.Kind): List<Track> = distinct(of(kind).flatMap { it.items })

    /** The first list of [kind] that holds [trackId]. */
    fun holding(kind: Playlist.Kind, trackId: String): Playlist? =
        (listOfNotNull(current(kind)) + of(kind)).firstOrNull { p -> p.items.any { it.id == trackId } }

    private fun edit(id: String, change: (Playlist) -> Playlist) {
        lists = lists.map { if (it.id == id) change(it) else it }
    }

    private fun distinct(items: List<Track>) = items.distinctBy { it.id }

    // ------------------------------------------------------------ as text

    /**
     * "b1" then a line per list ("L", id, kind, name), each followed by its
     * items ("T", id, title, artist, album, length), then the current lists
     * ("C", kind, id). Tab-separated, escaped as [Session] does.
     */
    fun encode(): String = buildString {
        append(VERSION).append('\n')
        for (p in lists) {
            line("L", p.id, p.kind.name, p.name)
            for (t in p.items) line("T", t.id, t.title, t.artist, t.album, t.durationMs.toString())
        }
        for ((kind, id) in current) line("C", kind.name, id)
    }

    private fun StringBuilder.line(vararg fields: String) {
        append(fields.joinToString("\t") { esc(it) }).append('\n')
    }

    companion object {
        private const val VERSION = "b1"
        const val MAX_NAME = 40
        /** The music list from before playlists, by the name Poom gave it. */
        const val FORMER = "รายการเดิม"
        /** The NAS folders added to the old library, kept as a list too. */
        const val FORMER_NAS = "คลัง NAS เดิม"
        private const val SUGGEST = "รายการที่ "

        /** The book in [text], or null when it is not one. */
        fun decode(text: String): PlaylistBook? = runCatching {
            val lines = text.split('\n').filter { it.isNotEmpty() }
            if (lines.firstOrNull() != VERSION) return null
            val lists = ArrayList<Playlist>()
            val current = HashMap<Playlist.Kind, String>()
            for (line in lines.drop(1)) {
                val f = line.split('\t').map(::unesc)
                when (f[0]) {
                    "L" -> lists.add(Playlist(f[1], f[3], Playlist.Kind.valueOf(f[2]), emptyList()))
                    "T" -> {
                        val last = lists.removeAt(lists.lastIndex)
                        lists.add(last.copy(items = last.items + Track(f[1], f[2], f[3], f[4], f[5].toLong())))
                    }
                    "C" -> current[Playlist.Kind.valueOf(f[1])] = f[2]
                }
            }
            PlaylistBook(lists, current)
        }.getOrNull()

        /**
         * The first book, from what 0.58 kept: the player's list becomes
         * [FORMER] (the current music list), and the NAS folders once added to
         * the old library become [FORMER_NAS]. Nothing kept: an empty book.
         */
        fun migrate(session: Session?, nasTracks: List<Track>): PlaylistBook {
            val book = PlaylistBook()
            if (nasTracks.isNotEmpty()) book.create(Playlist.Kind.MUSIC, FORMER_NAS, nasTracks, asIs = true)
            if (session != null && session.tracks.isNotEmpty()) book.create(Playlist.Kind.MUSIC, FORMER, session.tracks, asIs = true)
            return book
        }

        private fun esc(s: String) = s.replace("\\", "\\\\").replace("\t", "\\t").replace("\n", "\\n")

        private fun unesc(s: String): String {
            val out = StringBuilder()
            var i = 0
            while (i < s.length) {
                val c = s[i]
                if (c == '\\' && i + 1 < s.length) {
                    out.append(when (s[i + 1]) { 't' -> '\t'; 'n' -> '\n'; else -> s[i + 1] })
                    i += 2
                } else { out.append(c); i += 1 }
            }
            return out.toString()
        }
    }
}
