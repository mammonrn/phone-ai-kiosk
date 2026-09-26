package com.mammonrn.phoneaikiosk

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Which packages are closed (suspend, then unsuspend) when they leave the lock task list. */
class TemporaryAppCloserTest {

    private val self = "com.mammonrn.phoneaikiosk"
    private val base = listOf(self, "com.google.android.apps.maps", "com.xiaomi.smarthome")
    private val system = setOf("com.google.android.gms", "com.android.vending", "com.android.settings",
        "com.google.android.apps.maps")

    private fun plan(before: List<String>, after: List<String>, suspended: Set<String> = emptySet()) =
        TemporaryAppCloser.plan(before, after, self, { it in system }, { it in suspended })

    @Test fun `a Facebook or Instagram visit that ends is closed`() {
        for (pkg in listOf("com.facebook.lite", "com.facebook.katana", "com.instagram.android")) {
            val p = plan(base + pkg, base)
            assertEquals(listOf(pkg), p.suspend)
            assertTrue(p.skippedSystem.isEmpty())
        }
    }

    @Test fun `system packages are never suspended`() {
        for (pkg in listOf("com.google.android.gms", "com.android.vending", "com.android.settings")) {
            val p = plan(base + pkg, base)
            assertTrue(p.suspend.isEmpty())
            assertEquals(listOf(pkg), p.skippedSystem)
        }
    }

    @Test fun `YouTube playing on stays on the list and is not closed, when it stops it is`() {
        val yt = "app.revanced.android.youtube"
        assertTrue(plan(base + yt, base + yt).suspend.isEmpty())
        // The WiFi panel opened while it plays: the settings app joins, YouTube stays.
        assertTrue(plan(base + yt + "com.android.settings", base + yt).suspend.isEmpty())
        assertEquals(listOf(yt), plan(base + yt, base).suspend)
    }

    @Test fun `nothing still on the list, and never the kiosk itself, is closed`() {
        assertTrue(plan(base, base).suspend.isEmpty())
        assertTrue(plan(base + "com.facebook.lite", base + "com.facebook.lite").suspend.isEmpty())
        assertTrue(plan(base, emptyList()).suspend.none { it == self })
        // Maps is a system app here: taken off it would still not be suspended.
        assertTrue(plan(base, base - "com.google.android.apps.maps").suspend.isEmpty())
    }

    @Test fun `a visit replacing another closes the old app only`() {
        val p = plan(base + "com.facebook.lite", base + "com.instagram.android")
        assertEquals(listOf("com.facebook.lite"), p.suspend)
    }

    @Test fun `a package suspended by someone else is left alone`() {
        val p = plan(base + "com.facebook.lite", base, suspended = setOf("com.facebook.lite"))
        assertTrue(p.suspend.isEmpty())
        assertEquals(listOf("com.facebook.lite"), p.skippedOther)
    }

    private fun src(rel: String): String {
        val roots = listOf("src/main/java/com/mammonrn/phoneaikiosk", "app/src/main/java/com/mammonrn/phoneaikiosk")
        return roots.map { File(it, rel) }.first { it.exists() }.readText()
    }

    @Test fun `unsuspend runs in finally, after a written note, and leftovers are cleared on resume`() {
        val closer = src("TemporaryAppCloser.kt")
        val note = closer.indexOf(".commit()) {")
        val suspend = closer.indexOf("setPackagesSuspended(admin, p.suspend.toTypedArray(), true)")
        val fin = closer.indexOf("} finally {", suspend)
        assertTrue(note in 0 until suspend)
        assertTrue(fin > suspend && closer.indexOf("unsuspend(context, p.suspend)", fin) > fin)
        val restore = src("settings/WifiPanel.kt").substringAfter("fun restore(")
        assertTrue(restore.contains("TemporaryAppCloser.clearLeftovers(context)"))
        assertTrue(restore.contains("TemporaryAppCloser.setAllowlist(context, wanted)"))
        assertTrue(!restore.contains("dpm.setLockTaskPackages("))
    }
}
