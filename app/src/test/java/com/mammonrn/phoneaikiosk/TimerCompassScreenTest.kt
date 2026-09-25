package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.UiScale
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The timer's and the compass's screens and wiring (0.62.0, DESIGN.md 5ณ, 5ด),
 * read from the source: every size from UiScale, their words in their own
 * strings files, their icons 16x16 squares, in the right Control Panel group,
 * the countdown living outside its screen and ringing the alarm's way, and
 * the sensors on only while the compass is in front.
 */
class TimerCompassScreenTest {

    private fun file(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }
    private fun src(path: String) = file("src/main/java/com/mammonrn/phoneaikiosk/$path").readText().replace("\r\n", "\n")
    private fun body(source: String, head: String) = source.substringAfter(head).substringBefore("\n    }\n")

    private val timer by lazy { src("timer/TimerActivity.kt") }
    private val clock by lazy { src("timer/TimerClock.kt") }
    private val compass by lazy { src("compass/CompassActivity.kt") }
    private val service by lazy { src("voice/VoiceService.kt") }

    @Test
    fun `every size is a name from UiScale`() {
        val literalDp = Regex("""\bdp\(\s*\d""")
        val literalTextSize = Regex("""textSize\s*=\s*(if\s*\([^)]*\)\s*)?\d""")
        val literalTextArg = Regex("""\btext\([^()]*(\([^()]*\))?[^()]*,\s*\d+(\.\d+)?f\b""")
        for (path in listOf("timer/TimerActivity.kt", "compass/CompassActivity.kt", "ui/ToolWindow.kt")) {
            val bad = src(path).lines().withIndex().filter { (_, line) ->
                val code = line.substringBefore("//")
                literalDp.containsMatchIn(code) || literalTextSize.containsMatchIn(code) ||
                    literalTextArg.containsMatchIn(code) || "setTextSize(" in code
            }.map { (n, line) -> "$path:${n + 1}: ${line.trim()}" }
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
    }

    @Test
    fun `the big read-out fits the A07's window`() {
        // 384dp wide, less the desktop frame, the window's inset and the read-out's padding;
        // Press Start 2P is 1em a character and the longest stopwatch time is ten ("10:00:00.0").
        val room = 384 - 2 * UiScale.FRAME - 2 * UiScale.WINDOW_INSET - 2 * UiScale.BEVEL - 2 * UiScale.SPACE_S
        assertTrue(UiScale.LCD_DIGITS * 10 <= room)
        assertTrue(UiScale.LCD_DIGITS > UiScale.TEXT_DISPLAY)
    }

    @Test
    fun `their words are in their own strings files`() {
        val timerWords = Regex("""R\.string\.(\w+)""").findAll(timer).map { it.groupValues[1] }.toSet()
        val compassWords = Regex("""R\.string\.(\w+)""").findAll(compass).map { it.groupValues[1] }.toSet()
        val timerXml = file("src/main/res/values/strings_timer.xml").readText()
        val compassXml = file("src/main/res/values/strings_compass.xml").readText()
        for (w in timerWords - "settings_home") assertTrue(w, "name=\"$w\"" in timerXml)
        for (w in compassWords) assertTrue(w, "name=\"$w\"" in compassXml)
    }

    @Test
    fun `the new icons are 16x16 squares with at most four colours`() {
        for (name in listOf("stopwatch", "compass")) {
            val xml = file("src/main/res/drawable/ic_pixel_$name.xml").readText()
            assertTrue(name, "android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
            val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
            assertTrue("$name has ${colours.size} colours", colours.size in 1..4)
            for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
                assertFalse("$name draws curves", Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
            }
        }
    }

    @Test
    fun `the Control Panel opens them - the timer beside the alarms, the compass with the tools`() {
        val panel = src("settings/SettingsActivity.kt")
        val home = panel.substringAfter("Group(R.string.settings_group_home").substringBefore("Group(R.string.settings_group_media")
        assertTrue(home.indexOf("window_alarms") < home.indexOf("timer.TimerActivity"))
        assertTrue("Origin.PANEL" in home.substringAfter("timer.TimerActivity").substringBefore("\n"))
        val tools = panel.substringAfter("Group(R.string.settings_group_tools").substringBefore("Group(R.string.settings_group_setup")
        assertTrue("compass.CompassActivity" in tools)
        val manifest = file("src/main/AndroidManifest.xml").readText()
        for (name in listOf(".timer.TimerActivity", ".compass.CompassActivity")) {
            val entry = manifest.substringAfter("android:name=\"$name\"").substringBefore("/>")
            assertTrue(name, "android:exported=\"false\"" in entry && "android:screenOrientation=\"portrait\"" in entry)
        }
        assertTrue("android:exported=\"false\"" in manifest.substringAfter("android:name=\".timer.TimerReceiver\"").substringBefore("/>"))
    }

    @Test
    fun `closing the timer's screen never stops a countdown - only a ringing end is seen`() {
        val onPause = body(timer, "override fun onPause()")
        assertTrue("if (isFinishing) stopRinging(" in onPause)
        for (head in listOf("override fun onPause()", "override fun onStop()", "override fun onDestroy()",
                            "private fun closeApp()", "private fun goHome()")) {
            val b = body(timer, head)
            assertFalse(head, "resetCountdown" in b || "pauseCountdown" in b || "resetStopwatch" in b || "pauseStopwatch" in b)
        }
        // The counting is the process's, kept on disk, and booked with AlarmManager.
        assertTrue("setAlarmClock(" in clock && "setExactAndAllowWhileIdle(" in clock && "ELAPSED_REALTIME_WAKEUP" in clock)
        assertTrue("TimerClock.restore(this)" in src("MainActivity.kt"))
    }

    @Test
    fun `the end rings the alarm's way - its ringer, the deaf wake word, its stop`() {
        val ring = body(service, "fun startTimerRing()")
        assertTrue("alarmRinger.start()" in ring)
        assertTrue("VoiceState.alarmRinging = " in ring)
        assertTrue("alarmTimeout" in ring)
        assertTrue("ScreenWaker.wakeIfAsleep(this)" in ring)
        // The wake word is deaf while anything rings (unchanged, shared with the alarms).
        assertTrue("WakeGate.deaf(busy, VoiceState.alarmRinging.isNotEmpty()" in service)
        // Stopping the sound by hand (the Jarvis button, the home card) counts as seen; the limit does not.
        assertTrue("if (reason != \"timeout\") com.mammonrn.phoneaikiosk.timer.TimerClock.acknowledge(this)" in body(service, "fun stopAlarm(reason: String)"))
        assertTrue("ACTION_TIMER_RING" in service.substringAfter("override fun onStartCommand"))
    }

    @Test
    fun `the compass's sensors are on only while it is in front`() {
        assertTrue("listen(true)" in body(compass, "override fun onResume()"))
        assertTrue("listen(false)" in body(compass, "override fun onPause()"))
        assertTrue("sensors.unregisterListener(this)" in body(compass, "private fun listen(on: Boolean)"))
        // Registered nowhere else.
        assertTrue(Regex("""\bregisterListener\(""").findAll(compass).count() == 1)
    }

    @Test
    fun `logs carry states and numbers only`() {
        for (source in listOf(timer, clock, compass)) {
            for (line in source.lines().filter { "Log.i(" in it || "Log.w(" in it }) {
                assertFalse(line, "heading" in line.substringAfter("Log."))
            }
        }
    }
}
