package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.Answer
import com.mammonrn.phoneaikiosk.voice.KioskAction
import com.mammonrn.phoneaikiosk.voice.MapsLauncher
import com.mammonrn.phoneaikiosk.voice.SpokenAudio
import com.mammonrn.phoneaikiosk.voice.TurnPipeline
import com.mammonrn.phoneaikiosk.voice.VoiceSink
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Somewhere to watch the transitions, as in TurnPipelineTest. */
private class ActionSink : VoiceSink {
    override var stt = "idle"
    override var chat = "idle"
    override var tts = "idle"
    override var heard = ""
    override var reply = ""
    override var lastError = ""
    val trail = mutableListOf<String>()
}

/**
 * The phase-4 action path, on the phone side.
 *
 * The broker validates the destination and is the enforcement. Everything here
 * is the second and third opinion — the ones that still hold if the broker is
 * newer, wrong, or not the broker at all.
 */
class MapsActionTest {

    // ------------------------------------------------------- the destination

    @Test
    fun `a place name becomes a geo search inside maps`() {
        val uri = MapsLauncher.geoUriFor("เซ็นทรัลเชียงราย")
        assertTrue("got $uri", uri!!.startsWith("geo:0,0?q="))
        // Thai is percent-encoded, not passed through raw.
        assertFalse(uri.contains("เซ็นทรัล"))
        assertTrue(uri.contains("%"))
    }

    @Test
    fun `a space becomes percent twenty and not a plus`() {
        // URLEncoder writes "+" for a space, which inside a geo: query is a
        // literal plus sign — so the kiosk would search for "a+b".
        val near = MapsLauncher.geoUriFor("ภูชี้ฟ้า", 20.04567 to 99.89123)!!
        // Near the kiosk, rounded to ~1 km, so Maps looks in Chiang Rai first.
        assertTrue(near, near.startsWith("geo:20.05,99.89?q="))
        assertTrue(MapsLauncher.geoUriFor("ภูชี้ฟ้า", 999.0 to 0.0)!!.startsWith("geo:0,0?q="))
        val uri = MapsLauncher.geoUriFor("Central World")!!
        assertTrue("got $uri", uri.contains("%20"))
        assertFalse("a raw + would be searched for literally: $uri", uri.contains("+"))
    }

    @Test
    fun `an ampersand cannot add a second query parameter`() {
        val uri = MapsLauncher.geoUriFor("A and B")!!
        // One "?" and no bare "&": the destination is data, not structure.
        assertEquals(1, uri.count { it == '?' })
        assertFalse(uri.contains("&"))
    }

    @Test
    fun `uris are refused rather than opened`() {
        for (attempt in listOf(
            "https://evil.example/x",
            "http://evil.example",
            "geo:13.7,100.5?q=x",
            // The one that matters most: intent: is how a crafted string
            // reaches a different application entirely.
            "intent://scan/#Intent;scheme=zxing;package=com.evil;end",
            "javascript:alert(1)",
            "file:///sdcard/secret",
            "content://settings/secure",
            "tel:0812345678",
            "market://details?id=com.evil",
        )) {
            assertNull("$attempt must not become a map search",
                       MapsLauncher.geoUriFor(attempt))
        }
    }

    @Test
    fun `control characters and empty destinations are refused`() {
        val newline = Char(10)
        val nul = Char(0)
        val tab = Char(9)
        for (attempt in listOf(
            "", "   ", "123", "...",
            "สยาม" + newline + "rm -rf /",
            "สยาม" + nul,
            tab.toString(),
        )) {
            assertNull("must be refused: [$attempt]", MapsLauncher.geoUriFor(attempt))
        }
    }

    @Test
    fun `a destination longer than a place name is refused`() {
        val longest = "ก".repeat(MapsLauncher.MAX_DESTINATION_CHARS)
        assertTrue(MapsLauncher.geoUriFor(longest) != null)
        assertNull(MapsLauncher.geoUriFor(longest + "ก"))
    }

