package com.mammonrn.phoneaikiosk.files

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.storage.StorageManager
import android.text.InputType
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.widget.BaseAdapter
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.ui.ScreenDate
import com.mammonrn.phoneaikiosk.voice.DashboardState
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors
import com.mammonrn.phoneaikiosk.ui.UiScale
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.auth.IdentityGate
import com.mammonrn.phoneaikiosk.media.MusicActivity
import com.mammonrn.phoneaikiosk.media.VideoActivity

/**
 * The file manager (0.44.0, Poom): the phone's files, and the NAS read only.
 *
 * WHAT IT IS FOR. Looking through the phone's storage, a file's details,
 * unpacking a zip, copy / move / rename / delete, finding a file by name, and
 * reading the NAS in the house. Opened from the Control Panel's icon; a screen
 * of this app, not another app.
 *
 * STAYS IN THE KIOSK. It never starts anything but the kiosk's own screens:
 * no "open with", no system file picker, no settings page. Lock task would
 * refuse them anyway, and a refusal is a dead button. KioskScreens closes it
 * when Hey Jarvis is heard; work in progress is stopped and its half-made file
 * removed (FileOps.Work).
 *
 * ONE PAGE AT A TIME ([Page]), Back one level up: details → folder → the
 * folder above → the storage list → the Control Panel. The title bar's X and
 * the big button at the bottom go to the home screen.
 *
 * All file and network work runs on [worker]; the main thread only draws.
 * DESIGN.md 5ค has the rules this screen keeps.
 */
class FilesActivity : Activity() {

    private lateinit var thai: Typeface
    private lateinit var titleText: TextView
    private lateinit var content: FrameLayout
    /** 0.59.0: every folder, the phone's and the NAS's, is this one shared part (FolderBrowser). */
    private lateinit var browser: FolderBrowser
    private val localSources = HashMap<String, LocalSource>()

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-files") }

    /** Bumped on every page change: an answer from [worker] for an older page is dropped. */
    private var generation = 0

    private sealed class Page {
        object Roots : Page()
        object Permission : Page()
        data class Folder(val dir: File) : Page()
        data class Details(val file: File) : Page()
        data class Rename(val file: File) : Page()
        data class Search(val dir: File) : Page()
        data class Blocked(val dir: File, val appPrivate: Boolean) : Page()
        object NasSetup : Page()
        data class NasFolder(val path: String) : Page()
        data class NasDetails(val entry: NasEntry) : Page()
        object Working : Page()
    }

    private var page: Page = Page.Roots

    /** Copy, move or copy-from-NAS waiting for its destination folder. */
    private class Pick(val kind: Kind, val file: File?, val nas: NasEntry?) {
        enum class Kind { COPY, MOVE, DOWNLOAD }
        val name: String get() = file?.name ?: nas?.name.orEmpty()
    }

    private var pick: Pick? = null

    /** One line at the top of the next page: what just happened. */
    private var notice: String? = null
    private var noticeBad = false

    private var confirmingDelete = false
    /** 0.60.0: what is deleted once the owner's face or pattern passes (auth/IdentityGate). */
    private var afterPass: (() -> Unit)? = null
    private var confirmingForget = false

    /** The work in progress, if any: how far, and the way to stop it. */
    @Volatile private var work: FileOps.Work? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        browser = FolderBrowser(Retro(this, thai), worker, browserHost).apply { leavesTop = true }
        setContentView(buildWindow())
        hideSystemBars()
        show(Page.Roots)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Something may have changed while away (a permission granted over
        // adb, a file added): the storage list and a folder are read again.
        when (page) {
            is Page.Roots, is Page.Permission -> show(Page.Roots)
            is Page.Folder -> show(page)
            else -> Unit
        }
    }

