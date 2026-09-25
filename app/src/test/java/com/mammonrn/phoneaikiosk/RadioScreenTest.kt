package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.WakePause
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The radio's rules that live in its source (0.61.0, DESIGN.md 5ฏ): Jarvis rests
 * while it plays and is heard over it lowered, not stopped; one sound at a time
 * with the music and the video; no station in a log; sizes from UiScale; its
 * words in strings_radio.xml; its icons 16x16 squares.
 */
class RadioScreenTest {

    private fun file(path: String): File = listOf(File(path), File("app/$path")).first { it.exists() }
    private val src = "src/main/java/com/mammonrn/phoneaikiosk"
    private val service by lazy { file("$src/radio/RadioService.kt").readText() }
    private val screen by lazy { file("$src/radio/RadioActivity.kt").readText() }

    @Test
    fun `Jarvis rests while the radio plays, and says why`() {
        assertEquals("เล่นวิทยุ", WakePause.Source.RADIO.word)
        assertTrue("WakePause.hold(WakePause.Source.RADIO, this)" in service)
        assertTrue("WakePause.renew(" in service && "WakePause.release(" in service)
        assertTrue("WakePause.RENEW_MS" in service)
    }

    @Test
    fun `a question lowers the radio and the answer's end brings it back - never a pause`() {
        val quiet = service.substringAfter("override fun quietForJarvis()").substringBefore("override fun resumeAfterJarvis()")
        assertTrue("ducked = true" in quiet)
        assertFalse("pause" in quiet.lowercase().replace("wakepause", ""))
        assertFalse("stop" in quiet.lowercase())
        val resume = service.substringAfter("override fun resumeAfterJarvis()").substringBefore("// ----")
        assertTrue("ducked = false" in resume)
        assertTrue("if (ducked) DUCK else 1f" in service)
        assertTrue(Regex("""const val DUCK = 0\.1\d?f""").containsMatchIn(service))
    }

    @Test
    fun `one sound at a time with the music and the video`() {
        val play = service.substringAfter("fun play(context: Context, station: Station)").substringBefore("fun stop(")
        assertTrue("MusicPlayer.quietForVideo(context)" in play)
        assertTrue("VideoPlayer.quietForMusic(context)" in play)
        val music = file("$src/media/MusicService.kt").readText()
        val video = file("$src/media/VideoService.kt").readText()
        // Every place the music or a video starts, the radio stops.
        assertEquals(music.split("VideoPlayer.quietForMusic(context)").size - 1,
                     music.split("RadioPlayer.quietForMedia(context)").size - 1)
        assertEquals(video.split("MusicPlayer.quietForVideo(context)").size - 1,
                     video.split("RadioPlayer.quietForMedia(context)").size - 1)
    }

    @Test
    fun `audio focus is ExoPlayer's, and a failure never loops`() {
        assertTrue(Regex("""setAudioAttributes\([\s\S]*?, true\)""").containsMatchIn(service))
        assertTrue("setHandleAudioBecomingNoisy(true)" in service)
        assertTrue("DefaultLoadErrorHandlingPolicy(LOAD_RETRIES)" in service)
        assertTrue(Regex("""LOAD_RETRIES = [0-3]\b""").containsMatchIn(service))
        // A failure stops; nothing in the error path starts the station again.
        val onError = service.substringAfter("override fun onPlayerError").substringBefore("\n        })")
        assertFalse("start(" in onError)
        assertTrue("reprepared" in onError)
    }

