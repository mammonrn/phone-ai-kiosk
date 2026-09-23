package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Broker
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * What a failed dashboard refresh writes to logcat.
 *
 * It used to write "Failure" and nothing else, which is how a kiosk sat on
 * "กำลังโหลด…" with no way to tell a 401 from a 404 from a 502 without
 * attaching a debugger. It must still never write the URL: the dashboard's
 * query string is the phone's position.
 */
class BrokerDescribeTest {

    @Test
    fun `a broker refusal says its status and code`() {
        val error = Broker.Failure(404, "not_found", "ไม่พบ")
        assertEquals("http 404 not_found", Broker.describe(error))
    }

    @Test
    fun `anything else says only its type`() {
        val error = java.net.UnknownHostException(
            "https://kiosk.example/v1/dashboard?lat=13.76&lon=100.50")
        val text = Broker.describe(error)
        assertEquals("UnknownHostException", text)
        assertFalse(text.contains("13.76"))
        assertFalse(text.contains("lat="))
    }

    @Test
    fun `nothing at all is still a line`() {
        assertEquals("unknown", Broker.describe(null))
    }
}
