package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The voice recorder's screen and wiring (0.61.0, DESIGN.md 5ฐ), read from the
 * source: a delete only after the owner's face or pattern (5ฎ), every size from
 * UiScale (5ช), its words in its own strings file, its icons 16x16 squares,
 * reachable from the Control Panel, closing = stop and save, and the one
 * microphone shared rather than opened twice.
 */
class RecorderScreenTest {

    private fun file(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }
    private fun src(path: String) = file("src/main/java/com/mammonrn/phoneaikiosk/$path").readText().replace("\r\n", "\n")

    private val screen by lazy { src("recorder/RecorderActivity.kt") }

    @Test
    fun `a recording is deleted only after a pass`() {
        val calls = Regex("""runDelete\(file\)""").findAll(screen).map { m -> screen.substring(maxOf(0, m.range.first - 40), m.range.first) }.toList()
        assertTrue(calls.isNotEmpty())
        for (before in calls) assertTrue("runDelete outside the gate: $before", "afterPass = {" in before)
        assertTrue("IdentityGate.ask(this)" in screen && "if (IdentityGate.passed(data))" in screen)
        // The only delete of a file is inside runDelete.
        val deletes = Regex("""\.delete\(\)""").findAll(screen).count()
        assertEquals(1, deletes)
        assertTrue(screen.substringAfter("private fun runDelete(").substringBefore("\n    }\n").contains("file.delete()"))
    }

    @Test
    fun `every size is a name from UiScale`() {
        val literalDp = Regex("""\bdp\(\s*\d""")
        val literalTextSize = Regex("""textSize\s*=\s*(if\s*\([^)]*\)\s*)?\d""")
        val literalTextArg = Regex("""\btext\([^()]*(\([^()]*\))?[^()]*,\s*\d+(\.\d+)?f\b""")
        val bad = screen.lines().withIndex().filter { (_, line) ->
            val code = line.substringBefore("//")
            literalDp.containsMatchIn(code) || literalTextSize.containsMatchIn(code) ||
                literalTextArg.containsMatchIn(code) || "setTextSize(" in code
        }.map { (n, line) -> "${n + 1}: ${line.trim()}" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `leaving the screen stops and saves the recording`() {
        val onPause = screen.substringAfter("override fun onPause()").substringBefore("\n    }\n")
        assertTrue("if (isFinishing) VoiceMemo.stop(" in onPause)
        assertTrue("VoiceMemo.stop(" in screen.substringAfter("private fun goHome()").substringBefore("\n    }\n"))
        // The screen going dark (onStop) does not stop it.
        assertFalse("VoiceMemo.stop(" in screen.substringAfter("override fun onStop()").substringBefore("\n    }\n"))
    }

    @Test
    fun `one microphone - the recorder never opens a capture of its own`() {
        for (path in listOf("recorder/RecorderActivity.kt", "recorder/VoiceMemo.kt", "recorder/MemoWriter.kt", "recorder/RecorderRules.kt")) {
            val s = src(path)
            assertFalse(path, "AudioRecord" in s)
            assertFalse(path, "MediaRecorder" in s.lines().filterNot { it.trim().startsWith("*") || it.trim().startsWith("//") }.joinToString("\n"))
        }
        // The capture loop hands its frames on, after the turn check, in the frame callback.
        val service = src("voice/VoiceService.kt")
        val loop = service.substringAfter("recorder.listen(shouldStop").substringBefore("when (machine.onFrame(peak, fired))")
        assertTrue("MicTap.offer(frame, read, peak, busy)" in loop)
        assertTrue(loop.indexOf("val busy =") < loop.indexOf("MicTap.offer("))
    }

    @Test
    fun `Jarvis's card names the recorder, and the recorder rests Jarvis only through WakePause`() {
        assertTrue("RECORDER(\"บันทึกเสียง\")" in src("voice/WakePause.kt"))
        val memo = src("recorder/VoiceMemo.kt")
        assertTrue("override fun quietForJarvis() = stop(\"jarvis\")" in memo)
        assertTrue("WakePause.RENEW_MS" in memo)
        assertTrue("WakePause.hold(WakePause.Source.RECORDER" in src("recorder/RecorderRules.kt"))
    }

    @Test
    fun `from the Control Panel, as a screen of this app`() {
        val panel = src("settings/SettingsActivity.kt")
        assertTrue("ic_pixel_mic" in panel && "RecorderActivity::class.java" in panel)
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter(".recorder.RecorderActivity").substringBefore("/>")
        assertTrue("android:exported=\"false\"" in entry && "portrait" in entry)
    }

    @Test
    fun `its words are its own and formal, with no emoji`() {
        val strings = file("src/main/res/values/strings_recorder.xml").readText()
        val names = Regex("""R\.string\.((?:rec|window_recorder)[a-z_]*)""").findAll(screen + src("settings/SettingsActivity.kt"))
            .map { it.groupValues[1] }.toSet()
        assertTrue(names.size > 20)
        for (name in names) assertTrue("strings_recorder.xml has $name", "name=\"$name\"" in strings)
        assertFalse(strings.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() && it.code > 0x2000 && it != '›' })
        assertFalse(strings.any { Character.isSurrogate(it) })
        assertFalse("ครับ" in strings || "ค่ะ" in strings)
    }

    @Test
    fun `the new icons are 16x16 squares with at most four colours`() {
        for (name in listOf("mic", "record")) {
            val xml = file("src/main/res/drawable/ic_pixel_$name.xml").readText()
            assertTrue(name, "android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
            val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
            assertTrue("$name has ${colours.size} colours", colours.size in 1..4)
            for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
                assertFalse("$name draws curves", Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
            }
        }
    }
}
