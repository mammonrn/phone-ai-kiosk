package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.DashboardState
import com.mammonrn.phoneaikiosk.voice.WakeGate
import com.mammonrn.phoneaikiosk.voice.WakePause
import com.mammonrn.phoneaikiosk.voice.WakePause.Source
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * Jarvis rests while media plays (Poom, 2026-09-24): the wake word off, the
 * card saying why, the button still working, media quieted for a button
 * question and resumed after, and the wake word back on its own when the media
 * stops — by release, by a lapsed lease, or by the app restarting.
 */
class WakePauseTest {

    private var now = 1_000L
    private val calls = ArrayList<String>()
    private val posted = ArrayList<() -> Unit>()

    private fun player(name: String) = object : WakePause.Media {
        override fun quietForJarvis() { calls += "$name quiet" }
        override fun resumeAfterJarvis() { calls += "$name resume" }
    }

    @Before
    fun setUp() {
        WakePause.reset()
        WakePause.clock = { now }
        WakePause.post = { it() }
        WakePause.log = {}
    }

    @After
    fun tearDown() {
        WakePause.reset()
        WakePause.post = { it() }
    }

    // ------------------------------------------------ on, off, and why

    @Test
    fun `nothing playing, the wake word listens`() {
        assertFalse(WakePause.paused())
        assertNull(WakePause.reason())
    }

    @Test
    fun `music playing rests Jarvis, stopping it listens again`() {
        val hold = WakePause.hold(Source.MUSIC, player("music"))
        assertTrue(WakePause.paused())
        assertEquals(Source.MUSIC, WakePause.reason())
        WakePause.release(hold)
        assertFalse(WakePause.paused())
        assertNull(WakePause.reason())
    }

    @Test
    fun `two players, the wake word waits for both, and the card names the newest`() {
        val music = WakePause.hold(Source.MUSIC)
        val video = WakePause.hold(Source.VIDEO)
        assertEquals(Source.VIDEO, WakePause.reason())
        WakePause.release(video)
        assertTrue(WakePause.paused())
        assertEquals(Source.MUSIC, WakePause.reason())
        WakePause.release(music)
        assertFalse(WakePause.paused())
    }

    @Test
    fun `releasing twice is harmless`() {
        val hold = WakePause.hold(Source.MUSIC)
        WakePause.release(hold)
        WakePause.release(hold)
        assertFalse(WakePause.paused())
    }

    @Test
    fun `the words on the card`() {
        assertEquals("เล่นเพลง", Source.MUSIC.word)
        assertEquals("เล่นวิดีโอ", Source.VIDEO.word)
    }

    // ------------------------------------------------ the lease

    @Test
    fun `a player that dies without releasing lapses after the lease`() {
        WakePause.hold(Source.MUSIC)
        now += WakePause.LEASE_MS - 1
        assertTrue(WakePause.paused())
        now += 1
        assertFalse(WakePause.paused())
    }

    @Test
    fun `a player that renews keeps Jarvis resting for as long as it plays`() {
        val hold = WakePause.hold(Source.VIDEO)
        repeat(20) {                          // ten minutes of a film
            now += WakePause.RENEW_MS
            assertTrue(WakePause.renew(hold))
            assertTrue(WakePause.paused())
        }
    }

    @Test
    fun `renewing a lapsed or released hold says so`() {
        val lapsed = WakePause.hold(Source.MUSIC)
        now += WakePause.LEASE_MS
        assertFalse(WakePause.renew(lapsed))
        val released = WakePause.hold(Source.MUSIC)
        WakePause.release(released)
        assertFalse(WakePause.renew(released))
        assertFalse(WakePause.paused())
    }

    @Test
    fun `three renewals fit in one lease`() {
        assertTrue(WakePause.RENEW_MS * 3 <= WakePause.LEASE_MS)
    }

    // ------------------------------------------------ restart

    @Test
    fun `an app restart always starts listening`() {
        WakePause.hold(Source.MUSIC, player("music"))
        WakePause.turnStarted()
        // What VoiceService.onCreate does in the new process.
        WakePause.reset()
        assertFalse(WakePause.paused())
        // And no leftover turn: the next turnEnded resumes nothing.
        WakePause.turnEnded()
        assertEquals(listOf("music quiet"), calls)
    }

    @Test
    fun `the service resets the pause when it is created, and nothing is stored`() {
        val service = source("voice/VoiceService.kt")
        val onCreate = service.substringAfter("override fun onCreate()").substringBefore("override fun onStartCommand")
        assertTrue("VoiceService.onCreate must call WakePause.reset()", "WakePause.reset()" in onCreate)
        val pause = source("voice/WakePause.kt")
        for (storage in listOf("SharedPreferences", "java.io.File", "FileOutputStream", "getSharedPreferences")) {
            assertFalse("WakePause must stay in memory only ($storage)", storage in pause)
        }
    }

    // ------------------------------------------------ a question over the music

    @Test
    fun `a button question quiets the music, the answer done resumes it`() {
        WakePause.hold(Source.MUSIC, player("music"))
        WakePause.turnStarted()
        assertEquals(listOf("music quiet"), calls)
        // Still resting while the question is asked.
        assertTrue(WakePause.paused())
        WakePause.turnEnded()
        assertEquals(listOf("music quiet", "music resume"), calls)
        assertTrue(WakePause.paused())
    }

    @Test
    fun `media stopped during the question is not started again`() {
        val hold = WakePause.hold(Source.MUSIC, player("music"))
        WakePause.turnStarted()
        WakePause.release(hold)
        WakePause.turnEnded()
        assertEquals(listOf("music quiet"), calls)
        assertFalse(WakePause.paused())
    }

