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

    /**
     * Jump to [index] in [tracks] (a tap on the list). With shuffle on, the
     * tapped song starts a new order and every other song follows it once
     * (0.60.0: jumping into the old order left only its tail to play, and the
     * music stopped early — Poom on the A07).
     */
    fun jumpTo(index: Int): Track? {
        if (index !in tracks.indices) return null
        if (shuffle) { order = shuffledFrom(index); pos = 0 } else pos = order.indexOf(index)
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

    // ------------------------------------------------------------ editing the list (0.55.0)
    //
    // The track playing stays the track playing through every edit below, so
    // editing the list never changes what is heard — unless it is removed.

    /** Adds [more] to the end; in shuffle they join the order after what is left to play. */
    fun add(more: List<Track>) {
        if (more.isEmpty()) return
        val from = tracks.size
        tracks = tracks + more
        val added = (from until tracks.size).toList().let { if (shuffle) it.shuffled(random) else it }
        order = order + added.toIntArray()
        // Nothing was playing (an empty list): start at the first song added, and with
        // shuffle on put it first in the order, so none of the others is left out
        // (0.60.0: it started where it landed in the shuffle, and only the tail played).
        if (pos < 0) {
            if (shuffle) { order = shuffledFrom(from); pos = 0 } else pos = order.indexOf(from)
        }
    }

    /**
     * Removes the tracks at [indices] (in [tracks]). Returns true when the one
     * playing was among them: the caller then loads [current], which is the
     * next one still in the list (or null when the list is empty).
     */
    fun remove(indices: Set<Int>): Boolean {
        val gone = indices.filter { it in tracks.indices }.toSet()
        if (gone.isEmpty()) return false
        val playing = currentIndex
        val removedPlaying = playing in gone
        // What plays after: the playing track, or the first one after it in the order that stays.
        val keep = if (!removedPlaying) playing
                   else order.drop(pos.coerceAtLeast(0)).firstOrNull { it !in gone } ?: -1
        val newIndex = IntArray(tracks.size) { -1 }
        var n = 0
        for (i in tracks.indices) if (i !in gone) newIndex[i] = n++
        tracks = tracks.filterIndexed { i, _ -> i !in gone }
        order = order.filter { it !in gone }.map { newIndex[it] }.toIntArray()
        // Nothing after it: back to the first (or nothing, when the list is empty).
        pos = when {
            keep >= 0 -> order.indexOf(newIndex[keep])
            order.isEmpty() -> -1
            playing < 0 -> -1
            else -> 0
        }
        return removedPlaying
    }

    /** The same track with something learned (its length), in its place. */
    fun replace(index: Int, track: Track) {
        if (index in tracks.indices) tracks = tracks.toMutableList().also { it[index] = track }
    }

    fun clear() {
        tracks = emptyList(); order = IntArray(0); pos = -1
    }

    /** Sorts the list shown by [by]; the order played follows unless shuffle is on. */
    fun sort(by: Comparator<Track>) {
        if (tracks.isEmpty()) return
        val playing = current
        val sorted = tracks.withIndex().sortedWith { a, b -> by.compare(a.value, b.value) }
        val newIndex = IntArray(tracks.size).also { m -> sorted.forEachIndexed { n, iv -> m[iv.index] = n } }
        tracks = sorted.map { it.value }
        order = if (shuffle) order.map { newIndex[it] }.toIntArray() else IntArray(tracks.size) { it }
        pos = if (playing == null) pos else order.indexOf(tracks.indexOf(playing))
    }

    /** Back as it was saved: the list, the track, shuffle and repeat. */
    fun restore(list: List<Track>, index: Int, shuffleOn: Boolean, repeatMode: Repeat) {
        shuffle = false
        repeat = repeatMode
        set(list, index)
        if (list.isEmpty()) return
        if (shuffleOn) setShuffle(true)
    }

    // ------------------------------------------------------------ one file on its own (0.59.0)

    /** Everything the list is: its tracks, the play order, the place in it, shuffle and repeat. */
    class Snapshot internal constructor(
        val tracks: List<Track>,
        internal val order: IntArray,
        internal val pos: Int,
        val shuffle: Boolean,
        val repeat: Repeat,
    ) {
        val current: Track? get() = if (pos in order.indices) tracks[order[pos]] else null
        /** Where [current] is in [tracks]: right even when a song is in the list twice. */
        val currentIndex: Int get() = if (pos in order.indices) order[pos] else -1
    }

    fun snapshot() = Snapshot(tracks, order.copyOf(), pos, shuffle, repeat)

    /** Exactly as [s] was — the same shuffled order too, not a new one. */
    fun restore(s: Snapshot) {
        tracks = s.tracks; order = s.order.copyOf(); pos = s.pos; shuffle = s.shuffle; repeat = s.repeat
    }

    /** The play order from the current track on, for the list on screen. */
    fun upcoming(): List<Track> = if (pos < 0) emptyList() else order.drop(pos).map { tracks[it] }

    private fun shuffledFrom(first: Int): IntArray {
        val rest = (tracks.indices).filter { it != first }.shuffled(random)
        return (listOf(first) + rest).toIntArray()
    }
}
