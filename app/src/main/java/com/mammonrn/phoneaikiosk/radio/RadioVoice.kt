package com.mammonrn.phoneaikiosk.radio

import com.mammonrn.phoneaikiosk.media.MusicLibrary
import com.mammonrn.phoneaikiosk.voice.DONE_MARK

/**
 * A spoken radio command (0.63.0, Poom: "เปิดวิทยุ <สถานี>", "ปิดวิทยุ",
 * "สถานีถัดไป"), done on this phone BEFORE the reply is said. The broker
 * recognised the sentence in code (server/kiosk_broker/radio_cmd.py); only the
 * phone knows its stations, so it finds the one meant and says the real name.
 *
 * Finding a station: the same spoken name in the list (spaces and case aside),
 * then a name that contains it, then the closest by likeness (the music's
 * [MusicLibrary.likeness], ≥ [GUESS_MIN] and ahead of the next by
 * [GUESS_MARGIN]); two near ones are asked back, none is said to be none.
 *
 * [perform] returns null when done as the broker said, [DONE_MARK]-words when
 * done with the phone's truer words (the station's real name), or the words
 * to say instead. Plain Kotlin: RadioVoiceTest.
 */
object RadioVoice {

    val COMMANDS = setOf("play", "stop", "next", "previous")

    const val GUESS_MIN = 0.6
    const val GUESS_MARGIN = 0.15

    interface Deck {
        /** The stations in the list's order (starred first). */
        val stations: List<Station>
        /** The station playing or connecting now; null when the radio is off. */
        val onAir: String?
        /** The last station played, even when off; null when none ever was. */
        val last: String?
        fun play(station: Station)
        fun stop()
    }

    const val NOT_ON = "ตอนนี้ไม่ได้เปิดวิทยุอยู่ครับ"
    const val NO_STATIONS = "ยังไม่มีสถานีในรายการวิทยุครับ เพิ่มได้ในแอปวิทยุ"

    sealed class Found {
        data class One(val station: Station) : Found()
        data class Two(val a: Station, val b: Station) : Found()
        data object None : Found()
    }

    fun find(stations: List<Station>, spoken: String): Found {
        val want = squash(spoken)
        if (want.isEmpty()) return Found.None
        stations.firstOrNull { squash(it.name) == want }?.let { return Found.One(it) }
        val containing = stations.filter { squash(it.name).contains(want) }
        if (containing.size == 1) return Found.One(containing[0])
        val pool = containing.ifEmpty { stations }
        val ranked = pool.map { it to MusicLibrary.likeness(squash(it.name), want) }.sortedByDescending { it.second }
        val best = ranked.firstOrNull() ?: return Found.None
        if (best.second < GUESS_MIN && containing.isEmpty()) return Found.None
        val second = ranked.getOrNull(1)
        if (second != null && best.second - second.second < GUESS_MARGIN) return Found.Two(best.first, second.first)
        return Found.One(best.first)
    }

    fun perform(command: String, query: String, deck: Deck): String? {
        val list = deck.stations
        return when (command) {
            "play" -> {
                if (list.isEmpty()) return NO_STATIONS
                if (query.isBlank()) {
                    val station = list.firstOrNull { it.id == deck.last } ?: list.first()
                    deck.play(station)
                    return said("เปิดวิทยุ ${station.name} ครับ")
                }
                when (val found = find(list, query)) {
                    is Found.One -> { deck.play(found.station); said("เปิดวิทยุ ${found.station.name} ครับ") }
                    is Found.Two -> "หมายถึงสถานี “${found.a.name}” หรือ “${found.b.name}” ครับ"
                    Found.None -> "ไม่พบสถานี “${query.take(30)}” ในรายการวิทยุครับ"
                }
            }
            "stop" -> if (deck.onAir == null) NOT_ON else { deck.stop(); null }
            "next", "previous" -> {
                val on = deck.onAir ?: return NOT_ON
                if (list.isEmpty()) return NO_STATIONS
                val at = list.indexOfFirst { it.id == on }
                val step = if (command == "next") 1 else -1
                val station = list[Math.floorMod(at + step, list.size)]
                deck.play(station)
                said("${if (command == "next") "สถานีถัดไป" else "สถานีก่อนหน้า"} ${station.name} ครับ")
            }
            else -> null
        }
    }

    /** Done, in the phone's words (the station's real name). */
    private fun said(words: String) = DONE_MARK + words

    private fun squash(s: String) = s.lowercase().filter { !it.isWhitespace() }
}
