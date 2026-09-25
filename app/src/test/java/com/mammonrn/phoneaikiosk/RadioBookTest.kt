package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.radio.RadioBook
import com.mammonrn.phoneaikiosk.radio.RadioBook.Refusal
import com.mammonrn.phoneaikiosk.radio.Station
import com.mammonrn.phoneaikiosk.radio.StreamKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** The radio's station list (0.61.0, DESIGN.md 5ฏ): parse, edit, favourites first. */
class RadioBookTest {

    private val a = Station("a", "Alpha", "https://a.example.com/stream")
    private val b = Station("b", "Bravo", "https://b.example.com/live.m3u8")
    private val c = Station("c", "Charlie", "https://c.example.com/;stream/1")
    private val book = RadioBook(listOf(a, b, c))

    @Test
    fun `starred stations come first, each group in the order it was added`() {
        val starred = book.star("c", true).book!!.star("b", true).book!!
        assertEquals(listOf("b", "c", "a"), starred.ordered().map { it.id })
        assertEquals(listOf("a", "b", "c"), starred.stations.map { it.id })
        assertEquals(1, starred.number("b"))
        assertEquals(3, starred.number("a"))
        assertEquals(listOf("b", "c", "a", "d"),
                     starred.add("Delta", "https://d.example.com/x", "d").book!!.ordered().map { it.id })
        assertEquals(listOf("c", "a"), starred.star("b", false).book!!.ordered().map { it.id }.take(2))
    }

    @Test
    fun `add checks the name and the address`() {
        assertEquals(Refusal.NAME_EMPTY, book.add("   ", "https://x.example.com/", "x").refusal)
        assertEquals(Refusal.NAME_LONG, book.add("x".repeat(41), "https://x.example.com/", "x").refusal)
        assertEquals(Refusal.URL_BAD, book.add("X", "x.example.com/stream", "x").refusal)
        assertEquals(Refusal.URL_BAD, book.add("X", "ftp://x.example.com/", "x").refusal)
        assertEquals(Refusal.URL_BAD, book.add("X", "https://nohost/", "x").refusal)
        assertEquals(Refusal.URL_BAD, book.add("X", "https://x.example.com/a b", "x").refusal)
        assertEquals(Refusal.URL_TOO_LONG, book.add("X", "https://x.example.com/" + "a".repeat(2100), "x").refusal)
        // The same stream written a little differently is still the same stream.
        assertEquals(Refusal.DUPLICATE, book.add("Again", "HTTPS://A.EXAMPLE.COM/stream/", "x").refusal)
        val added = book.add("  Radio   Thailand  ", " https://x.example.com:8000/live ", "x")
        assertTrue(added.ok)
        assertEquals(Station("x", "Radio Thailand", "https://x.example.com:8000/live"), added.book!!.find("x"))
        assertTrue(book.add("Plain", "http://plain.example.com/stream", "p").ok)
    }

    @Test
    fun `the list has a ceiling`() {
        val full = RadioBook((1..RadioBook.MAX_STATIONS).map { Station("s$it", "S$it", "https://s$it.example.com/") })
        assertEquals(Refusal.FULL, full.add("One more", "https://more.example.com/", "m").refusal)
    }

    @Test
    fun `rename keeps the star and the place, and refuses another station's address`() {
        val starred = book.star("b", true).book!!
        val renamed = starred.edit("b", "Bravo FM", "https://b.example.com/live.m3u8").book!!
        assertEquals(Station("b", "Bravo FM", "https://b.example.com/live.m3u8", favourite = true), renamed.find("b"))
        assertEquals(listOf("a", "b", "c"), renamed.stations.map { it.id })
        assertEquals(Refusal.DUPLICATE, book.edit("b", "Bravo", "https://a.example.com/stream").refusal)
        assertEquals(Refusal.MISSING, book.edit("zz", "Z", "https://z.example.com/").refusal)
    }

