package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.ui.CardBoard
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * The music player (0.53.0), checked from its source and with CardBoard: it
 * plays on outside its screen, rests Jarvis through WakePause, never records,
 * and its home card never pushes Jarvis under 156dp.
 */
class MusicScreenTest {

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()

    private fun media(name: String) = file("src/main/java/com/mammonrn/phoneaikiosk/media/$name")

    @Test
    fun `the player lives in a media playback service, the screen is ours and not exported`() {
        val manifest = file("src/main/AndroidManifest.xml")
        val service = manifest.substringAfter("android:name=\".media.MusicService\"").substringBefore("/>")
        assertTrue("android:foregroundServiceType=\"mediaPlayback\"" in service)
        assertTrue("android:exported=\"false\"" in service)
        val activity = manifest.substringAfter("android:name=\".media.MusicActivity\"").substringBefore("/>")
        assertTrue("android:exported=\"false\"" in activity)
        assertTrue("FOREGROUND_SERVICE_MEDIA_PLAYBACK" in manifest)
        // The player is not in the screen: closing it cannot stop the music.
        assertFalse("ExoPlayer" in media("MusicActivity.kt").substringAfter("class MusicActivity"))
    }

    @Test
    fun `jarvis rests through WakePause and keeps the hold while quieted`() {
        val service = media("MusicService.kt")
        assertTrue("WakePause.hold(WakePause.Source.MUSIC, this)" in service)
        assertTrue("WakePause.renew(" in service && "WakePause.release(" in service)
        assertTrue("override fun quietForJarvis()" in service && "override fun resumeAfterJarvis()" in service)
        // Quieted is still "meant to play": the hold is taken for either.
        assertTrue("if (meant || quieted) takeHold() else releaseHold()" in service)
    }

    @Test
    fun `nothing in the player records, and only ExoPlayer, no decoder extension`() {
        for (name in listOf("MusicService.kt", "MusicActivity.kt", "MediaSources.kt", "MusicShelf.kt")) {
            val src = media(name)
            for (mic in listOf("AudioRecord", "MediaRecorder", "RECORD_AUDIO", "Visualizer")) {
                assertFalse("$name uses $mic", mic in src)
            }
        }
        val catalog = file("../gradle/libs.versions.toml").ifEmpty { file("gradle/libs.versions.toml") }
        assertTrue("media3-exoplayer" in catalog)
        assertFalse("media3-decoder-ffmpeg" in catalog)
    }

    @Test
    fun `the Control Panel opens it and the home card is registered last`() {
        val panel = file("src/main/java/com/mammonrn/phoneaikiosk/settings/SettingsActivity.kt")
        assertTrue("ic_pixel_music" in panel && "MusicActivity::class.java" in panel)
        val main = file("src/main/java/com/mammonrn/phoneaikiosk/MainActivity.kt")
        val setUp = main.substringAfter("private fun setUpCards()").substringBefore("alarmStop.setOnClickListener")
        assertTrue(setUp.lastIndexOf("card(\"music\"") > setUp.lastIndexOf("card(\"home\""))
        assertTrue("board.holdOpen(\"music\", has)" in main)
        val layout = file("src/main/res/layout/activity_main.xml")
        for (id in listOf("music_toggle", "music_next")) {
            val view = layout.substringAfter("android:id=\"@+id/$id\"").substringBefore("/>")
            assertTrue("$id is a 48dp word button", "android:layout_height=\"48dp\"" in view && "android:text=\"@string/" in view)
        }
    }

    @Test
    fun `a held card stays open in its place while the others fold for it`() {
        val board = CardBoard(fixedOrder = true)
        board.register(CardBoard.Spec("weather", 60 * CardBoard.MINUTE, alwaysOpen = true))
        board.register(CardBoard.Spec("gold", 120 * CardBoard.MINUTE))
        board.register(CardBoard.Spec("music", 60 * CardBoard.MINUTE))
        board.report("gold", "a", 0); board.report("music", "t", 0)
        board.holdOpen("music", true)
        val slots = board.layout(10 * CardBoard.MINUTE)
        assertEquals(listOf("weather", "gold", "music"), slots.map { it.id })          // place kept
        val open = mapOf("weather" to 300, "gold" to 200, "music" to 100)
        val bar = mapOf("weather" to 30, "gold" to 30, "music" to 30)
        val fitted = board.fit(slots, open, bar, available = 450, nowMs = 10 * CardBoard.MINUTE)
        assertEquals(mapOf("weather" to true, "gold" to false, "music" to true), fitted.associate { it.id to it.open })
        board.holdOpen("music", false)
        assertTrue(board.layout(3 * 60 * CardBoard.MINUTE).first { it.id == "music" }.open.not())
    }

    @Test
    fun `the new icons are 16x16 squares with at most four colours`() {
        for (name in listOf("music", "play", "pause", "stop", "prev", "next")) {
            val xml = file("src/main/res/drawable/ic_pixel_$name.xml")
            assertTrue(name, "android:viewportWidth=\"16\"" in xml && "Our own pixel art" in xml)
            val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1] }.toSet()
            assertTrue("$name has ${colours.size} colours", colours.size in 1..4)
            for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
                assertFalse("$name draws curves", Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
            }
        }
    }

    @Test
    fun `its words are formal written Thai`() {
        val strings = file("src/main/res/values/strings.xml")
        val ours = Regex("""<string name="(music_[a-z_]+|window_music)">([^<]*)</string>""")
            .findAll(strings).map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(ours.size >= 30)
        for ((name, text) in ours) {
            for (spoken in listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว")) {
                assertFalse("$name is colloquial ($spoken): $text", spoken in text)
            }
        }
    }
}
