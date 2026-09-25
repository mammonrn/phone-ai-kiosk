package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.drive.DriveAuth
import com.mammonrn.phoneaikiosk.drive.DriveFile
import com.mammonrn.phoneaikiosk.drive.DriveHttpError
import com.mammonrn.phoneaikiosk.drive.DriveJson
import com.mammonrn.phoneaikiosk.drive.DriveNameProblem
import com.mammonrn.phoneaikiosk.drive.DriveNames
import com.mammonrn.phoneaikiosk.drive.DrivePath
import com.mammonrn.phoneaikiosk.drive.DriveProblem
import com.mammonrn.phoneaikiosk.drive.DriveStatus
import com.mammonrn.phoneaikiosk.drive.DriveStatus.Companion.Outcome
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * Google Drive (0.61.0, DESIGN.md 5ฑ): what Drive answers is read right, the
 * breadcrumb survives any name, every "not connected" has its words, and the
 * locked rules hold in the code itself — trash only, never deleted for good;
 * trash only after the owner's face or pattern; no token written; Google's
 * screen kept out of the kiosk until Poom approves.
 */
class DriveTest {

    // ------------------------------------------------ what Drive answers

    @Test
    fun `a files list page is read, folders first, Google Docs marked`() {
        val page = DriveJson.page("""
            {"nextPageToken":"NEXT","files":[
              {"id":"f1","name":"song.mp3","mimeType":"audio/mpeg","size":"4096","modifiedTime":"2026-09-24T08:53:01.123Z"},
              {"id":"d1","name":"Music","mimeType":"application/vnd.google-apps.folder","modifiedTime":"2026-01-01T00:00:00Z"},
              {"id":"g1","name":"Budget","mimeType":"application/vnd.google-apps.spreadsheet"},
              {"name":"no id, dropped","mimeType":"text/plain"},
              {"id":"a1","name":"alpha.txt","mimeType":"text/plain","size":"12"}
            ]}""")
        assertEquals("NEXT", page.next)
        assertEquals(4, page.files.size)
        val song = page.files.first { it.id == "f1" }
        assertEquals(4096L, song.size)
        assertEquals(java.time.Instant.parse("2026-09-24T08:53:01.123Z").toEpochMilli(), song.modifiedMs)
        assertFalse(song.folder || song.native)
        val folder = page.files.first { it.id == "d1" }
        assertTrue(folder.folder && !folder.native)
        assertEquals(0L, folder.size)
        val sheet = page.files.first { it.id == "g1" }
        assertTrue(sheet.native && !sheet.folder)
        assertEquals(listOf("Music", "alpha.txt", "Budget", "song.mp3"), DriveJson.sorted(page.files).map { it.name })
    }

    @Test
    fun `the last page has no next token, and an empty folder is empty`() {
        val page = DriveJson.page("""{"files":[]}""")
        assertNull(page.next)
        assertTrue(page.files.isEmpty())
        assertNull(DriveJson.page("""{"nextPageToken":"","files":[]}""").next)
        assertEquals(0L, DriveJson.time("not a time"))
    }

    @Test
    fun `a file's name never goes into its own toString`() {
        val f = DriveFile("id", "private-name.jpg", "image/jpeg", 1, 0)
        assertFalse("private-name" in f.toString())
    }

    @Test
    fun `errors, quota and query quoting`() {
        assertEquals("storageQuotaExceeded", DriveJson.errorReason(
            """{"error":{"code":403,"errors":[{"reason":"storageQuotaExceeded"}],"status":"PERMISSION_DENIED"}}"""))
        assertEquals("NOT_FOUND", DriveJson.errorReason("""{"error":{"code":404,"status":"NOT_FOUND"}}"""))
        assertEquals("", DriveJson.errorReason("<html>"))
        assertEquals("", DriveJson.errorReason(null))
        assertEquals(15L to 100L, DriveJson.quota("""{"storageQuota":{"limit":"100","usage":"85"}}"""))
        assertNull(DriveJson.quota("""{"storageQuota":{"usage":"85"}}"""))
        assertEquals("""'it\'s a \\ test'""", DriveJson.quoted("""it's a \ test"""))
    }

