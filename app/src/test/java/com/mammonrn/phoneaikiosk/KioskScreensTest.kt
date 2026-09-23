package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.auth.VerifyActivity
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * "Hey Jarvis" always ends on the home screen (0.43.0, Poom): every screen of
 * ours closes, each its own way, and new screens are covered without a list.
 */
class KioskScreensTest {

    @After
    fun clear() = KioskScreens.reset()

    private val manifest: String = listOf(File("src/main/AndroidManifest.xml"), File("app/src/main/AndroidManifest.xml"))
        .first { it.exists() }.readText()

    @Test
    fun `every open screen but home is closed, newest first`() {
        val order = ArrayList<String>()
        KioskScreens.opened(1, "SettingsActivity") { order += "settings" }
        KioskScreens.opened(2, "VerifyActivity") { order += "verify" }
        assertEquals(2, KioskScreens.leaveAllButHome("wake"))
        assertEquals(listOf("verify", "settings"), order)
    }

    @Test
    fun `a closed screen is forgotten, and nothing open closes nothing`() {
        KioskScreens.opened(1, "SettingsActivity") {}
        KioskScreens.closed(1)
        assertEquals(0, KioskScreens.leaveAllButHome("wake"))
        assertEquals(emptyList<String>(), KioskScreens.openScreens())
    }

    @Test
    fun `a leavable screen leaves its own way, any other is finished`() {
        var cancelled = false
        var finished = false
        val leavable = object : KioskScreens.Leavable { override fun leaveForJarvis() { cancelled = true } }
        KioskScreens.leaveFor(leavable) { finished = true }()
        assertTrue(cancelled && !finished)
        KioskScreens.leaveFor(Any()) { finished = true }()
        assertTrue(finished)
    }

    @Test
    fun `the identity check is leavable, so it cancels rather than being cut off`() {
        assertTrue(KioskScreens.Leavable::class.java.isAssignableFrom(VerifyActivity::class.java))
    }

    @Test
    fun `the tracker is registered for the whole app, so future screens are covered`() {
        assertTrue(manifest.contains("android:name=\".KioskApp\""))
        // One process: an activity in another process would be invisible to it.
        assertTrue(!manifest.contains("android:process"))
    }
}
