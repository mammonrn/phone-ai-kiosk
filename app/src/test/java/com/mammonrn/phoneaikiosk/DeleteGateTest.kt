package com.mammonrn.phoneaikiosk

import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * 0.60.0 (Poom): nothing is deleted without the owner's face or pattern. In
 * 0.59.0 a file, a folder or a playlist could be deleted after "are you sure"
 * alone. Every way to delete now runs only after IdentityGate passes.
 */
class DeleteGateTest {

    private fun src(path: String): String =
        listOf(File("src/main/java/com/mammonrn/phoneaikiosk/$path"), File("app/src/main/java/com/mammonrn/phoneaikiosk/$path"))
            .first { it.exists() }.readText().replace("\r\n", "\n")

    @Test
    fun `a file or a folder is deleted only after a pass`() {
        val files = src("files/FilesActivity.kt")
        val calls = Regex("""runDelete\(file\)""").findAll(files).map { m -> files.substring(maxOf(0, m.range.first - 40), m.range.first) }.toList()
        assertTrue(calls.isNotEmpty())
        for (before in calls) assertTrue("runDelete outside the gate: $before", "afterPass = {" in before)
        assertTrue("IdentityGate.ask(this)" in files && "IdentityGate.passed(data)" in files)
        // Forgetting the NAS settings deletes the stored password: gated as well.
        val forget = files.substringAfter("R.string.nas_forget_confirm").substringBefore("nas_privacy")
        assertTrue("NasStore.delete" in forget.substringAfter("afterPass = {"))
    }

    @Test
    fun `a playlist is deleted, or emptied, only after a pass`() {
        val pages = src("media/PlaylistPages.kt")
        val delete = pages.substringAfter("R.string.playlist_delete_yes")
        assertTrue(delete.indexOf("host.afterIdentity {") in 0 until delete.indexOf("it.delete(list.id)"))
        for (screen in listOf("media/MusicActivity.kt", "media/VideoActivity.kt")) {
            val s = src(screen)
            assertTrue(screen, "override fun afterIdentity(then: () -> Unit) { afterPass = then; IdentityGate.ask(" in s)
            assertTrue(screen, "if (IdentityGate.passed(data)) then?.invoke()" in s)
        }
        val clear = src("media/MusicActivity.kt").substringAfter("add(getString(R.string.music_list_clear)) {").substringBefore("}\n")
        assertTrue("MusicPlayer.clear" in clear.substringAfter("afterPass = {"))
    }
}
