package com.mammonrn.phoneaikiosk.ui

/**
 * Which page of a paged card is showing, and which pages have news nobody has
 * seen yet. Pure; PagedPanel draws it and PagesTest holds it (DESIGN.md,
 * "การ์ดหลายหน้า").
 *
 * THE RULES:
 *  * The first page registered is the one shown until something says otherwise.
 *  * NEWS on a page ([news]) makes that page the one shown — the card opened
 *    because of it, so it should open on it — UNLESS a finger chose a page in
 *    the last [touchHoldMs]: then the choice stands and the page with news is
 *    marked unseen instead, so its tab says "ใหม่".
 *  * A page stops being unseen the moment it is shown, by news or by a tap.
 *  * The first report for a page is not news (the same rule as CardBoard):
 *    after a restart nothing is new.
 */
class Pages(ids: List<String>, private val touchHoldMs: Long = TOUCH_HOLD_MS) {

    val ids: List<String> = ids.distinct()

    init {
        require(this.ids.isNotEmpty()) { "a paged card needs at least one page" }
    }

    var current: String = this.ids.first()
        private set

    private val unseen = LinkedHashSet<String>()
    private val signatures = HashMap<String, String>()
    private var touchedAt = Long.MIN_VALUE

    fun hasUnseen(id: String): Boolean = id in unseen

    /** Position of the page on screen, 0-based, for "1/2". */
    fun index(id: String = current): Int = ids.indexOf(id)

    /** A finger chose a page (a tab or a swipe). */
    fun choose(id: String, nowMs: Long) {
        if (id !in ids) return
        current = id
        unseen.remove(id)
        touchedAt = nowMs
    }

    /** Shows a page without it counting as a finger's choice (a page went away). */
    fun show(id: String) {
        if (id !in ids) return
        current = id
        unseen.remove(id)
    }

    /** The page after (or before) the current one, for a swipe; stays at the ends. */
    fun step(by: Int, nowMs: Long) {
        val next = (index() + by).coerceIn(0, ids.lastIndex)
        choose(ids[next], nowMs)
    }

    /**
     * This page's news signature. A DIFFERENT one after the first is news: the
     * page is shown, or, while a finger's choice holds, marked unseen.
     * Returns true when it was news.
     */
    fun news(id: String, signature: String, nowMs: Long): Boolean {
        if (id !in ids) return false
        val before = signatures.put(id, signature)
        if (before == null || before == signature) return false
        val fingerHolds = touchedAt != Long.MIN_VALUE && nowMs - touchedAt < touchHoldMs
        if (fingerHolds && id != current) {
            unseen.add(id)
        } else {
            current = id
            unseen.remove(id)
        }
        return true
    }

    companion object {
        /** The same two minutes a tap keeps a folded card open (CardBoard). */
        const val TOUCH_HOLD_MS = 2 * 60_000L
    }
}