    /**
     * The phone's limit must not be looser than the broker's, or the broker
     * becomes the only thing standing between a long string and an Intent.
     */
    @Test
    fun `the phone is no more permissive about length than the broker`() {
        // server/kiosk_broker/actions.py: MAX_DESTINATION_CHARS = 80
        assertEquals(80, MapsLauncher.MAX_DESTINATION_CHARS)
    }

    @Test
    fun `only google maps is ever named`() {
        assertEquals("com.google.android.apps.maps", MapsLauncher.MAPS_PACKAGE)
    }

    // ------------------------------------------------------------ the pipeline

    private fun pipeline(
        sink: ActionSink,
        answer: Answer,
        perform: (KioskAction) -> String?,
        sayLocally: (String) -> Boolean = { true },
        play: (ByteArray) -> Boolean = { true },
    ) = TurnPipeline(
        transcribe = { "พาไปเซ็นทรัล" },
        ask = { _, _ -> answer },
        speak = { SpokenAudio(ByteArray(100), "") },
        play = play,
        sayLocally = sayLocally,
        perform = perform,
        state = sink,
        log = { sink.trail.add(it) },
    )

    private fun wav() = ByteArray(TurnPipeline.WAV_HEADER_BYTES + 4000)

    @Test
    fun `an approved action is carried out before the answer is spoken`() {
        val sink = ActionSink()
        val order = mutableListOf<String>()
        val action = KioskAction(KioskAction.OPEN_MAPS, "เซ็นทรัลเชียงราย")

        val (outcome, _) = pipeline(
            sink,
            Answer("กำลังเปิดแผนที่ครับ", "c1", action),
            perform = { order.add("maps"); null },
            play = { order.add("speak"); true },
        ).run(wav(), null)

        assertEquals(TurnPipeline.Outcome.COMPLETED, outcome)
        // OPENED FIRST (0.66, Poom): "กำลังเปิดแผนที่" is said only once the map
        // opened; when it cannot, the reason is said instead.
        assertEquals(listOf("maps", "speak"), order)
    }

    @Test
    fun `a turn with no action performs nothing`() {
        val sink = ActionSink()
        var performed = false
        pipeline(
            sink,
            Answer("บ่ายโมงครับ", "c1", null),
            perform = { performed = true; null },
        ).run(wav(), null)
        assertFalse(performed)
    }

    @Test
    fun `an action that fails is said out loud and does not fail the turn`() {
        val sink = ActionSink()
        val spoken = mutableListOf<String>()
        val action = KioskAction(KioskAction.OPEN_MAPS, "เซ็นทรัล")

        val (outcome, _) = pipeline(
            sink,
            Answer("กำลังเปิดแผนที่ครับ", "c1", action),
            perform = { "เครื่องนี้ยังไม่มีแผนที่ครับ" },
            sayLocally = { spoken.add(it); true },
        ).run(wav(), null)

        // The reason is the reply itself (0.66): "กำลังเปิดแผนที่" is never said
        // for a map that did not open. The turn still completed.
        assertEquals(TurnPipeline.Outcome.COMPLETED, outcome)
        assertEquals("เครื่องนี้ยังไม่มีแผนที่ครับ", sink.reply)
        assertEquals(emptyList<String>(), spoken)
        assertEquals("action-failed", sink.lastError)
    }

    @Test
    fun `the log records the action type and never the destination`() {
        val sink = ActionSink()
        val action = KioskAction(KioskAction.OPEN_MAPS, "โรงพยาบาลมหาราช")
        pipeline(sink, Answer("กำลังเปิดแผนที่ครับ", "c1", action), perform = { null })
            .run(wav(), null)

        val logged = sink.trail.joinToString(" ")
        assertTrue("expected the type, got: $logged", logged.contains("action=open_maps"))
        assertFalse("the destination must not be logged: $logged",
                    logged.contains("โรงพยาบาลมหาราช"))
    }
}
