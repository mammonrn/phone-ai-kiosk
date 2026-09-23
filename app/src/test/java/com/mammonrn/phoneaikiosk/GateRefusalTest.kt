package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.FadingLine
import com.mammonrn.phoneaikiosk.voice.Broker
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Test

/** The broker's speech gate, as the phone reads it (2026-09-23). */
class GateRefusalTest {

    @Test
    fun `a refusal is read as its reason and doubts`() {
        val gate = JSONObject("""{"pass":false,"reason":"not-a-question","doubts":["weak-wake","no-ask"]}""")
        assertEquals("not-a-question (weak-wake,no-ask)", Broker.gateRefusal(gate))
    }

    @Test
    fun `a pass, or a broker older than the gate, is no refusal`() {
        assertEquals("", Broker.gateRefusal(JSONObject("""{"pass":true,"reason":"question"}""")))
        assertEquals("", Broker.gateRefusal(null))
    }

    @Test
    fun `the not-heard notice goes after a few seconds, not a minute`() {
        val line = FadingLine()
        assertEquals("x", line.visible("x", 0, false, FadingLine.NOTICE_HOLD_MS))
        assertEquals("x", line.visible("x", 4_000, false, FadingLine.NOTICE_HOLD_MS))
        assertEquals("", line.visible("x", 5_000, false, FadingLine.NOTICE_HOLD_MS))
    }
}
