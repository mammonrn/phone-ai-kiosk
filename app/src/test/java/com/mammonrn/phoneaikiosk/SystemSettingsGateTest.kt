package com.mammonrn.phoneaikiosk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poom 2026-09-26 ("สแกนหน้าก่อนเข้าตั้งค่าระบบรวม WiFi: ใช่"): every way from the kiosk
 * into a system settings screen (com.android.settings) passes the identity check first,
 * with the hour's grant (not a fresh scan), and the settings package joins the lock-task
 * allowlist only after that pass. Read from the source, as the other gate tests do.
 */
class SystemSettingsGateTest {

    private val root = listOf(File("src"), File("app/src")).first { File(it, "main/java").exists() }
    private val java = File(root, "main/java/com/mammonrn/phoneaikiosk")

    private fun source(path: String) = File(java, path).readText()

    /** Every Kotlin file the app is built from (main and debug). */
    private fun allSources(): Map<String, String> =
        listOf(File(root, "main/java"), File(root, "debug/java")).filter { it.exists() }
            .flatMap { dir -> dir.walkTopDown().filter { it.isFile && it.extension == "kt" }.map { it.relativeTo(dir).path.replace('\\', '/') to it.readText() } }
            .toMap()

    /**
     * The body of `fun [name](` in [src]: braces matched, or up to the next declaration for
     * an expression body (comments are few enough not to matter here).
     */
    private fun body(src: String, name: String): String {
        val at = Regex("""fun $name\(""").find(src)?.range?.last ?: error("no fun $name")
        var parens = 0
        var i = at
        while (true) { when (src[i]) { '(' -> parens++; ')' -> { parens--; if (parens == 0) break } }; i++ }
        while (src[i] != '{' && src[i] != '=') i++
        if (src[i] == '=') {
            val end = listOf(src.indexOf("\n\n", i), src.indexOf("\n    fun ", i), src.length).filter { it >= 0 }.min()
            return src.substring(i, end)
        }
        val open = i
        var depth = 0
        for (i in open until src.length) {
            when (src[i]) { '{' -> depth++; '}' -> { depth--; if (depth == 0) return src.substring(open, i + 1) } }
        }
        error("unbalanced $name")
    }

    /** Lines that call [call], comments left out. */
    private fun callers(call: String): List<String> = allSources().flatMap { (path, text) ->
        text.lines().mapIndexedNotNull { i, line ->
            val code = line.substringBefore("//").trim()
            if (code.startsWith("*") || code.startsWith("/*")) null
            else if (call in code) "$path:${i + 1}: $code" else null
        }
    }

