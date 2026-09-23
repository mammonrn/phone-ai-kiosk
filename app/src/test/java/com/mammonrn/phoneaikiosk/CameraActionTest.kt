package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Answer
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.CameraAppLauncher
import com.mammonrn.phoneaikiosk.voice.KioskAction
import com.mammonrn.phoneaikiosk.voice.MapsLauncher
import com.mammonrn.phoneaikiosk.voice.SpokenAudio
import com.mammonrn.phoneaikiosk.voice.TurnPipeline
import com.mammonrn.phoneaikiosk.voice.VoiceSink
import java.io.File
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

private class CameraSink : VoiceSink {
    override var stt = "idle"
    override var chat = "idle"
    override var tts = "idle"
    override var heard = ""
    override var reply = ""
    override var lastError = ""
}

/**
 * "ขอดูกล้อง" opens Xiaomi Home — one package, named in three places that
 * must agree, and no argument that could steer it anywhere else.
 */
class CameraActionTest {

    // ------------------------------------------------------------ the package

    @Test
    fun `the package is the Play Store id of Xiaomi Home`() {
        // Checked on play.google.com, 2026-09-23. com.xiaomi.mihome is a 404.
        assertEquals("com.xiaomi.smarthome", CameraAppLauncher.PACKAGE)
    }

    private val manifest: String by lazy {
        File("src/main/AndroidManifest.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test
    fun `the manifest can see Xiaomi Home and nothing wider`() {
        assertTrue(Regex("""<package\s+android:name="${Regex.escape(CameraAppLauncher.PACKAGE)}"\s*/>""")
            .containsMatchIn(manifest))
        assertTrue("QUERY_ALL_PACKAGES must not be used",
                   !manifest.contains("QUERY_ALL_PACKAGES"))
        // Exactly the two named packages, no <intent> that would widen it.
        val queries = Regex("<queries>(.*?)</queries>", RegexOption.DOT_MATCHES_ALL)
            .find(manifest)!!.groupValues[1]
        // Maps, Xiaomi Home, and the settings app for the WiFi button (0.42.0).
        assertEquals(3, Regex("<package ").findAll(queries).count())
        assertTrue(!queries.contains("<intent"))
    }

    @Test
    fun `lock task allows exactly the kiosk, maps and xiaomi home`() {
        assertEquals(
            listOf("self", MapsLauncher.MAPS_PACKAGE, CameraAppLauncher.PACKAGE),
            LockTaskAllowlist.packages("self").toList(),
        )
    }

    // ------------------------------------------------------ what the phone reads

    @Test
    fun `the camera action is read with no argument at all`() {
        val action = Broker.parseAction(JSONObject("""{"type":"open_camera_app"}"""))
        assertNotNull(action)
        assertEquals(KioskAction.OPEN_CAMERA_APP, action!!.type)
        assertEquals("", action.destination)
    }

    @Test
    fun `anything smuggled beside the camera type is dropped`() {
        val action = Broker.parseAction(JSONObject(
            """{"type":"open_camera_app","destination":"com.android.settings",
                "package":"com.android.settings","url":"intent://x"}"""))
        assertEquals("", action!!.destination)
    }

    @Test
    fun `other actions are refused on the phone too`() {
        for (type in listOf("call_phone", "send_sms", "open_app", "launch", "set_light",
                            "OPEN_CAMERA_APP", "")) {
            assertNull(type, Broker.parseAction(JSONObject().put("type", type)
                .put("destination", "x")))
        }
        assertNull(Broker.parseAction(null))
    }

    @Test
    fun `maps still needs its destination`() {
        assertNull(Broker.parseAction(JSONObject("""{"type":"open_maps"}""")))
        assertNotNull(Broker.parseAction(JSONObject("""{"type":"open_maps","destination":"สยาม"}""")))
    }

    // --------------------------------------------------------------- the turn

    private fun wav() = ByteArray(TurnPipeline.WAV_HEADER_BYTES + 4000)

    private fun pipeline(sink: CameraSink, perform: (KioskAction) -> String?,
                         play: (ByteArray) -> Boolean, sayLocally: (String) -> Boolean) =
        TurnPipeline(
            transcribe = { "ขอดูกล้อง" },
            ask = { _, _ -> Answer("กำลังเปิดกล้องให้ครับ", "c1",
                                   KioskAction(KioskAction.OPEN_CAMERA_APP, "")) },
            speak = { SpokenAudio(ByteArray(100), "") },
            play = play,
            sayLocally = sayLocally,
            perform = perform,
            state = sink,
            log = {},
        )

    @Test
    fun `jarvis says it first, then the app opens`() {
        val order = mutableListOf<String>()
        pipeline(CameraSink(), perform = { order.add("open"); null },
                 play = { order.add("speak"); true }, sayLocally = { true }).run(wav(), null)
        assertEquals(listOf("speak", "open"), order)
    }

    @Test
    fun `no xiaomi home is said out loud, briefly`() {
        val spoken = mutableListOf<String>()
        val failure = CameraAppLauncher.spokenFailure(CameraAppLauncher.Result.NOT_INSTALLED)
        val (outcome, _) = pipeline(CameraSink(), perform = { failure },
                                    play = { true }, sayLocally = { spoken.add(it); true })
            .run(wav(), null)
        assertEquals(TurnPipeline.Outcome.COMPLETED, outcome)
        assertEquals(listOf(failure), spoken)
        assertTrue(failure.length < 60)
    }
}
