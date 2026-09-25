package com.mammonrn.phoneaikiosk.uicheck

import android.app.Activity
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.view.MotionEvent
import android.view.PixelCopy
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import android.graphics.Bitmap
import android.graphics.Rect
import android.os.SystemClock
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckPreset
import com.google.android.apps.common.testing.accessibility.framework.AccessibilityCheckResult.AccessibilityCheckResultType
import com.google.android.apps.common.testing.accessibility.framework.uielement.AccessibilityHierarchyAndroid
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.voice.VoiceState
import com.mammonrn.phoneaikiosk.voice.WakePause
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.util.Locale

/**
 * THE UI WALK (0.63.0, Poom 2026-09-25): every screen of the kiosk, in one run,
 * with no model in the loop — `scripts/ui-check` starts it (debug broadcast
 * TEST_UI_CHECK) and reads one summary.
 *
 * INSIDE THE KIOSK'S OWN PROCESS, NOT AN INSTRUMENTED TEST. `am instrument`
 * force-stops the app to start a test, and on the A07 that took the kiosk out
 * of lock task for the whole walk (seen 2026-09-25, four runs). Here nothing is
 * stopped: the walk is a thread in the running kiosk, like the other TEST_*
 * hooks, and exists only in the debug build.
 *
 * It opens screens and presses their buttons directly (fast) and checks the
 * REAL views, not a dump of them:
 *   overlap   no button or text on another (one inside the other is fine; the
 *             exit corner may lie over what cannot be pressed)
 *   touch     every button at least 48dp on its short side (a button the
 *             screen cuts at a scrolling edge is only partly shown: skipped)
 *   cut       no text cut short: "…" or lines taller than their view
 *   home      the Jarvis window at least 156dp, the exit corner 72dp in the
 *             bottom-right corner
 *   a11y      Google's Accessibility Test Framework, errors only
 * and writes, per screen, a picture and a JSON line to the app's files
 * (ui-check/), which the script pulls. A screen with a camera open gets NO
 * picture, ever (CLAUDE.md): its checks come from the views alone.
 *
 * KNOWN EXCEPTIONS are listed by name with the reason, so a rule that waits
 * for Poom is reported, never silently dropped.
 *
 * A NEW SCREEN IS ADDED HERE (CLAUDE.md).
 */
class UiWalk(private val ctx: Context, private val only: List<String>?) {

    private val main = Handler(Looper.getMainLooper())

    /** Runs [block] on the main thread and waits for it (the walk itself is a background thread). */
    private fun mainSync(block: () -> Unit) {
        val done = CountDownLatch(1)
        main.post { try { block() } finally { done.countDown() } }
        done.await(10, TimeUnit.SECONDS)
    }
    private val density = ctx.resources.displayMetrics.density
    private val screenW = ctx.resources.displayMetrics.widthPixels
    private val screenH = ctx.resources.displayMetrics.heightPixels
    private val out = File(ctx.getExternalFilesDir(null), "ui-check").apply { deleteRecursively(); mkdirs() }
    private val results = JSONArray()
    private val a11y = AccessibilityCheckPreset.getAccessibilityHierarchyChecksForPreset(AccessibilityCheckPreset.LATEST)

    /** Rules waiting for Poom (report ก): shown as exceptions, not failures. */
    private val waiting = mapOf(
        "weather_titlebar" to "แถบหัวหน้าต่างหน้าแรกสูง 25dp (แตะเพื่อหุบ) รอ Poom",
        "gold_titlebar" to "แถบหัวหน้าต่างหน้าแรกสูง 25dp (แตะเพื่อหุบ) รอ Poom",
        "crypto_titlebar" to "แถบหัวหน้าต่างหน้าแรกสูง 25dp (แตะเพื่อหุบ) รอ Poom",
        "home_titlebar" to "แถบหัวหน้าต่างหน้าแรกสูง 25dp (แตะเพื่อหุบ) รอ Poom",
        "gold_pages" to "จุดเปลี่ยนหน้าทอง/น้ำมันอยู่ในแถบหัว 25dp รอ Poom",
    )

    // ------------------------------------------------------------ moving about

    /**
     * The screen in front, from the kiosk's own record (KioskScreens), never one
     * on its way out.
     */
    private fun top(): Activity? = KioskScreens.resumed?.get()?.takeUnless { it.isFinishing }

