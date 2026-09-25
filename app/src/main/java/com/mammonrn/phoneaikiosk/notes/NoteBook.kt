package com.mammonrn.phoneaikiosk.notes

import org.json.JSONArray
import org.json.JSONObject

/** One line on a list. [id] is ours; a log line may carry a count, never the text. */
data class NoteItem(val id: String, val text: String, val done: Boolean = false)

/**
 * One list: "รายการซื้อของ" and "โน้ต" are built in (their names are the
 * screen's words, by id); the person may make more. Items keep the order they
 * were added in.
 */
data class NoteList(val id: String, val name: String, val items: List<NoteItem>) {
    val builtIn: Boolean get() = id in NoteBook.BUILT_IN

    /** What the screen shows: the ones still to do in the order added, then the ticked. */
    fun ordered(): List<NoteItem> = items.filterNot { it.done } + items.filter { it.done }

    val pending: List<NoteItem> get() = items.filterNot { it.done }
    val doneCount: Int get() = items.count { it.done }
}

/**
 * THE NOTES AND THE SHOPPING LIST (0.62.0, Poom approved, DESIGN.md 5ต).
 *
 * Kept in the app's own files only (NoteStore): never logged, never sent
 * anywhere — except what the person says to Jarvis, and a list read out loud.
 *
 * Removing an item, or clearing the ticked ones, needs no identity check (no
 * file of anybody's is deleted — Poom's ruling for a song taken off a list);
 * a whole list is deleted after "แน่ใจไหม" in place, and the two built-in
 * lists cannot be deleted at all.
 *
 * NO ANDROID IN HERE (org.json is on the unit tests' classpath), so NoteBookTest
 * drives every edit. Every edit returns a new book or a reason.
 */
class NoteBook(val lists: List<NoteList>) {

    enum class Refusal { EMPTY, TOO_LONG, DUPLICATE, FULL, MISSING, BUILT_IN, NOT_SAVED }

    class Result private constructor(val book: NoteBook?, val refusal: Refusal?, val itemId: String? = null) {
        val ok: Boolean get() = book != null
        companion object {
            fun ok(book: NoteBook, itemId: String? = null) = Result(book, null, itemId)
            fun no(why: Refusal) = Result(null, why)
        }
    }

    fun list(id: String): NoteList? = lists.firstOrNull { it.id == id }

    fun item(listId: String, itemId: String): NoteItem? = list(listId)?.items?.firstOrNull { it.id == itemId }

    // ------------------------------------------------------------ items

    /**
     * A new line at the end of [listId]. The same text still to do is refused
     * (DUPLICATE, its id given); the same text already ticked is unticked and
     * moved to the end instead — it is needed again, and the list stays one line each.
     */
    fun add(listId: String, text: String, id: String): Result {
        val list = list(listId) ?: return Result.no(Refusal.MISSING)
        val t = clean(text)
        check(t)?.let { return Result.no(it) }
        val same = list.items.firstOrNull { key(it.text) == key(t) }
        if (same != null && !same.done) return Result.no(Refusal.DUPLICATE)
        if (same != null) {
            val again = same.copy(text = t, done = false)
            return Result.ok(with(list.copy(items = list.items.filterNot { it.id == same.id } + again)), same.id)
        }
        if (list.items.size >= MAX_ITEMS) return Result.no(Refusal.FULL)
        return Result.ok(with(list.copy(items = list.items + NoteItem(id, t))), id)
    }

    fun tick(listId: String, itemId: String, done: Boolean): Result =
        change(listId, itemId) { it.copy(done = done) }

    fun edit(listId: String, itemId: String, text: String): Result {
        val list = list(listId) ?: return Result.no(Refusal.MISSING)
        val t = clean(text)
        check(t)?.let { return Result.no(it) }
        if (list.items.any { it.id != itemId && !it.done && key(it.text) == key(t) }) return Result.no(Refusal.DUPLICATE)
        return change(listId, itemId) { it.copy(text = t) }
    }

    /** One line off the list. No identity check: nobody's file is deleted. */
    fun remove(listId: String, itemId: String): Result {
        val list = list(listId) ?: return Result.no(Refusal.MISSING)
        if (list.items.none { it.id == itemId }) return Result.no(Refusal.MISSING)
        return Result.ok(with(list.copy(items = list.items.filterNot { it.id == itemId })))
    }

    /** "ล้างที่ติ๊กแล้ว": every ticked line off the list; the rest keep their order. */
    fun clearDone(listId: String): Result {
        val list = list(listId) ?: return Result.no(Refusal.MISSING)
        return Result.ok(with(list.copy(items = list.items.filterNot { it.done })))
    }

    // ------------------------------------------------------------ lists

    fun addList(name: String, id: String): Result {
        val n = clean(name)
        checkName(n, null)?.let { return Result.no(it) }
        if (lists.size >= MAX_LISTS) return Result.no(Refusal.FULL)
        return Result.ok(NoteBook(lists + NoteList(id, n, emptyList())), id)
    }

