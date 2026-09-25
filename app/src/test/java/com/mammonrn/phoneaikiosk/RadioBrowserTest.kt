package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.radio.RadioBrowser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** "ค้นหาสถานีไทย" (0.61.0): what is asked of Radio Browser, and how its answer is read. */
class RadioBrowserTest {

    @Test
    fun `the query asks for working Thai stations, most voted first, a limited count`() {
        val q = RadioBrowser.query("")
        assertTrue(q.startsWith("/json/stations/search?"))
        assertTrue("countrycode=TH" in q)
        assertTrue("hidebroken=true" in q)
        assertTrue("order=votes" in q && "reverse=true" in q)
        assertTrue("limit=${RadioBrowser.LIMIT}" in q)
        assertFalse("name=" in q)
        assertTrue("order=clickcount" in RadioBrowser.query("", RadioBrowser.Order.CLICKS))
        // Words typed are sent encoded, and nothing else about the phone is.
        assertTrue("name=%E0%B8%A5%E0%B8%B9%E0%B8%81%E0%B8%97%E0%B8%B8%E0%B9%88%E0%B8%87" in RadioBrowser.query(" ลูกทุ่ง "))
        assertTrue("name=cool+93" in RadioBrowser.query("cool 93"))
    }

    @Test
    fun `the answer is read into stations, each address once`() {
        val json = """[
          {"stationuuid":"u1","name":"Cool fahrenheit","url":"https://c.example.com/x","url_resolved":"https://c.example.com/;stream/1",
           "codec":"MP3","bitrate":128,"hls":0,"votes":4086,"lastcheckok":1,"countrycode":"TH"},
          {"stationuuid":"u2","name":"COOLISM","url":"https://c.example.com/;stream/1","url_resolved":"",
           "codec":"mp3","bitrate":128,"hls":0,"votes":619,"lastcheckok":1},
          {"stationuuid":"u3","name":"MCOT Loei FM 100.00","url":"https://m.example.com/Loei.stream_aac/playlist.m3u8",
           "url_resolved":"https://m.example.com/Loei.stream_aac/playlist.m3u8","codec":"AAC","bitrate":128,"hls":1,"votes":69,"lastcheckok":1},
          {"stationuuid":"u4","name":"ลูกทุ่ง รักไทย ๙๐","url":"http://p.example.com:8896/;stream.mp3","codec":"MP3","bitrate":96,"votes":1782,"lastcheckok":1},
          {"stationuuid":"u5","name":"No address","url":"","url_resolved":"","codec":"MP3"},
          {"stationuuid":"u6","name":"   ","url":"https://blank.example.com/"},
          {"stationuuid":"u7","name":"A very long station name that the directory sends as it is, far too long",
           "url_resolved":"https://long.example.com/","bitrate":-1}
        ]"""
        val found = RadioBrowser.parse(json)
        assertEquals(listOf("u1", "u3", "u4", "u7"), found.map { it.uuid })
        val cool = found[0]
        assertEquals("https://c.example.com/;stream/1", cool.url)
        assertEquals("MP3", cool.codec)
        assertEquals(128, cool.bitrate)
        assertEquals(4086, cool.votes)
        assertTrue(cool.checkedOk && cool.secure && !cool.hls)
        assertTrue(found[1].hls)
        assertEquals("ลูกทุ่ง รักไทย ๙๐", found[2].name)
        assertFalse(found[2].secure)
        assertTrue(found[3].name.length <= 40)
        assertEquals(0, found[3].bitrate)
    }

    @Test
    fun `an answer that is not a list is no stations, not a crash`() {
        assertTrue(RadioBrowser.parse("").isEmpty())
        assertTrue(RadioBrowser.parse("<html>502</html>").isEmpty())
        assertTrue(RadioBrowser.parse("""{"error":"x"}""").isEmpty())
        assertTrue(RadioBrowser.parse("[]").isEmpty())
    }

    @Test
    fun `the servers are the directory's own and are all https`() {
        assertTrue(RadioBrowser.SERVERS.isNotEmpty())
        assertTrue(RadioBrowser.SERVERS.all { it.endsWith(".api.radio-browser.info") })
        assertTrue("phone-ai-kiosk" in RadioBrowser.USER_AGENT)
    }
}