    private fun settle(ms: Long = 900) {
        mainSync {}
        SystemClock.sleep(ms)
        mainSync {}
    }

    /**
     * Back to the kiosk's own home screen: every other screen of ours closed the
     * kiosk's way (as Hey Jarvis does), and the home screen comes forward by itself.
     *
     * NEVER STARTED BY CLASS. The home screen is singleInstance in the home task;
     * started from here it opened a SECOND one in a new task and lock task went
     * off for ten seconds (seen on the A07, 2026-09-25); asked for with the HOME
     * intent from here, lock task went off for fourteen. So the walk never starts
     * it: the kiosk's own home screen is what is left when the others close.
     */
    private fun home() {
        mainSync { KioskScreens.leaveAllButHome("ui-check") }
        waitFor("MainActivity")
        settle(1500)
    }

    private fun waitFor(name: String, ms: Long = 8000): Boolean {
        val end = SystemClock.uptimeMillis() + ms
        while (SystemClock.uptimeMillis() < end) {
            if (top()?.javaClass?.simpleName == name) return true
            SystemClock.sleep(150)
        }
        return false
    }

    private fun views(): List<View> {
        val root = top()?.window?.decorView ?: return emptyList()
        val all = ArrayList<View>()
        fun walk(v: View) {
            if (!v.isShown) return
            all.add(v)
            if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
        }
        mainSync { walk(root) }
        return all
    }

    private fun words(v: View): String =
        ((v as? TextView)?.text?.toString().orEmpty() + "|" + (v.contentDescription ?: "")).trim('|')

    /**
     * Presses the first shown view whose text or description starts with [prefix]
     * ([anywhere]: contains it), or its pressable parent. Views hidden from the
     * screen reader (behind a pop-up) are not pressed.
     */
    private fun press(prefix: String, nth: Int = 0, anywhere: Boolean = false): Boolean {
        val hits = views().filter { v ->
            val t = (v as? TextView)?.text?.toString().orEmpty()
            val d = v.contentDescription?.toString().orEmpty()
            !hiddenFromReader(v) &&
                (if (anywhere) t.contains(prefix) || d.contains(prefix) else t.startsWith(prefix) || d.startsWith(prefix))
        }
        val v = hits.getOrNull(nth) ?: return false
        var target: View? = v
        while (target != null && !target.isClickable) target = target.parent as? View
        target ?: return false
        mainSync { target.performClick() }
        settle()
        return true
    }

    private fun pressId(id: Int): Boolean {
        val v = top()?.findViewById<View>(id) ?: return false
        mainSync { v.performClick() }
        settle()
        return true
    }

    private fun panel() {
        home()
        pressId(R.id.settings_button)
        waitFor("SettingsActivity")
        settle()
    }

    private fun openApp(folder: String?, app: String, activity: String?) {
        panel()
        if (folder != null) press("โฟลเดอร์$folder")
        press(app)
        if (activity != null) waitFor(activity)
        settle(1200)
    }

    // ------------------------------------------------------------ the checks

    private class Seen(val view: View, val rect: Rect, val full: Rect, val label: String, val id: String)

    private fun idOf(v: View): String =
        if (v.id > 0) runCatching { v.resources.getResourceEntryName(v.id) }.getOrDefault("") else ""

    private fun hiddenFromReader(v: View): Boolean {
        var p: View? = v
        while (p != null) {
            if (p.importantForAccessibility == View.IMPORTANT_FOR_ACCESSIBILITY_NO_HIDE_DESCENDANTS && p !== v) return true
            p = p.parent as? View
        }
        return false
    }

    private fun isAncestor(a: View, b: View): Boolean {
        var p = b.parent
        while (p != null) { if (p === a) return true; p = p.parent }
        return false
    }

    private fun wanted(name: String) = only == null || only.any { name.startsWith(it) }

