package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The notes screen and its wiring (0.62.0, DESIGN.md 5ต), read from the
 * source: every size from UiScale (5ช), no identity check for a line or a
 * list (nobody's file is deleted), a whole list only after "แน่ใจไหม", done
 * shown as a shape, its words in its own strings file, its icon 16x16 squares,
 * reachable from the Control Panel's first group, and no line's words in a log.
 */
class NotesScreenTest {

    private fun file(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }
    private fun src(path: String) = file("src/main/java/com/mammonrn/phoneaikiosk/$path").readText().replace("\r\n", "\n")

    private val screen by lazy { src("notes/NotesActivity.kt") }

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
    fun `a line or a list goes without an identity check, a whole list only after are-you-sure`() {
        assertFalse("IdentityGate" in screen)
        // The one call that deletes a list sits behind the in-place question.
        val calls = Regex("""deleteList\(list\)""").findAll(screen).toList()
        assertTrue(calls.size == 1)
        val before = screen.substring(maxOf(0, calls[0].range.first - 400), calls[0].range.first)
        assertTrue("notes_delete_list_ask" in before && "confirmingDelete" in screen)
        // Built-in lists cannot be deleted by the model either (NoteBookTest), and the screen offers no tools for them.
        assertTrue("if (!list.builtIn) listTools(col, list)" in screen)
    }

    @Test
    fun `done is a shape - the ticked box and a line through the words`() {
        assertTrue("ic_pixel_check_on" in screen && "ic_pixel_check_off" in screen)
        assertTrue("Paint.STRIKE_THRU_TEXT_FLAG" in screen)
    }

    @Test
    fun `no line's words and no list's name reach a log`() {
        for (path in listOf("notes/NotesActivity.kt", "notes/NoteStore.kt", "notes/NoteVoice.kt", "notes/NoteBook.kt")) {
            for (line in src(path).lines().filter { "Log." in it }) {
                assertFalse("$path: $line", Regex("""\$\{?(text|item|name|list\.name|typed|t)\b""").containsMatchIn(line))
            }
        }
        val service = src("voice/VoiceService.kt").substringAfter("private fun note(action: KioskAction)").substringBefore("\n    }\n")
        for (line in service.lines().filter { "Log." in it }) assertFalse(line, "params[\"text\"]" in line)
    }

    @Test
    fun `from the Control Panel's first group, as a screen of this app`() {
        val panel = src("settings/SettingsActivity.kt")
        val home = panel.substringAfter("Group(R.string.settings_group_home").substringBefore("Group(R.string.settings_group_media")
        assertTrue("ic_pixel_note" in home && "NotesActivity::class.java" in home && "Origin.PANEL" in home)
        val manifest = file("src/main/AndroidManifest.xml").readText()
        val entry = manifest.substringAfter(".notes.NotesActivity").substringBefore("/>")
        assertTrue("android:exported=\"false\"" in entry && "portrait" in entry)
    }

    @Test
    fun `its words are its own and formal, with no emoji`() {
        val strings = file("src/main/res/values/strings_notes.xml").readText()
        val names = Regex("""R\.string\.((?:notes|window_notes)[a-z_]*)""").findAll(screen + src("settings/SettingsActivity.kt"))
            .map { it.groupValues[1] }.toSet()
        assertTrue(names.size > 30)
        for (name in names) assertTrue("strings_notes.xml has $name", "name=\"$name\"" in strings)
        assertFalse(strings.any { Character.getType(it) == Character.OTHER_SYMBOL.toInt() && it.code > 0x2000 && it != '◄' })
        assertFalse(strings.any { Character.isSurrogate(it) })
        assertFalse("ครับ" in strings || "ค่ะ" in strings)
        // An unescaped " in an Android string is swallowed: the quotes are “ ”.
        for (line in strings.lines().filter { "<string " in it }) {
            assertFalse(line, "\"" in line.substringAfter(">").substringBefore("</string>"))
        }
    }

    @Test
    fun `the icon is 16x16 squares with at most four colours`() {
        val xml = file("src/main/res/drawable/ic_pixel_note.xml").readText()
        assertTrue("android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
        val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
        assertTrue("${colours.size} colours", colours.size in 1..4)
        for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
            assertFalse("draws curves", Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
        }
    }
}
