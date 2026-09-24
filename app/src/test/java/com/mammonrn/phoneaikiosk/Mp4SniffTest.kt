package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.media.Mp4Sniff
import com.mammonrn.phoneaikiosk.media.PlayerChoice
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.io.ByteArrayOutputStream

/** 0.60.0: an .m4a is told by what is inside it — AAC to Media3, ALAC to LibVLC. */
class Mp4SniffTest {

    private fun box(type: String, vararg body: ByteArray): ByteArray {
        val inner = ByteArrayOutputStream().apply { body.forEach { write(it) } }.toByteArray()
        val size = 8 + inner.size
        return byteArrayOf((size ushr 24).toByte(), (size ushr 16).toByte(), (size ushr 8).toByte(), size.toByte()) +
            type.toByteArray(Charsets.ISO_8859_1) + inner
    }

    private fun bytes(n: Int) = ByteArray(n)

    /** A file: ftyp, then moov with a video track and a sound track of [codec]; moov at the end when [late]. */
    private fun m4a(codec: String, late: Boolean = false): ByteArray {
        fun trak(handler: String, entry: String) = box("trak",
            box("tkhd", bytes(84)),
            box("mdia",
                box("mdhd", bytes(24)),
                box("hdlr", bytes(8), handler.toByteArray(Charsets.ISO_8859_1), bytes(13)),
                box("minf", box("stbl", box("stsd", bytes(4), byteArrayOf(0, 0, 0, 1), box(entry, bytes(28)))))))
        val moov = box("moov", box("mvhd", bytes(100)), trak("vide", "avc1"), trak("soun", codec))
        val ftyp = box("ftyp", "M4A ".toByteArray(Charsets.ISO_8859_1), bytes(8))
        val mdat = box("mdat", bytes(500))
        return if (late) ftyp + mdat + moov else ftyp + moov + mdat
    }

    private fun sniff(file: ByteArray) = Mp4Sniff.audioCodec(file.size.toLong()) { pos, len ->
        file.copyOfRange(pos.toInt(), pos.toInt() + len)
    }

    @Test
    fun `the sound track's own codec, wherever moov is`() {
        assertEquals("alac", sniff(m4a("alac")))
        assertEquals("mp4a", sniff(m4a("mp4a")))
        assertEquals("alac", sniff(m4a("alac", late = true)))
        assertNull(sniff(box("ftyp", bytes(8)) + box("mdat", bytes(40))))
        assertNull(sniff(ByteArray(3)))
    }

    @Test
    fun `an m4a goes where its codec plays, the rest by name`() {
        assertEquals(PlayerChoice.Engine.VLC, PlayerChoice.forFile("01 When I Fall In Love.m4a") { "alac" })
        assertEquals(PlayerChoice.Engine.MEDIA3, PlayerChoice.forFile("Over_the_Horizon.m4a") { "mp4a" })
        // Unknown inside: Media3 first; it hands over to LibVLC if it cannot decode it.
        assertEquals(PlayerChoice.Engine.MEDIA3, PlayerChoice.forFile("x.m4a") { null })
        for (name in listOf("คู่โจร1.DAT", "a.mpg", "a.mpeg", "a.vob", "a.ts", "a.wmv", "a.wma", "a.flv", "a.alac")) {
            assertEquals(name, PlayerChoice.Engine.VLC, PlayerChoice.forFile(name) { error("not asked") })
        }
        for (name in listOf("a.mp3", "a.flac", "a.wav", "a.aac", "a.ogg", "a.opus", "a.mp4", "a.m4v", "a.mkv",
                            "a.webm", "a.3gp", "a.mov", "a.avi")) {
            assertEquals(name, PlayerChoice.Engine.MEDIA3, PlayerChoice.forFile(name) { error("not asked") })
        }
    }
}