    @Test
    fun `an HTTP answer becomes words a person can act on`() {
        assertEquals(DriveProblem.NOT_FOUND, DriveProblem.ofHttp(404, "notFound"))
        assertEquals(DriveProblem.FULL, DriveProblem.ofHttp(403, "storageQuotaExceeded"))
        assertEquals(DriveProblem.BUSY, DriveProblem.ofHttp(403, "userRateLimitExceeded"))
        assertEquals(DriveProblem.BUSY, DriveProblem.ofHttp(429, ""))
        assertEquals(DriveProblem.API_OFF, DriveProblem.ofHttp(403, "accessNotConfigured"))
        assertEquals(DriveProblem.NO_PERMISSION, DriveProblem.ofHttp(403, "insufficientFilePermissions"))
        assertEquals(DriveProblem.BUSY, DriveProblem.ofHttp(503, ""))
        assertEquals(DriveProblem.OTHER, DriveProblem.ofHttp(400, "badRequest"))
        assertEquals(DriveProblem.NO_NETWORK, DriveProblem.of(java.net.UnknownHostException()))
        assertEquals(DriveProblem.NO_NETWORK, DriveProblem.of(java.io.IOException("x", java.net.SocketTimeoutException())))
        assertEquals(DriveProblem.FULL, DriveProblem.of(DriveHttpError(403, "storageQuotaExceeded")))
    }

    // ------------------------------------------------ paths and the breadcrumb

    @Test
    fun `a path walks down and back up, whatever the names`() {
        val music = DrivePath.child(DrivePath.ROOT, "1AbC-_x", "เพลง / 2024 | ดี")
        val deep = DrivePath.child(music, "9xYz", "a%b c")
        assertEquals("9xYz", DrivePath.idOf(deep))
        assertEquals("1AbC-_x", DrivePath.idOf(music))
        assertEquals("root", DrivePath.idOf(DrivePath.ROOT))
        assertEquals("a%b c", DrivePath.nameOf(deep))
        assertNull(DrivePath.nameOf(DrivePath.ROOT))
        assertEquals(music, DrivePath.parent(deep))
        assertEquals(DrivePath.ROOT, DrivePath.parent(music))
        assertNull(DrivePath.parent(DrivePath.ROOT))
        assertEquals(listOf("Drive ของฉัน" to "root", "เพลง / 2024 | ดี" to music, "a%b c" to deep),
                     DrivePath.crumbs(deep, "Drive ของฉัน"))
        assertEquals(listOf("Drive" to "root"), DrivePath.crumbs(DrivePath.ROOT, "Drive"))
        assertTrue(DrivePath.isValid(deep) && DrivePath.isValid(DrivePath.ROOT))
        assertFalse(DrivePath.isValid("/storage/emulated/0"))
        assertFalse(DrivePath.isValid("root/..|x"))
    }

    @Test
    fun `a name already in the Drive folder is made free, as on the phone`() {
        val taken = listOf("photo.jpg", "Photo (2).JPG", "Album")
        assertEquals("new.jpg", DriveNames.free(taken, "new.jpg"))
        assertEquals("PHOTO (3).jpg", DriveNames.free(taken, "PHOTO.jpg"))
        assertEquals("Album (2)", DriveNames.free(taken, "Album"))
        assertEquals(DriveNameProblem.NAME_EMPTY, DriveNames.problem("  ", taken))
        assertEquals(DriveNameProblem.NAME_TAKEN, DriveNames.problem("ALBUM", taken))
        assertEquals(DriveNameProblem.NAME_TOO_LONG, DriveNames.problem("ก".repeat(90), taken))
        assertNull(DriveNames.problem("Album", taken, self = "album"))
        // Drive allows '/' in a name; the path encodes it.
        assertNull(DriveNames.problem("a/b", taken))
    }

    // ------------------------------------------------ not connected, in words, never hanging

