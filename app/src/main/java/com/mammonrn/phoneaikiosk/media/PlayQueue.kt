package com.mammonrn.phoneaikiosk.media

import kotlin.random.Random

/**
 * What plays next (0.53.0, Poom): the list, the place in it, shuffle and
 * repeat. Plain Kotlin — PlayQueueTest drives every case.
 *
 *  * PLAYS ON: at the end of a track the next one starts; at the end of the
 *    list it stops, unless repeat is ALL (back to the first) or ONE (the same
 *    track again).
 *  * SHUFFLE is an order over the same list, made when it is switched on; the
 *    track playing stays playing and comes first in the new order, so
 *    switching shuffle never jumps. Switching it off goes back to list order
 *    from the track playing.
 *  * "ถัดไป" pressed by hand at the end of the list with repeat OFF goes to the
 *    first track (a person asked for another song); only the automatic
 *    end-of-track stops there.
 */
class PlayQueue(private val random: Random = Random.Default) {

    enum class Repeat { OFF, ALL, ONE }

    var tracks: List<Track> = emptyList()
        private set
    private var order: IntArray = IntArray(0)
    private var pos = -1

    var shuffle = false
        private set
    var repeat = Repeat.OFF

    val current: Track? get() = if (pos in order.indices) tracks[order[pos]] else null

    /** Where the current track is in [tracks] (the list shown), or -1. */
    val currentIndex: Int get() = if (pos in order.indices) order[pos] else -1

    val isEmpty: Boolean get() = tracks.isEmpty()

    /** A new list, starting at [start] (an index in [list]). */
    fun set(list: List<Track>, start: Int = 0) {
        tracks = list
        if (list.isEmpty()) { order = IntArray(0); pos = -1; return }
        val first = start.coerceIn(0, list.size - 1)
        order = if (shuffle) shuffledFrom(first) else IntArray(list.size) { it }
        pos = if (shuffle) 0 else first
    }

    /** Jump to [index] in [tracks] (a tap on the list). */
    fun jumpTo(index: Int): Track? {
        if (index !in tracks.indices) return null
        pos = order.indexOf(index)
        return current
    }

    /**
     * The next track. [auto]: the last one finished by itself, so repeat ONE
     * plays it again and the end of the list with repeat OFF is the end.
     */
    fun next(auto: Boolean): Track? {
        if (order.isEmpty()) return null
        if (auto && repeat == Repeat.ONE) return current
        if (pos + 1 < order.size) { pos += 1; return current }
        if (auto && repeat == Repeat.OFF) return null
        pos = 0
        return current
    }

    /** The track before, wrapping to the last. */
    fun previous(): Track? {
        if (order.isEmpty()) return null
        pos = if (pos - 1 >= 0) pos - 1 else order.size - 1
        return current
    }

    fun setShuffle(on: Boolean) {
        if (on == shuffle) return
        shuffle = on
        if (tracks.isEmpty()) return
        val playing = currentIndex.coerceAtLeast(0)
        if (on) { order = shuffledFrom(playing); pos = 0 }
        else { order = IntArray(tracks.size) { it }; pos = playing }
    }

    /** OFF → ALL → ONE → OFF, the order Winamp's button goes round. */
    fun cycleRepeat(): Repeat {
        repeat = Repeat.entries[(repeat.ordinal + 1) % Repeat.entries.size]
        return repeat
    }

    /** The play order from the current track on, for the list on screen. */
    fun upcoming(): List<Track> = if (pos < 0) emptyList() else order.drop(pos).map { tracks[it] }

    private fun shuffledFrom(first: Int): IntArray {
        val rest = (tracks.indices).filter { it != first }.shuffled(random)
        return (listOf(first) + rest).toIntArray()
    }
}
