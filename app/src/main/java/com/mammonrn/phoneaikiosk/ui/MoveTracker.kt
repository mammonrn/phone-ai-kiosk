package com.mammonrn.phoneaikiosk.ui

import kotlin.math.abs

/**
 * When a price window has NEWS: a move of at least [thresholdPct] percent
 * (Poom, 2026-09-23 — crypto 3%, gold and fuel 1%).
 *
 * WHAT IT IS MEASURED AGAINST: the prices at the window's LAST news — not the
 * last refresh (a slow drift of 0.1% a minute would never count) and not a
 * fixed clock window (the move is what matters, however long it took). The
 * first prices after a start are the first reference; they open the window
 * but, as CardBoard says, are not "เพิ่งอัปเดต". Measured at every dashboard
 * refresh, once a minute.
 *
 * Any one price crossing the line is news for the window, and then every
 * price becomes the new reference, so the next news is a further move from
 * what was on screen when this one was flagged. A price that appears for the
 * first time (a new coin in the top four) is taken as its reference quietly.
 *
 * Pure; CardBoard gets [generation] as the window's signature, which changes
 * exactly when there is news. See MoveTrackerTest.
 */
class MoveTracker(private val thresholdPct: Double) {

    private val reference = HashMap<String, Double>()

    /** Bumped on every piece of news. The card's signature. */
    var generation = 0
        private set

    /** Takes this refresh's prices; true when one moved enough to be news. */
    fun update(prices: Map<String, Double>): Boolean {
        val usable = prices.filterValues { !it.isNaN() && it > 0.0 }
        if (usable.isEmpty()) return false
        if (reference.isEmpty()) {
            reference.putAll(usable)
            generation++
            return true
        }
        var moved = false
        for ((name, price) in usable) {
            val was = reference[name]
            if (was == null) {
                reference[name] = price
                continue
            }
            if (abs(price - was) / was * 100.0 >= thresholdPct) moved = true
        }
        if (moved) {
            reference.clear()
            reference.putAll(usable)
            generation++
        }
        return moved
    }

    companion object {
        const val CRYPTO_PCT = 3.0
        const val COMMODITIES_PCT = 1.0
    }
}