    @Test fun `only WifiPanel and BluetoothSystem open a system settings screen or allow the settings app`() {
        val screen = Regex("""Settings\.(ACTION_[A-Z_]+|Panel\.ACTION_[A-Z_]+)""")
        val literal = Regex(""""(com\.android\.settings|android\.settings\.[A-Z_.]+)"""")
        val allowed = setOf("com/mammonrn/phoneaikiosk/settings/WifiPanel.kt", "com/mammonrn/phoneaikiosk/settings/BluetoothSystem.kt")
        for ((path, text) in allSources()) {
            if (path in allowed) continue
            assertFalse("$path starts a system settings screen", screen.containsMatchIn(text))
            assertFalse("$path names the settings app", literal.containsMatchIn(text))
            assertFalse("$path allows the settings app", "SETTINGS_PACKAGE" in text && "setLockTaskPackages" in text)
        }
        // The shared allowlist never holds it.
        assertFalse("SETTINGS" in body(source("LockTaskAllowlist.kt"), "packages"))
    }

    @Test fun `the WiFi panel opens only from a passed check, after the allowlist is restored`() {
        assertEquals(listOf("com/mammonrn/phoneaikiosk/settings/SettingsActivity.kt"),
            callers("WifiPanel.openAfterPass(").map { it.substringBefore(':') }.distinct())
        val panel = source("settings/SettingsActivity.kt")
        // The tile asks for the check and touches nothing else.
        val tile = body(panel, "openWifi")
        assertTrue(tile, "IdentityGate.ask(this)" in tile)
        for (no in listOf("WifiPanel", "setLockTaskPackages", "openWifiNow")) assertFalse("openWifi uses $no", no in tile)
        // Only openWifiNow opens it, and only a passed check schedules openWifiNow.
        assertEquals(1, Regex("""WifiPanel\.openAfterPass\(""").findAll(panel).count())
        assertTrue("WifiPanel.openAfterPass(" in body(panel, "openWifiNow"))
        val uses = Regex("""openWifiNow\(\)""").findAll(panel).count()
        assertEquals("openWifiNow: its definition and one scheduled call", 2, uses)
        val checked = body(panel, "wifiChecked")
        val passed = checked.indexOf("IdentityGate.passed(data)")
        val scheduled = checked.indexOf("wifiAfterResume = { openWifiNow() }")
        val refused = checked.indexOf("check not passed")
        assertTrue(checked, passed in 0 until scheduled && scheduled < refused)
        assertFalse("a refused check touches the allowlist", "WifiPanel" in checked.substring(refused))
        // wifiChecked answers only the gate's request.
        val result = body(panel, "onActivityResult")
        assertTrue(Regex("""requestCode == com\.mammonrn\.phoneaikiosk\.auth\.IdentityGate\.REQUEST\)\s*\{\s*wifiChecked\(data\)""").containsMatchIn(result))
        assertEquals(2, Regex("""wifiChecked\(""").findAll(panel).count())
        // onResume restores the kiosk's own list BEFORE the visit starts.
        val resume = body(panel, "onResume")
        assertTrue(resume, resume.indexOf("WifiPanel.restore(this)") in 0 until resume.indexOf("wifiAfterResume?.let"))
    }

    @Test fun `the Bluetooth system screen opens only from a passed check`() {
        assertEquals(listOf("com/mammonrn/phoneaikiosk/settings/BluetoothActivity.kt"),
            callers("BluetoothSystem.openSettings(").map { it.substringBefore(':') }.distinct())
        val page = source("settings/BluetoothActivity.kt")
        assertTrue("BluetoothSystem.openSettings(" in body(page, "openSystem"))
        // openSystem(): its definition, and the FORGET and SYSTEM answers of a passed check.
        val calls = page.lines().filter { "openSystem()" in it.substringBefore("//") }
        assertEquals(calls.joinToString("\n"), 3, calls.size)
        assertTrue(calls.any { "fun openSystem()" in it })
        assertTrue(calls.filterNot { "fun openSystem()" in it }.all { "Want.FORGET" in it || "Want.SYSTEM" in it })
        val result = body(page, "onActivityResult")
        assertTrue(result.indexOf("IdentityGate.passed(data)") in 0 until result.indexOf("Want.SYSTEM"))
        // Every button to the system screen goes through the check.
        assertTrue(page.lines().filter { "button(" in it }.none { "openSystem" in it })
        assertTrue(Regex("""systemAfterCheck\(\) = check\(Want\.SYSTEM\)""").containsMatchIn(page))
        assertTrue("IdentityGate.ask(this)" in body(page, "check"))
    }

    @Test fun `the pairing confirmation is allowed only after a passed search check`() {
        assertEquals(listOf("com/mammonrn/phoneaikiosk/settings/BluetoothActivity.kt"),
            callers("BluetoothSystem.allowPairingDialog(").map { it.substringBefore(':') }.distinct())
        val page = source("settings/BluetoothActivity.kt")
        assertTrue("allowPairingDialog" in body(page, "pair"))
        // A pairing needs a found device, found only by a search a passed check started.
        val starts = page.lines().filter { "startSearch()" in it.substringBefore("//") && "l.startSearch()" !in it }
        assertTrue(starts.joinToString("\n"), starts.all { "fun startSearch()" in it || "Want.SEARCH" in it })
    }

    @Test fun `the check is the hour's grant, not a fresh scan`() {
        val gate = body(source("auth/IdentityGate.kt"), "ask")
        assertTrue(gate, "VerifyActivity.Mode.VERIFY" in gate)
        assertFalse(gate, "fresh" in gate)
    }

    @Test fun `a refused WiFi check says why in formal words`() {
        val strings = File(root, "main/res/values/strings.xml").readText()
        for (name in listOf("wifi_check_nothing_enrolled", "wifi_check_cancelled", "wifi_check_failed")) {
            val text = Regex("""<string name="$name">([^<]+)</string>""").find(strings)?.groupValues?.get(1)
            assertTrue(name, text != null && text.startsWith("ยังไม่ได้เปิดหน้า WiFi เพราะ"))
            for (spoken in listOf("นะ", "ได้เลย", "เอง")) assertFalse("$name: $spoken", spoken in text!!)
        }
    }
}
