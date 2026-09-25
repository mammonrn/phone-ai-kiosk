package com.mammonrn.phoneaikiosk.drive

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.auth.IdentityGate
import com.mammonrn.phoneaikiosk.files.FileOps
import com.mammonrn.phoneaikiosk.files.FilesActivity
import com.mammonrn.phoneaikiosk.files.FolderBrowser
import com.mammonrn.phoneaikiosk.files.FolderEntry
import com.mammonrn.phoneaikiosk.files.FolderSource
import com.mammonrn.phoneaikiosk.files.ImageViewerActivity
import com.mammonrn.phoneaikiosk.media.MusicActivity
import com.mammonrn.phoneaikiosk.media.Track
import com.mammonrn.phoneaikiosk.media.VideoActivity
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.ScreenDate
import com.mammonrn.phoneaikiosk.ui.UiScale
import com.mammonrn.phoneaikiosk.voice.DashboardState
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * GOOGLE DRIVE IN THE FILE MANAGER (0.61.0, Poom; DESIGN.md 5ฑ). Opened from
 * the file manager's storage list, and from a phone file's details with
 * "อัปโหลดไป Google Drive" ([EXTRA_UPLOAD]). Its own screen so the file
 * manager is not touched more than two buttons' worth; it looks and moves
 * the same: the same window, the same shared folder browser (FolderBrowser
 * over [DriveSource]), Back one level at a time.
 *
 * What it does: list and open folders, make a folder, upload a phone file,
 * download a Drive file to Download/Drive on the phone, rename, move to
 * Drive's trash (after "are you sure" AND the owner's face or pattern, like
 * every delete — DESIGN.md 5ฎ), and play or show a song, a video or a picture
 * alone in the kiosk's own players (downloaded to the app's cache first; one
 * such file is kept at a time).
 *
 * NEVER HANGS: every Drive or Google call runs on [worker] with a timeout,
 * and "not connected" is a page with the reason in words ([DriveStatus]).
 * Starts nothing but the kiosk's own screens — except Google's consent screen,
 * which only DriveAuth may show, and only once Poom approves.
 */
class DriveActivity : Activity() {

    private lateinit var r: Retro
    private lateinit var titleText: TextView
    private lateinit var content: FrameLayout
    private lateinit var browser: FolderBrowser
    private lateinit var source: DriveSource
    private lateinit var api: DriveApi

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { t -> Thread(t, "kiosk-drive") }
    private var generation = 0

    private sealed class Page {
        object Checking : Page()
        data class NotConnected(val status: DriveStatus) : Page()
        data class Folder(val path: String) : Page()
        data class Details(val path: String) : Page()
        data class Rename(val path: String) : Page()
        data class NewFolder(val parent: String) : Page()
        object Working : Page()
    }

    private var page: Page = Page.Checking

    /** A phone file waiting for its Drive folder (from the file manager's details). */
    private var upload: File? = null