    private fun check(name: String, extra: ((Activity?) -> List<String>)? = null, picture: Boolean = true) {
        if (!wanted(name)) return
        settle(600)
        val screenOn = (ctx.getSystemService(android.os.PowerManager::class.java))?.isInteractive == true
        val act = top()
        val activity = act?.javaClass?.simpleName ?: "none"
        val issues = ArrayList<String>()
        val excepted = ArrayList<String>()
        val seen = ArrayList<Seen>()
        mainSync {
            val root = act?.window?.decorView ?: return@mainSync
            fun walk(v: View) {
                if (!v.isShown || hiddenFromReader(v)) return
                val speaks = v.isClickable || (v is TextView && v.text.isNotEmpty()) || !v.contentDescription.isNullOrEmpty()
                if (speaks && v.width > 0 && v.height > 0) {
                    val r = Rect()
                    if (v.getGlobalVisibleRect(r)) {
                        val at = IntArray(2); v.getLocationOnScreen(at)
                        seen.add(Seen(v, r, Rect(at[0], at[1], at[0] + v.width, at[1] + v.height),
                            words(v).replace('\n', ' ').take(40), idOf(v)))
                    }
                }
                if (v is ViewGroup) for (i in 0 until v.childCount) walk(v.getChildAt(i))
            }
            walk(root)
            // Text cut short.
            for (s in seen) {
                val t = s.view as? TextView ?: continue
                val layout = t.layout ?: continue
                val ellipsized = (0 until layout.lineCount).any { layout.getEllipsisCount(it) > 0 }
                val room = t.height - t.compoundPaddingTop - t.compoundPaddingBottom
                if (ellipsized || (room > 0 && layout.height > room + 2))
                    issues.add("cut (${if (ellipsized) "…" else "clipped"}): '${s.label}' ${s.rect.toShortString()}")
            }
            // Accessibility Test Framework: errors only.
            runCatching {
                val hierarchy = AccessibilityHierarchyAndroid.newBuilder(root).build()
                for (c in a11y) for (r in c.runCheckOnHierarchy(hierarchy)) {
                    if (r.type != AccessibilityCheckResultType.ERROR) continue
                    val b = r.element?.boundsInScreen
                    val what = listOfNotNull(r.element?.className?.toString()?.substringAfterLast('.'),
                        r.element?.resourceName?.toString()?.substringAfterLast('/')).joinToString(" ")
                    val line = "a11y ${c.javaClass.simpleName}: $what ${b ?: ""} ${
                        runCatching { r.getMessage(Locale.ENGLISH) }.getOrDefault("").toString().take(80)}"
                    // The same view as a rule that waits for Poom: an exception, said, not a failure.
                    val waits = seen.firstOrNull { s -> b != null && s.full.left == b.left && s.full.top == b.top &&
                        s.full.right == b.right && s.full.bottom == b.bottom }?.let { waitingFor(it) }
                    if (waits != null) excepted.add("$line — $waits") else issues.add(line)
                }
            }.onFailure { issues.add("a11y could not run: ${it.javaClass.simpleName}") }
        }
        val area = screenW * screenH
        val min = (48 * density) - 1
        for (s in seen) {
            if (!s.view.isClickable || !s.view.isEnabled) continue
            if (s.rect != s.full) continue                      // cut by a scrolling edge: partly shown
            if (minOf(s.rect.width(), s.rect.height()) < min) {
                val line = "touch: '${s.label}' ${(s.rect.width() / density).toInt()}×${(s.rect.height() / density).toInt()}dp"
                val why = waitingFor(s)
                if (why != null) excepted.add("$line — $why") else issues.add(line)
            }
        }
        for ((i, a) in seen.withIndex()) for (b in seen.subList(i + 1, seen.size)) {
            if (isAncestor(a.view, b.view) || isAncestor(b.view, a.view)) continue
            if (a.rect.width() * a.rect.height() > area * 0.35 || b.rect.width() * b.rect.height() > area * 0.35) continue
            val corner = a.id == "exit_corner" || b.id == "exit_corner"
            if (corner && !(if (a.id == "exit_corner") b.view.isClickable else a.view.isClickable)) continue
            val x = Rect(a.rect)
            if (!x.intersect(b.rect) || x.width() <= 3 || x.height() <= 3) continue
            issues.add("overlap: '${a.label}' ${a.rect.toShortString()} × '${b.label}' ${b.rect.toShortString()}")
        }
        extra?.let { issues.addAll(it(act)) }
        if (!screenOn) issues.add("the screen was off")

