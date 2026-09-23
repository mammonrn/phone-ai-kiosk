package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.SpokenAudio
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The two ways an answer can be heard short, told apart in one log line.
 * 2026-09-23: 158 characters answered, one sentence heard, and the log could
 * not say whether the text was cut or the playback was.
 */
class SpeechLengthLogTest {

    @Test
    fun `a reply cut before synthesis says so`() {
        assertEquals(" spoken=79/158 cut=yes", Broker.spokenChars(158, 79, true))
        assertEquals(" spoken=21/21 cut=no", Broker.spokenChars(21, 21, false))
    }

    @Test
    fun `an older broker without the input count still reports what it can`() {
        assertEquals(" spoken=79 cut=yes", Broker.spokenChars(null, 79, true))
        assertEquals("", Broker.spokenChars(null, null, false))
    }

    @Test
    fun `playback that ran its length is full, one that stopped is early`() {
        assertEquals("full", SpokenAudio.played(playMs = 2549, audioMs = 2240))   // the real line
        assertEquals("full", SpokenAudio.played(playMs = 2100, audioMs = 2240))   // timer slack
        assertEquals("early", SpokenAudio.played(playMs = 900, audioMs = 2240))
        assertEquals("?", SpokenAudio.played(playMs = 900, audioMs = null))
    }
}