    @Test
    fun `every way of not being connected has its own status`() {
        fun d(outcome: Outcome?, allowed: Boolean = false, online: Boolean = true, off: Boolean = false) =
            DriveStatus.decide(allowed, online, off, outcome)
        assertEquals(DriveStatus.CONNECTED, d(Outcome.Token))
        assertEquals(DriveStatus.NEEDS_APPROVAL, d(Outcome.NeedsConsent, allowed = false))
        assertEquals(DriveStatus.NEEDS_CONSENT, d(Outcome.NeedsConsent, allowed = true))
        assertEquals(DriveStatus.NOT_REGISTERED, d(Outcome.Error(DriveStatus.DEVELOPER_ERROR)))
        assertEquals(DriveStatus.NO_ACCOUNT, d(Outcome.Error(DriveStatus.SIGN_IN_REQUIRED)))
        assertEquals(DriveStatus.NO_ACCOUNT, d(Outcome.Error(DriveStatus.INVALID_ACCOUNT)))
        assertEquals(DriveStatus.NO_NETWORK, d(Outcome.Error(DriveStatus.NETWORK_ERROR)))
        assertEquals(DriveStatus.NO_PLAY_SERVICES, d(Outcome.Error(DriveStatus.SERVICE_VERSION_UPDATE_REQUIRED)))
        assertEquals(DriveStatus.TIMED_OUT, d(Outcome.Timeout))
        assertEquals(DriveStatus.TIMED_OUT, d(Outcome.Error(DriveStatus.TIMEOUT)))
        assertEquals(DriveStatus.CANCELLED, d(Outcome.Error(DriveStatus.CANCELED)))
        assertEquals(DriveStatus.FAILED, d(Outcome.Error(13)))
        assertEquals(DriveStatus.FAILED, d(Outcome.Other))
        assertEquals(DriveStatus.FAILED, d(null))
        // Offline is said as offline, whatever Google answered — but a token in hand still works.
        assertEquals(DriveStatus.NO_NETWORK, d(Outcome.Error(DriveStatus.DEVELOPER_ERROR), online = false))
        assertEquals(DriveStatus.NO_NETWORK, d(Outcome.Timeout, online = false))
        assertEquals(DriveStatus.CONNECTED, d(Outcome.Token, online = false))
        // The owner's own "ยกเลิกการเชื่อมต่อ" wins over everything.
        assertEquals(DriveStatus.DISCONNECTED, d(Outcome.Token, off = true))
        // "เชื่อมต่อ" shows only where pressing it can change something.
        assertTrue(DriveStatus.NEEDS_APPROVAL.connectable && DriveStatus.DISCONNECTED.connectable)
        assertFalse(DriveStatus.NOT_REGISTERED.connectable || DriveStatus.NO_NETWORK.connectable || DriveStatus.CONNECTED.connectable)
    }

    @Test
    fun `every status and every problem has words on screen`() {
        val screen = source("drive/DriveActivity.kt")
        val strings = file("src/main/res/values/strings_drive.xml")
        for (s in DriveStatus.values()) assertTrue("DriveStatus.${s.name} has words", "DriveStatus.${s.name} ->" in screen)
        for (p in DriveProblem.values()) assertTrue("DriveProblem.${p.name} has words", "DriveProblem.${p.name} ->" in screen)
        val used = Regex("""R\.string\.(drive_[a-z_]+)""").findAll(screen + source("files/FilesActivity.kt")).map { it.groupValues[1] }.toSet()
        assertTrue(used.size > 40)
        for (name in used) assertTrue("strings_drive.xml has $name", "name=\"$name\"" in strings)
        for ((name, text) in Regex("""<string name="(drive_[a-z_]+)">([^<]*)<""").findAll(strings).map { it.groupValues[1] to it.groupValues[2] }) {
            for (spoken in listOf("ได้เลย", "นะครับ", "นะคะ", " เอง", "แอพ", "เข้าใจแล้ว")) {
                assertFalse("$name is colloquial ($spoken): $text", spoken in text)
            }
        }
    }

    @Test
    fun `asking Google and calling Drive always end in time`() {
        val auth = source("drive/DriveAuth.kt")
        // Every wait on Google has its limit: as many limits as waits.
        val waits = Regex("""Tasks\.await\(""").findAll(auth).count()
        assertTrue(waits >= 4)
        assertEquals(waits, Regex("""ASK_SECONDS, TimeUnit\.SECONDS\)""").findAll(auth).count())
        val api = source("drive/DriveApi.kt")
        assertTrue("conn.connectTimeout = CONNECT_MS" in api && "conn.readTimeout = READ_MS" in api)
        // The folder browser ends its loading on any throw; the Drive source only throws.
        val browser = source("files/FolderBrowser.kt")
        assertTrue("runCatching { from.list(at) }" in browser)
    }

    // ------------------------------------------------ the locked rules, in the code

