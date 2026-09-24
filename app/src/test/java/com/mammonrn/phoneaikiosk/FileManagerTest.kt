package com.mammonrn.phoneaikiosk

import com.hierynomus.mssmb2.SMB2MessageCommandCode
import com.hierynomus.mssmb2.SMBApiException
import com.mammonrn.phoneaikiosk.files.FileOps
import com.mammonrn.phoneaikiosk.files.FileOps.Reason
import com.mammonrn.phoneaikiosk.files.NasConfig
import com.mammonrn.phoneaikiosk.files.NasForm
import com.mammonrn.phoneaikiosk.files.NasProblem
import com.mammonrn.phoneaikiosk.files.StorageAreas
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * The file manager (0.44.0): nothing overwritten, nothing moved into itself,
 * no zip entry out of its folder, deletes that delete, and the NAS read only
 * with failures in words. On real files in a temporary folder.
 */
class FileManagerTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun file(dir: File, name: String, text: String = name) = File(dir, name).apply {
        parentFile?.mkdirs()
        writeText(text)
    }

    private fun refused(reason: Reason, block: () -> Unit) {
        try {
            block()
            fail("expected $reason")
        } catch (e: FileOps.Refused) {
            assertEquals(reason, e.reason)
        }
    }

    // ------------------------------------------------ listing and names

    @Test
    fun `folders come first, then files, by name ignoring case`() {
        val dir = tmp.newFolder("d")
        file(dir, "b.txt"); file(dir, "A.txt"); File(dir, "zeta").mkdir(); File(dir, "Alpha").mkdir()
        assertEquals(listOf("Alpha", "zeta", "A.txt", "b.txt"), FileOps.list(dir)!!.map { it.name })
    }

    @Test
    fun `an unreadable folder lists as null, not as empty`() {
        assertNull(FileOps.list(File(tmp.root, "not-there")))
        assertEquals(emptyList<File>(), FileOps.list(tmp.newFolder("empty")))
    }

    @Test
    fun `a free name never overwrites`() {
        val dir = tmp.newFolder("d")
        assertEquals("photo.jpg", FileOps.freeName(dir, "photo.jpg"))
        file(dir, "photo.jpg")
        assertEquals("photo (2).jpg", FileOps.freeName(dir, "photo.jpg"))
        file(dir, "photo (2).jpg")
        // Taken whatever the case; the new name keeps the case it was given.
        assertEquals("PHOTO (3).jpg", FileOps.freeName(dir, "PHOTO.jpg"))
        File(dir, "Album").mkdir()
        assertEquals("Album (2)", FileOps.freeName(dir, "Album"))
        file(dir, ".nomedia")
        assertEquals(".nomedia (2)", FileOps.freeName(dir, ".nomedia"))
    }

    @Test
    fun `names that cannot be`() {
        val dir = tmp.newFolder("d")
        file(dir, "taken.txt")
        assertEquals(Reason.NAME_EMPTY, FileOps.nameProblem("   ", dir))
        assertEquals(Reason.NAME_SLASH, FileOps.nameProblem("a/b", dir))
        assertEquals(Reason.NAME_DOTS, FileOps.nameProblem("..", dir))
        assertEquals(Reason.NAME_TOO_LONG, FileOps.nameProblem("ก".repeat(90), dir))   // 270 bytes
        assertEquals(Reason.NAME_TAKEN, FileOps.nameProblem("TAKEN.txt", dir))
        assertNull(FileOps.nameProblem("ใหม่.txt", dir))
        // A file renamed to its own name in another case is not "taken" by itself.
        assertNull(FileOps.nameProblem("Taken.txt", dir, File(dir, "taken.txt")))
    }

    @Test
    fun `rename keeps the content and trims the name`() {
        val dir = tmp.newFolder("d")
        val f = file(dir, "old.txt", "hello")
        val renamed = FileOps.rename(f, "  ใหม่.txt ")
        assertEquals("ใหม่.txt", renamed.name)
        assertEquals("hello", renamed.readText())
        assertFalse(f.exists())
        refused(Reason.NAME_TAKEN) { FileOps.rename(file(dir, "other.txt"), "ใหม่.txt") }
    }

    // ------------------------------------------------ copy, move, delete

    @Test
    fun `copy a file never overwrites and says the new name`() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        val f = file(src, "a.txt", "new")
        file(dst, "a.txt", "old")
        val made = FileOps.copy(f, dst)
        assertEquals("a (2).txt", made.name)
        assertEquals("old", File(dst, "a.txt").readText())
        assertEquals("new", made.readText())
        assertTrue(f.exists())
    }

    @Test
    fun `copy a folder copies all of it, and counts as it goes`() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        file(src, "album/1.jpg", "one"); file(src, "album/sub/2.jpg", "two")
        val work = FileOps.Work()
        val made = FileOps.copy(File(src, "album"), dst, work)
        assertEquals("two", File(made, "sub/2.jpg").readText())
        assertEquals(2, work.totalFiles)
        assertEquals(2, work.doneFiles)
        assertEquals(6L, work.doneBytes)
    }

    @Test
    fun `a folder cannot go into itself`() {
        val root = tmp.newFolder("r")
        val album = File(root, "album").apply { mkdirs() }
        val inner = File(album, "inner").apply { mkdirs() }
        refused(Reason.INTO_ITSELF) { FileOps.copy(album, album) }
        refused(Reason.INTO_ITSELF) { FileOps.copy(album, inner) }
        refused(Reason.INTO_ITSELF) { FileOps.move(album, inner) }
        // A sibling whose name only starts the same is not "inside".
        val albums = File(root, "album2").apply { mkdirs() }
        assertTrue(FileOps.copy(album, albums).isDirectory)
    }

    @Test
    fun `move goes, and moving to where it is says so`() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        val f = file(src, "a.txt", "x")
        refused(Reason.SAME_PLACE) { FileOps.move(f, src) }
        val moved = FileOps.move(f, dst)
        assertFalse(f.exists())
        assertEquals("x", moved.readText())
    }

    @Test
    fun `a stopped copy leaves nothing half made, and the original whole`() {
        val src = tmp.newFolder("src"); val dst = tmp.newFolder("dst")
        file(src, "album/1.jpg", "x".repeat(200_000)); file(src, "album/2.jpg", "y".repeat(200_000))
        // Stopped in the middle of the first file, as the stop button would.
        val stopper = object : FileOps.Work() {
            override fun check() {
                if (doneBytes > 0) cancelled = true
                super.check()
            }
        }
        try {
            FileOps.copy(File(src, "album"), dst, stopper)
            fail("expected a stop")
        } catch (e: FileOps.Cancelled) {
            // expected
        }
        assertTrue(stopper.doneBytes > 0)
        assertEquals(emptyList<String>(), dst.list()!!.toList())
        assertEquals(2, File(src, "album").list()!!.size)
    }

    @Test
    fun `delete removes a whole folder, and a gone file is said to be gone`() {
        val root = tmp.newFolder("r")
        file(root, "album/1.jpg"); file(root, "album/sub/2.jpg")
        assertEquals(3, FileOps.countInside(File(root, "album")))
        FileOps.deleteTree(File(root, "album"))
        assertFalse(File(root, "album").exists())
        refused(Reason.GONE) { FileOps.deleteTree(File(root, "album")) }
    }

    // ------------------------------------------------ search

    @Test
    fun `search finds by part of the name, any case, skipping what it is told`() {
        val root = tmp.newFolder("r")
        file(root, "Download/Invoice-2026.pdf"); file(root, "Pictures/invoice scan.jpg")
        file(root, "Android/data/app/invoice.db"); file(root, "notes.txt")
        val skip = { f: File -> StorageAreas.appPrivate(f, listOf(root)) }
        val found = FileOps.search(root, " INVOICE ", skip = skip)
        assertEquals(setOf("Invoice-2026.pdf", "invoice scan.jpg"), found.files.map { it.name }.toSet())
        assertFalse(found.more)
        assertEquals(0, FileOps.search(root, "   ").files.size)
    }

    @Test
    fun `search stops at the limit and says there were more`() {
        val root = tmp.newFolder("r")
        repeat(FileOps.SEARCH_LIMIT + 5) { file(root, "photo$it.jpg") }
        val found = FileOps.search(root, "photo")
        assertEquals(FileOps.SEARCH_LIMIT, found.files.size)
        assertTrue(found.more)
    }

    // ------------------------------------------------ zip

    private fun zip(dir: File, name: String, vararg entries: Pair<String, String>): File {
        val out = File(dir, name)
        ZipOutputStream(out.outputStream()).use { z ->
            for ((entry, text) in entries) {
                z.putNextEntry(ZipEntry(entry))
                z.write(text.toByteArray())
                z.closeEntry()
            }
        }
        return out
    }

    @Test
    fun `the details page counts what is inside a zip without extracting it`() {
        val dir = tmp.newFolder("d")
        val z = zip(dir, "photos.zip", "a.txt" to "A", "sub/b.txt" to "B", "ไทย.txt" to "ก")
        val summary = FileOps.zipSummary(z)
        assertEquals(3, summary.files)
        assertEquals(5L, summary.bytes)            // "A", "B", and "ก" is 3 bytes in UTF-8
        assertEquals(listOf("photos.zip"), dir.list()!!.toList())
    }

    @Test
    fun `unzip extracts everything and never overwrites a folder already there`() {
        val dir = tmp.newFolder("d")
        val z = zip(dir, "photos.zip", "a.txt" to "A", "sub/b.txt" to "B", "ไทย.txt" to "ก")
        File(dir, "photos").mkdir()
        val out = FileOps.unzip(z)
        assertEquals("photos (2)", out.name)
        assertEquals("A", File(out, "a.txt").readText())
        assertEquals("B", File(out, "sub/b.txt").readText())
        assertEquals("ก", File(out, "ไทย.txt").readText())
    }

    @Test
    fun `a zip that climbs out of its folder is refused, and nothing is written`() {
        val dir = tmp.newFolder("d")
        for (bad in listOf("../evil.txt", "sub/../../evil.txt", "/etc/evil.txt", "..\\evil.txt", "C:/evil.txt")) {
            val z = zip(dir, "bad.zip", "ok.txt" to "fine", bad to "evil")
            refused(Reason.UNSAFE_ZIP) { FileOps.unzip(z) }
            assertFalse("nothing extracted for $bad", File(dir, "bad").exists())
            assertFalse(File(tmp.root, "evil.txt").exists())
            z.delete()
        }
    }

    @Test
    fun `unsafe entry names`() {
        for (bad in listOf("../a", "a/../../b", "/a", "\\a", "..\\a", "C:\\a", "a/..")) {
            assertTrue(bad, FileOps.unsafeEntryName(bad))
        }
        for (ok in listOf("a", "a/b", "a..b", "..a", "ไทย/ไฟล์.txt", "a/./b")) {
            assertFalse(ok, FileOps.unsafeEntryName(ok))
        }
    }

    @Test
    fun `a file that is not a zip is said to be damaged`() {
        val dir = tmp.newFolder("d")
        val notZip = file(dir, "fake.zip", "not a zip at all")
        refused(Reason.ZIP_DAMAGED) { FileOps.unzip(notZip) }
        assertFalse(File(dir, "fake").exists())
    }

    @Test
    fun `the app never switches Android's own zip path check off`() {
        for (f in sources()) {
            val code = f.readText()
            assertFalse("${f.name} must not replace Android's zip path check",
                        "ZipPathValidator.clearCallback" in code || "ZipPathValidator.setCallback" in code)
        }
    }

    // ------------------------------------------------ kinds and sizes

    @Test
    fun `sizes read as a person reads them`() {
        assertEquals("0 B", FileOps.formatSize(0))
        assertEquals("999 B", FileOps.formatSize(999))
        assertEquals("1 KB", FileOps.formatSize(1000))
        assertEquals("1.5 KB", FileOps.formatSize(1500))
        assertEquals("340 KB", FileOps.formatSize(340_000))
        assertEquals("1.23 MB", FileOps.formatSize(1_234_000))
        assertEquals("12.3 MB", FileOps.formatSize(12_340_000))
        assertEquals("64 GB", FileOps.formatSize(64_000_000_000))
    }

    @Test
    fun `kinds by extension, zip apart from the archives it cannot open`() {
        assertEquals(FileOps.Kind.ZIP, FileOps.kindOfName("A.ZIP"))
        assertEquals(FileOps.Kind.OTHER_ARCHIVE, FileOps.kindOfName("x.rar"))
        assertEquals(FileOps.Kind.OTHER_ARCHIVE, FileOps.kindOfName("x.7z"))
        assertEquals(FileOps.Kind.IMAGE, FileOps.kindOfName("x.jpeg"))
        assertEquals(FileOps.Kind.OTHER, FileOps.kindOfName("noext"))
    }

    // ------------------------------------------------ where the system says no

    @Test
    fun `Android data and obb are the apps' own, on every root, any case`() {
        val phone = File("/storage/emulated/0")
        val sd = File("/storage/1234-ABCD")
        val roots = listOf(phone, sd)
        assertTrue(StorageAreas.appPrivate(File(phone, "Android/data"), roots))
        assertTrue(StorageAreas.appPrivate(File(phone, "Android/obb/com.game"), roots))
        assertTrue(StorageAreas.appPrivate(File(sd, "android/DATA"), roots))
        assertFalse(StorageAreas.appPrivate(File(phone, "Android"), roots))
        assertFalse(StorageAreas.appPrivate(File(phone, "Android/media"), roots))
        assertFalse(StorageAreas.appPrivate(File(phone, "Android/database"), roots))
        assertFalse(StorageAreas.appPrivate(File(phone, "Download"), roots))
    }

    @Test
    fun `never above a storage root, and the trail starts at the root's name`() {
        val phone = File("/storage/emulated/0")
        assertNull(StorageAreas.rootOf(File("/storage"), listOf(phone)))
        assertNull(StorageAreas.rootOf(File("/storage/emulated/01"), listOf(phone)))
        assertEquals(phone, StorageAreas.rootOf(File(phone, "Download/x"), listOf(phone)))
        assertEquals(listOf("เครื่อง", "Download", "Photos"),
                     StorageAreas.trail(File(phone, "Download/Photos"), phone, "เครื่อง"))
        assertEquals(listOf("เครื่อง"), StorageAreas.trail(phone, phone, "เครื่อง"))
    }

    // ------------------------------------------------ the NAS form

    private fun ok(address: String, share: String = "", user: String = "", password: String = "pw") =
        (NasForm.parse(address, share, user, password) as NasForm.Result.Ok).config

    private fun bad(address: String, share: String = "Media") =
        (NasForm.parse(address, share, "", "") as NasForm.Result.Bad).problem

    @Test
    fun `the address takes what people paste`() {
        assertEquals(NasConfig("192.168.1.20", 445, "Media", "", "", "pw"), ok("192.168.1.20", "Media"))
        assertEquals(4445, ok("nas.local:4445", "Media").port)
        with(ok("smb://nas.local/Media/Films")) { assertEquals("nas.local", host); assertEquals("Media", share) }
        with(ok("\\\\nas\\Photos")) { assertEquals("nas", host); assertEquals("Photos", share) }
        // The share field wins over one written in the address.
        assertEquals("Music", ok("smb://nas/Media", "Music").share)
        with(ok("nas", "Media", user = "HOME\\poom")) { assertEquals("HOME", domain); assertEquals("poom", user) }
    }

    @Test
    fun `the one thing wrong with a form`() {
        assertEquals(NasForm.Problem.NO_ADDRESS, bad("  "))
        assertEquals(NasForm.Problem.BAD_PORT, bad("nas:99999"))
        assertEquals(NasForm.Problem.BAD_PORT, bad("nas:abc"))
        assertEquals(NasForm.Problem.BAD_ADDRESS, bad("my nas"))
        assertEquals(NasForm.Problem.BAD_ADDRESS, bad("user@nas"))
        assertEquals(NasForm.Problem.NO_SHARE, bad("nas", share = " "))
    }

    @Test
    fun `the password never shows in a log line or on screen by accident`() {
        val config = NasConfig("192.168.1.20", 445, "Media", "poom", "", "s3cret-pw")
        val text = config.toString()
        assertFalse("s3cret-pw" in text)
        assertFalse("192.168.1.20" in text)
        assertFalse("poom" in text)
        // And it round-trips through what NasStore seals.
        assertEquals(config, NasConfig.fromJson(config.toJson()))
        assertNull(NasConfig.fromJson("not json"))
    }

    @Test
    fun `the settings are sealed with the Keystore box and kept out of backups`() {
        val nas = source("files/Nas.kt")
        val store = nas.substringAfter("object NasStore")
        assertTrue("SecretBox.seal" in store && "SecretBox.open" in store)
        assertTrue("noBackupFilesDir" in store)
        assertFalse("SharedPreferences" in store)
    }

    // ------------------------------------------------ NAS failures in words

    private fun smb(status: Long) = SMBApiException(status, SMB2MessageCommandCode.SMB2_SESSION_SETUP, null)

    @Test
    fun `each way the NAS fails has its own words`() {
        assertEquals(NasProblem.LOGON_FAILURE, NasProblem.of(smb(0xC000006DL)))
        assertEquals(NasProblem.ACCOUNT_BLOCKED, NasProblem.of(smb(0xC0000234L)))
        assertEquals(NasProblem.SHARE_NOT_FOUND, NasProblem.of(smb(0xC00000CCL)))
        assertEquals(NasProblem.ACCESS_DENIED, NasProblem.of(smb(0xC0000022L)))
        assertEquals(NasProblem.PATH_NOT_FOUND, NasProblem.of(smb(0xC0000034L)))
        assertEquals(NasProblem.HOST_NOT_FOUND, NasProblem.of(java.net.UnknownHostException("nas.local")))
        assertEquals(NasProblem.NO_ANSWER, NasProblem.of(java.net.SocketTimeoutException()))
        // smbj's own wait for an answer, wrapped as it throws it.
        assertEquals(NasProblem.NO_ANSWER, NasProblem.of(com.hierynomus.protocol.transport.TransportException(
            java.util.concurrent.TimeoutException())))
        assertEquals(NasProblem.NO_ANSWER, NasProblem.of(java.net.ConnectException("ECONNREFUSED")))
        assertEquals(NasProblem.LOCAL_NETWORK_DENIED,
                     NasProblem.of(java.net.ConnectException("connect failed: EPERM (Operation not permitted)")))
        assertEquals(NasProblem.NO_NETWORK, NasProblem.of(java.net.SocketException("ENETUNREACH")))
        // Wrapped, as smbj wraps: the cause decides.
        assertEquals(NasProblem.HOST_NOT_FOUND,
                     NasProblem.of(java.io.IOException("x", java.net.UnknownHostException())))
        assertEquals(NasProblem.OTHER, NasProblem.of(IllegalStateException()))
    }

    @Test
    fun `every NAS problem and every refusal has a sentence on screen`() {
        val strings = file("src/main/res/values/strings.xml")
        val screen = source("files/FilesActivity.kt")
        for (p in NasProblem.values()) assertTrue("NasProblem.${p.name} has words", "NasProblem.${p.name} ->" in screen)
        for (r in Reason.values()) assertTrue("Reason.${r.name} has words", "FileOps.Reason.${r.name} ->" in screen)
        for (name in Regex("""R\.string\.((?:files|nas)_[a-z_]+)""").findAll(screen).map { it.groupValues[1] }.toSet()) {
            assertTrue("strings.xml has $name", "name=\"$name\"" in strings)
        }
    }

    // ------------------------------------------------ read only, quiet, and inside the kiosk

    @Test
    fun `the NAS is read only in code`() {
        val nas = source("files/Nas.kt").substringAfter("class NasSession").substringBefore("object NasStore")
        for (write in listOf(".mkdir(", ".rm(", ".rmdir(", "GENERIC_WRITE", "GENERIC_ALL", "FILE_OVERWRITE",
                             "FILE_CREATE", "FILE_SUPERSEDE", "outputStream", ".write(", "deleteOnClose", "setFileInformation")) {
            // download() writes to the PHONE's file; that one line is the only allowed outputStream.
            val count = Regex(Regex.escape(write)).findAll(nas).count()
            val allowed = if (write == "outputStream") 1 else if (write == ".write(") 1 else 0
            assertTrue("NAS code uses $write $count times", count <= allowed)
        }
        assertTrue("GENERIC_READ" in nas && "FILE_OPEN" in nas)
    }

    @Test
    fun `nothing about the NAS or a file's name goes into a log line`() {
        for (path in listOf("files/FilesActivity.kt", "files/Nas.kt")) {
            val code = source(path)
            // An enum's name and an exception's class name are words we chose, not data.
            for (line in Regex("""Log\.[diwe]\([^\n]*""").findAll(code).map { it.value }
                .map { it.replace("reason.name", "").replace("javaClass.simpleName", "") }) {
                for (secret in listOf("host", "password", "user", "config", ".name", "path", "e.message", "absolutePath")) {
                    assertFalse("$path logs $secret: $line", secret in line.substringAfter(",").lowercase())
                }
            }
        }
    }

    @Test
    fun `the file manager starts nothing but the kiosk's own screen`() {
        val screen = source("files/FilesActivity.kt")
        val starts = Regex("""startActivity\(([^\n]*)""").findAll(screen).map { it.groupValues[1] }.toList()
        assertEquals(1, starts.size)
        assertTrue(starts.single().contains("MainActivity::class.java"))
        for (outside in listOf("ACTION_VIEW", "ACTION_SEND", "createChooser", "ACTION_OPEN_DOCUMENT",
                               "ACTION_MANAGE_APP_ALL_FILES", "Settings.ACTION")) {
            assertFalse("FilesActivity must not use $outside", outside in screen)
        }
    }

    @Test
    fun `the Control Panel opens it and the manifest keeps it inside the app`() {
        val panel = source("settings/SettingsActivity.kt")
        assertTrue("ic_pixel_files" in panel && "FilesActivity::class.java" in panel)
        val manifest = file("src/main/AndroidManifest.xml")
        val activity = manifest.substringAfter("android:name=\".files.FilesActivity\"").substringBefore("/>")
        assertTrue("android:exported=\"false\"" in activity)
        assertTrue("MANAGE_EXTERNAL_STORAGE" in manifest)
        assertTrue("NEARBY_WIFI_DEVICES" in manifest && "neverForLocation" in manifest)
        assertFalse("<uses-permission android:name=\"android.permission.ACCESS_FINE_LOCATION\"" in manifest)
    }

    // ------------------------------------------------ icons

    @Test
    fun `the new icons are 16x16 squares with at most four colours`() {
        for (name in listOf("files", "folder", "file", "lock", "nas", "phone")) {
            val xml = file("src/main/res/drawable/ic_pixel_$name.xml")
            assertTrue(name, "android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
            val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
            assertTrue("$name has ${colours.size} colours", colours.size in 1..4)
            for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
                assertFalse("$name draws curves", Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
            }
        }
    }

    // ------------------------------------------------ words

    @Test
    fun `the file manager speaks formal written Thai`() {
        val strings = file("src/main/res/values/strings.xml")
        val ours = Regex("""<string name="((?:files|nas)_[a-z_]+)">([^<]*)<""").findAll(strings)
            .map { it.groupValues[1] to it.groupValues[2] }.toList()
        assertTrue(ours.size > 50)
        for ((name, text) in ours) {
            for (spoken in listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว", "ไม่ใส่ก็ได้")) {
                assertFalse("$name is colloquial ($spoken): $text", spoken in text)
            }
        }
    }

    private fun source(path: String) = file("src/main/java/com/mammonrn/phoneaikiosk/$path")

    private fun sources(): List<File> = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.exists() }.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText()
}
