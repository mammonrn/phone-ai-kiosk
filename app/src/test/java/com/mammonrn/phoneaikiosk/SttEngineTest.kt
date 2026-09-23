package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.DeviceSttProbe
import com.mammonrn.phoneaikiosk.voice.VoiceState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which transcriber the phone asks for, and what it reports about its own. */
class SttEngineTest {

    @Test
    fun `no override means no header and the broker's default`() {
        assertNull(Broker.sttProviderHeader(null))
        assertNull(VoiceState.sttOverride)          // nothing set at start-up
    }

    @Test
    fun `only the broker's three transcribers are ever sent`() {
        assertEquals("groq", Broker.sttProviderHeader("groq"))
        assertEquals("groq-hints", Broker.sttProviderHeader(" Groq-Hints "))
        assertEquals("google", Broker.sttProviderHeader("google"))
        assertNull(Broker.sttProviderHeader("device"))
        assertNull(Broker.sttProviderHeader("whisper"))
        assertNull(Broker.sttProviderHeader(""))
    }

    @Test
    fun `the dump says what was asked for and what was used`() {
        val dump = VoiceState.dump()
        assertTrue(dump.contains("stt-engine : asked=broker default"))
        assertTrue(dump.contains("device-stt :"))
    }

    @Test
    fun `the device probe line says where thai stands`() {
        assertTrue(DeviceSttProbe.describe(listOf("th-TH"), emptyList(), emptyList())
            .contains("th-TH INSTALLED"))
        assertTrue(DeviceSttProbe.describe(emptyList(), listOf("th-TH", "en-US"), emptyList())
            .contains("supported, not downloaded"))
        assertTrue(DeviceSttProbe.describe(listOf("en-US"), emptyList(), listOf("th"))
            .contains("downloading"))
        assertTrue(DeviceSttProbe.describe(listOf("en-US"), listOf("en-GB"), emptyList())
            .contains("not offered"))
        assertTrue(DeviceSttProbe.describe(emptyList(), emptyList(), emptyList())
            .endsWith("NOT used for turns"))
    }
}
