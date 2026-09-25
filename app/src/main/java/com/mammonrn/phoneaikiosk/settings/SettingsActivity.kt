package com.mammonrn.phoneaikiosk.settings

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.InputFilter
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.alarm.AlarmBook
import com.mammonrn.phoneaikiosk.alarm.AlarmStore
import com.mammonrn.phoneaikiosk.auth.AccessGrant
import com.mammonrn.phoneaikiosk.auth.AuthStore
import com.mammonrn.phoneaikiosk.auth.VerifyActivity
import com.mammonrn.phoneaikiosk.ui.ScreenDate
import com.mammonrn.phoneaikiosk.ui.RetroType
import com.mammonrn.phoneaikiosk.voice.DashboardState
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The Control Panel: settings, in a 1995 window, inside the kiosk.
 *
 * WHAT IT IS FOR. Everything the voice cannot comfortably say: which days an
 * alarm rings, renaming one, deleting one, ring once or every day. Opened from
 * the taskbar's panel button; closed by the big "กลับหน้าหลัก" button at the
 * bottom, the title bar's X, or Back — three ways home, because a kiosk has no
 * other way out of a screen.
 *
 * STAYS IN THE KIOSK. It is our own activity, so lock task mode allows it, and
 * closing it starts MainActivity again rather than trusting the task stack:
 * the home screen is singleInstance, so this window lives in a task of its
 * own, and "back to the kiosk" is said explicitly.
 *
 * GROWS BY CATEGORY. The first page is the panel's icons, one per category
 * ([GROUPS]). A new kind of setting is a new entry there and a page of its
 * own; nothing else here changes. DESIGN.md, "Control Panel", has the rules.
 *
 * Built in code rather than XML because every page is the same few 1995 parts
 * — a title bar, a raised button, a sunken field — composed differently, and
 * the helpers below keep each part defined once.
 */
class SettingsActivity : Activity() {

    private lateinit var pixel: Typeface
    internal lateinit var thai: Typeface
    internal lateinit var titleText: TextView
    private lateinit var content: FrameLayout

    /** The page on screen, so Back can go up one level before leaving. */
    internal var page = Page.HOME

    internal enum class Page { HOME, ALARMS, EDIT, SOURCES, AUTH, LIGHTS, LIGHT_NAME }

    /** The "ไฟในบ้าน" page (0.47.0), in a file of its own: settings/LightsPage. */
    private val lights by lazy { LightsPage(this) }

    /** [label] is read each time the panel is drawn, so a switch can say its state. */
    private class Category(val icon: Int, val label: (SettingsActivity) -> String,
                           val open: (SettingsActivity) -> Unit)