    private var notice: String? = null
    private var noticeBad = false
    private var confirmingTrash = false
    private var confirmingDisconnect = false
    private var afterPass: (() -> Unit)? = null
    @Volatile private var work: FileOps.Work? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        r = Retro(this, thai)
        api = DriveApi(this)
        source = DriveSource(api, getString(R.string.drive_crumb))
        browser = FolderBrowser(r, worker, browserHost).apply { leavesTop = true }
        upload = intent.getStringExtra(EXTRA_UPLOAD)?.let(::File)?.takeIf { it.isFile }
        setContentView(buildWindow())
        hideSystemBars()
        check()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Play services may have been allowed in the locked task for the consent screen: never past it.
        DriveAuth.restoreAllowlist(this)
    }

    override fun onDestroy() {
        work?.cancelled = true
        worker.shutdownNow()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    @Deprecated("startActivityForResult's partner; this app has no androidx.activity.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        when (requestCode) {
            DriveAuth.REQUEST_CONSENT -> {
                val s = DriveAuth.finishConsent(this, data, resultCode)
                if (s == DriveStatus.CONNECTED) show(Page.Folder(DrivePath.ROOT)) else show(Page.NotConnected(s))
            }
            IdentityGate.REQUEST -> {
                val then = afterPass
                afterPass = null
                if (IdentityGate.passed(data)) {
                    Log.i(TAG, "trash: identity passed")
                    then?.invoke()
                } else {
                    Log.i(TAG, "trash: identity not passed")
                    say(IdentityGate.refusal(this, data), bad = true)
                    confirmingTrash = false
                    show(page)
                }
            }
        }
    }

    private fun goBack() {
        when (val p = page) {
            is Page.Working -> work?.cancelled = true
            is Page.Checking, is Page.NotConnected -> finish()
            is Page.Folder -> browser.up()
            is Page.Details -> show(Page.Folder(DrivePath.parent(p.path) ?: DrivePath.ROOT))
            is Page.Rename -> show(Page.Details(p.path))
            is Page.NewFolder -> show(Page.Folder(p.parent))
        }
    }

    /** The close button: back where this screen was opened from (ui/Origin, Poom 2026-09-25). */
    private fun closeApp() {
        com.mammonrn.phoneaikiosk.ui.Origin.close(this, "drive-close")
    }

    private fun goHome() {
        KioskScreens.leaveAllButHome("drive-home")
        startActivity(Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------ connecting

    /** Asks Google silently; the answer decides the first page. Never hangs: DriveAuth waits at most 20 s. */
    private fun check() {
        show(Page.Checking)
        val asked = generation
        worker.execute {
            val s = DriveAuth.ask(this)
            runOnUiThread {
                if (asked != generation) return@runOnUiThread
                show(if (s == DriveStatus.CONNECTED) Page.Folder(DrivePath.ROOT) else Page.NotConnected(s))
            }
        }
    }

    /** "เชื่อมต่อ": asks again; Google's consent screen only when Poom has approved it. */
    private fun connect() {
        DriveAuth.reconnect(this)
        show(Page.Checking)
        val asked = generation
        worker.execute {
            val s = DriveAuth.ask(this)
            runOnUiThread {
                if (asked != generation) return@runOnUiThread
                when {
                    s == DriveStatus.CONNECTED -> show(Page.Folder(DrivePath.ROOT))
                    s == DriveStatus.NEEDS_CONSENT && DriveAuth.allowConsentScreen(this) -> Unit
                    else -> show(Page.NotConnected(s))
                }
            }
        }
    }

    private fun disconnect() {
        confirmingDisconnect = false
        show(Page.Checking)
        val asked = generation
        worker.execute {
            val revoked = DriveAuth.disconnect(this)
            runOnUiThread {
                if (asked != generation) return@runOnUiThread
                say(getString(if (revoked) R.string.drive_disconnected_done else R.string.drive_disconnected_local), bad = !revoked)
                show(Page.NotConnected(DriveStatus.DISCONNECTED))
            }
        }
    }

    private fun statusWords(s: DriveStatus): String = getString(when (s) {
        DriveStatus.CONNECTED -> R.string.drive_status_connected
        DriveStatus.NEEDS_APPROVAL -> R.string.drive_status_needs_approval
        DriveStatus.NEEDS_CONSENT -> R.string.drive_status_needs_consent
        DriveStatus.NOT_REGISTERED -> R.string.drive_status_not_registered
        DriveStatus.NO_ACCOUNT -> R.string.drive_status_no_account
        DriveStatus.NO_NETWORK -> R.string.drive_status_no_network
        DriveStatus.NO_PLAY_SERVICES -> R.string.drive_status_no_play_services
        DriveStatus.TIMED_OUT -> R.string.drive_status_timed_out
        DriveStatus.DISCONNECTED -> R.string.drive_status_disconnected
        DriveStatus.CANCELLED -> R.string.drive_status_cancelled
        DriveStatus.FAILED -> R.string.drive_status_failed
    })

    private fun problemWords(e: Throwable): String = when (e) {
        is DriveNotConnected -> statusWords(e.status)
        is FileOps.Refused -> getString(if (e.reason == FileOps.Reason.NOT_ENOUGH_SPACE) R.string.drive_problem_space else R.string.drive_problem_other)
        else -> getString(when (DriveProblem.of(e)) {
            DriveProblem.NOT_FOUND -> R.string.drive_problem_not_found
            DriveProblem.NO_PERMISSION -> R.string.drive_problem_permission
            DriveProblem.FULL -> R.string.drive_problem_full
            DriveProblem.BUSY -> R.string.drive_problem_busy
            DriveProblem.API_OFF -> R.string.drive_problem_api_off
            DriveProblem.NO_NETWORK -> R.string.drive_status_no_network
            DriveProblem.OTHER -> R.string.drive_problem_other
        })
    }

    // ------------------------------------------------------------ the window

    private fun buildWindow(): View {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        val root = r.column().apply {
            setBackgroundColor(r.color(R.color.retro_desktop))
            setPadding(r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME))
        }
        val window = r.column().apply {
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bar = r.row().apply {
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_drive) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_S), r.dp(UiScale.ICON_S)))
        titleText = r.bold("", UiScale.TEXT_BASE).apply {
            setTextColor(r.color(R.color.retro_title_text))
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = com.mammonrn.phoneaikiosk.ui.Origin.closeWords(this@DriveActivity)
            isClickable = true
            setOnClickListener { closeApp() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(r.dp(UiScale.ICON_M), r.dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = r.dp(UiScale.WINDOW_INSET) })
        root.addView(r.button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.FRAME) })
        return root
    }

    private fun show(next: Page) {
        if (next !is Page.Details) confirmingTrash = false
        if (next !is Page.Folder) confirmingDisconnect = false
        page = next
        generation += 1
        when (next) {
            is Page.Checking -> showChecking()
            is Page.NotConnected -> showNotConnected(next.status)
            is Page.Folder -> showFolder(next.path)
            is Page.Details -> showDetails(next.path)
            is Page.Rename -> showName(next.path, rename = true)
            is Page.NewFolder -> showName(next.parent, rename = false)
            is Page.Working -> Unit
        }
    }

    private fun showChecking() {
        titleText.text = getString(R.string.drive_title)
        val list = r.column()
        list.addView(r.text(getString(R.string.drive_checking), UiScale.TEXT_BASE, dim = true))
        setPage(list)
    }

    /** "ยังไม่ได้เชื่อมต่อ Google Drive" and why, in words; "เชื่อมต่อ" and "ลองใหม่". */
    private fun showNotConnected(s: DriveStatus) {
        titleText.text = getString(R.string.drive_title)
        val list = r.column()
        addNotice(list)
        list.addView(r.button(getString(R.string.files_go_back)) { finish() }, LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
        list.addView(heading(R.drawable.ic_pixel_drive, getString(R.string.drive_not_connected)))
        list.addView(r.text(statusWords(s), UiScale.TEXT_BASE), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        val explain = r.text("", UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_bad)); visibility = View.GONE }
        if (s.connectable) {
            list.addView(r.button(getString(R.string.drive_connect), big = true) {
                if (!DriveAuth.SIGN_IN_ALLOWED && s == DriveStatus.NEEDS_APPROVAL) {
                    // Nothing opens: Google's screen may not come into the kiosk until Poom approves.
                    explain.text = getString(R.string.drive_connect_needs_approval)
                    explain.visibility = View.VISIBLE
                } else connect()
            }, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
            list.addView(explain, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        }
        list.addView(r.button(getString(R.string.drive_retry)) { check() },
                     LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
        list.addView(r.text(getString(R.string.drive_privacy), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    // ------------------------------------------------------------ a folder

    private var disconnectArea: LinearLayout? = null

    private fun showFolder(path: String) {
        titleText.text = folderTitle(path)
        val box = r.column()
        addNotice(box)
        val up = upload
        if (up != null) box.addView(uploadBanner(up), LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = r.dp(UiScale.SPACE_S) })
        browser.showFile = if (up != null) { _ -> false } else { _ -> true }
        browser.emptyWords = getString(if (up != null) R.string.drive_empty_pick else R.string.files_empty)
        browser.setExtras(listOf(
            r.button(getString(R.string.drive_new_folder)) { (page as? Page.Folder)?.let { show(Page.NewFolder(it.path)) } },
            r.button(getString(R.string.drive_retry)) { browser.reload() }))
        (browser.view.parent as? ViewGroup)?.removeView(browser.view)
        box.addView(browser.view, LinearLayout.LayoutParams(MATCH, 0, 1f))
        if (up == null) box.addView(r.text(getString(R.string.folder_hold_hint), UiScale.TEXT_NOTE, dim = true),
                                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val area = r.column().also { disconnectArea = it }
        box.addView(area, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        drawDisconnect(path)
        setPage(box)
        browser.open(source, path)
    }

    /** "ยกเลิกการเชื่อมต่อ" at My Drive only, asked once more in place. */
    private fun drawDisconnect(path: String) {
        val area = disconnectArea ?: return
        area.removeAllViews()
        if (path != DrivePath.ROOT || upload != null) return
        if (!confirmingDisconnect) {
            area.addView(r.button(getString(R.string.drive_disconnect)) { confirmingDisconnect = true; drawDisconnect(path) },
                         LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)))
            return
        }
        area.addView(r.text(getString(R.string.drive_disconnect_confirm), UiScale.TEXT_BASE))
        area.addView(r.pair(getString(R.string.drive_disconnect_yes), { disconnect() },
                            getString(R.string.cancel), { confirmingDisconnect = false; drawDisconnect(path) }),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
    }

    private fun folderTitle(path: String) = DrivePath.nameOf(path) ?: getString(R.string.drive_title)

    private val browserHost = object : FolderBrowser.Host {
        override fun onFile(source: FolderSource, entry: FolderEntry) {
            if (entry.blocked) return show(Page.Details(entry.path))
            when (FileOps.kindOfName(entry.name)) {
                FileOps.Kind.AUDIO, FileOps.Kind.VIDEO, FileOps.Kind.IMAGE -> openFile(entry.path)
                else -> show(Page.Details(entry.path))
            }
        }

        override fun onHold(source: FolderSource, entry: FolderEntry) = show(Page.Details(entry.path))

        override fun onAboveTop() = finish()

        override fun onChanged() {
            if (page !is Page.Folder) return
            page = Page.Folder(browser.path)
            titleText.text = folderTitle(browser.path)
            confirmingDisconnect = false
            drawDisconnect(browser.path)
        }

        override fun failureWords(error: Throwable): String {
            Log.i(TAG, "list failed ${error.javaClass.simpleName}")
            // No token any more (revoked elsewhere, account gone): the not-connected page says why.
            if (error is DriveNotConnected) handler.post { if (page is Page.Folder) show(Page.NotConnected(error.status)) }
            return problemWords(if (!DriveAuth.online(this@DriveActivity)) DriveNotConnected(DriveStatus.NO_NETWORK) else error)
        }
    }

    private fun uploadBanner(file: File): View = r.column().apply {
        setBackgroundColor(r.color(R.color.retro_title))
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        addView(r.bold(getString(R.string.drive_pick_upload, file.name), UiScale.TEXT_BASE).apply {
            setTextColor(r.color(R.color.retro_title_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        addView(r.row().apply {
            addView(r.button(getString(R.string.drive_upload_here)) { (page as? Page.Folder)?.let { runUpload(file, it.path) } },
                    LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
            addView(r.button(getString(R.string.cancel)) { finish() },
                    LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)).apply { marginStart = r.dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    // ------------------------------------------------------------ details

    private fun showDetails(path: String) {
        val f = source.file(path) ?: return show(Page.Folder(DrivePath.parent(path) ?: DrivePath.ROOT))
        titleText.text = getString(R.string.files_details_title)
        val list = r.column()
        addNotice(list)
        list.addView(r.button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
        val entry = FolderEntry(f.name, path, f.folder, f.size, f.modifiedMs, blocked = f.native)
        list.addView(heading(FolderBrowser.iconOf(entry), f.name))
        val kinds = resources.getStringArray(R.array.files_kinds)
        val kind = if (f.folder) FileOps.Kind.FOLDER else FileOps.kindOfName(f.name)
        list.addView(fact(getString(R.string.files_type), if (f.native) getString(R.string.drive_native_kind) else kinds[kind.ordinal]))
        if (!f.folder && !f.native) list.addView(fact(getString(R.string.files_size), FileOps.formatSize(f.size)))
        list.addView(fact(getString(R.string.files_modified), when_(f.modifiedMs)))
        list.addView(fact(getString(R.string.files_location),
                          DrivePath.crumbs(DrivePath.parent(path) ?: DrivePath.ROOT, getString(R.string.drive_crumb)).joinToString(" › ") { it.first }))
        if (f.native) {
            list.addView(r.text(getString(R.string.drive_native_note), UiScale.TEXT_BASE),
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        } else if (!f.folder) {
            if (kind == FileOps.Kind.AUDIO || kind == FileOps.Kind.VIDEO || kind == FileOps.Kind.IMAGE) {
                list.addView(r.button(getString(R.string.drive_open), big = true) { openFile(path) },
                             LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
            }
            list.addView(r.button(getString(R.string.drive_download)) { runDownload(path) },
                         LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
        }
        val area = r.column()
        list.addView(area, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        fun drawTrash() {
            area.removeAllViews()
            if (!confirmingTrash) {
                area.addView(r.pair(getString(R.string.files_rename), { show(Page.Rename(path)) },
                                    getString(R.string.drive_trash), { confirmingTrash = true; drawTrash() }))
                return
            }
            // Asked in place, then the owner's face or pattern: only a pass moves it to the trash.
            val box = r.column().apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            }
            box.addView(r.text(getString(if (f.folder) R.string.drive_trash_confirm_folder else R.string.drive_trash_confirm_file, f.name), UiScale.TEXT_BASE))
            box.addView(r.pair(getString(R.string.drive_trash_yes), { afterPass = { runTrash(path) }; IdentityGate.ask(this) },
                               getString(R.string.cancel), { confirmingTrash = false; drawTrash() }),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            area.addView(box)
        }
        drawTrash()
        list.addView(r.text(getString(R.string.drive_trash_note), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    /** A new name: a rename of [at], or a new folder in [at]. The name is checked against the folder as Drive has it now. */
    private fun showName(at: String, rename: Boolean) {
        val f = if (rename) source.file(at) ?: return show(Page.Folder(DrivePath.parent(at) ?: DrivePath.ROOT)) else null
        titleText.text = getString(if (rename) R.string.files_rename else R.string.drive_new_folder)
        val form = r.column()
        if (f != null) form.addView(heading(FolderBrowser.iconOf(FolderEntry(f.name, at, f.folder)), f.name))
        form.addView(r.label(getString(if (rename) R.string.files_new_name else R.string.drive_folder_name)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        val field = r.field(f?.name.orEmpty())
        if (f != null) {
            val dot = f.name.lastIndexOf('.')
            field.setSelection(0, if (!f.folder && dot > 0) dot else f.name.length)
        }
        form.addView(field, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val error = r.errorLine()
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        val folder = if (rename) DrivePath.parent(at) ?: DrivePath.ROOT else at
        form.addView(r.pair(getString(R.string.save), {
            val name = field.text.toString().trim()
            r.hideKeyboard(field)
            val asked = generation
            worker.execute {
                val result = runCatching {
                    val taken = api.list(DrivePath.idOf(folder)).map { it.name }
                    DriveNames.problem(name, taken, self = f?.name)?.let { return@runCatching it }
                    if (f != null) api.rename(f.id, name) else api.createFolder(DrivePath.idOf(folder), name)
                }
                runOnUiThread {
                    if (asked != generation) return@runOnUiThread
                    result.onSuccess { made ->
                        when (made) {
                            is DriveNameProblem -> {
                                error.text = getString(when (made) {
                                    DriveNameProblem.NAME_EMPTY -> R.string.files_reason_name_empty
                                    DriveNameProblem.NAME_TOO_LONG -> R.string.files_reason_name_long
                                    DriveNameProblem.NAME_TAKEN -> R.string.files_reason_name_taken
                                })
                                error.visibility = View.VISIBLE
                            }
                            is DriveFile -> {
                                Log.i(TAG, if (rename) "rename ok" else "mkdir ok")
                                say(getString(if (rename) R.string.drive_done_rename else R.string.drive_done_mkdir, made.name))
                                if (f != null) source.forget(at)
                                show(Page.Folder(folder))
                            }
                        }
                    }.onFailure { e ->
                        Log.i(TAG, "name failed ${e.javaClass.simpleName}")
                        error.text = problemWords(e)
                        error.visibility = View.VISIBLE
                    }
                }
            }
        }, getString(R.string.cancel), { goBack() }, big = true), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        setPage(ScrollView(this).apply { addView(form) })
        field.requestFocus()
    }

    // ------------------------------------------------------------ the work

    private fun runTrash(path: String) {
        val f = source.file(path) ?: return
        val parent = DrivePath.parent(path) ?: DrivePath.ROOT
        runWork(getString(R.string.drive_working_trash, f.name), "trash") { _ ->
            api.trash(f.id)
            source.forget(path)
            getString(R.string.drive_done_trash, f.name) to Page.Folder(parent)
        }
    }

    /** To the phone's Download/Drive, never over a file already there. */
    private fun runDownload(path: String) {
        val f = source.file(path) ?: return
        runWork(getString(R.string.drive_working_download, f.name), "download") { job ->
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS), DOWNLOAD_FOLDER)
            if (!dir.isDirectory && !dir.mkdirs()) throw FileOps.Refused(FileOps.Reason.NOT_WRITABLE)
            if (dir.usableSpace in 1 until f.size + SPACE_MARGIN) throw FileOps.Refused(FileOps.Reason.NOT_ENOUGH_SPACE)
            val target = File(dir, FileOps.freeName(dir, f.name))
            api.download(f.id, target, f.size, job)
            val renamed = target.name != f.name
            (getString(R.string.drive_done_download, target.name) + if (renamed) " " + getString(R.string.files_renamed_note) else "") to Page.Details(path)
        }
    }

    private fun runUpload(file: File, folder: String) {
        runWork(getString(R.string.drive_working_upload, file.name), "upload") { job ->
            if (!file.isFile) throw FileOps.Refused(FileOps.Reason.GONE)
            val taken = api.list(DrivePath.idOf(folder)).map { it.name }
            val name = DriveNames.free(taken, file.name)
            api.upload(DrivePath.idOf(folder), file, name, job)
            upload = null
            (getString(R.string.drive_done_upload, name) + if (name != file.name) " " + getString(R.string.files_renamed_note) else "") to Page.Folder(folder)
        }
    }

    /**
     * A song, a video or a picture from Drive, alone in the kiosk's own player
     * or viewer (DESIGN.md 5ฌ: never added to a playlist). Downloaded to the
     * app's cache first — the players then treat it as a phone file, VLC-only
     * kinds included — and only the last one opened is kept there.
     */
    private fun openFile(path: String) {
        val f = source.file(path) ?: return
        val kind = FileOps.kindOfName(f.name)
        runWork(getString(R.string.drive_working_open, f.name), "open") { job ->
            val dir = File(cacheDir, CACHE_FOLDER)
            dir.listFiles()?.forEach { it.delete() }
            dir.mkdirs()
            if (dir.usableSpace in 1 until f.size + SPACE_MARGIN) throw FileOps.Refused(FileOps.Reason.NOT_ENOUGH_SPACE)
            val target = File(dir, FileOps.freeName(dir, f.name))
            api.download(f.id, target, f.size, job)
            handler.post { play(kind, target) }
            "" to Page.Folder(DrivePath.parent(path) ?: DrivePath.ROOT)
        }
    }

    private fun play(kind: FileOps.Kind, file: File) {
        Log.i(TAG, "open one file: ${kind.name.lowercase()}")
        when (kind) {
            FileOps.Kind.AUDIO -> startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(this, MusicActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.FILES).putExtra(FilesActivity.SINGLE, Track.LOCAL + file.absolutePath))
            FileOps.Kind.VIDEO -> startActivity(com.mammonrn.phoneaikiosk.ui.Origin.from(Intent(this, VideoActivity::class.java), com.mammonrn.phoneaikiosk.ui.Origin.FILES).putExtra(FilesActivity.SINGLE, Track.LOCAL + file.absolutePath))
            else -> startActivity(Intent(this, ImageViewerActivity::class.java).putExtra(FilesActivity.SINGLE, file.absolutePath))
        }
    }

    /** The work on [worker] with a progress page and a stop button; a stop leaves nothing half-made (DriveApi). */
    private fun runWork(title: String, kind: String, block: (FileOps.Work) -> Pair<String, Page>) {
        val job = FileOps.Work()
        work = job
        val from = page
        page = Page.Working
        generation += 1
        titleText.text = title
        val box = r.column()
        box.addView(r.bold(title, UiScale.TEXT_HEADING))
        val track = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS))
        }
        val fill = View(this).apply { setBackgroundColor(r.color(R.color.retro_title)) }
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        box.addView(track, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PROGRESS)).apply { topMargin = r.dp(UiScale.SPACE_M) })
        val line = r.text("", UiScale.TEXT_NOTE)
        box.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        box.addView(r.button(getString(R.string.files_cancel_work), big = true) { job.cancelled = true },
                    LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_L) })
        setPage(ScrollView(this).apply { addView(box) })
        val ticker = object : Runnable {
            override fun run() {
                if (work !== job) return
                val fraction = if (job.totalBytes > 0) (job.doneBytes.toFloat() / job.totalBytes).coerceIn(0f, 1f) else 0f
                (fill.layoutParams as FrameLayout.LayoutParams).width = ((track.width - track.paddingLeft - track.paddingRight) * fraction).toInt()
                fill.requestLayout()
                line.text = if (job.totalBytes > 0) getString(R.string.drive_progress, FileOps.formatSize(job.doneBytes), FileOps.formatSize(job.totalBytes))
                            else getString(R.string.drive_checking)
                handler.postDelayed(this, 200)
            }
        }
        handler.post(ticker)
        worker.execute {
            val result = runCatching { block(job) }
            runOnUiThread {
                handler.removeCallbacks(ticker)
                if (work === job) work = null
                result.onSuccess { (message, next) ->
                    Log.i(TAG, "$kind ok")
                    if (message.isNotEmpty()) say(message)
                    show(next)
                }.onFailure { e ->
                    Log.i(TAG, "$kind failed ${if (e is DriveHttpError) "http=" + e.code else e.javaClass.simpleName}")
                    say(if (e is FileOps.Cancelled) getString(R.string.drive_stopped) else problemWords(e), bad = e !is FileOps.Cancelled)
                    if (e is DriveNotConnected) show(Page.NotConnected(e.status))
                    else show(when (from) { is Page.Details, is Page.Folder -> from; else -> Page.Folder(DrivePath.ROOT) })
                }
            }
        }
    }

    // ------------------------------------------------------------ the parts

    private fun say(message: String, bad: Boolean = false) {
        notice = message
        noticeBad = bad
    }

    private fun addNotice(box: LinearLayout) {
        val message = notice ?: return
        box.addView(r.text(message, UiScale.TEXT_BASE).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            if (noticeBad) setTextColor(r.color(R.color.retro_bad))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = r.dp(UiScale.SPACE_S) })
        notice = null
    }

    private fun heading(icon: Int, name: String): View = r.row().apply {
        setPadding(0, r.dp(UiScale.SPACE_S), 0, 0)
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
        addView(r.bold(name, UiScale.TEXT_HEADING).apply { setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0) }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun fact(name: String, value: String): View = r.column().apply {
        setPadding(0, r.dp(UiScale.SPACE_S), 0, 0)
        addView(r.text(name, UiScale.TEXT_NOTE, dim = true))
        addView(r.text(value, UiScale.TEXT_ITEM))
    }

    private fun when_(ms: Long): String {
        if (ms <= 0) return "—"
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return ScreenDate.format(c) + " " + DashboardState.clock12(
            String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    private fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    companion object {
        /** `logcat -s KioskDrive:I`: kinds, statuses and codes; never a name, a path, an account or a token. */
        const val TAG = "KioskDrive"
        /** A phone file to upload: the file manager's "อัปโหลดไป Google Drive". */
        const val EXTRA_UPLOAD = "com.mammonrn.phoneaikiosk.DRIVE_UPLOAD"
        /** Downloads go to Download/Drive on the phone, where the file manager shows them. */
        const val DOWNLOAD_FOLDER = "Drive"
        private const val CACHE_FOLDER = "drive-open"
        private const val SPACE_MARGIN = 200L * 1000 * 1000
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT

        fun uploadIntent(context: Context, file: File): Intent =
            Intent(context, DriveActivity::class.java).putExtra(EXTRA_UPLOAD, file.absolutePath)
                .putExtra(com.mammonrn.phoneaikiosk.ui.Origin.EXTRA, com.mammonrn.phoneaikiosk.ui.Origin.FILES)
    }
}