    @Test
    fun `no station name, address or song goes to a log`() {
        val radio = file("$src/radio").listFiles()!!.filter { it.name.endsWith(".kt") }
        val logCall = Regex("""Log\.[diwe]\([^\n]*""")
        val banned = listOf("station.name", "stationName", ".url", "url)", "Title", "title", "words", "f.name", "inner")
        val bad = radio.flatMap { f ->
            logCall.findAll(f.readText()).map { it.value }
                .filter { line -> banned.any { b -> line.substringAfter(",").contains(b, ignoreCase = true) } }
                .filterNot { "number(station.id)" in it && "kind=" in it }
                .map { "${f.name}: $it" }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }

    @Test
    fun `the radio screen writes no size as a number`() {
        val literalDp = Regex("""\bdp\(\s*\d""")
        val literalTextSize = Regex("""textSize\s*=\s*(if\s*\([^)]*\)\s*)?\d""")
        val literalTextArg = Regex("""\btext\([^()]*(\([^()]*\))?[^()]*,\s*\d+(\.\d+)?f\b""")
        val bad = screen.lines().withIndex().filter { (_, line) ->
            val code = line.substringBefore("//")
            literalDp.containsMatchIn(code) || literalTextSize.containsMatchIn(code) || literalTextArg.containsMatchIn(code)
        }.map { "${it.index + 1}: ${it.value.trim()}" }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        // Heights given to views are a control's or an icon's.
        val height = Regex("""LayoutParams\([^,()]+(?:\([^()]*\))?,\s*(?:\w+\.)?dp\(UiScale\.(\w+)\)""")
        val allowed = setOf("TOUCH", "PRIMARY", "ROW", "ICON_S", "ICON_M", "ICON_L", "ICON_XL")
        val wrong = height.findAll(screen).map { it.groupValues[1] }.filter { it !in allowed }.toList()
        assertTrue(wrong.joinToString(), wrong.isEmpty())
        // Rows are at least a finger tall.
        assertTrue("minimumHeight = dp(UiScale.ROW)" in screen)
    }

    @Test
    fun `every string the radio uses is in strings_radio, and it is in the Control Panel`() {
        val strings = file("src/main/res/values/strings_radio.xml").readText()
        val used = Regex("""R\.string\.(radio_\w+|window_radio)""").findAll(screen + service)
            .map { it.groupValues[1] }.toSet()
        val missing = used.filter { "name=\"$it\"" !in strings }
        assertTrue(missing.joinToString(), missing.isEmpty())
        val main = file("src/main/res/values/strings.xml").readText()
        assertFalse("radio_ strings belong in strings_radio.xml", "name=\"radio_" in main)
        val panel = file("$src/settings/SettingsActivity.kt").readText()
        assertTrue("R.drawable.ic_pixel_radio" in panel && "RadioActivity::class.java" in panel)
        val manifest = file("src/main/AndroidManifest.xml").readText()
        assertTrue(".radio.RadioActivity" in manifest)
        assertTrue(Regex("""\.radio\.RadioService"[\s\S]{0,120}mediaPlayback""").containsMatchIn(manifest))
    }

    @Test
    fun `the radio icons are 16x16 squares in at most four colours`() {
        for (name in listOf("ic_pixel_radio", "ic_pixel_radio_light")) {
            val xml = file("src/main/res/drawable/$name.xml").readText()
            assertTrue(name, "android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
            val paths = Regex("""android:pathData="([^"]*)"""").findAll(xml).map { it.groupValues[1] }.toList()
            assertTrue(paths.isNotEmpty())
            for (p in paths) {
                val cleaned = p.replace(Regex("""\s+"""), "")
                assertTrue("$name: only squares: $cleaned", Regex("""^(M\d+,\d+h\d+v\d+h-\d+z)+$""").matches(cleaned))
                for (m in Regex("""M(\d+),(\d+)h(\d+)v(\d+)h-(\d+)z""").findAll(cleaned)) {
                    val (x, y, w, h, back) = m.destructured
                    assertEquals("$name: closes where it began", w, back)
                    assertTrue("$name: inside 16x16", x.toInt() + w.toInt() <= 16 && y.toInt() + h.toInt() <= 16)
                }
            }
            val colours = Regex("""android:fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
            assertTrue("$name: ${colours.size} colours", colours.size in 1..4)
        }
    }
}