    /** A heading and its icons on the panel's first page. */
    private class Group(val title: Int, val items: List<Category>)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        setContentView(buildWindow())
        hideSystemBars()
        showHome()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Back from the WiFi panel: the settings app leaves the allowlist.
        WifiPanel.restore(this)
        torch.watch(true)
        if (page == Page.HOME) showHome()
    }

    override fun onPause() {
        super.onPause()
        torch.watch(false)
    }

    // --------------------------------------------------------- the torch

    /**
     * The phone's flash as a torch (0.42.0). setTorchMode needs no permission
     * and no camera session; the state comes back from the system's callback,
     * so the label is what the LED is really doing — including when the
     * identity check's camera takes the flash and turns it off.
     */
    private val torch by lazy { Torch() }

    private inner class Torch {
        private val cameras = getSystemService(android.hardware.camera2.CameraManager::class.java)
        private val id: String? = runCatching {
            cameras?.cameraIdList?.firstOrNull { cid ->
                cameras.getCameraCharacteristics(cid)
                    .get(android.hardware.camera2.CameraCharacteristics.FLASH_INFO_AVAILABLE) == true
            }
        }.getOrNull()
        var on = false
            private set
        val available get() = id != null
        private val callback = object : android.hardware.camera2.CameraManager.TorchCallback() {
            override fun onTorchModeChanged(cameraId: String, enabled: Boolean) {
                if (cameraId != id || on == enabled) return
                on = enabled
                if (page == Page.HOME) showHome()
            }
        }

        fun watch(yes: Boolean) {
            if (id == null || cameras == null) return
            if (yes) runCatching { cameras.registerTorchCallback(callback, repeatHandler) }
            else runCatching { cameras.unregisterTorchCallback(callback) }
        }

        fun toggle() {
            val cid = id ?: return
            runCatching { cameras?.setTorchMode(cid, !on) }
                .onFailure { android.util.Log.i("KioskTorch", "refused ${it.javaClass.simpleName}") }
        }

        fun label(): String = getString(when {
            !available -> R.string.torch_unavailable
            on -> R.string.torch_on
            else -> R.string.torch_off
        })
    }

    private fun openWifi() {
        if (!WifiPanel.open(this)) {
            android.widget.Toast.makeText(this, R.string.wifi_unavailable, android.widget.Toast.LENGTH_LONG).show()
        }
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    private fun goBack() {
        when (page) {
            Page.EDIT -> showAlarms()
            Page.LIGHT_NAME -> lights.back(null)
            Page.ALARMS, Page.SOURCES, Page.AUTH, Page.LIGHTS -> showHome()
            Page.HOME -> goHome()
        }
    }

    /** Back to the kiosk screen, said explicitly — see the class notes. */
    private fun goHome() {
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------ the window

    private fun buildWindow(): View {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.retro_desktop))
            setPadding(dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))

        // Title bar: icon, title, and a close button big enough for a thumb.
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_control_panel) },
                    LinearLayout.LayoutParams(dp(UiScale.ICON_S), dp(UiScale.ICON_S)))
        titleText = TextView(this).apply {
            setTextColor(color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = getString(R.string.settings_close)
            isClickable = true
            setOnClickListener { goHome() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.WINDOW_INSET) })

        // The way home, always at the bottom, the biggest thing on the page.
        root.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        return root
    }

    // ---------------------------------------------------------------- home

    /** For a page in another file: back to the panel's icons. */
    internal fun showHomeFromPage() = showHome()

    private fun showHome() {
        page = Page.HOME
        titleText.text = getString(R.string.settings_title)
        // Three icons to a row, as many rows as there are categories: one
        // row of fixed-width icons ran off the screen at the third (0.37.0).
        val grid = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS))
        }
        for ((g, group) in GROUPS.withIndex()) {
          grid.addView(label(getString(group.title)).apply {
              setPadding(dp(UiScale.SPACE_XS), if (g == 0) 0 else dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_XS))
          }, LinearLayout.LayoutParams(MATCH, WRAP))
          for (row in group.items.chunked(ICONS_PER_ROW)) {
            val line = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            grid.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
            for ((index, category) in row.withIndex()) line.addView(LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER_HORIZONTAL
                isClickable = true
                setBackgroundResource(R.drawable.retro_button)
                setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
                setOnClickListener { category.open(this@SettingsActivity) }
                addView(ImageView(context).apply { setImageResource(category.icon) },
                        LinearLayout.LayoutParams(dp(UiScale.ICON_XL), dp(UiScale.ICON_XL)))
                addView(text(category.label(this@SettingsActivity), UiScale.TEXT_BASE).apply {
                    gravity = Gravity.CENTER
                    setPadding(0, dp(UiScale.SPACE_S), 0, 0)
                })
            }, LinearLayout.LayoutParams(0, WRAP, 1f).apply { if (index > 0) marginStart = dp(UiScale.SPACE_S) })
            // A short last row keeps the icons the same width as the rows above.
            repeat(ICONS_PER_ROW - row.size) {
                line.addView(View(this), LinearLayout.LayoutParams(0, 0, 1f).apply { marginStart = dp(UiScale.SPACE_S) })
            }
        }
        }
        setPage(ScrollView(this).apply { addView(LinearLayout(this@SettingsActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(grid, LinearLayout.LayoutParams(MATCH, WRAP))
            addView(text(getString(R.string.settings_hint), UiScale.TEXT_NOTE, dim = true).apply {
                setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), 0)
            })
        }) })
    }

    // -------------------------------------------------------------- alarms

    private var confirmingDelete: Int? = null

    private fun showAlarms() {
        page = Page.ALARMS
        titleText.text = getString(R.string.window_alarms)
        val book = AlarmStore.load(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

        list.addView(button(getString(R.string.settings_back_to_panel)) { showHome() },
                     LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))

        if (book.alarms.isEmpty()) {
            // An empty page is an invitation: what to press, or what to say.
            list.addView(text(getString(R.string.alarms_empty), UiScale.TEXT_BASE).apply {
                setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_L), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_L))
            })
        }
        for (alarm in book.alarms) list.addView(alarmRow(book, alarm), LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dp(UiScale.SPACE_S) })

        val full = book.alarms.size >= AlarmBook.MAX_ALARMS
        list.addView(button(getString(if (full) R.string.alarms_full else R.string.alarm_add),
                            big = true, enabled = !full) { showEdit(null) },
                     LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_M) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    /**
     * Where each number on the screen comes from, with its licence — the
     * attribution Open-Meteo (CC BY 4.0), CAMS and OpenStreetMap (ODbL) ask
     * for. Read-only: a heading and a line per source.
     */
    private fun showSources() {
        page = Page.SOURCES
        titleText.text = getString(R.string.window_sources)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(button(getString(R.string.settings_back_to_panel)) { showHome() },
                     LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        for (entry in resources.getStringArray(R.array.data_sources)) {
            val (heading, detail) = entry.split("|", limit = 2).let { it[0] to it.getOrElse(1) { "" } }
            list.addView(label(heading), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
            list.addView(text(detail, UiScale.TEXT_NOTE))
        }
        setPage(ScrollView(this).apply { addView(list) })
    }

    // ------------------------------------------------------ identity check

    private var confirmingAuthDelete: String? = null
    private var lastAuthOutcome: String? = null

    /** What is deleted once the identity check passes: "face" or "pattern". */
    private var deleteAfterPass: String? = null

    /** A line under the delete buttons: deleted, not deleted, or the VPS's answer. */
    private var deleteNote: String? = null

    private fun deleteNow(key: String) {
        if (key == "face") AuthStore.deleteFace(this) else AuthStore.deletePattern(this)
        AccessGrant.close()
        android.util.Log.i("KioskAuth", "deleted $key")
    }

    /**
     * THE WAY OUT when the camera is broken AND the pattern is forgotten
     * (Poom, 0.42.0): Poom runs `allow-auth-reset` on the VPS, and for ten
     * minutes this phone may delete without a pass — once. Needs both the VPS
     * and the phone in hand; a stranger holding only the phone gets "not yet".
     */
    private fun askVpsForReset(key: String) {
        val token = com.mammonrn.phoneaikiosk.voice.TokenStore(this).token()
        if (token.isNullOrEmpty()) {
            deleteNote = getString(R.string.auth_reset_error)
            showAuth()
            return
        }
        deleteNote = getString(R.string.auth_reset_checking)
        showAuth()
        Thread {
            val answer = runCatching {
                com.mammonrn.phoneaikiosk.voice.Broker(
                    com.mammonrn.phoneaikiosk.voice.VoiceState.brokerBaseUrl, token).authReset()
            }
            runOnUiThread {
                val allowed = answer.getOrNull()
                deleteNote = when (allowed) {
                    true -> { deleteNow(key); getString(R.string.auth_delete_done) }
                    false -> getString(R.string.auth_reset_denied)
                    null -> getString(R.string.auth_reset_error)
                }
                android.util.Log.i("KioskAuth", "reset asked allowed=$allowed")
                confirmingAuthDelete = null
                if (page == Page.AUTH) showAuth()
            }
        }.start()
    }
    private var grantLine: TextView? = null
    private var grantClose: View? = null
    private val grantTicker = object : Runnable {
        override fun run() {
            if (page != Page.AUTH) return
            showGrant()
            repeatHandler.postDelayed(this, 1000)
        }
    }

    /** The countdown, and the close button only while there is something to close. */
    private fun showGrant() {
        grantLine?.text = grantText()
        grantClose?.visibility = if (AccessGrant.isOpen()) View.VISIBLE else View.GONE
    }

    private fun grantText(): String {
        val left = AccessGrant.remainingMs()
        if (left <= 0) return getString(R.string.auth_grant_closed)
        val seconds = (left + 999) / 1000
        return getString(R.string.auth_grant_open, "%d:%02d".format(seconds / 60, seconds % 60))
    }

    private fun openVerify(mode: VerifyActivity.Mode) {
        @Suppress("DEPRECATION")
        startActivityForResult(VerifyActivity.intent(this, mode), REQUEST_AUTH)
    }

    /**
     * The identity check's page: whether a face and a pattern are enrolled,
     * enrol or change them (VerifyActivity asks for a pass first when either
     * exists), delete either at once, and try the check. DESIGN.md, "ยืนยันตัวตน".
     */
    private fun showAuth() {
        page = Page.AUTH
        titleText.text = getString(R.string.window_auth)
        val face = AuthStore.loadFace(this)
        val hasPattern = AuthStore.hasPattern(this)
        val list = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(button(getString(R.string.settings_back_to_panel)) { showHome() },
                     LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))

        val faceStatus = if (face != null) {
            getString(R.string.auth_face_yes, ScreenDate.format(
                java.util.Calendar.getInstance().apply { timeInMillis = face.createdAtMs }))
        } else {
            getString(R.string.auth_face_no)
        }
        list.addView(label(faceStatus), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        list.addView(authRow(
            primary = getString(if (face != null) R.string.auth_reenroll_face else R.string.auth_enroll_face),
            onPrimary = { openVerify(VerifyActivity.Mode.ENROLL) },
            deleteKey = if (face != null) "face" else null,
            confirm = getString(R.string.auth_delete_face_confirm)))

        list.addView(label(getString(if (hasPattern) R.string.auth_pattern_yes else R.string.auth_pattern_no)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        list.addView(authRow(
            primary = getString(if (hasPattern) R.string.auth_change_pattern else R.string.auth_set_pattern),
            onPrimary = { openVerify(VerifyActivity.Mode.SET_PATTERN) },
            deleteKey = if (hasPattern) "pattern" else null,
            confirm = getString(R.string.auth_delete_pattern_confirm)))
        deleteNote?.let {
            list.addView(text(it, UiScale.TEXT_NOTE), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        }

        val enrolled = face != null || hasPattern
        list.addView(button(getString(R.string.auth_test), enabled = enrolled) {
            openVerify(VerifyActivity.Mode.VERIFY)
        }, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_L) })
        val result = when (lastAuthOutcome) {
            VerifyActivity.OUTCOME_PASSED -> R.string.auth_result_passed
            VerifyActivity.OUTCOME_CANCELLED -> R.string.auth_result_cancelled
            VerifyActivity.OUTCOME_FAILED -> R.string.auth_result_failed
            else -> null
        }
        if (result != null) {
            list.addView(text(getString(result), UiScale.TEXT_NOTE), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        }
        val line = text(grantText(), UiScale.TEXT_NOTE)
        grantLine = line
        list.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        val close = button(getString(R.string.auth_close_grant)) {
            AccessGrant.close()
            showGrant()
        }
        grantClose = close
        list.addView(close, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_S) })
        showGrant()

        // The id the broker approves (`approve-enrollment` on the VPS). Not a
        // secret; the first four characters are what Poom types.
        AuthStore.identityId(this)?.let { id ->
            list.addView(text(getString(R.string.auth_identity, id.take(4)), UiScale.TEXT_NOTE),
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
            val server = when (com.mammonrn.phoneaikiosk.voice.VoiceState.grantStatus) {
                "approved" -> getString(R.string.auth_server_approved)
                "pending" -> getString(R.string.auth_server_pending, id.take(4))
                "error" -> getString(R.string.auth_server_error)
                else -> getString(R.string.auth_server_unknown)
            }
            list.addView(text(server, UiScale.TEXT_NOTE), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        }
        list.addView(text(getString(R.string.auth_privacy), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_L) })
        setPage(ScrollView(this).apply { addView(list) })
        repeatHandler.removeCallbacks(grantTicker)
        repeatHandler.post(grantTicker)
    }

    /**
     * "enrol / change" beside "delete". Delete asks once in place and then
     * NEEDS A PASS (0.42.0, Poom: deleting without one was a hole): the
     * identity check opens, and only a pass deletes. "ยืนยันตัวตนไม่ได้" is the
     * way out through the VPS ([askVpsForReset]).
     */
    private fun authRow(primary: String, onPrimary: () -> Unit, deleteKey: String?,
                        confirm: String): View {
        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(UiScale.SPACE_S), 0, 0)
        }
        if (deleteKey != null && confirmingAuthDelete == deleteKey) {
            val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
            box.addView(text(confirm, UiScale.TEXT_NOTE))
            box.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                addView(button(getString(R.string.auth_delete_verify)) {
                    deleteAfterPass = deleteKey
                    deleteNote = null
                    @Suppress("DEPRECATION")
                    startActivityForResult(VerifyActivity.intent(this@SettingsActivity,
                        VerifyActivity.Mode.VERIFY), REQUEST_DELETE)
                }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
                addView(button(getString(R.string.cancel)) {
                    confirmingAuthDelete = null
                    deleteNote = null
                    showAuth()
                }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
            box.addView(button(getString(R.string.auth_reset_ask)) { askVpsForReset(deleteKey) },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_S) })
            row.addView(box, LinearLayout.LayoutParams(MATCH, WRAP))
            return row
        }
        row.addView(button(primary) { onPrimary() }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
        if (deleteKey != null) {
            row.addView(button(getString(R.string.auth_delete)) {
                confirmingAuthDelete = deleteKey
                showAuth()
            }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        }
        return row
    }

    @Deprecated("startActivityForResult's partner; this app has no androidx.activity.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode == REQUEST_DELETE) {
            val key = deleteAfterPass
            deleteAfterPass = null
            val passed = data?.getStringExtra(VerifyActivity.EXTRA_OUTCOME) == VerifyActivity.OUTCOME_PASSED
            if (passed && key != null) deleteNow(key)
            deleteNote = getString(if (passed) R.string.auth_delete_done else R.string.auth_delete_no_pass)
            confirmingAuthDelete = null
            if (page == Page.AUTH) showAuth()
            return
        }
        if (requestCode != REQUEST_AUTH) return
        lastAuthOutcome = data?.getStringExtra(VerifyActivity.EXTRA_OUTCOME)
        if (page == Page.AUTH) {
            showAuth()
            // The grant is asked for in the background; show its answer when it lands.
            repeatHandler.postDelayed({ if (page == Page.AUTH) showAuth() }, 2500)
        }
    }

    /** One alarm: tick box, time, name, when it repeats, and edit / delete. */
    private fun alarmRow(book: AlarmBook, alarm: AlarmBook.Alarm): View {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        val top = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        // The tick box is the on/off switch: a 48dp target with the word beside it.
        top.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = true
            contentDescription = getString(if (alarm.enabled) R.string.alarm_on else R.string.alarm_off)
            setOnClickListener {
                book.setEnabled(alarm.id, !alarm.enabled)
                AlarmStore.save(this@SettingsActivity, book)
                showAlarms()
            }
            addView(ImageView(context).apply {
                setImageResource(if (alarm.enabled) R.drawable.ic_pixel_check_on else R.drawable.ic_pixel_check_off)
            }, LinearLayout.LayoutParams(dp(UiScale.ICON_L), dp(UiScale.ICON_L)))
            addView(text(getString(if (alarm.enabled) R.string.alarm_on else R.string.alarm_off), UiScale.TEXT_NOTE).apply {
                setPadding(dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_S), 0)
            })
        }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        top.addView(text(RetroType.pixelify(DashboardState.clock12(alarm.time), pixel), UiScale.TEXT_VALUE, dim = !alarm.enabled),
                    LinearLayout.LayoutParams(WRAP, WRAP))
        top.addView(text(alarm.label, UiScale.TEXT_ITEM, dim = !alarm.enabled).apply {
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        box.addView(top)

        val bottom = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, dp(UiScale.SPACE_S), 0, 0)
        }
        if (confirmingDelete == alarm.id) {
            // Deleting cannot be undone, so it asks once, in place.
            bottom.addView(text(getString(R.string.alarm_delete_confirm), UiScale.TEXT_NOTE), LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(button(getString(R.string.alarm_delete)) {
                book.remove(alarm.id)
                AlarmStore.save(this, book)
                confirmingDelete = null
                showAlarms()
            }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
            bottom.addView(button(getString(R.string.cancel)) {
                confirmingDelete = null
                showAlarms()
            }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        } else {
            bottom.addView(text(AlarmBook.repeatText(alarm), UiScale.TEXT_NOTE, dim = true), LinearLayout.LayoutParams(0, WRAP, 1f))
            bottom.addView(button(getString(R.string.edit)) { showEdit(alarm) },
                           LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
            bottom.addView(button(getString(R.string.alarm_delete)) {
                confirmingDelete = alarm.id
                showAlarms()
            }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        }
        box.addView(bottom)
        return box
    }

    // ---------------------------------------------------------------- edit

    private fun showEdit(alarm: AlarmBook.Alarm?) {
        page = Page.EDIT
        titleText.text = getString(if (alarm == null) R.string.alarm_add else R.string.alarm_edit_title)
        var hour = alarm?.hour ?: 6
        var minute = alarm?.minute ?: 0
        var days = alarm?.days ?: AlarmBook.EVERY_DAY
        var mode = when {
            alarm == null -> Repeat.DAILY
            alarm.once -> Repeat.ONCE
            alarm.days == AlarmBook.EVERY_DAY -> Repeat.DAILY
            else -> Repeat.DAYS
        }

        val form = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(UiScale.SPACE_S))
        }
        val error = text("", UiScale.TEXT_NOTE).apply {
            setTextColor(color(R.color.retro_bad))
            visibility = View.GONE
        }

        // Time: the big display and two spinners, Win95 style — ▲ / ▼ buttons
        // a thumb can hit, repeating while held.
        form.addView(label(getString(R.string.alarm_time)))
        val shown = text("", UiScale.TEXT_DISPLAY).apply { gravity = Gravity.CENTER }
        fun refreshTime() {
            shown.text = RetroType.pixelify(DashboardState.clock12(
                String.format(java.util.Locale.US, "%02d:%02d", hour, minute)), pixel)
        }
        refreshTime()
        form.addView(shown, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER
            addView(spinner(getString(R.string.alarm_hour),
                up = { hour = (hour + 1) % 24; refreshTime() },
                down = { hour = (hour + 23) % 24; refreshTime() }))
            addView(spinner(getString(R.string.alarm_minute),
                up = { minute = (minute + 1) % 60; refreshTime() },
                down = { minute = (minute + 59) % 60; refreshTime() }),
                LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(UiScale.SPACE_L) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })

        // Name.
        form.addView(label(getString(R.string.alarm_name)), LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dp(UiScale.SPACE_M) })
        val name = EditText(this).apply {
            setText(alarm?.label.orEmpty())
            hint = getString(R.string.alarm_name_hint)
            filters = arrayOf(InputFilter.LengthFilter(AlarmBook.MAX_LABEL_CHARS))
            typeface = thai
            textSize = UiScale.TEXT_HEADING
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            setTextColor(color(R.color.retro_text))
        }
        form.addView(name, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })

        // Repeat: three big choices, then the days when "เลือกวัน".
        form.addView(label(getString(R.string.alarm_repeat)), LinearLayout.LayoutParams(MATCH, WRAP)
            .apply { topMargin = dp(UiScale.SPACE_M) })
        val modeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val dayRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        fun renderRepeat() {
            modeRow.removeAllViews()
            for (choice in Repeat.values()) {
                modeRow.addView(toggle(getString(choice.label), choice == mode) {
                    mode = choice
                    if (choice == Repeat.DAYS && days == AlarmBook.EVERY_DAY) days = AlarmBook.WEEKDAYS
                    renderRepeat()
                }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { marginEnd = dp(UiScale.SPACE_XS) })
            }
            dayRow.removeAllViews()
            dayRow.visibility = if (mode == Repeat.DAYS) View.VISIBLE else View.GONE
            // Monday first, the way a Thai week is read.
            for (index in listOf(1, 2, 3, 4, 5, 6, 0)) {
                val bit = 1 shl index
                dayRow.addView(toggle(AlarmBook.DAY_NAMES[index], days and bit != 0) {
                    days = days xor bit
                    renderRepeat()
                }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { marginEnd = dp(UiScale.SPACE_XS) })
            }
        }
        renderRepeat()
        form.addView(modeRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        form.addView(dayRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })

        // Save / cancel.
        form.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.save), big = true) {
                val chosenDays = when (mode) {
                    Repeat.DAILY -> AlarmBook.EVERY_DAY
                    Repeat.DAYS -> days
                    Repeat.ONCE -> days
                }
                if (mode == Repeat.DAYS && chosenDays == 0) {
                    error.text = getString(R.string.alarm_pick_a_day)
                    error.visibility = View.VISIBLE
                    return@button
                }
                val book = AlarmStore.load(this@SettingsActivity)
                val label = name.text.toString()
                val ok = if (alarm == null) {
                    book.add(hour, minute, label, chosenDays, mode == Repeat.ONCE) != null
                } else {
                    book.update(alarm.id, hour, minute, label, chosenDays, mode == Repeat.ONCE)
                }
                if (!ok) {
                    error.text = getString(if (book.timeTaken(hour, minute, alarm?.id))
                        R.string.alarm_time_taken else R.string.alarms_full)
                    error.visibility = View.VISIBLE
                    return@button
                }
                AlarmStore.save(this@SettingsActivity, book)
                showAlarms()
            }, LinearLayout.LayoutParams(0, dp(UiScale.PRIMARY), 1f))
            addView(button(getString(R.string.cancel), big = true) { showAlarms() },
                    LinearLayout.LayoutParams(0, dp(UiScale.PRIMARY), 1f).apply { marginStart = dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })

        setPage(ScrollView(this).apply { addView(form) })
    }

    private enum class Repeat(val label: Int) {
        DAILY(R.string.alarm_daily), DAYS(R.string.alarm_some_days), ONCE(R.string.alarm_once)
    }

    // ------------------------------------------------------------- the parts

    internal fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    internal fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    internal fun label(value: String) = text(value, UiScale.TEXT_NOTE).apply {
        typeface = Typeface.create(thai, Typeface.BOLD)
    }

    /** A raised 1995 button that sinks while pressed. */
    internal fun button(value: String, big: Boolean = false, enabled: Boolean = true,
                        onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = if (big) UiScale.TEXT_HEADING else UiScale.TEXT_BASE
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(if (enabled) R.color.retro_text else R.color.retro_dim))
        setBackgroundResource(R.drawable.retro_button)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
        minWidth = dp(UiScale.TOUCH)
        isClickable = enabled
        isEnabled = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    /** A button that stays down while chosen: the day and repeat choices. */
    private fun toggle(value: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        gravity = Gravity.CENTER
        setTextColor(color(if (on) R.color.retro_title_text else R.color.retro_text))
        if (on) setBackgroundColor(color(R.color.retro_title))
        else setBackgroundResource(R.drawable.retro_button)
        contentDescription = value + if (on) " (เลือกอยู่)" else ""
        isClickable = true
        setOnClickListener { onClick() }
    }

    /** "ชั่วโมง ▲ ▼": two 48dp buttons that repeat while held. */
    private fun spinner(name: String, up: () -> Unit, down: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_HORIZONTAL
        addView(text(name, UiScale.TEXT_NOTE).apply { gravity = Gravity.CENTER })
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(repeating("▲", up), LinearLayout.LayoutParams(dp(UiScale.SYMBOL_W), dp(UiScale.TOUCH)))
            addView(repeating("▼", down), LinearLayout.LayoutParams(dp(UiScale.SYMBOL_W), dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        })
    }

    private val repeatHandler = Handler(Looper.getMainLooper())

    @android.annotation.SuppressLint("ClickableViewAccessibility")
    private fun repeating(symbol: String, step: () -> Unit) = TextView(this).apply {
        text = symbol
        textSize = UiScale.TEXT_VALUE
        gravity = Gravity.CENTER
        setTextColor(color(R.color.retro_text))
        setBackgroundResource(R.drawable.retro_button)
        isClickable = true
        val again = object : Runnable {
            override fun run() {
                step()
                repeatHandler.postDelayed(this, REPEAT_MS)
            }
        }
        setOnTouchListener { view, event ->
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    view.isPressed = true
                    step()
                    repeatHandler.postDelayed(again, REPEAT_START_MS)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    view.isPressed = false
                    repeatHandler.removeCallbacks(again)
                }
            }
            true
        }
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    internal fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    internal fun color(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
        private const val REPEAT_START_MS = 450L
        private const val REPEAT_MS = 110L
        private const val ICONS_PER_ROW = 3
        private const val REQUEST_AUTH = 37
        private const val REQUEST_DELETE = 38

        /**
         * The panel's icons IN GROUPS (Poom 2026-09-25, CLAUDE.md 3ก), each under a
         * short heading. ADD A SETTING HERE, in the group it belongs to: an icon, a
         * name, and the page it opens (DESIGN.md 5ก). The order is the reason:
         *  1. in the house and used most — the lights, the alarms, the torch — first,
         *     on the first screen without scrolling;
         *  2. media together; 3. tools together;
         *  4. setting the phone up, with "ที่มาข้อมูล" always the very last icon.
         */
        private val GROUPS = listOf(
            Group(R.string.settings_group_home, listOf(
                // 0.47.0: name the lights, allow each one, see its state. Kept on
                // the VPS; this is where they are set up, not switched.
                Category(R.drawable.ic_pixel_bulb_on, { it.getString(R.string.window_lights) }) { it.lights.open() },
                Category(R.drawable.ic_pixel_alarm_clock, { it.getString(R.string.window_alarms) }) { it.showAlarms() },
                Category(R.drawable.ic_pixel_torch, { it.torch.label() }) { it.torch.toggle() },
            )),
            Group(R.string.settings_group_media, listOf(
                // 0.53.0: the music player. The music is in media/MusicService and
                // plays on when this screen closes.
                Category(R.drawable.ic_pixel_music, { it.getString(R.string.window_music) }) {
                    it.startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(it, com.mammonrn.phoneaikiosk.media.MusicActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.PANEL))
                },
                // 0.56.0: the video player (media/VideoActivity); the sound plays on when it closes.
                Category(R.drawable.ic_pixel_video, { it.getString(R.string.window_video) }) {
                    it.startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(it, com.mammonrn.phoneaikiosk.media.VideoActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.PANEL))
                },
                // 0.61.0: the phone's camera, for a photo (camera/CameraActivity).
                Category(R.drawable.ic_pixel_camera, { it.getString(R.string.window_camera) }) {
                    it.startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(it, com.mammonrn.phoneaikiosk.camera.CameraActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.PANEL))
                },
                // 0.61.0: the voice recorder (recorder/RecorderActivity); the wake word rests while it records.
                Category(R.drawable.ic_pixel_mic, { it.getString(R.string.window_recorder) }) {
                    it.startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(it, com.mammonrn.phoneaikiosk.recorder.RecorderActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.PANEL))
                },
            )),
            Group(R.string.settings_group_tools, listOf(
                // 0.44.0: the file manager, a screen of its own (files/FilesActivity).
                Category(R.drawable.ic_pixel_folder, { it.getString(R.string.window_files) }) {
                    it.startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(it, com.mammonrn.phoneaikiosk.files.FilesActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.PANEL))
                },
                // 0.52.0: the engineering calculator, a screen of its own (calc/CalculatorActivity).
                Category(R.drawable.ic_pixel_calculator, { it.getString(R.string.window_calculator) }) {
                    it.startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(it, com.mammonrn.phoneaikiosk.calc.CalculatorActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.PANEL))
                },
            )),
            Group(R.string.settings_group_setup, listOf(
                // 0.42.0: WiFi opens the system's panel for one visit (WifiPanel);
                // the torch is a switch, and its label is its state.
                Category(R.drawable.ic_pixel_wifi, { it.getString(R.string.window_wifi) }) { it.openWifi() },
                Category(R.drawable.ic_pixel_face, { it.getString(R.string.window_auth) }) { it.showAuth() },
                Category(R.drawable.ic_pixel_sources, { it.getString(R.string.window_sources) }) { it.showSources() },
            )),
        )
    }
}
