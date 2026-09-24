package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.files.FileOps
import com.mammonrn.phoneaikiosk.media.MediaKinds
import com.mammonrn.phoneaikiosk.media.PlayQueue
import com.mammonrn.phoneaikiosk.media.PlayerChoice
import com.mammonrn.phoneaikiosk.media.Playlist
import com.mammonrn.phoneaikiosk.media.PlaylistBook
import com.mammonrn.phoneaikiosk.media.Session
import com.mammonrn.phoneaikiosk.media.Track
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

/** 0.59.0: playlists, what a file is, the one place a player is chosen, and a file played on its own. */
class PlaylistBookTest {

    private fun t(n: Int, title: String = "เพลง $n") = Track("local:/sdcard/Music/$n.mp3", title, "ศิลปิน", "", n * 1000L)

    // ------------------------------------------------------------ what a file is

    @Test
    fun `Poom's list of kinds, a space before the dot included`() {
        for (name in listOf("a.mp3", "b.FLAC", "c.wav", "d.aac", "e.m4a", "f.ogg", "g.opus", "h.wma", "10 ทิ้งรักลงแม่น้ำ .flac")) {
            assertEquals(name, MediaKinds.Kind.AUDIO, MediaKinds.kindOf(name))
        }
        for (name in listOf("a.mp4", "b.mkv", "c.webm", "d.3gp", "e.mov", "f.avi", "g.mpg", "h.mpeg", "คู่โจร1.DAT",
                            "j.vob", "k.ts", "l.wmv", "m.flv")) {
            assertEquals(name, MediaKinds.Kind.VIDEO, MediaKinds.kindOf(name))
        }
        assertEquals(MediaKinds.Kind.IMAGE, MediaKinds.kindOf("x.HEIC"))
        assertEquals(MediaKinds.Kind.OTHER, MediaKinds.kindOf("notes"))
        // The file manager reads the same list.
        assertEquals(FileOps.Kind.VIDEO, FileOps.kindOfName("คู่โจร2.DAT"))
        assertEquals(FileOps.Kind.AUDIO, FileOps.kindOfName("x.wma"))
    }

    @Test
    fun `the player is chosen in one place, and what Media3 cannot play is not yet, by name`() {
        // Measured on the A07 (codecprobe): no MPEG-1/2 picture, no ASF reader.
        for (name in listOf("คู่โจร1.DAT", "x.mpg", "x.mpeg", "x.vob", "x.wmv", "x.wma")) {
            assertEquals(name, PlayerChoice.Engine.NOT_YET, PlayerChoice.forName(name))
        }
        for (name in listOf("x.mp4", "x.mkv", "x.avi", "x.flac", "x.m4a", "x.opus")) {
            assertEquals(name, PlayerChoice.Engine.MEDIA3, PlayerChoice.forName(name))
        }
        assertEquals("DAT", PlayerChoice.typeWord("คู่โจร1.DAT"))
        assertFalse(PlayerChoice.playsAudio("x.mp4"))
        assertTrue(PlayerChoice.playsVideo("x.avi"))
    }

    // ------------------------------------------------------------ playlists

    @Test
    fun `lists are made, renamed and deleted, with names that cannot clash`() {
        val book = PlaylistBook()
        assertEquals("รายการที่ 1", book.suggestName(Playlist.Kind.MUSIC))
        val a = book.create(Playlist.Kind.MUSIC, "  ลูกทุ่ง ", listOf(t(1), t(2)))
        assertEquals("ลูกทุ่ง", a.name)
        assertEquals(PlaylistBook.Problem.TAKEN, book.nameProblem(Playlist.Kind.MUSIC, "ลูกทุ่ง"))
        assertNull(book.nameProblem(Playlist.Kind.MUSIC, "ลูกทุ่ง", self = a.id))      // its own name is fine
        assertNull(book.nameProblem(Playlist.Kind.VIDEO, "ลูกทุ่ง"))                    // another kind is another list
        assertEquals(PlaylistBook.Problem.EMPTY, book.nameProblem(Playlist.Kind.MUSIC, "   "))
        assertEquals(PlaylistBook.Problem.TOO_LONG, book.nameProblem(Playlist.Kind.MUSIC, "ก".repeat(41)))
        val b = book.create(Playlist.Kind.MUSIC, "ร็อก")
        assertEquals(b, book.current(Playlist.Kind.MUSIC))                             // the new one is current
        book.rename(a.id, "ลูกทุ่งเก่า")
        assertEquals("ลูกทุ่งเก่า", book.get(a.id)!!.name)
        book.delete(b.id)
        assertEquals(listOf(a.id), book.of(Playlist.Kind.MUSIC).map { it.id })
        assertEquals(a.id, book.current(Playlist.Kind.MUSIC)!!.id)                     // falls to what is left
        assertTrue(book.of(Playlist.Kind.VIDEO).isEmpty())
    }