    @Test
    fun `media started during a question is quiet at once and plays after`() {
        WakePause.turnStarted()
        WakePause.hold(Source.VIDEO, player("video"))
        assertEquals(listOf("video quiet"), calls)
        WakePause.turnEnded()
        assertEquals(listOf("video quiet", "video resume"), calls)
    }

    @Test
    fun `a turn ended twice, or never started, resumes nothing twice`() {
        WakePause.hold(Source.MUSIC, player("music"))
        WakePause.turnEnded()
        assertEquals(emptyList<String>(), calls)
        WakePause.turnStarted()
        WakePause.turnStarted()
        WakePause.turnEnded()
        WakePause.turnEnded()
        assertEquals(listOf("music quiet", "music resume"), calls)
    }

    @Test
    fun `a player with no controls still rests Jarvis and is never called`() {
        WakePause.hold(Source.MUSIC, null)
        WakePause.turnStarted()
        WakePause.turnEnded()
        assertTrue(WakePause.paused())
        assertEquals(emptyList<String>(), calls)
    }

    @Test
    fun `players are called through post, the main thread on the phone`() {
        WakePause.post = { posted += it }
        WakePause.hold(Source.MUSIC, player("music"))
        WakePause.turnStarted()
        assertEquals(emptyList<String>(), calls)
        posted.forEach { it() }
        assertEquals(listOf("music quiet"), calls)
    }

    // ------------------------------------------------ the button always works

    @Test
    fun `media playing makes the detector deaf`() {
        assertTrue(WakeGate.deaf(turnBusy = false, alarmRinging = false, now = 10, hearingFrom = 0,
                                 mediaPlaying = true))
        assertFalse(WakeGate.deaf(turnBusy = false, alarmRinging = false, now = 10, hearingFrom = 0,
                                  mediaPlaying = false))
        // The older reasons are unchanged.
        assertTrue(WakeGate.deaf(true, false, 10, 0, false))
        assertTrue(WakeGate.deaf(false, true, 10, 0, false))
        assertTrue(WakeGate.deaf(false, false, 10, 11, false))
    }

    @Test
    fun `the Jarvis button does not look at the pause at all`() {
        // Its only inputs are the wake-only test mode and a listening machine.
        assertTrue(WakeGate.buttonMayStart(wakeOnly = false, machineListening = true))
        assertFalse(WakeGate.buttonMayStart(wakeOnly = false, machineListening = false))
        assertFalse(WakeGate.buttonMayStart(wakeOnly = true, machineListening = true))
        val service = source("voice/VoiceService.kt")
        val button = service.substringAfter("intent?.action == ACTION_BUTTON_LISTEN) {")
            .substringBefore("ACTION_RETURN_HOME")
        assertTrue("the button path decides with WakeGate.buttonMayStart", "WakeGate.buttonMayStart" in button)
        assertFalse("the button path must not consult the pause", "WakePause" in button)
    }

    @Test
    fun `the capture loop asks the pause, quiets on start and resumes on every end`() {
        val service = source("voice/VoiceService.kt")
        assertTrue("WakePause.paused()" in service.substringAfter("val deaf = WakeGate.deaf("))
        assertTrue("WakePause.turnStarted()" in service.substringAfter("CaptureMachine.Step.STARTED ->")
            .substringBefore("CaptureMachine.Step.CAPTURING"))
        assertTrue("WakePause.turnEnded()" in service.substringAfter("CaptureMachine.Step.CANCELLED ->")
            .substringBefore("CaptureMachine.Step.FINISHED"))
        val finally = service.substringAfter("private fun runTurn(").substringAfter("} finally {")
            .substringBefore("override fun dump(")
        assertTrue("runTurn's finally resumes the media", "WakePause.turnEnded()" in finally)
    }

    // ------------------------------------------------ the Jarvis card

    private fun card(stt: String = "idle", chat: String = "idle", tts: String = "idle", resting: String? = null) =
        DashboardState.jarvisState("open", stt, chat, tts, "ready", "listening", "thinking", "speaking",
                                   "offline", resting)

    @Test
    fun `the card says Jarvis rests while media plays, and what it is doing during a question`() {
        assertEquals("ready", card())
        assertEquals("จาร์วิส · พักระหว่างเล่นเพลง", card(resting = "จาร์วิส · พักระหว่างเล่นเพลง"))
        assertEquals("listening", card(stt = "recording", resting = "rest"))
        assertEquals("thinking", card(chat = "asking", resting = "rest"))
        assertEquals("speaking", card(tts = "speaking", resting = "rest"))
    }

    @Test
    fun `the words name the reason and the way to ask`() {
        val strings = file("src/main/res/values/strings.xml")
        val title = Regex("""name="jarvis_resting">([^<]+)<""").find(strings)!!.groupValues[1]
        val prompt = Regex("""name="kiosk_prompt_resting">([^<]+)<""").find(strings)!!.groupValues[1]
        assertTrue(title.startsWith("จาร์วิส · พัก") && "%1\$s" in title)
        assertTrue("%1\$s" in prompt && "ไม่ฟังคำปลุก" in prompt && "ปุ่มจาร์วิส" in prompt)
        // Formal written Thai (ux-ui-design): a request starts with กรุณา.
        assertTrue("กรุณา" in prompt)
        for (spoken in listOf("ได้เลย", "นะ", "เอง")) assertFalse(spoken in prompt)
    }

    private fun source(path: String) = file("src/main/java/com/mammonrn/phoneaikiosk/$path")

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()
}
