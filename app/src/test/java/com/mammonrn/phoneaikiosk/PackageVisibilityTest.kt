package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.MapsLauncher
import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The bug this file exists for.
 *
 * versionCode 9 reported `maps-package: com.google.android.apps.maps
 * installed=false` on an A07 where `pm list packages` listed Maps and it was
 * running version 26.06.01. Nothing was broken on the phone and nothing was
 * broken in the code: from Android 11, an app targeting API 30 or above cannot
 * see another package unless the manifest says it wants to, and what it gets
 * instead is NameNotFound — indistinguishable from the package not existing.
 *
 * `MapsLauncher.MAPS_PACKAGE` and the `<queries>` entry are therefore two
 * halves of one fact, kept in two files. This is what holds them together.
 *
 * WHY IT READS THE MANIFEST AS TEXT. The interesting failure is somebody
 * editing one half — renaming the constant, or tidying a manifest — and the
 * other half staying as it was. Only something that looks at both catches that,
 * and a JVM unit test can read a file.
 */
class PackageVisibilityTest {

    private val manifest: String by lazy {
        val file = File("src/main/AndroidManifest.xml")
        assertTrue("cannot find ${file.absolutePath}", file.isFile)
        // Comments stripped first. The manifest explains in prose why
        // QUERY_ALL_PACKAGES is not used, and a test that greps for the bare
        // word fails on the explanation — which is what happened the first
        // time this ran. What matters is the declarations, not the reasoning.
        file.readText().replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    @Test
    fun `the manifest declares the maps package it intends to open`() {
        assertTrue(
            "AndroidManifest.xml has no <queries> block, so on Android 11+ the app " +
                "cannot see Google Maps and cannot start it either",
            manifest.contains("<queries>"),
        )
        assertTrue(
            "the <queries> block must name ${MapsLauncher.MAPS_PACKAGE}, which is the " +
                "package MapsLauncher opens",
            Regex("""<package\s+android:name="${Regex.escape(MapsLauncher.MAPS_PACKAGE)}"\s*/>""")
                .containsMatchIn(manifest),
        )
    }

    /**
     * The shortcut that would also have fixed the bug, and must not be taken.
     *
     * QUERY_ALL_PACKAGES lets an app enumerate everything installed. Google Play
     * restricts it, and it is a very large answer to a question about one app.
     */
    @Test
    fun `it does not ask to see every package on the phone`() {
        assertFalse(
            "QUERY_ALL_PACKAGES is not the fix; name the one package instead",
            Regex("""<uses-permission[^>]*QUERY_ALL_PACKAGES""").containsMatchIn(manifest),
        )
    }

    /**
     * A geo: intent filter would also make Maps visible — and every other maps
     * application with it. This kiosk opens Google Maps specifically, and the
     * declaration should say only that.
     */
    @Test
    fun `visibility is granted to exactly the packages the actions open`() {
        // Three now: Google Maps, Xiaomi Home for "ขอดูกล้อง", and the system
        // settings app for the Control Panel's WiFi button (Poom, 0.42.0). Each
        // is named, each belongs to one action, and a fourth is a decision.
        val declared = Regex("""<package\s+android:name="([^"]+)"""")
            .findAll(manifest).map { it.groupValues[1] }.toList()
        assertEquals(
            listOf(MapsLauncher.MAPS_PACKAGE,
                   com.mammonrn.phoneaikiosk.voice.CameraAppLauncher.PACKAGE,
                   com.mammonrn.phoneaikiosk.settings.WifiPanel.SETTINGS_PACKAGE) +
                // 0.65.0 (Poom): Facebook and Instagram behind the identity check, and the Play Store to install them.
                com.mammonrn.phoneaikiosk.social.SocialVisit.META_PACKAGES +
                com.mammonrn.phoneaikiosk.social.SocialVisit.PLAY_PACKAGE,
            declared,
        )
    }

    @Test
    fun `the package constant is still google maps`() {
        // If this ever legitimately changes, the manifest has to change with it,
        // and the test above is what will say so.
        assertEquals("com.google.android.apps.maps", MapsLauncher.MAPS_PACKAGE)
    }

    /**
     * The debug overlay must not quietly drop the block during manifest merge.
     * It does not declare its own <queries>, so there is nothing to conflict —
     * this asserts that stays true.
     */
    @Test
    fun `the debug overlay does not replace the queries block`() {
        val debug = File("src/debug/AndroidManifest.xml")
        if (!debug.isFile) return
        val text = debug.readText()
        assertFalse(
            "a <queries> block in the debug overlay would need tools:replace " +
                "handling; there is none today and adding one needs thought",
            text.contains("<queries>"),
        )
    }
}
