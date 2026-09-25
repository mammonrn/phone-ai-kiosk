package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.radio.RadioVoice
import com.mammonrn.phoneaikiosk.radio.Station
import com.mammonrn.phoneaikiosk.timer.Countdown
import com.mammonrn.phoneaikiosk.timer.TimerVoice
import com.mammonrn.phoneaikiosk.voice.DONE_MARK
import com.mammonrn.phoneaikiosk.voice.isDoneWords
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The countdown and the radio by voice (0.63.0, Poom): done on the phone before
 * the reply, and what could not be done said in the phone's own words.
 */
class TimerRadioVoiceTest {

    private class Clock(override var countdown: Countdown = Countdown()) : TimerVoice.Deck {
        override var now = 1_000_000L
        val done = ArrayList<String>()
        override fun clear() { done.add("clear"); countdown = countdown.reset() }
        override fun start(ms: Long) {
            done.add("start $ms")
            countdown = countdown.withSet(ms).start(now, now)
        }
    }

    @Test fun `a length starts the countdown and the broker's words stand`() {
        val deck = Clock()
        assertNull(TimerVoice.perform("start", 300, deck))
        assertEquals(listOf("start 300000"), deck.done)
        assertEquals(Countdown.State.RUNNING, deck.countdown.state)
    }

    @Test fun `a new length replaces the one that was counting`() {
        val deck = Clock()
        TimerVoice.perform("start", 300, deck)
        assertNull(TimerVoice.perform("start", 60, deck))
        assertEquals(listOf("start 300000", "clear", "start 60000"), deck.done)
    }

    @Test fun `stop with nothing counting says so`() {
        val deck = Clock()
        assertEquals(TimerVoice.NOTHING, TimerVoice.perform("stop", 0, deck))
        TimerVoice.perform("start", 300, deck)
        assertNull(TimerVoice.perform("stop", 0, deck))
        assertEquals(Countdown.State.IDLE, deck.countdown.state)
    }

    @Test fun `status is the phone's own words about what is left`() {
        val deck = Clock()
        TimerVoice.perform("start", 300, deck)
        deck.now += 100_000
        val said = TimerVoice.perform("status", 0, deck)!!
        assertTrue(isDoneWords(said))
        assertEquals("เหลืออีก 3 นาที 20 วินาที ครับ", said.removePrefix(DONE_MARK))
        assertEquals(DONE_MARK + TimerVoice.NOTHING, TimerVoice.perform("status", 0, Clock()))
    }

    @Test fun `a length out of range is refused in words`() {
        assertEquals("ตั้งได้ตั้งแต่ 1 วินาทีถึง 24 ชั่วโมงครับ", TimerVoice.perform("start", 0, Clock()))
    }

    private val stations = listOf(
        Station("a", "Cool Fahrenheit 93", "https://x/a"), Station("b", "EFM 94", "https://x/b"),
        Station("c", "Green Wave 106.5", "https://x/c"), Station("d", "Hotwave (Chill Online)", "https://x/d"),
        Station("e", "ลูกทุ่งเน็ตเวิร์ค", "https://x/e"))

    private class Radio(override val stations: List<Station>, override var onAir: String? = null) : RadioVoice.Deck {
        override var last: String? = onAir
        val played = ArrayList<String>()
        override fun play(station: Station) { played.add(station.id); onAir = station.id; last = station.id }
        override fun stop() { played.add("stop"); onAir = null }
    }

    @Test fun `a station by its name, or part of it, and the real name said`() {
        val deck = Radio(stations)
        assertEquals(DONE_MARK + "เปิดวิทยุ EFM 94 ครับ", RadioVoice.perform("play", "efm", deck))
        assertEquals(DONE_MARK + "เปิดวิทยุ Hotwave (Chill Online) ครับ", RadioVoice.perform("play", "hot wave", deck))
        assertEquals(DONE_MARK + "เปิดวิทยุ ลูกทุ่งเน็ตเวิร์ค ครับ", RadioVoice.perform("play", "ลูกทุ่ง", deck))
        assertEquals(listOf("b", "d", "e"), deck.played)
    }

    @Test fun `no such station is said, and nothing plays`() {
        val deck = Radio(stations)
        assertEquals("ไม่พบสถานี “จส100” ในรายการวิทยุครับ", RadioVoice.perform("play", "จส100", deck))
        assertTrue(deck.played.isEmpty())
    }

    @Test fun `just the radio plays the last station, else the first`() {
        val deck = Radio(stations)
        RadioVoice.perform("play", "", deck)
        assertEquals("a", deck.played.last())
        deck.last = "c"; deck.onAir = null
        RadioVoice.perform("play", "", deck)
        assertEquals("c", deck.played.last())
    }

    @Test fun `next and previous go round the list, only while on`() {
        val deck = Radio(stations, onAir = "e")
        assertEquals(DONE_MARK + "สถานีถัดไป Cool Fahrenheit 93 ครับ", RadioVoice.perform("next", "", deck))
        assertEquals(DONE_MARK + "สถานีก่อนหน้า ลูกทุ่งเน็ตเวิร์ค ครับ", RadioVoice.perform("previous", "", deck))
        val off = Radio(stations)
        assertEquals(RadioVoice.NOT_ON, RadioVoice.perform("next", "", off))
        assertEquals(RadioVoice.NOT_ON, RadioVoice.perform("stop", "", off))
    }

    @Test fun `stop turns it off and the broker's words stand`() {
        val deck = Radio(stations, onAir = "a")
        assertNull(RadioVoice.perform("stop", "", deck))
        assertNull(deck.onAir)
    }

    @Test fun `an empty list says where to add stations`() {
        assertEquals(RadioVoice.NO_STATIONS, RadioVoice.perform("play", "efm", Radio(emptyList())))
    }
}
