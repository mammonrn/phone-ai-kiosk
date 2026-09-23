package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.voice.VoiceState
import java.io.File
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Poom's rule: the household's screen shows the data and what Jarvis is doing
 * in words — not mic=, wake=, taps=. Those move to dumpsys, and come back on
 * the screen only when somebody switches debug mode on over adb.
 */
class DiagnosticsHiddenTest {

    @Test
    fun `debug mode is off unless somebody turns it on`() {
        assertFalse(VoiceState.showDiagnostics)
    }

    @Test
    fun `everything that left the screen is still in the dump`() {
        VoiceState.kioskLine = "owner=yes  lock=on  awake=on  token=yes  taps=0/10"
        val dump = VoiceState.dump()
        for (expected in listOf("kiosk      : owner=yes", "taps=0/10", "status-line: mic=",
                                "third-line : loc ", "screen-idle:", "on-screen  : diagnostics hidden")) {
            assertTrue("dump lacks \"$expected\"", dump.contains(expected))
        }
    }

    private val layout: String by lazy {
        File("src/main/res/layout/activity_main.xml").readText()
            .replace(Regex("<!--.*?-->", RegexOption.DOT_MATCHES_ALL), "")
    }

    /** The attributes of the view with this id, as declared in the layout. */
    private fun view(id: String): String {
        val start = layout.indexOf("android:id=\"@+id/$id\"")
        assertTrue("no view $id in the layout", start >= 0)
        val open = layout.lastIndexOf('<', start)
        return layout.substring(open, layout.indexOf('>', start))
    }

    @Test
    fun `the jarvis window's diagnostic lines start hidden`() {
        assertTrue(view("voice_status").contains("android:visibility=\"gone\""))
    }

    @Test
    fun `the taskbar's status starts hidden but keeps its place`() {
        // INVISIBLE, not GONE: the clock tray must stay on the right.
        assertTrue(view("status").contains("android:visibility=\"invisible\""))
    }

    @Test
    fun `the clock tray is one line and smaller than before`() {
        val clock = view("taskbar_clock")
        assertTrue(clock.contains("android:textSize=\"9sp\""))
    }
}