    fun renameList(id: String, name: String): Result {
        val list = list(id) ?: return Result.no(Refusal.MISSING)
        if (list.builtIn) return Result.no(Refusal.BUILT_IN)
        val n = clean(name)
        checkName(n, id)?.let { return Result.no(it) }
        return Result.ok(with(list.copy(name = n)))
    }

    /** A whole list, after "แน่ใจไหม" on the screen. The built-in two stay. */
    fun removeList(id: String): Result {
        val list = list(id) ?: return Result.no(Refusal.MISSING)
        if (list.builtIn) return Result.no(Refusal.BUILT_IN)
        return Result.ok(NoteBook(lists.filterNot { it.id == id }))
    }

    private fun checkName(n: String, self: String?): Refusal? = when {
        n.isEmpty() -> Refusal.EMPTY
        n.length > MAX_NAME -> Refusal.TOO_LONG
        lists.any { it.id != self && key(it.name) == key(n) } -> Refusal.DUPLICATE
        else -> null
    }

    private fun change(listId: String, itemId: String, how: (NoteItem) -> NoteItem): Result {
        val list = list(listId) ?: return Result.no(Refusal.MISSING)
        if (list.items.none { it.id == itemId }) return Result.no(Refusal.MISSING)
        return Result.ok(with(list.copy(items = list.items.map { if (it.id == itemId) how(it) else it })), itemId)
    }

    private fun with(list: NoteList) = NoteBook(lists.map { if (it.id == list.id) list else it })

    fun encode(): String {
        val out = JSONArray()
        for (l in lists) {
            val items = JSONArray()
            for (i in l.items) items.put(JSONObject().put("id", i.id).put("text", i.text).put("done", i.done))
            out.put(JSONObject().put("id", l.id).put("name", l.name).put("items", items))
        }
        return JSONObject().put("version", 1).put("lists", out).toString(1)
    }

    companion object {
        const val SHOPPING = "shopping"
        const val NOTES = "notes"
        val BUILT_IN = listOf(SHOPPING, NOTES)

        /** A line: typed. What comes by voice is held to the broker's 60 (NoteVoice.MAX_VOICE_CHARS). */
        const val MAX_TEXT = 120
        const val MAX_NAME = 30
        const val MAX_ITEMS = 300
        const val MAX_LISTS = 20

        /** A new phone: the two built-in lists, empty. Their names are the screen's (by id). */
        fun fresh(): NoteBook = NoteBook(BUILT_IN.map { NoteList(it, "", emptyList()) })

        /**
         * The book from its file. A broken entry (no id, a repeated id, empty
         * text) is skipped, not fatal: one bad line never loses the rest. The
         * built-in lists are always there, first. Null when the text is not a book at all.
         */
        fun decode(text: String): NoteBook? {
            val root = runCatching { JSONObject(text) }.getOrNull() ?: return null
            val arr = root.optJSONArray("lists") ?: return null
            val read = ArrayList<NoteList>()
            val listIds = HashSet<String>()
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val id = o.optString("id").trim()
                if (id.isEmpty() || !listIds.add(id)) continue
                val name = clean(o.optString("name"))
                if (id !in BUILT_IN && (name.isEmpty() || name.length > MAX_NAME)) continue
                val items = ArrayList<NoteItem>()
                val itemIds = HashSet<String>()
                val raw = o.optJSONArray("items") ?: JSONArray()
                for (j in 0 until raw.length()) {
                    val it = raw.optJSONObject(j) ?: continue
                    val itemId = it.optString("id").trim()
                    val t = clean(it.optString("text"))
                    if (itemId.isEmpty() || !itemIds.add(itemId) || t.isEmpty() || t.length > MAX_TEXT) continue
                    if (items.size >= MAX_ITEMS) break
                    items.add(NoteItem(itemId, t, it.optBoolean("done", false)))
                }
                read.add(NoteList(id, if (id in BUILT_IN) "" else name, items))
            }
            val builtIn = BUILT_IN.map { b -> read.firstOrNull { it.id == b } ?: NoteList(b, "", emptyList()) }
            return NoteBook(builtIn + read.filterNot { it.id in BUILT_IN }.take(MAX_LISTS - BUILT_IN.size))
        }

        /** One line: no control characters, no runs of spaces. */
        fun clean(text: String): String =
            text.filterNot { Character.getType(it) == Character.CONTROL.toInt() && !it.isWhitespace() || Character.getType(it) == Character.FORMAT.toInt() }
                .replace(Regex("\\s+"), " ").trim()

        fun check(t: String): Refusal? = when {
            t.isEmpty() -> Refusal.EMPTY
            t.length > MAX_TEXT -> Refusal.TOO_LONG
            else -> null
        }

        /** Two lines are the same thing when they differ only in case and spaces. */
        fun key(text: String): String = text.filterNot { it.isWhitespace() }.lowercase()
    }
}