    @Test
    fun `a folder added twice adds nothing twice`() {
        val book = PlaylistBook()
        val p = book.create(Playlist.Kind.MUSIC, "ทดสอบ", listOf(t(1)))
        assertEquals(2, book.add(p.id, listOf(t(1), t(2), t(3), t(3))))
        assertEquals(0, book.add(p.id, listOf(t(2), t(3))))
        assertEquals(listOf(1, 2, 3).map { t(it).id }, book.get(p.id)!!.items.map { it.id })
    }

    @Test
    fun `voice looks through every list of its kind, each song once`() {
        val book = PlaylistBook()
        book.create(Playlist.Kind.MUSIC, "ก", listOf(t(1), t(2)))
        book.create(Playlist.Kind.MUSIC, "ข", listOf(t(2), t(3)))
        book.create(Playlist.Kind.VIDEO, "วิดีโอ", listOf(Track("local:/v.mp4", "วิดีโอ")))
        assertEquals(listOf(1, 2, 3).map { t(it).id }, book.searchable(Playlist.Kind.MUSIC).map { it.id })
        assertEquals("ก", book.holding(Playlist.Kind.MUSIC, t(1).id)!!.name)
    }

    @Test
    fun `the book comes back as it was kept, names with tabs and Thai`() {
        val book = PlaylistBook()
        book.create(Playlist.Kind.MUSIC, "มี\tแท็บ", listOf(t(1, "ชื่อ\\แปลก\nสองบรรทัด"), t(2)))
        val v = book.create(Playlist.Kind.VIDEO, "หนัง", listOf(Track("nas:Movies\\a.mkv", "a")))
        val back = PlaylistBook.decode(book.encode())!!
        assertEquals(book.lists, back.lists)
        assertEquals(v.id, back.current(Playlist.Kind.VIDEO)!!.id)
        assertNull(PlaylistBook.decode("not a book"))
    }

    @Test
    fun `the list from before playlists becomes รายการเดิม, whole, a song twice if it was twice`() {
        val old = listOf(t(1), t(2), t(1), t(3))
        val session = Session(old, 2, 42_000, shuffle = false, repeat = PlayQueue.Repeat.ALL)
        val book = PlaylistBook.migrate(Session.decode(session.encode()), emptyList())
        val former = book.current(Playlist.Kind.MUSIC)!!
        assertEquals(PlaylistBook.FORMER, former.name)
        assertEquals(old, former.items)
        // The old NAS library is kept as a list too; nothing kept is nothing made.
        val withNas = PlaylistBook.migrate(session, listOf(Track("nas:Music\\x.mp3", "x")))
        assertEquals(listOf(PlaylistBook.FORMER_NAS, PlaylistBook.FORMER), withNas.lists.map { it.name })
        assertEquals(PlaylistBook.FORMER, withNas.current(Playlist.Kind.MUSIC)!!.name)
        assertTrue(PlaylistBook.migrate(null, emptyList()).lists.isEmpty())
    }

    @Test
    fun `a session keeps its playlist, and one kept by 0_58 still reads`() {
        val s = Session(listOf(t(1)), 0, 5_000, true, PlayQueue.Repeat.ONE, playlistId = "p3")
        assertEquals("p3", Session.decode(s.encode())!!.playlistId)
        val old = "s1\t0\t5000\t0\tOFF\nlocal:/a.mp3\ta\t\t\t0\n"
        val read = Session.decode(old)!!
        assertNull(read.playlistId)
        assertEquals(1, read.tracks.size)
    }

    // ------------------------------------------------------------ one file on its own

    @Test
    fun `a file played on its own puts the list back exactly, shuffle order and repeat too`() {
        val q = PlayQueue(Random(7))
        q.set((1..8).map { t(it) }, 0)
        q.setShuffle(true)
        q.repeat = PlayQueue.Repeat.ONE
        q.next(auto = false); q.next(auto = false)
        val before = q.snapshot()
        val upcoming = q.upcoming()
        val playing = q.current

        q.setShuffle(false)
        q.set(listOf(Track("local:/sdcard/Download/one.mp3", "one")), 0)
        q.repeat = PlayQueue.Repeat.OFF

        q.restore(before)
        assertEquals(playing, q.current)
        assertEquals(upcoming, q.upcoming())
        assertEquals(PlayQueue.Repeat.ONE, q.repeat)
        assertTrue(q.shuffle)
    }
}