    override fun onDestroy() {
        // Hey Jarvis, the X, or Back from the top: whatever was running stops.
        work?.cancelled = true
        worker.shutdown()
        handler.removeCallbacksAndMessages(null)
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** The owner's face or pattern for a delete: only a pass deletes (auth/IdentityGate). */
    @Deprecated("startActivityForResult's partner; this app has no androidx.activity.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IdentityGate.REQUEST) return
        val then = afterPass
        afterPass = null
        if (IdentityGate.passed(data)) {
            Log.i(TAG, "delete: identity passed")
            then?.invoke()
        } else {
            Log.i(TAG, "delete: identity not passed")
            say(IdentityGate.refusal(this, data), bad = true)
            confirmingDelete = false
            confirmingForget = false
            show(page)
        }
    }

    private fun goBack() {
        when (val p = page) {
            is Page.Working -> work?.cancelled = true
            is Page.Roots -> if (pick != null) { pick = null; show(Page.Roots) } else finish()
            is Page.Permission, is Page.NasSetup -> show(Page.Roots)
            is Page.Folder -> browser.up()
            is Page.Details -> show(Page.Folder(p.file.parentFile ?: return show(Page.Roots)))
            is Page.Rename -> show(Page.Details(p.file))
            is Page.Search -> show(Page.Folder(p.dir))
            is Page.Blocked -> show(folderAbove(p.dir))
            is Page.NasFolder -> browser.up()
            is Page.NasDetails -> show(Page.NasFolder(p.entry.path.substringBeforeLast('\\', "")))
        }
    }

    /** The folder above [dir], or the storage list when [dir] is a storage root. */
    private fun folderAbove(dir: File): Page {
        val parent = dir.parentFile
        return if (parent == null || StorageAreas.rootOf(dir, roots()) == null || roots().any { it.absolutePath == dir.absolutePath }) Page.Roots
        else Page.Folder(parent)
    }

    /** To the kiosk screen, closing the Control Panel under this too. */
    private fun goHome() {
        KioskScreens.leaveAllButHome("files-home")
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
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_folder) },
                    LinearLayout.LayoutParams(dp(UiScale.ICON_S), dp(UiScale.ICON_S)))
        titleText = TextView(this).apply {
            setTextColor(color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = getString(R.string.settings_home)
            isClickable = true
            setOnClickListener { goHome() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))
        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.WINDOW_INSET) })
        root.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        return root
    }

    private fun show(next: Page) {
        if (next !is Page.Details) confirmingDelete = false
        if (next !is Page.NasSetup) confirmingForget = false
        page = next
        generation += 1
        when (next) {
            is Page.Roots -> showRoots()
            is Page.Permission -> showPermission()
            is Page.Folder -> showFolder(next.dir)
            is Page.Details -> showDetails(next.file)
            is Page.Rename -> showRename(next.file)
            is Page.Search -> showSearch(next.dir)
            is Page.Blocked -> showBlocked(next)
            is Page.NasSetup -> showNasSetup()
            is Page.NasFolder -> showNasFolder(next.path)
            is Page.NasDetails -> showNasDetails(next.entry)
            is Page.Working -> Unit
        }
    }

    // ------------------------------------------------------------ storage

    private fun hasAllFiles(): Boolean = Environment.isExternalStorageManager()

    /** The phone's shared storage, then any mounted SD card. Never anything above them. */
    private fun roots(): List<File> {
        val list = ArrayList<File>()
        list.add(Environment.getExternalStorageDirectory())
        val manager = getSystemService(StorageManager::class.java)
        manager?.storageVolumes?.forEach { volume ->
            val dir = volume.directory
            if (volume.isRemovable && dir != null && volume.state == Environment.MEDIA_MOUNTED &&
                list.none { it.absolutePath == dir.absolutePath }) list.add(dir)
        }
        return list
    }

    private fun rootName(root: File): String =
        if (root.absolutePath == Environment.getExternalStorageDirectory().absolutePath)
            getString(R.string.files_this_phone) else getString(R.string.files_sd_card)

    private fun trail(dir: File): String {
        val root = StorageAreas.rootOf(dir, roots()) ?: return dir.name
        return StorageAreas.trail(dir, root, rootName(root)).joinToString(" › ")
    }

    // ------------------------------------------------------------ roots

    private fun showRoots() {
        titleText.text = getString(R.string.window_files)
        val list = column()
        addNoticeAndPick(list)
        list.addView(button(getString(R.string.settings_back_to_panel)) { finish() },
                     LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        val allowed = hasAllFiles()
        for (root in roots()) {
            val sub = if (allowed) getString(R.string.files_space,
                FileOps.formatSize(root.usableSpace), FileOps.formatSize(root.totalSpace))
                else getString(R.string.files_no_permission_title)
            list.addView(bigRow(if (allowed) R.drawable.ic_pixel_phone else R.drawable.ic_pixel_lock,
                                rootName(root), sub) {
                show(if (allowed) Page.Folder(root) else Page.Permission)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        }
        // The NAS is a source, never a destination (read only), so it is not
        // offered while a destination is being chosen.
        if (pick == null) {
            val config = runCatching { NasStore.load(this) }.getOrNull()
            list.addView(bigRow(R.drawable.ic_pixel_nas, getString(R.string.files_nas),
                if (config != null) getString(R.string.files_nas_set, config.share)
                else getString(R.string.files_nas_not_set)) {
                show(if (config != null) Page.NasFolder("") else Page.NasSetup)
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        } else {
            list.addView(text(getString(R.string.files_pick_open_folder), UiScale.TEXT_NOTE, dim = true).apply {
                setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), 0)
            })
        }
        setPage(ScrollView(this).apply { addView(list) })
    }

    private fun showPermission() {
        titleText.text = getString(R.string.files_no_permission_title)
        val list = column()
        list.addView(button(getString(R.string.files_go_back)) { show(Page.Roots) },
                     LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        list.addView(heading(R.drawable.ic_pixel_lock, getString(R.string.files_this_phone)))
        list.addView(text(getString(R.string.files_no_permission), UiScale.TEXT_BASE),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        // The one command that grants it, with this build's package name.
        list.addView(text("adb shell appops set --uid $packageName MANAGE_EXTERNAL_STORAGE allow", UiScale.TEXT_NOTE).apply {
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            setTextIsSelectable(false)
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    // ------------------------------------------------------------ a folder

    private fun showFolder(dir: File) {
        val roots = roots()
        if (StorageAreas.appPrivate(dir, roots)) return show(Page.Blocked(dir, true))
        val root = StorageAreas.rootOf(dir, roots) ?: return show(Page.Roots)
        titleText.text = if (roots.any { it.absolutePath == dir.absolutePath }) rootName(dir)
                         else dir.name.ifEmpty { getString(R.string.window_files) }
        val box = column(fill = true)
        addNoticeAndPick(box) { (page as? Page.Folder)?.dir }
        // While choosing a destination, only folders show.
        browser.showFile = if (pick != null) { _ -> false } else { _ -> true }
        browser.emptyWords = getString(if (pick != null) R.string.files_empty_pick else R.string.files_empty)
        browser.setExtras(if (pick == null) listOf(button(getString(R.string.files_search)) {
            (page as? Page.Folder)?.let { show(Page.Search(it.dir)) }
        }) else emptyList())
        addBrowser(box)
        if (pick == null) box.addView(text(getString(R.string.folder_hold_hint), UiScale.TEXT_NOTE, dim = true),
                                      LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        setPage(box)
        val source = localSources.getOrPut(root.absolutePath) {
            LocalSource(root, crumbName(root), roots)
        }
        if (browser.source !== source || browser.path != dir.absolutePath || browser.rows.isEmpty()) browser.open(source, dir.absolutePath)
        else browser.reload()
    }

    /** The browser's place on [box]: taken off the page it was on before. */
    private fun addBrowser(box: LinearLayout) {
        (browser.view.parent as? ViewGroup)?.removeView(browser.view)
        box.addView(browser.view, LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    /** "เครื่อง" / "การ์ด SD": the first word of the breadcrumb. */
    private fun crumbName(root: File): String =
        if (root.absolutePath == Environment.getExternalStorageDirectory().absolutePath)
            getString(R.string.folder_crumb_phone) else getString(R.string.folder_crumb_sd)

    /**
     * What the shared browser asks of this screen: a file tapped opens where it
     * belongs — music and video on their own in the kiosk's players (0.59.0,
     * never added to a playlist), a picture in the viewer, anything else its
     * details; a row held shows its details; up from the top is the storage list.
     */
    private val browserHost = object : FolderBrowser.Host {
        override fun onFile(source: FolderSource, entry: FolderEntry) {
            val id = source.trackId(entry)
            when (FileOps.kindOfName(entry.name)) {
                FileOps.Kind.AUDIO -> {
                    Log.i(TAG, "open one file: audio")
                    startActivity(Intent(this@FilesActivity, MusicActivity::class.java).putExtra(SINGLE, id))
                }
                FileOps.Kind.VIDEO -> {
                    Log.i(TAG, "open one file: video")
                    startActivity(Intent(this@FilesActivity, VideoActivity::class.java).putExtra(SINGLE, id))
                }
                FileOps.Kind.IMAGE -> if (source is LocalSource) {
                    Log.i(TAG, "open one file: picture")
                    startActivity(Intent(this@FilesActivity, ImageViewerActivity::class.java).putExtra(SINGLE, entry.path))
                } else show(Page.NasDetails(nasEntry(entry)))
                else -> onHold(source, entry)
            }
        }

        override fun onHold(source: FolderSource, entry: FolderEntry) {
            if (source is NasSource) { if (!entry.folder) show(Page.NasDetails(nasEntry(entry))) }
            else if (!entry.blocked) show(Page.Details(File(entry.path)))
        }

        override fun onAboveTop() = show(Page.Roots)

        /** The browser moved: this screen's page follows, so Back and the title are right. */
        override fun onChanged() {
            val s = browser.source ?: return
            val at = browser.path
            if (page !is Page.Folder && page !is Page.NasFolder) return
            if (s is NasSource) {
                page = Page.NasFolder(at)
                titleText.text = if (at.isEmpty()) getString(R.string.files_nas) else at.substringAfterLast('\\')
            } else {
                page = Page.Folder(File(at))
                titleText.text = if (roots().any { it.absolutePath == at }) rootName(File(at)) else File(at).name
            }
        }

        override fun failureWords(error: Throwable): String {
            val problem = if (!isOnline()) NasProblem.NO_NETWORK else NasProblem.of(error)
            // The problem's name only: never the address, the user or the message.
            Log.i(TAG, "nas list failed problem=$problem")
            return nasWords(problem)
        }
    }

    private fun nasEntry(e: FolderEntry) = NasEntry(e.name, e.path, e.folder, e.size, e.modifiedMs)

    /** One row of a search result: its icon, name, and what it is. */
    private fun rowFor(file: File, roots: List<File>): Row {
        val blocked = file.isDirectory && StorageAreas.appPrivate(file, roots)
        return Row(
            icon = FolderBrowser.iconOf(FolderEntry(file.name, file.path, file.isDirectory, blocked = blocked)),
            name = file.name,
            // Read when the row is drawn, so a folder of thousands opens at once.
            sub = {
                when {
                    blocked -> getString(R.string.files_blocked_row)
                    file.isDirectory -> getString(R.string.files_folder_items, file.list()?.size ?: 0)
                    else -> FileOps.formatSize(file.length()) + " · " + when_(file.lastModified())
                }
            },
            open = {
                when {
                    blocked -> show(Page.Blocked(file, true))
                    file.isDirectory -> show(Page.Folder(file))
                    else -> show(Page.Details(file))
                }
            },
            manage = null,
        )
    }

    private fun showBlocked(p: Page.Blocked) {
        titleText.text = getString(R.string.files_blocked_title)
        val list = column()
        list.addView(button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        list.addView(heading(R.drawable.ic_pixel_lock, p.dir.name))
        list.addView(text(getString(if (p.appPrivate) R.string.files_blocked_app else R.string.files_blocked_other), UiScale.TEXT_BASE),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    // ------------------------------------------------------------ details

    private fun showDetails(file: File) {
        if (!file.exists()) {
            say(getString(R.string.files_reason_gone), bad = true)
            return show(Page.Folder(file.parentFile ?: return show(Page.Roots)))
        }
        titleText.text = getString(R.string.files_details_title)
        val kind = FileOps.kind(file)
        val list = column()
        addNoticeAndPick(list)
        list.addView(button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        list.addView(heading(iconFor(file), file.name))
        val kinds = resources.getStringArray(R.array.files_kinds)
        list.addView(fact(getString(R.string.files_type), kinds[kind.ordinal]))
        if (!file.isDirectory) list.addView(fact(getString(R.string.files_size), FileOps.formatSize(file.length())))
        list.addView(fact(getString(R.string.files_modified), when_(file.lastModified())))
        list.addView(fact(getString(R.string.files_location), trail(file.parentFile ?: file)))
        val inside = if (file.isDirectory || kind == FileOps.Kind.ZIP)
            fact(getString(R.string.files_inside), getString(R.string.data_loading)).also(list::addView) else null
        val insideValue = (inside as? LinearLayout)?.getChildAt(1) as? TextView

        val unzip = if (kind == FileOps.Kind.ZIP) button(getString(R.string.files_unzip), big = true) { runUnzip(file) }
            .also { list.addView(it, LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_M) }) }
            else null
        if (kind == FileOps.Kind.OTHER_ARCHIVE) {
            list.addView(text(getString(R.string.files_other_archive), UiScale.TEXT_NOTE, dim = true),
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        }
        list.addView(pair(getString(R.string.files_copy), { startPick(Pick.Kind.COPY, file) },
                          getString(R.string.files_move), { startPick(Pick.Kind.MOVE, file) }),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(if (kind == FileOps.Kind.ZIP) 8 else 14) })
        val deleteArea = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        list.addView(deleteArea, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        var insideCount = -1
        fun drawDelete() {
            deleteArea.removeAllViews()
            if (!confirmingDelete) {
                deleteArea.addView(pair(getString(R.string.files_rename), { show(Page.Rename(file)) },
                                        getString(R.string.files_delete), { confirmingDelete = true; drawDelete() }))
                return
            }
            // Deleting cannot be undone: always asked, in place, saying what goes.
            val box = LinearLayout(this).apply {
                orientation = LinearLayout.VERTICAL
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            }
            box.addView(text(if (file.isDirectory)
                getString(R.string.files_delete_confirm_folder, file.name, insideCount.coerceAtLeast(0))
                else getString(R.string.files_delete_confirm_file, file.name), UiScale.TEXT_BASE))
            box.addView(pair(getString(R.string.files_delete_yes), { afterPass = { runDelete(file) }; IdentityGate.ask(this) },
                             getString(R.string.cancel), { confirmingDelete = false; drawDelete() }),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
            deleteArea.addView(box)
        }
        drawDelete()
        setPage(ScrollView(this).apply { addView(list) })

        if (insideValue != null) {
            val asked = generation
            worker.execute {
                val line = if (file.isDirectory) {
                    val n = FileOps.countInside(file)
                    runOnUiThread { insideCount = n }
                    getString(R.string.files_items, n)
                } else runCatching {
                    val s = FileOps.zipSummary(file)
                    getString(R.string.files_zip_inside, s.files, FileOps.formatSize(s.bytes))
                }.getOrElse {
                    // Known before anyone presses: why it cannot be unpacked,
                    // and the button goes dim rather than invite a failure.
                    runOnUiThread { if (asked == generation) unzip?.let(::disable) }
                    if (it is FileOps.Refused) words(it) else getString(R.string.files_zip_unreadable)
                }
                runOnUiThread { if (asked == generation) insideValue.text = line }
            }
        }
    }

    private fun iconFor(file: File): Int = FolderBrowser.iconOf(FolderEntry(file.name, file.path, file.isDirectory))

    private fun showRename(file: File) {
        titleText.text = getString(R.string.files_rename)
        val form = column()
        form.addView(heading(iconFor(file), file.name))
        form.addView(label(getString(R.string.files_new_name)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        val field = field(file.name)
        // The name without its extension is selected, as a desktop does it.
        val dot = file.name.lastIndexOf('.')
        field.setSelection(0, if (!file.isDirectory && dot > 0) dot else file.name.length)
        form.addView(field, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
        val error = errorLine()
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        form.addView(pair(getString(R.string.save), {
            val result = runCatching { FileOps.rename(file, field.text.toString()) }
            result.onSuccess {
                say(getString(R.string.files_done_rename, it.name))
                Log.i(TAG, "rename ok")
                show(Page.Details(it))
            }.onFailure {
                // The text stays as typed; the reason is under it.
                error.text = words(it)
                error.visibility = View.VISIBLE
            }
        }, getString(R.string.cancel), { show(Page.Details(file)) }, big = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        setPage(ScrollView(this).apply { addView(form) })
        field.requestFocus()
    }

    // ------------------------------------------------------------ search

    private fun showSearch(dir: File) {
        titleText.text = getString(R.string.files_search)
        val box = column(fill = true)
        box.addView(button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        box.addView(text(getString(R.string.files_search_in, trail(dir)), UiScale.TEXT_NOTE, dim = true).apply {
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        val field = field("").apply {
            hint = getString(R.string.files_search_hint)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
        }
        val status = text("", UiScale.TEXT_NOTE)
        val results = FrameLayout(this).apply { setBackgroundResource(R.drawable.retro_field) }
        val roots = roots()
        fun run() {
            val query = field.text.toString().trim()
            if (query.isEmpty()) {
                status.text = getString(R.string.files_search_type)
                return
            }
            hideKeyboard(field)
            work?.cancelled = true
            val job = FileOps.Work().also { work = it }
            results.removeAllViews()
            val asked = generation
            val ticker = object : Runnable {
                override fun run() {
                    if (asked != generation || work !== job) return
                    status.text = getString(R.string.files_searching, job.doneFiles)
                    handler.postDelayed(this, 250)
                }
            }
            handler.post(ticker)
            worker.execute {
                val found = runCatching {
                    FileOps.search(dir, query, job) { StorageAreas.appPrivate(it, roots) }
                }.getOrNull()
                runOnUiThread {
                    handler.removeCallbacks(ticker)
                    if (work === job) work = null
                    if (asked != generation || found == null) return@runOnUiThread
                    Log.i(TAG, "search found=${found.files.size} more=${found.more} folders=${found.foldersLookedIn}")
                    status.text = when {
                        found.files.isEmpty() -> getString(R.string.files_search_none, query)
                        found.more -> getString(R.string.files_search_more, found.files.size)
                        else -> getString(R.string.files_search_count, found.files.size)
                    }
                    if (found.files.isNotEmpty()) {
                        results.addView(fileList(found.files.map { f ->
                            val row = rowFor(f, roots)
                            Row(row.icon, f.name, { trail(f.parentFile ?: f) }, row.open, null)
                        }), FrameLayout.LayoutParams(MATCH, MATCH))
                    }
                }
            }
        }
        field.setOnEditorActionListener { _, action, _ -> if (action == EditorInfo.IME_ACTION_SEARCH) { run(); true } else false }
        box.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(field, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
            addView(button(getString(R.string.files_search)) { run() },
                    LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        box.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        box.addView(results, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        setPage(box)
        field.requestFocus()
    }

    // ------------------------------------------------------------ copy, move, delete, unzip

    private fun startPick(kind: Pick.Kind, file: File) {
        pick = Pick(kind, file, null)
        show(Page.Folder(file.parentFile ?: return show(Page.Roots)))
    }

    private fun paste(dest: File) {
        val p = pick ?: return
        pick = null
        when (p.kind) {
            Pick.Kind.COPY -> runWork(getString(R.string.files_working_copy, p.name), "copy") { job ->
                val made = FileOps.copy(p.file!!, dest, job)
                done(R.string.files_done_copy, made, p.name) to Page.Folder(dest)
            }
            Pick.Kind.MOVE -> runWork(getString(R.string.files_working_move, p.name), "move") { job ->
                val made = FileOps.move(p.file!!, dest, job)
                done(R.string.files_done_move, made, p.name) to Page.Folder(dest)
            }
            Pick.Kind.DOWNLOAD -> runWork(getString(R.string.files_working_download, p.name), "nas-copy") { job ->
                val entry = p.nas!!
                val target = File(dest, FileOps.freeName(dest, entry.name))
                if (dest.usableSpace in 1 until entry.size) throw FileOps.Refused(FileOps.Reason.NOT_ENOUGH_SPACE)
                withNas { it.download(entry, target, job) }
                done(R.string.files_done_download, target, entry.name) to Page.Folder(dest)
            }
        }
    }

    private fun done(message: Int, made: File, asked: String): String =
        getString(message, made.name) + if (made.name != asked) " " + getString(R.string.files_renamed_note) else ""

    private fun runDelete(file: File) {
        val parent = file.parentFile ?: return
        runWork(getString(R.string.files_working_delete, file.name), "delete", deleting = true) { job ->
            FileOps.deleteTree(file, job)
            getString(R.string.files_done_delete, file.name) to Page.Folder(parent)
        }
    }

    private fun runUnzip(zip: File) {
        val parent = zip.parentFile ?: return
        runWork(getString(R.string.files_working_unzip, zip.name), "unzip") { job ->
            val out = FileOps.unzip(zip, job)
            getString(R.string.files_done_unzip, out.name) to Page.Folder(out)
        }
    }

    /**
     * Runs [block] on the worker with a progress page and a stop button. The
     * block returns what to say and where to go; a refusal is said in words,
     * a stop says what was undone. Logged as the kind and the outcome only.
     */
    private fun runWork(title: String, kind: String, deleting: Boolean = false,
                        block: (FileOps.Work) -> Pair<String, Page>) {
        val job = FileOps.Work()
        work = job
        val from = page
        page = Page.Working
        generation += 1
        titleText.text = title
        val box = column()
        box.addView(text(title, UiScale.TEXT_HEADING).apply { typeface = Typeface.create(thai, Typeface.BOLD) })
        val track = FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS))
        }
        val fill = View(this).apply { setBackgroundColor(color(R.color.retro_title)) }
        track.addView(fill, FrameLayout.LayoutParams(0, MATCH))
        box.addView(track, LinearLayout.LayoutParams(MATCH, dp(UiScale.PROGRESS)).apply { topMargin = dp(UiScale.SPACE_M) })
        val line = text("", UiScale.TEXT_NOTE)
        box.addView(line, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        box.addView(button(getString(R.string.files_cancel_work), big = true) { job.cancelled = true },
                    LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_L) })
        setPage(ScrollView(this).apply { addView(box) })
        val ticker = object : Runnable {
            override fun run() {
                if (work !== job) return
                val fraction = when {
                    job.totalBytes > 0 -> job.doneBytes.toFloat() / job.totalBytes
                    job.totalFiles > 0 -> job.doneFiles.toFloat() / job.totalFiles
                    else -> 0f
                }.coerceIn(0f, 1f)
                (fill.layoutParams as FrameLayout.LayoutParams).width = ((track.width - track.paddingLeft -
                    track.paddingRight) * fraction).toInt()
                fill.requestLayout()
                line.text = if (job.totalBytes > 0) getString(R.string.files_progress, job.doneFiles, job.totalFiles,
                    FileOps.formatSize(job.doneBytes), FileOps.formatSize(job.totalBytes))
                else getString(R.string.files_progress_count, job.doneFiles, job.totalFiles)
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
                    say(message)
                    show(next)
                }.onFailure { e ->
                    Log.i(TAG, "$kind failed ${if (e is FileOps.Refused) e.reason.name else e.javaClass.simpleName}")
                    say(when {
                        e is FileOps.Cancelled && deleting -> getString(R.string.files_stopped_delete)
                        e is FileOps.Cancelled -> getString(R.string.files_stopped)
                        else -> words(e)
                    }, bad = e !is FileOps.Cancelled)
                    show(when (from) {
                        is Page.Details -> if (from.file.exists()) from else Page.Folder(from.file.parentFile ?: File("/"))
                        is Page.Folder -> from
                        else -> Page.Roots
                    })
                }
            }
        }
    }

    // ------------------------------------------------------------ the NAS

    private fun showNasSetup() {
        titleText.text = getString(R.string.nas_setup_title)
        val existing = runCatching { NasStore.load(this) }.getOrNull()
        val form = column()
        addNoticeAndPick(form)
        form.addView(button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        form.addView(text(getString(R.string.nas_setup_intro), UiScale.TEXT_NOTE),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        fun labelled(name: Int, hint: Int, value: String, password: Boolean = false): EditText {
            form.addView(label(getString(name)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
            val f = field(value).apply {
                this.hint = getString(hint)
                inputType = if (password) InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
                            else InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI
                if (password) typeface = thai
            }
            form.addView(f, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
            return f
        }
        val shownAddress = existing?.let { if (it.port == NasConfig.DEFAULT_PORT) it.host else "${it.host}:${it.port}" }.orEmpty()
        val address = labelled(R.string.nas_address, R.string.nas_address_hint, shownAddress)
        val share = labelled(R.string.nas_share, R.string.nas_share_hint, existing?.share.orEmpty())
        val user = labelled(R.string.nas_user, R.string.nas_user_hint,
            existing?.let { if (it.domain.isNotEmpty()) "${it.domain}\\${it.user}" else it.user }.orEmpty())
        // The saved password is never put back on screen, not even as dots.
        val password = labelled(R.string.nas_password,
            if (existing != null) R.string.nas_password_keep else R.string.nas_password, "", password = true)
        val error = errorLine()
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        // A message about what was wrong goes as soon as it is being put right.
        for (f in listOf(address, share, user, password)) f.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { error.visibility = View.GONE }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        form.addView(button(getString(R.string.nas_save_connect), big = true) {
            val typed = password.text.toString()
            when (val result = NasForm.parse(address.text.toString(), share.text.toString(), user.text.toString(),
                                             if (typed.isEmpty() && existing != null) existing.password else typed)) {
                is NasForm.Result.Bad -> {
                    error.text = getString(when (result.problem) {
                        NasForm.Problem.NO_ADDRESS -> R.string.nas_form_no_address
                        NasForm.Problem.BAD_ADDRESS -> R.string.nas_form_bad_address
                        NasForm.Problem.BAD_PORT -> R.string.nas_form_bad_port
                        NasForm.Problem.NO_SHARE -> R.string.nas_form_no_share
                    })
                    error.visibility = View.VISIBLE
                }
                is NasForm.Result.Ok -> {
                    NasStore.save(this, result.config)
                    Log.i(TAG, "nas settings saved")
                    hideKeyboard(password)
                    show(Page.NasFolder(""))
                }
            }
        }, LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_M) })
        if (existing != null) {
            if (!confirmingForget) {
                form.addView(button(getString(R.string.nas_forget)) { confirmingForget = true; show(Page.NasSetup) },
                             LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_S) })
            } else {
                form.addView(text(getString(R.string.nas_forget_confirm), UiScale.TEXT_BASE),
                             LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
                form.addView(pair(getString(R.string.files_delete_yes), {
                    afterPass = {
                        NasStore.delete(this)
                        Log.i(TAG, "nas settings deleted")
                        say(getString(R.string.nas_forgotten))
                        show(Page.Roots)
                    }
                    IdentityGate.ask(this)
                }, getString(R.string.cancel), { confirmingForget = false; show(Page.NasSetup) }),
                    LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
            }
        }
        form.addView(text(getString(R.string.nas_privacy), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        setPage(ScrollView(this).apply { addView(form) })
    }

    private fun nasTrail(path: String): String {
        val share = runCatching { NasStore.load(this) }.getOrNull()?.share.orEmpty()
        return (listOf("NAS", share) + path.split('\\').filter { it.isNotEmpty() }).joinToString(" › ")
    }

    /**
     * Worker thread only. A FRESH CONNECTION FOR EACH ACTION, closed after it:
     * on the A07 a connection kept open between pages stalled on its second
     * request for smbj's full 20-second timeout, while a new one answered in
     * a tenth of a second. A NAS that sleeps drops kept connections too.
     */
    private fun <T> withNas(block: (NasSession) -> T): T {
        val config = NasStore.load(this) ?: throw IllegalStateException("no nas settings")
        return NasSession.open(config).use(block)
    }

    private var nasSource: NasSource? = null

    /** The NAS, read only, in the same browser as the phone's folders (0.59.0). */
    private fun showNasFolder(path: String) {
        titleText.text = if (path.isEmpty()) getString(R.string.files_nas) else path.substringAfterLast('\\')
        val box = column(fill = true)
        addNoticeAndPick(box)
        browser.showFile = { true }
        browser.emptyWords = getString(R.string.files_empty)
        browser.setExtras(listOf(button(getString(R.string.nas_retry)) { browser.reload() },
                                 button(getString(R.string.nas_edit)) { show(Page.NasSetup) }))
        addBrowser(box)
        setPage(box)
        val source = nasSource ?: NasSource(this).also { nasSource = it }
        browser.open(source, path)
    }

    private class NoNetwork : Exception()

    private fun isOnline(): Boolean {
        val cm = getSystemService(android.net.ConnectivityManager::class.java) ?: return true
        return cm.activeNetwork != null
    }

    private fun showNasDetails(entry: NasEntry) {
        titleText.text = getString(R.string.files_details_title)
        val list = column()
        addNoticeAndPick(list)
        list.addView(button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        list.addView(heading(FolderBrowser.iconOf(FolderEntry(entry.name, entry.path, false)), entry.name))
        val kinds = resources.getStringArray(R.array.files_kinds)
        list.addView(fact(getString(R.string.files_type), kinds[FileOps.kindOfName(entry.name).ordinal]))
        list.addView(fact(getString(R.string.files_size), FileOps.formatSize(entry.size)))
        list.addView(fact(getString(R.string.files_modified), when_(entry.modifiedMs)))
        list.addView(fact(getString(R.string.files_location), nasTrail(entry.path.substringBeforeLast('\\', ""))))
        list.addView(button(getString(R.string.files_download), big = true) {
            pick = Pick(Pick.Kind.DOWNLOAD, null, entry)
            show(Page.Roots)
        }, LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_M) })
        list.addView(text(getString(R.string.nas_read_only), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        setPage(ScrollView(this).apply { addView(list) })
    }

    // ------------------------------------------------------------ words

    private fun words(e: Throwable): String = if (e is FileOps.Refused) getString(when (e.reason) {
        FileOps.Reason.INTO_ITSELF -> R.string.files_reason_into_itself
        FileOps.Reason.SAME_PLACE -> R.string.files_reason_same_place
        FileOps.Reason.NOT_ENOUGH_SPACE -> R.string.files_reason_space
        FileOps.Reason.NOT_WRITABLE -> R.string.files_reason_not_writable
        FileOps.Reason.GONE -> R.string.files_reason_gone
        FileOps.Reason.UNSAFE_ZIP -> R.string.files_reason_unsafe_zip
        FileOps.Reason.ZIP_PASSWORD -> R.string.files_reason_zip_password
        FileOps.Reason.ZIP_DAMAGED -> R.string.files_reason_zip_damaged
        FileOps.Reason.NAME_EMPTY -> R.string.files_reason_name_empty
        FileOps.Reason.NAME_SLASH -> R.string.files_reason_name_slash
        FileOps.Reason.NAME_DOTS -> R.string.files_reason_name_dots
        FileOps.Reason.NAME_TOO_LONG -> R.string.files_reason_name_long
        FileOps.Reason.NAME_TAKEN -> R.string.files_reason_name_taken
    }) else if (isNasError(e)) nasWords(NasProblem.of(e)) else getString(R.string.files_failed)

    private fun isNasError(e: Throwable): Boolean =
        e is com.hierynomus.smbj.common.SMBRuntimeException || e is java.net.SocketException ||
            e is java.net.UnknownHostException || e is java.net.SocketTimeoutException ||
            e.cause?.let(::isNasError) == true

    private fun nasWords(problem: NasProblem): String = getString(when (problem) {
        NasProblem.NO_NETWORK -> R.string.nas_problem_no_network
        NasProblem.LOCAL_NETWORK_DENIED -> R.string.nas_problem_local_network
        NasProblem.HOST_NOT_FOUND -> R.string.nas_problem_host
        NasProblem.NO_ANSWER -> R.string.nas_problem_no_answer
        NasProblem.LOGON_FAILURE -> R.string.nas_problem_logon
        NasProblem.ACCOUNT_BLOCKED -> R.string.nas_problem_account
        NasProblem.SHARE_NOT_FOUND -> R.string.nas_problem_share
        NasProblem.ACCESS_DENIED -> R.string.nas_problem_access
        NasProblem.PATH_NOT_FOUND -> R.string.nas_problem_path
        NasProblem.PROTOCOL -> R.string.nas_problem_protocol
        NasProblem.OTHER -> R.string.nas_problem_other
    })

    /** "Thu 24/09/2026 8:53 AM": the date and the 12-hour time used everywhere on screen. */
    private fun when_(ms: Long): String {
        if (ms <= 0) return "—"
        val c = Calendar.getInstance().apply { timeInMillis = ms }
        return ScreenDate.format(c) + " " + DashboardState.clock12(
            String.format(Locale.US, "%02d:%02d", c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE)))
    }

    private fun say(message: String, bad: Boolean = false) {
        notice = message
        noticeBad = bad
    }

    // ------------------------------------------------------------ the parts

    /** The notice from the last action, and the destination banner while choosing one. */
    private fun addNoticeAndPick(box: LinearLayout, here: (() -> File?)? = null) {
        notice?.let { message ->
            box.addView(text(message, UiScale.TEXT_BASE).apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
                if (noticeBad) setTextColor(color(R.color.retro_bad))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
            notice = null
        }
        val p = pick ?: return
        val banner = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.retro_title))
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        banner.addView(TextView(this).apply {
            text = getString(when (p.kind) {
                Pick.Kind.COPY -> R.string.files_pick_copy
                Pick.Kind.MOVE -> R.string.files_pick_move
                Pick.Kind.DOWNLOAD -> R.string.files_pick_download
            }, p.name)
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setTextColor(color(R.color.retro_title_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.MIDDLE
        })
        banner.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(getString(R.string.files_paste_here), enabled = here != null) { here?.invoke()?.let(::paste) },
                    LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
            addView(button(getString(R.string.cancel)) {
                pick = null
                show(page)
            }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        box.addView(banner, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
    }

    private class Row(val icon: Int, val name: String, val sub: () -> String, val open: () -> Unit,
                      val manage: (() -> Unit)?)

    /** A recycling list: a folder of thousands of photos draws only what is on screen. */
    private fun fileList(rows: List<Row>): ListView = ListView(this).apply {
        divider = null
        selector = ColorDrawable(0)
        isVerticalScrollBarEnabled = true
        adapter = object : BaseAdapter() {
            override fun getCount() = rows.size
            override fun getItem(position: Int) = rows[position]
            override fun getItemId(position: Int) = position.toLong()
            override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
                val row = rows[position]
                val view = (convert as? LinearLayout) ?: rowView()
                (view.getChildAt(0) as ImageView).setImageResource(row.icon)
                val texts = view.getChildAt(1) as LinearLayout
                (texts.getChildAt(0) as TextView).text = row.name
                (texts.getChildAt(1) as TextView).text = runCatching { row.sub() }.getOrDefault("")
                view.setOnClickListener { row.open() }
                val manage = view.getChildAt(2)
                manage.visibility = if (row.manage != null) View.VISIBLE else View.GONE
                manage.setOnClickListener { row.manage?.invoke() }
                return view
            }
        }
    }

    private fun rowView(): LinearLayout = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(UiScale.ROW)
        setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS))
        isClickable = true
        background = StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_pressed), ColorDrawable(color(R.color.retro_face)))
            addState(intArrayOf(), ColorDrawable(0))
        }
        addView(ImageView(context), LinearLayout.LayoutParams(dp(UiScale.ICON_L), dp(UiScale.ICON_L)))
        addView(LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_XS), 0)
            addView(text("", UiScale.TEXT_ITEM).apply {
                maxLines = 1
                // Long names keep their start and their extension.
                ellipsize = TextUtils.TruncateAt.MIDDLE
            })
            addView(text("", UiScale.TEXT_NOTE, dim = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(button(getString(R.string.files_manage)) {},
                LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
    }

    /** A storage root or the NAS on the first page: a big raised button with two lines. */
    private fun bigRow(icon: Int, name: String, sub: String, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_button)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            isClickable = true
            setOnClickListener { onClick() }
            addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(UiScale.ICON_L), dp(UiScale.ICON_L)))
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
                addView(text(name, UiScale.TEXT_HEADING).apply { typeface = Typeface.create(thai, Typeface.BOLD) })
                addView(text(sub, UiScale.TEXT_NOTE, dim = true))
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            minimumHeight = dp(UiScale.ROW)
        }

    private fun heading(icon: Int, name: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setPadding(0, dp(UiScale.SPACE_S), 0, 0)
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(UiScale.ICON_L), dp(UiScale.ICON_L)))
        addView(text(name, UiScale.TEXT_HEADING).apply {
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    /** "ขนาด | 1.2 MB": a label over its value, the value in full (it may wrap). */
    private fun fact(name: String, value: String): View = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(0, dp(UiScale.SPACE_S), 0, 0)
        addView(text(name, UiScale.TEXT_NOTE, dim = true))
        addView(text(value, UiScale.TEXT_ITEM))
    }

    /** Two buttons side by side, equal width, 48dp (56dp when [big]). */
    private fun pair(a: String, onA: () -> Unit, b: String, onB: () -> Unit, big: Boolean = false): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(button(a, big) { onA() }, LinearLayout.LayoutParams(0, dp(if (big) 56 else 48), 1f))
            addView(button(b, big) { onB() }, LinearLayout.LayoutParams(0, dp(if (big) 56 else 48), 1f)
                .apply { marginStart = dp(UiScale.SPACE_S) })
        }

    private fun field(value: String) = EditText(this).apply {
        setText(value)
        typeface = thai
        textSize = UiScale.TEXT_HEADING
        setSingleLine()
        imeOptions = EditorInfo.IME_ACTION_DONE
        setBackgroundResource(R.drawable.retro_field)
        setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        setTextColor(color(R.color.retro_text))
    }

    private fun errorLine() = text("", UiScale.TEXT_NOTE).apply {
        setTextColor(color(R.color.retro_bad))
        visibility = View.GONE
    }

    private fun column(fill: Boolean = false) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        if (!fill) setPadding(0, 0, 0, dp(UiScale.SPACE_S))
    }

    private fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    private fun label(value: String) = text(value, UiScale.TEXT_NOTE).apply {
        typeface = Typeface.create(thai, Typeface.BOLD)
    }

    /** A raised 1995 button that sinks while pressed. */
    private fun button(value: String, big: Boolean = false, enabled: Boolean = true,
                       onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = if (big) UiScale.TEXT_HEADING else UiScale.TEXT_BASE
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(if (enabled) R.color.retro_text else R.color.retro_dim))
        setBackgroundResource(R.drawable.retro_button)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
        minWidth = dp(UiScale.TOUCH)
        maxLines = 1
        isClickable = enabled
        isEnabled = enabled
        if (enabled) setOnClickListener { onClick() }
    }

    private fun disable(button: TextView) {
        button.isEnabled = false
        button.isClickable = false
        button.setOnClickListener(null)
        button.setTextColor(color(R.color.retro_dim))
    }

    private fun hideKeyboard(view: View) {
        getSystemService(android.view.inputmethod.InputMethodManager::class.java)
            ?.hideSoftInputFromWindow(view.windowToken, 0)
    }

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = TypedValue.applyDimension(
        TypedValue.COMPLEX_UNIT_DIP, value.toFloat(), resources.displayMetrics).toInt()

    private fun color(id: Int): Int = ContextCompat.getColor(this, id)

    companion object {
        /** `logcat -s KioskFiles:I`: kinds and outcomes, never names, paths, addresses or passwords. */
        const val TAG = "KioskFiles"
        /** 0.59.0: the one file to play or show on its own (a Track id: "local:/…" or "nas:…"; a path for a picture). */
        const val SINGLE = "com.mammonrn.phoneaikiosk.SINGLE"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
