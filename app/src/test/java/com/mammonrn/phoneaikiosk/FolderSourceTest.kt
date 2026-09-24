package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.files.LocalSource
import com.mammonrn.phoneaikiosk.files.StorageAreas
import com.mammonrn.phoneaikiosk.media.MediaKinds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

/** 0.59.0: the folder browser's source — the breadcrumb, one level up, and a whole folder added. */
class FolderSourceTest {

    @get:Rule val tmp = TemporaryFolder()

    private fun tree(): File {
        val root = tmp.newFolder("storage")
        fun file(path: String) = File(root, path).apply { parentFile.mkdirs(); writeText("x") }
        file("Music/2024/มกราคม/a.mp3")
        file("Music/2024/มกราคม/b.flac")
        file("Music/2024/cover.jpg")
        file("Music/c.wma")
        file("Music/notes.txt")
        file("Android/data/some.app/secret.mp3")
        return root
    }

    @Test
    fun `the new words are formal written Thai`() {
        val strings = listOf(File("src/main/res/values/strings.xml"), File("app/src/main/res/values/strings.xml"))
            .first { it.exists() }.readText()
        val ours = Regex("""<string name="((?:folder|playlist|picker|picture)_[a-z_]+)">([^<]*)<""").findAll(strings)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(ours.size > 40)
        for ((name, text) in ours) {
            for (spoken in listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว", "ไม่ใส่ก็ได้")) {
                assertTrue("$name is colloquial ($spoken): $text", spoken !in text)
            }
        }
        // The count on the button is a number that is filled in, never a fixed "1".
        assertTrue("""<string name="picker_add_songs">เพิ่ม %1${'$'}d เพลง</string>""" in strings)
        assertTrue("""<string name="folder_ticked">เลือกแล้ว %d รายการ</string>""" in strings)
    }

    @Test
    fun `the breadcrumb names each folder and where a tap on it goes`() {
        val root = tree()
        val deep = File(root, "Music/2024/มกราคม")
        val crumbs = StorageAreas.crumbs(deep, root, "เครื่อง")
        assertEquals(listOf("เครื่อง", "Music", "2024", "มกราคม"), crumbs.map { it.first })
        assertEquals(root.absolutePath, crumbs.first().second.absolutePath)
        assertEquals(File(root, "Music").absolutePath, crumbs[1].second.absolutePath)
        assertEquals(listOf("เครื่อง"), StorageAreas.crumbs(root, root, "เครื่อง").map { it.first })
    }

    @Test
    fun `up one level stops at the top, and the apps' own folders stay shut`() {
        val root = tree()
        val source = LocalSource(root, "เครื่อง", listOf(root))
        assertNull(source.parent(root.absolutePath))
        assertEquals(File(root, "Music").absolutePath, source.parent(File(root, "Music/2024").absolutePath))
        val top = source.list(root.absolutePath)!!
        assertEquals(listOf("Android", "Music"), top.map { it.name })
        val android = source.list(File(root, "Android").absolutePath)!!
        assertTrue(android.single { it.name == "data" }.blocked)
        assertNull(source.list(File(root, "Android/data").absolutePath))
    }

    @Test
    fun `a whole folder adds every song under it, folder by folder, and nothing locked`() {
        val root = tree()
        val source = LocalSource(root, "เครื่อง", listOf(root))
        val songs = source.collect(root.absolutePath, MediaKinds::isAudio)
        assertEquals(listOf("a.mp3", "b.flac", "c.wma"), songs.map { it.name })
        assertEquals(2, source.collect(root.absolutePath, MediaKinds::isAudio, limit = 2).size)
        assertTrue(source.trackId(songs.first()).startsWith("local:"))
    }
}
