package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.KioskAction
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Round 2A: the broker's "verify first" action reaches the service intact. */
class VerifyIdentityActionTest {

    @Test
    fun `the broker's verify_identity action is understood and carries nothing`() {
        val action = Broker.parseAction(JSONObject("""{"type":"verify_identity","destination":"x"}"""))
        assertEquals(KioskAction.VERIFY_IDENTITY, action?.type)
        assertEquals("", action?.destination)
    }

    @Test
    fun `the service opens the identity check for it and resumes after a pass`() {
        val service = File("src/main/java/com/mammonrn/phoneaikiosk/voice/VoiceService.kt").readText()
        assertTrue(service.contains("KioskAction.VERIFY_IDENTITY) return verifyForPrivate()"))
        assertTrue(service.contains("ACTION_AUTH_PASSED"))
        val verify = File("src/main/java/com/mammonrn/phoneaikiosk/auth/VerifyActivity.kt").readText()
        assertTrue("only a check opened for a private question resumes one",
                   verify.contains("getBooleanExtra(EXTRA_FOR_PRIVATE, false)"))
    }
}