    @Test
    fun `remove takes one station off and nothing else`() {
        val after = book.remove("b").book!!
        assertEquals(listOf(a, c), after.stations)
        assertEquals(Refusal.MISSING, after.remove("b").refusal)
        assertNull(after.find("b"))
    }

    @Test
    fun `the file round-trips, and broken entries are skipped without losing the rest`() {
        val starred = book.star("a", true).book!!
        assertEquals(starred.stations, RadioBook.decode(starred.encode())!!.stations)
        val text = """{"version":1,"stations":[
            {"id":"a","name":"Alpha","url":"https://a.example.com/","fav":true},
            {"id":"","name":"No id","url":"https://n.example.com/"},
            {"id":"b","name":"","url":"https://b.example.com/"},
            {"id":"c","name":"Bad url","url":"not a url"},
            {"id":"a","name":"Repeated id","url":"https://r.example.com/"},
            {"id":"d","name":"Same stream","url":"https://A.example.com"},
            "garbage",
            {"id":"e","name":"Echo","url":"https://e.example.com/x.pls"}]}"""
        val read = RadioBook.decode(text)!!
        assertEquals(listOf("a", "e"), read.stations.map { it.id })
        assertTrue(read.stations[0].favourite)
        assertNull(RadioBook.decode("not json"))
        assertNull(RadioBook.decode("""{"version":1}"""))
    }

    @Test
    fun `names from the directory are made to fit without splitting a Thai syllable`() {
        assertEquals("Short", RadioBook.shorten("  Short "))
        val spaced = RadioBook.shorten("Radio Station With A Very Long Name That Goes On And On")
        assertTrue(spaced.length <= RadioBook.MAX_NAME)
        assertTrue("Radio Station With A Very Long Name That Goes On And On".startsWith(spaced))
        // 39 consonants, then a consonant with a vowel mark over it at the cut.
        val thai = "ก".repeat(39) + "กิ" + "ขขขข"
        val cut = RadioBook.shorten(thai)
        assertTrue(cut.length <= RadioBook.MAX_NAME)
        // The character after the cut is never a vowel or tone mark left without its consonant.
        assertFalse(thai[cut.length] == 'ิ')
        assertEquals(39, cut.length)
    }

    @Test
    fun `stream kinds and playlists`() {
        assertEquals(StreamKind.Kind.HLS, StreamKind.of("https://x.example.com/live/playlist.m3u8?token=1"))
        assertEquals(StreamKind.Kind.PLAYLIST, StreamKind.of("https://x.example.com/listen.pls"))
        assertEquals(StreamKind.Kind.PLAYLIST, StreamKind.of("https://x.example.com/listen.M3U"))
        assertEquals(StreamKind.Kind.DIRECT, StreamKind.of("https://x.example.com/;stream/1"))
        assertTrue(StreamKind.plain("http://x.example.com/"))
        assertFalse(StreamKind.plain("https://x.example.com/"))
        val pls = "[playlist]\nNumberOfEntries=2\nFile1=https://one.example.com/stream\nTitle1=One\nFile2=https://two.example.com/\n"
        assertEquals("https://one.example.com/stream", StreamKind.firstInPlaylist(pls))
        val m3u = "#EXTM3U\n#EXTINF:-1,Station\nhttps://s.example.com/live\n"
        assertEquals("https://s.example.com/live", StreamKind.firstInPlaylist(m3u))
        assertNull(StreamKind.firstInPlaylist("<html>not a playlist</html>"))
    }

    @Test
    fun `the seed in the APK is a valid list of https stations`() {
        val seed = listOf(File("src/main/assets/radio_stations.json"), File("app/src/main/assets/radio_stations.json"))
            .first { it.exists() }
        val read = RadioBook.decode(seed.readText())
        assertNotNull(read)
        val count = Regex("\"id\"").findAll(seed.readText()).count()
        assertEquals("every seed entry must survive decoding", count, read!!.stations.size)
        assertTrue(read.stations.isNotEmpty())
        // The app allows no cleartext: a seed station that could not play would be a broken promise.
        assertTrue(read.stations.all { it.url.startsWith("https://") })
    }
}