    @Test
    fun `nothing is ever deleted from Drive for good - trash only`() {
        val all = sources().joinToString("\n") { it.readText() }
        for (forbidden in listOf("emptyTrash", "files.delete", "\"DELETE\"", "requestMethod = \"DELETE",
                                 "to \"DELETE\"", "/trash?", "Method-Override\" to \"DELETE")) {
            assertFalse("the app must not use $forbidden", forbidden in all)
        }
        val api = source("drive/DriveApi.kt")
        // The one removal: trashed=true through PATCH.
        val trash = api.substringAfter("fun trash(").substringBefore("\n    }")
        assertTrue(trash, "put(\"trashed\", true)" in trash && "patch(" in trash)
        assertEquals(1, Regex("""\"trashed\"""").findAll(api).count())
        assertTrue("X-HTTP-Method-Override\" to \"PATCH\"" in api)
        // Every method this file sends is one of these.
        for (m in Regex("""(?:call|open)\("[a-z-]+", "([A-Z]+)"""").findAll(api).map { it.groupValues[1] }) {
            assertTrue("method $m", m in setOf("GET", "POST", "PUT"))
        }
    }

    @Test
    fun `moving to Drive's trash happens only after the owner's face or pattern`() {
        val screen = source("drive/DriveActivity.kt")
        val calls = Regex("""runTrash\(path\)""").findAll(screen).map { m -> screen.substring(maxOf(0, m.range.first - 40), m.range.first) }.toList()
        assertEquals(1, calls.size)
        for (before in calls) assertTrue("runTrash outside the gate: $before", "afterPass = {" in before)
        assertTrue("IdentityGate.ask(this)" in screen && "if (IdentityGate.passed(data))" in screen)
        assertEquals(1, Regex("""api\.trash\(""").findAll(screen).count())
        assertTrue("api.trash(" in screen.substringAfter("private fun runTrash("))
    }

    @Test
    fun `no token is written anywhere or logged`() {
        for (f in sources().filter { "/drive/" in it.path.replace('\\', '/') }) {
            val code = f.readText()
            for (write in listOf("putString", "openFileOutput", "writeText", "writeBytes", "FileWriter", "SecretBox.seal")) {
                if (f.name == "DriveApi.kt" && write == "writeBytes") continue
                assertFalse("${f.name} writes with $write", write in code)
            }
            for (line in Regex("""Log\.[diwe]\([^\n]*""").findAll(code).map { it.value }) {
                val said = line.substringAfter(",").lowercase()
                for (secret in listOf("token\"", "\$token", "{token", "account", ".name}", "\$name", "path", "e.message", "absolutepath", "email")) {
                    assertFalse("${f.name} logs $secret: $line", secret in said.replace("token dropped", "").replace("new token", ""))
                }
            }
        }
        // The Authorization header is the token's only way out of memory.
        val api = source("drive/DriveApi.kt")
        assertEquals(1, Regex(Regex.escape("\$token")).findAll(api).count())
        assertTrue("\"Bearer \$token\"" in api)
    }

    @Test
    fun `Google's screen is in the kiosk only for a moment - approved by Poom, withdrawn on answer, cancel or 5 minutes`() {
        // Poom approved (2026-09-25) Play services in the locked task for the consent screen only.
        assertTrue(DriveAuth.SIGN_IN_ALLOWED)
        assertEquals(5L * 60 * 1000, DriveAuth.CONSENT_MAX_MS)
        val auth = source("drive/DriveAuth.kt")
        val allowBody = auth.substringAfter("fun allowConsentScreen(").substringBefore("\n    }")
        assertTrue("timer.postDelayed(it, CONSENT_MAX_MS)" in allowBody)          // the 5-minute cap is armed with it
        assertTrue("withdrawn(app, \"timeout\")" in allowBody)
        val finish = auth.substringAfter("fun finishConsent(").substringBefore("\n    }")
        assertTrue("\"answered\" else \"cancelled\"" in finish)                  // done or cancelled: withdrawn at once
        val withdrawn = auth.substringAfter("private fun withdrawn(").substringBefore("\n    }")
        assertTrue("timer.removeCallbacks" in withdrawn && "restoreAllowlist(context)" in withdrawn)
        val allow = auth.substringAfter("fun allowConsentScreen(").substringBefore("\n    }")
        assertTrue("if (!SIGN_IN_ALLOWED) return false" in allow)
        assertTrue(allow.indexOf("if (!SIGN_IN_ALLOWED) return false") < allow.indexOf("setLockTaskPackages"))
        // Only here does anything add a package to the allowlist, and only Play services.
        for (f in sources()) {
            val code = f.readText()
            if ("GMS_PACKAGE" in code && "setLockTaskPackages" in code) assertEquals("DriveAuth.kt", f.name)
        }
        // Put back on every Drive screen resume and after Google's answer.
        val screen = source("drive/DriveActivity.kt")
        assertTrue("DriveAuth.restoreAllowlist(this)" in screen.substringAfter("override fun onResume()").substringBefore("\n    }"))
        assertTrue("withdrawn(activity," in auth.substringAfter("fun finishConsent(").substringBefore("\n    }"))
        // The permanent list is untouched.
        assertEquals(3, LockTaskAllowlist.packages("self").size)
    }

    @Test
    fun `the Drive screen starts nothing but the kiosk's own screens`() {
        val screen = source("drive/DriveActivity.kt")
        val starts = Regex("""startActivity\(([^\n]*)""").findAll(screen).map { it.groupValues[1] }.toList()
        val ours = listOf("MainActivity::class.java", "MusicActivity::class.java", "VideoActivity::class.java", "ImageViewerActivity::class.java")
        assertTrue(starts.isNotEmpty())
        for (start in starts) assertTrue(start, ours.any { it in start })
        for (outside in listOf("ACTION_VIEW", "ACTION_SEND", "createChooser", "ACTION_OPEN_DOCUMENT", "Settings.ACTION", "startIntentSender")) {
            assertFalse("DriveActivity must not use $outside", outside in screen)
        }
        // A single file, never a playlist (DESIGN.md 5ฌ).
        assertTrue("FilesActivity.SINGLE" in screen && "Playlist" !in screen)
        val manifest = file("src/main/AndroidManifest.xml")
        assertTrue("android:exported=\"false\"" in manifest.substringAfter("android:name=\".drive.DriveActivity\"").substringBefore("/>"))
    }

    @Test
    fun `the drive icon is our own 16x16 pixel art`() {
        val xml = file("src/main/res/drawable/ic_pixel_drive.xml")
        assertTrue("android:viewportWidth=\"16\"" in xml && "android:viewportHeight=\"16\"" in xml)
        val colours = Regex("""fillColor="(#[0-9A-Fa-f]{6})"""").findAll(xml).map { it.groupValues[1].uppercase() }.toSet()
        assertTrue(colours.size in 1..4)
        for (data in Regex("""pathData="([^"]+)"""").findAll(xml).map { it.groupValues[1] }) {
            assertFalse(Regex("[cCsSqQtTaAlL]").containsMatchIn(data))
        }
    }

    private fun source(path: String) = file("src/main/java/com/mammonrn/phoneaikiosk/$path")

    private fun sources(): List<File> = listOf(File("src/main/java"), File("app/src/main/java"))
        .first { it.exists() }.walkTopDown().filter { it.extension == "kt" }.toList()

    private fun file(path: String): String =
        listOf(File(path), File("app/$path")).first { it.exists() }.readText().replace("\r\n", "\n")

    /** Poom 2026-09-25: the five-minute case, on a simulated clock — no need to take the Drive grant away. */
    @Test
    fun `Google's screen is withdrawn after five minutes on a simulated clock`() {
        val w = com.mammonrn.phoneaikiosk.drive.ConsentWindow(DriveAuth.CONSENT_MAX_MS)
        assertFalse(w.expired(0))                         // nothing open, nothing to withdraw
        w.open(now = 10_000)
        assertTrue(w.isOpen)
        assertFalse(w.expired(10_000 + 60_000))           // one minute: still allowed
        assertEquals(60_000L, w.remaining(10_000 + 4 * 60_000))
        assertFalse(w.expired(10_000 + 5 * 60_000 - 1))   // a millisecond before five minutes
        assertTrue(w.expired(10_000 + 5 * 60_000))        // five minutes: withdrawn
        assertTrue(w.expired(5_000))                      // a clock that went backwards: withdrawn
        assertTrue(w.close())
        assertFalse(w.isOpen)
        assertFalse(w.expired(10_000 + 10 * 60_000))      // after closing, nothing left open
        assertFalse(w.close())
        // Wired: the timer arms the window, withdrawing closes it, the Drive screen checks it on resume.
        val auth = source("drive/DriveAuth.kt")
        assertTrue("window.open(android.os.SystemClock.elapsedRealtime())" in auth)
        assertTrue("window.close()" in auth.substringAfter("private fun withdrawn(").substringBefore("\n    }"))
        val screen = source("drive/DriveActivity.kt")
        assertTrue("DriveAuth.withdrawIfExpired(this)" in screen.substringAfter("override fun onResume()").substringBefore("\n    }"))
    }
}