        val noPicture = !picture || activity in CAMERA_SCREENS
        if (!noPicture) {
            val shot: Bitmap? = act?.let { picture(it) }
            shot?.let { File(out, "$name.png").outputStream().use { o -> it.compress(Bitmap.CompressFormat.PNG, 90, o) } }
        }
        results.put(JSONObject().put("name", name).put("activity", activity).put("pass", issues.isEmpty())
            .put("issues", JSONArray(issues.distinct())).put("exceptions", JSONArray(excepted.distinct()))
            .put("picture", !noPicture).put("screenOn", screenOn)
            .put("focus", act?.hasWindowFocus() == true).put("finishing", act?.isFinishing == true)
            .put("attached", act?.window?.decorView?.isAttachedToWindow == true)
            .put("instance", System.identityHashCode(act)))
        File(out, "results.json").writeText(results.toString(1))
    }

    private fun waitingFor(s: Seen): String? =
        waiting[s.id] ?: if (s.label.startsWith("หน้า") && s.label.contains("ทอง")) waiting["gold_pages"] else null

    /**
     * The screen as the app's window draws it (PixelCopy). A video's picture is a
     * surface of its own and is not in it; the controls and the layout are.
     */
    private fun picture(act: Activity): Bitmap? {
        val window = act.window ?: return null
        val decor = window.decorView
        if (decor.width <= 0 || decor.height <= 0) return null
        val bitmap = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val done = CountDownLatch(1)
        var ok = false
        runCatching {
            PixelCopy.request(window, bitmap, { result -> ok = result == PixelCopy.SUCCESS; done.countDown() }, main)
        }.onFailure { done.countDown() }
        done.await(5, TimeUnit.SECONDS)
        return if (ok) bitmap else null
    }

    private fun homeRules(act: Activity?): List<String> {
        val out = ArrayList<String>()
        fun box(id: Int): Rect? {
            var r: Rect? = null
            mainSync {
                val v = act?.findViewById<View>(id) ?: return@mainSync
                val at = IntArray(2); v.getLocationOnScreen(at)
                r = Rect(at[0], at[1], at[0] + v.width, at[1] + v.height)
            }
            return r
        }
        val card = box(R.id.card_jarvis)
        if (card == null || card.height() / density < 156 - 0.5)
            out.add("home: Jarvis window ${card?.height()?.div(density)?.toInt() ?: 0}dp < 156dp")
        val corner = box(R.id.exit_corner)
        val side = (72 * density).toInt()
        if (corner == null || corner.right != screenW || corner.bottom != screenH ||
            kotlin.math.abs(corner.width() - side) > 2 || kotlin.math.abs(corner.height() - side) > 2)
            out.add("home: exit corner ${corner?.toShortString()} is not 72dp in the bottom-right corner")
        return out
    }

    // ------------------------------------------------------------ Jarvis's states

    private fun homeInState(name: String, set: () -> Unit, reset: () -> Unit) {
        set()
        SystemClock.sleep(700)                                   // the badge redraws every 300 ms
        check("home-jarvis-$name", ::homeRules)
        reset()
    }

    // ------------------------------------------------------------ the walk

    fun everyScreen() {
        home()
        check("home", ::homeRules)
        val idle = { VoiceState.stt = "idle"; VoiceState.chat = "idle"; VoiceState.tts = "idle" }
        homeInState("listening", { VoiceState.stt = "recording" }, idle)
        homeInState("thinking", { VoiceState.chat = "asking" }, idle)
        homeInState("speaking", { VoiceState.tts = "speaking" }, idle)
        val media = object : WakePause.Media {
            override fun quietForJarvis() {}
            override fun resumeAfterJarvis() {}
        }
        var hold: WakePause.Hold? = null
        homeInState("resting", { hold = WakePause.hold(WakePause.Source.MUSIC, media) }, { hold?.let { WakePause.release(it) } })
        val mic = VoiceState.mic
        homeInState("unavailable", { VoiceState.mic = "error" }, { VoiceState.mic = mic })

        panel(); check("panel")
        panel(); press("โฟลเดอร์สื่อ"); check("folder-media")
        panel(); press("โฟลเดอร์เครื่องมือ"); check("folder-tools")
        panel(); press("ไฟในบ้าน"); settle(1500); check("lights")
        openApp(null, "จัดการไฟล์", "FilesActivity"); check("files")
        openApp(null, "ยืนยันตัวตน", null); check("identity", picture = false)
        openApp(null, "ที่มาข้อมูล", null); check("sources")

        openApp("สื่อ", "เครื่องเล่นเพลง", "MusicActivity"); check("music")
        openApp("สื่อ", "เครื่องเล่นวิดีโอ", "VideoActivity"); check("video-list")
        videoFull()
        openApp("สื่อ", "วิทยุ", "RadioActivity"); check("radio")
        openApp("สื่อ", "กล้อง", "CameraActivity"); check("camera", picture = false)
        openApp("สื่อ", "บันทึกเสียง", "RecorderActivity"); check("recorder")

        openApp("เครื่องมือ", "นาฬิกาปลุก", null); check("alarms")
        openApp("เครื่องมือ", "จับเวลา", "TimerActivity"); check("timer-countdown")
        press("○ นาฬิกาจับเวลา"); check("timer-stopwatch")
        openApp("เครื่องมือ", "โน้ต", "NotesActivity"); check("notes")
        openApp("เครื่องมือ", "ระดับน้ำ", "CompassActivity"); check("level")

        openApp("เครื่องมือ", "เครื่องคิดเลข", "CalculatorActivity")
        for (tab in listOf("คำนวณ", "ไฟฟ้า", "โซลาร์", "หน่วย", "ราคา", "ประวัติ")) {
            press(tab)
            check("calc-$tab-1")
            val pages = views().firstNotNullOfOrNull { v ->
                Regex("หน้า \\d+ จาก (\\d+)").find(v.contentDescription?.toString().orEmpty())?.groupValues?.get(1)?.toInt()
            } ?: 1
            for (k in 2..pages) {
                press("หน้า ")
                check("calc-$tab-$k")
            }
        }
        home()
    }

    /** The first film, paused at once (its place moves a second at most), full screen standing and lying. */
    private fun videoFull() {
        openApp("สื่อ", "เครื่องเล่นวิดีโอ", "VideoActivity")
        if (!press("ดูถึง", anywhere = true) && !press("ยังไม่ได้ดู", anywhere = true)) { results.put(JSONObject().put("name", "video-full").put("pass", false)
            .put("issues", JSONArray(listOf("no video in the list")))); return }
        settle(2500)
        com.mammonrn.phoneaikiosk.media.VideoPlayer.pause(ctx)
        settle()
        // The controls up first: hidden, the full-screen button is not there to press.
        showControls()
        val fill = views().firstOrNull { (it as? TextView)?.text?.toString()?.startsWith("เต็มจอ") == true }
        if (fill != null && (fill as TextView).text.contains("ปิด")) { mainSync { fill.performClick() }; settle() }
        showControls()
        check("video-full-portrait", { act -> if (fullScreen()) emptyList() else listOf("video: not full screen") })
        com.mammonrn.phoneaikiosk.media.VideoActivity.debugTurn?.let { turn -> mainSync { turn(true) } }
        settle(2500)
        showControls()
        check("video-full-landscape", { act ->
            val turned = act?.resources?.configuration?.orientation == android.content.res.Configuration.ORIENTATION_LANDSCAPE
            listOfNotNull(if (turned) null else "video: did not turn to landscape",
                          if (fullScreen()) null else "video: not full screen")
        })
        com.mammonrn.phoneaikiosk.media.VideoActivity.debugTurn?.let { turn -> mainSync { turn(false) } }
        settle(1500)
    }

    /** Full screen on: the button reads "เต็มจอ เปิด" (its words, not a guess from the layout). */
    private fun fullScreen(): Boolean = views().any {
        val t = (it as? TextView)?.text?.toString().orEmpty()
        t.startsWith("เต็มจอ") && t.contains("เปิด")
    }

    /** A tap in the middle of the picture brings the controls up (they hide after 4 s). */
    private fun showControls() {
        val shown = views().any { (it as? TextView)?.text?.toString()?.startsWith("เต็มจอ") == true }
        if (!shown) {
            val decor = top()?.window?.decorView
            if (decor != null) mainSync {
                val t = SystemClock.uptimeMillis()
                val x = decor.width / 2f; val y = decor.height / 3f
                decor.dispatchTouchEvent(MotionEvent.obtain(t, t, MotionEvent.ACTION_DOWN, x, y, 0))
                decor.dispatchTouchEvent(MotionEvent.obtain(t, t + 50, MotionEvent.ACTION_UP, x, y, 0))
            }
            settle(700)
        }
    }

    private companion object {
        /** Screens with a camera open: checked, never photographed (CLAUDE.md). */
        val CAMERA_SCREENS = setOf("CameraActivity", "VerifyActivity", "FaceActivity")
    }
}
