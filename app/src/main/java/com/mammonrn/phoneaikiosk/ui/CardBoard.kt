package com.mammonrn.phoneaikiosk.ui

/**
 * Which cards the kiosk shows open, which as a one-line bar, and in what order.
 *
 * THE SCREEN HAS TO GROW. There are five cards now and more are coming, and a
 * phone does not get taller. So the screen shows what CHANGED, open and at the
 * top, and folds away what has been sitting still. The Jarvis window is not a
 * card: it lives outside this board, at a fixed height, and never moves.
 *
 * THE RULES, which are the whole design and are therefore written down here
 * and tested rather than left to feel:
 *
 *  * AN UPDATE is a change a person would notice — each card says what that is
 *    by the signature it reports (see MainActivity): the weather's whole
 *    degrees and its word, the gold price, each coin's day move in whole
 *    percent. A refetch that brings the same signature is NOT an update. The
 *    first signature a card ever reports opens it, so everything starts
 *    open — but is not "เพิ่งอัปเดต": after a restart or an update nothing is
 *    news, and a badge on every window would say nothing at all.
 *  * ORDER: most recently updated first. Cards that have never reported sit
 *    at the end in their registered order.
 *  * NO SHUFFLING: the order is recomputed at most once per [reorderGapMs]. A
 *    card that updates in between keeps its place until then; its "เพิ่ง
 *    อัปเดต" badge is what shows at once.
 *  * FRESH ("เพิ่งอัปเดต") for [freshMs] after an update.
 *  * COLLAPSED once a card has gone its own `collapseAfterMs` without an
 *    update. A collapsed card is a bar: its title and a one-line summary.
 *  * PINNED cards (an alarm ringing) are always open and always first.
 *  * TOUCH: tapping a bar opens it for [touchOpenMs] without counting as an
 *    update, so it does not jump to the top under the finger.
 *
 * FIXED ORDER (0.36.0, [fixedOrder]): the kiosk now keeps every card in its
 * registered place — weather, alarms, commodities, crypto — and news shows as
 * a card opening and its "เพิ่งอัปเดต" badge, never as a card moving. A
 * screen read at a glance from across a room is read by position; a price
 * that moves the weather down makes the person look for it (DESIGN.md, ก).
 * Pinned cards still come first. Per card, [Spec.alwaysOpen] keeps one open
 * whatever happens (the weather), and [Spec.openOnFirst] = false keeps one
 * folded until real news or a tap (crypto: its first prices are not news).
 */
class CardBoard(
    private val freshMs: Long = FRESH_MS,
    private val reorderGapMs: Long = REORDER_GAP_MS,
    private val touchOpenMs: Long = TOUCH_OPEN_MS,
    private val fixedOrder: Boolean = false,
) {

    /**
     * One card, as registered: its id, how long it stays open unchanged,
     * whether it is never folded, and whether its first report opens it.
     */
    class Spec(
        val id: String,
        val collapseAfterMs: Long,
        val alwaysOpen: Boolean = false,
        val openOnFirst: Boolean = true,
    )

    /** What the screen should do with one card right now. */
    data class Slot(val id: String, val open: Boolean, val fresh: Boolean)

    private class State(val spec: Spec, val rank: Int) {
        var signature: String? = null
        var changedAt: Long = Long.MIN_VALUE
        var openedByTouchAt: Long = Long.MIN_VALUE
        var pinned: Boolean = false
        var firstOnly: Boolean = true
    }

    private val cards = LinkedHashMap<String, State>()
    private var order: List<String> = emptyList()
    private var orderedAt: Long = Long.MIN_VALUE

    fun register(spec: Spec) {
        if (spec.id !in cards) {
            cards[spec.id] = State(spec, cards.size)
            order = order + spec.id
        }
    }

    /** A card's current content signature. Only a DIFFERENT one is an update. */
    fun report(id: String, signature: String, nowMs: Long) {
        val card = cards[id] ?: return
        if (card.signature == signature) return
        card.firstOnly = card.signature == null
        card.signature = signature
        card.changedAt = nowMs
    }

    fun touch(id: String, nowMs: Long) {
        cards[id]?.openedByTouchAt = nowMs
    }

    fun pin(id: String, pinned: Boolean) {
        cards[id]?.pinned = pinned
    }

    fun layout(nowMs: Long): List<Slot> {
        if (fixedOrder) {
            order = cards.values.sortedBy { it.rank }.map { it.spec.id }
        } else if (orderedAt == Long.MIN_VALUE || nowMs - orderedAt >= reorderGapMs) {
            order = cards.values
                .sortedWith(compareByDescending<State> { it.changedAt }.thenBy { it.rank })
                .map { it.spec.id }
            orderedAt = nowMs
        }
        // Pinned cards jump the queue at once: an alarm cannot wait a minute.
        val pinned = order.filter { cards.getValue(it).pinned }
        return (pinned + order.filterNot { it in pinned }).map { id ->
            val card = cards.getValue(id)
            val sinceChange = when {
                card.changedAt == Long.MIN_VALUE -> Long.MAX_VALUE
                // The first report is not news; a card that asks to wait for
                // news stays folded through it.
                card.firstOnly && !card.spec.openOnFirst -> Long.MAX_VALUE
                else -> nowMs - card.changedAt
            }
            val touched = card.openedByTouchAt != Long.MIN_VALUE &&
                nowMs - card.openedByTouchAt < touchOpenMs
            Slot(
                id = id,
                open = card.spec.alwaysOpen || card.pinned || touched ||
                    sinceChange < card.spec.collapseAfterMs,
                fresh = !card.firstOnly && sinceChange < freshMs,
            )
        }
    }

    companion object {
        const val MINUTE = 60_000L
        const val FRESH_MS = 10 * MINUTE
        const val REORDER_GAP_MS = MINUTE
        const val TOUCH_OPEN_MS = 2 * MINUTE
    }
}
