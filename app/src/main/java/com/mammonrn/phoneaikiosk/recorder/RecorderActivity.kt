package com.mammonrn.phoneaikiosk.recorder

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Typeface
import android.media.MediaMetadataRetriever
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
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
import com.mammonrn.phoneaikiosk.media.MusicActivity
import com.mammonrn.phoneaikiosk.media.Track
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.ScreenDate
import com.mammonrn.phoneaikiosk.ui.UiScale
import com.mammonrn.phoneaikiosk.voice.DashboardState
import com.mammonrn.phoneaikiosk.voice.VoiceState
import java.io.File
import java.util.Calendar
import java.util.Locale
import java.util.concurrent.Executors

/**
 * The voice recorder (0.61.0, DESIGN.md 5ฐ): record, pause, carry on, stop;
 * the recordings newest first; play one in the music player on its own (the
 * file manager's single-file mode, 5ฌ), rename it, delete it after the owner's
 * face or pattern (5ฎ).
 *
 * The recording itself is [VoiceMemo] (the process's, not this screen's).
 * LEAVING THIS SCREEN WHILE RECORDING STOPS AND SAVES IT: X, the bottom button,
 * Back, and Hey Jarvis (KioskScreens finishes every screen, DESIGN 11) all end
 * in [onPause] with isFinishing. The display going dark does not finish the
 * screen, so a recording carries on with the screen off.
 *
 * ONE PAGE AT A TIME: the recorder and its list → a recording's details →
 * rename. Back one level; X and the bottom button go home.
 */
class RecorderActivity : Activity() {

    private lateinit var r: Retro
    private lateinit var thai: Typeface
    private lateinit var titleText: TextView
    private lateinit var content: FrameLayout

    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { t -> Thread(t, "kiosk-rec-list") }

    private sealed class Page {
        object Main : Page()
        data class Details(val file: File) : Page()
        data class Rename(val file: File) : Page()
    }

    private var page: Page = Page.Main
    private var generation = 0

    /** One line at the top of the next page: what just happened. */
    private var notice: String? = null
    private var noticeBad = false

    private var confirmingDelete = false
    /** What is deleted once the owner's face or pattern passes (auth/IdentityGate). */
    private var afterPass: (() -> Unit)? = null

    /** The recordings, newest first, as last read; and their lengths once known. */
    private var items: List<RecorderNames.Item> = emptyList()
    private val lengths = HashMap<String, Long>()

    // The live parts of the main page, refreshed five times a second.
    private var stateText: TextView? = null
    private var elapsedText: TextView? = null
    private var meter: LevelMeter? = null
    private var silentText: TextView? = null
    private var drawnVersion = -1

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        r = Retro(this, thai)
        VoiceMemo.remember(this)
        VoiceMemo.prepare(this)
        setContentView(buildWindow())
        hideSystemBars()
        show(Page.Main)
    }

    override fun onStart() {
        super.onStart()
        handler.post(tick)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        // Nothing is re-read here: a delete redraws itself (onActivityResult runs
        // first), and redrawing again would drop the line saying what happened.
    }

    override fun onPause() {
        // Leaving for good — X, Back, Hey Jarvis: stop and SAVE, at once, so the
        // wake word is back before the next screen is even drawn.
        if (isFinishing) VoiceMemo.stop("screen-closed")
        super.onPause()
    }

    override fun onStop() {
        handler.removeCallbacks(tick)
        super.onStop()
    }

    override fun onDestroy() {
        // Belt and braces: a screen destroyed without finishing first (the
        // system) keeps recording only if it is not being rebuilt as a new one.
        if (isFinishing) VoiceMemo.stop("screen-closed")
        handler.removeCallbacksAndMessages(null)
        worker.shutdown()
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
            Log.i(VoiceMemo.TAG, "delete: identity passed")
            then?.invoke()
        } else {
            Log.i(VoiceMemo.TAG, "delete: identity not passed")
            say(IdentityGate.refusal(this, data), bad = true)
            confirmingDelete = false
            show(page)
        }
    }

    private fun goBack() {
        when (val p = page) {
            is Page.Main -> finish()
            is Page.Details -> show(Page.Main)
            is Page.Rename -> show(Page.Details(p.file))
        }
    }

    /** To the kiosk screen, closing the Control Panel under this too (and stopping a recording). */
    /** The close button: back where this screen was opened from (ui/Origin, Poom 2026-09-25). */
    private fun closeApp() {
        VoiceMemo.stop("screen-closed")
        com.mammonrn.phoneaikiosk.ui.Origin.close(this, "recorder-close")
    }

    private fun goHome() {
        VoiceMemo.stop("screen-closed")
        KioskScreens.leaveAllButHome("recorder-home")
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
            setBackgroundColor(r.color(R.color.retro_desktop))
            setPadding(r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME), r.dp(UiScale.FRAME))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET), r.dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_mic) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_S), r.dp(UiScale.ICON_S)))
        titleText = TextView(this).apply {
            setTextColor(r.color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        bar.addView(titleText, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = com.mammonrn.phoneaikiosk.ui.Origin.closeWords(this@RecorderActivity)
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
        if (next !is Page.Details) confirmingDelete = false
        page = next
        generation += 1
        stateText = null; elapsedText = null; meter = null; silentText = null
        when (next) {
            is Page.Main -> showMain()
            is Page.Details -> showDetails(next.file)
            is Page.Rename -> showRename(next.file)
        }
    }

    // ------------------------------------------------------------ the recorder

    private fun showMain() {
        titleText.text = getString(R.string.window_recorder)
        drawnVersion = VoiceMemo.version
        val list = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
        addNotice(list)

        // What is happening, how long, how loud.
        val panel = r.column().apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        }
        stateText = r.bold("", UiScale.TEXT_HEADING).apply { gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(stateText, LinearLayout.LayoutParams(MATCH, WRAP))
        elapsedText = r.bold("", UiScale.TEXT_DISPLAY).apply { gravity = Gravity.CENTER_HORIZONTAL }
        panel.addView(elapsedText, LinearLayout.LayoutParams(MATCH, WRAP))
        panel.addView(r.text(getString(R.string.rec_level), UiScale.TEXT_NOTE, dim = true),
                      LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        meter = LevelMeter(this).apply {
            setBackgroundResource(R.drawable.retro_field)
            contentDescription = getString(R.string.rec_level_desc)
        }
        panel.addView(meter, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PROGRESS)))
        silentText = r.text(getString(R.string.rec_mic_silent), UiScale.TEXT_NOTE).apply {
            setTextColor(r.color(R.color.retro_bad))
            visibility = View.GONE
        }
        panel.addView(silentText, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        list.addView(panel, LinearLayout.LayoutParams(MATCH, WRAP))

        // The big buttons: what can be done now.
        val state = VoiceMemo.flow.state
        val blocked = whyNotRecord()
        val buttons = r.row()
        when (state) {
            RecorderFlow.State.IDLE -> buttons.addView(
                iconButton(R.drawable.ic_pixel_record, getString(R.string.rec_start), enabled = blocked == null && !VoiceMemo.saving) {
                    startRecording()
                }, LinearLayout.LayoutParams(0, r.dp(UiScale.ICON_BUTTON), 1f))
            RecorderFlow.State.RECORDING -> {
                buttons.addView(iconButton(R.drawable.ic_pixel_pause, getString(R.string.rec_pause)) {
                    VoiceMemo.pause(); show(Page.Main)
                }, LinearLayout.LayoutParams(0, r.dp(UiScale.ICON_BUTTON), 1f))
                buttons.addView(iconButton(R.drawable.ic_pixel_stop, getString(R.string.rec_stop)) {
                    VoiceMemo.stop("button"); show(Page.Main)
                }, LinearLayout.LayoutParams(0, r.dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = r.dp(UiScale.SPACE_S) })
            }
            RecorderFlow.State.PAUSED -> {
                buttons.addView(iconButton(R.drawable.ic_pixel_record, getString(R.string.rec_resume)) {
                    VoiceMemo.resume(); show(Page.Main)
                }, LinearLayout.LayoutParams(0, r.dp(UiScale.ICON_BUTTON), 1f))
                buttons.addView(iconButton(R.drawable.ic_pixel_stop, getString(R.string.rec_stop)) {
                    VoiceMemo.stop("button"); show(Page.Main)
                }, LinearLayout.LayoutParams(0, r.dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = r.dp(UiScale.SPACE_S) })
            }
        }
        list.addView(buttons, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        if (state == RecorderFlow.State.IDLE && blocked != null) {
            list.addView(r.text(blocked, UiScale.TEXT_NOTE).apply { setTextColor(r.color(R.color.retro_bad)) },
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }

        // Why Jarvis is not listening, and that nothing is lost by leaving.
        val notes = if (state == RecorderFlow.State.IDLE) listOf(getString(R.string.rec_note_where, where()))
            else listOf(getString(R.string.rec_note_resting), getString(R.string.rec_note_closing))
        for (line in notes) list.addView(r.text(line, UiScale.TEXT_NOTE, dim = true),
                                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })

        // The recordings.
        val rows = r.column()
        list.addView(rows, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_L) })
        drawList(rows)
        setPage(ScrollView(this).apply { addView(list) })
        refreshLive()
        loadList(rows)
    }

    /** Why recording cannot start, in words, or null when it can. */
    private fun whyNotRecord(): String? = when {
        !Environment.isExternalStorageManager() -> getString(R.string.rec_no_permission)
        VoiceState.mic != "open" -> getString(R.string.rec_no_mic)
        else -> null
    }

    private fun startRecording() {
        val c = Calendar.getInstance()
        val base = RecorderNames.defaultBase(c.get(Calendar.YEAR), c.get(Calendar.MONTH) + 1, c.get(Calendar.DAY_OF_MONTH),
                                             c.get(Calendar.HOUR_OF_DAY), c.get(Calendar.MINUTE))
        VoiceMemo.start(this, base)
        show(Page.Main)
    }

    private val tick = object : Runnable {
        override fun run() {
            if (page is Page.Main && VoiceMemo.version != drawnVersion) show(Page.Main) else refreshLive()
            handler.postDelayed(this, TICK_MS)
        }
    }

    /** The state word, the time, the meter: five times a second, without rebuilding the page. */
    private fun refreshLive() {
        val state = VoiceMemo.flow.state
        stateText?.text = getString(when {
            state == RecorderFlow.State.RECORDING -> R.string.rec_state_recording
            state == RecorderFlow.State.PAUSED -> R.string.rec_state_paused
            VoiceMemo.saving -> R.string.rec_state_saving
            else -> R.string.rec_state_idle
        })
        val shown = if (state == RecorderFlow.State.IDLE) 0L else VoiceMemo.elapsedMs()
        val time = RecorderNames.length(shown)
        elapsedText?.let {
            if (it.text.toString() != time) it.text = time
            it.contentDescription = getString(R.string.rec_elapsed_desc, time)
        }
        meter?.fraction = if (state == RecorderFlow.State.RECORDING) RecorderNames.level(VoiceMemo.peak) else 0f
        val silent = state == RecorderFlow.State.RECORDING &&
            SystemClock.elapsedRealtime() - maxOf(VoiceMemo.lastFrameAt, VoiceMemo.startedAt) > SILENT_MS
        silentText?.visibility = if (silent) View.VISIBLE else View.GONE
    }

    private fun drawList(rows: LinearLayout) {
        rows.removeAllViews()
        rows.addView(r.label(getString(R.string.rec_list_title, items.size)))
        val busy = VoiceMemo.flow.state != RecorderFlow.State.IDLE
        if (items.isEmpty()) {
            rows.addView(r.text(getString(R.string.rec_list_empty), UiScale.TEXT_BASE, dim = true),
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
            return
        }
        rows.addView(r.text(getString(if (busy) R.string.rec_list_busy else R.string.rec_open_hint), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        for (item in items) rows.addView(row(item, enabled = !busy),
                                         LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
    }

    /** One recording: its name, then "date time · length · size". */
    private fun row(item: RecorderNames.Item, enabled: Boolean): View = r.row().apply {
        minimumHeight = r.dp(UiScale.ROW)
        setPadding(r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS), r.dp(UiScale.SPACE_XS))
        if (enabled) {
            setBackgroundResource(R.drawable.retro_button)
            isClickable = true
            setOnClickListener { show(Page.Details(File(VoiceMemo.folder(), item.name))) }
        }
        alpha = if (enabled) 1f else DIM
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_type_audio) },
                LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
        addView(r.column().apply {
            setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            addView(r.text(RecorderNames.base(item.name), UiScale.TEXT_ITEM).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
            addView(r.text(sub(item), UiScale.TEXT_NOTE, dim = true).apply {
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
            })
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
    }

    private fun sub(item: RecorderNames.Item): String {
        val parts = arrayListOf(when_(item.modifiedMs))
        lengths[key(item)]?.takeIf { it > 0 }?.let { parts += RecorderNames.length(it) }
        parts += FileOps.formatSize(item.bytes)
        return parts.joinToString(" · ")
    }

    /** Reads the folder on the worker, then each length; draws again when they arrive. */
    private fun loadList(rows: LinearLayout) {
        val asked = generation
        val known = HashSet(lengths.keys)  // the map itself belongs to the main thread
        worker.execute {
            val files = VoiceMemo.folder().listFiles()?.filter { it.isFile && RecorderNames.isRecording(it.name) }.orEmpty()
            val read = RecorderNames.newestFirst(files.map { RecorderNames.Item(it.name, it.lastModified(), it.length()) })
            // The one being written is not a recording yet.
            val writing = VoiceMemo.writing
            val listed = read.filter { it.name != writing }
            runOnUiThread { if (asked == generation) { items = listed; drawList(rows) } }
            val found = HashMap<String, Long>()
            for (item in listed) {
                if (key(item) in known) continue
                found[key(item)] = lengthOf(File(VoiceMemo.folder(), item.name))
            }
            if (found.isNotEmpty()) runOnUiThread {
                lengths.putAll(found)
                if (asked == generation) drawList(rows)
            }
            Log.i(VoiceMemo.TAG, "list recordings=${listed.size}")
        }
    }

    private fun key(item: RecorderNames.Item) = "${item.name}|${item.modifiedMs}|${item.bytes}"

    private fun lengthOf(file: File): Long {
        val retriever = MediaMetadataRetriever()
        return try {
            retriever.setDataSource(file.path)
            retriever.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0L
        } catch (e: Exception) {
            0L
        } finally {
            runCatching { retriever.release() }
        }
    }

    // ------------------------------------------------------------ one recording

    private fun showDetails(file: File) {
        if (!file.exists()) {
            say(getString(R.string.rec_gone), bad = true)
            return show(Page.Main)
        }
        titleText.text = getString(R.string.rec_details_title)
        val name = RecorderNames.base(file.name)
        val list = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
        addNotice(list)
        list.addView(r.button(getString(R.string.files_go_back)) { goBack() }, LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
        list.addView(r.row().apply {
            setPadding(0, r.dp(UiScale.SPACE_S), 0, 0)
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_type_audio) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
            addView(r.bold(name, UiScale.TEXT_HEADING).apply { setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0) },
                    LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        list.addView(fact(getString(R.string.rec_fact_date), when_(file.lastModified())))
        val length = lengths.entries.firstOrNull { it.key.startsWith(file.name + "|") }?.value ?: 0L
        if (length > 0) list.addView(fact(getString(R.string.rec_fact_length), RecorderNames.length(length)))
        list.addView(fact(getString(R.string.rec_fact_size), FileOps.formatSize(file.length())))
        list.addView(fact(getString(R.string.rec_fact_where), where()))

        // Play: the music player, this one file on its own — never added to a playlist (5ฌ).
        list.addView(r.button(getString(R.string.rec_play), big = true) {
            Log.i(VoiceMemo.TAG, "play one recording")
            startActivity(Intent(this, MusicActivity::class.java).putExtra(FilesActivity.SINGLE, Track.LOCAL + file.path))
        }, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_L) })

        val deleteArea = r.column()
        list.addView(deleteArea, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        fun drawDelete() {
            deleteArea.removeAllViews()
            if (!confirmingDelete) {
                deleteArea.addView(r.pair(getString(R.string.rec_rename), { show(Page.Rename(file)) },
                                          getString(R.string.rec_delete), { confirmingDelete = true; drawDelete() }))
                return
            }
            // DESIGN 5ฎ: "are you sure" here first, then the face or the pattern; only a pass deletes.
            val box = r.column().apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            }
            box.addView(r.text(getString(R.string.rec_delete_confirm, name), UiScale.TEXT_BASE))
            box.addView(r.pair(getString(R.string.rec_delete_yes), { afterPass = { runDelete(file) }; IdentityGate.ask(this) },
                               getString(R.string.cancel), { confirmingDelete = false; drawDelete() }),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            deleteArea.addView(box)
        }
        drawDelete()
        setPage(ScrollView(this).apply { addView(list) })
    }

    /** Runs only after IdentityGate passed (onActivityResult). */
    private fun runDelete(file: File) {
        val name = RecorderNames.base(file.name)
        confirmingDelete = false
        if (!file.exists()) {
            say(getString(R.string.rec_gone), bad = true)
        } else if (file.delete()) {
            Log.i(VoiceMemo.TAG, "delete ok")
            VoiceMemo.scan(file)
            say(getString(R.string.rec_deleted, name))
        } else {
            Log.i(VoiceMemo.TAG, "delete failed")
            say(getString(R.string.rec_delete_failed), bad = true)
            return show(Page.Details(file))
        }
        show(Page.Main)
    }

    private fun showRename(file: File) {
        titleText.text = getString(R.string.rec_rename)
        val form = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
        form.addView(r.label(getString(R.string.rec_new_name)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        val field = r.field(RecorderNames.base(file.name))
        field.setSelection(field.text.length)
        form.addView(field, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        val error = r.errorLine()
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        field.addTextChangedListener(object : android.text.TextWatcher {
            override fun afterTextChanged(s: android.text.Editable?) { error.visibility = View.GONE }
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) = Unit
        })
        form.addView(r.pair(getString(R.string.save), {
            val typed = field.text.toString().trim()
            val others = file.parentFile?.list()?.toList().orEmpty()
            val problem = RecorderNames.problem(typed, others, self = file.name)
            if (problem != null) {
                // The text stays as typed; the reason is under it.
                error.text = getString(when (problem) {
                    RecorderNames.Problem.EMPTY -> R.string.rec_name_empty
                    RecorderNames.Problem.BAD_CHARACTER -> R.string.rec_name_bad_char
                    RecorderNames.Problem.DOTS -> R.string.rec_name_dots
                    RecorderNames.Problem.TOO_LONG -> R.string.rec_name_too_long
                    RecorderNames.Problem.TAKEN -> R.string.rec_name_taken
                })
                error.visibility = View.VISIBLE
                return@pair
            }
            val ext = file.name.substringAfterLast('.', "")
            val target = File(file.parentFile, if (ext.isEmpty()) typed else "$typed.$ext")
            if (target.path == file.path || file.renameTo(target)) {
                Log.i(VoiceMemo.TAG, "rename ok")
                VoiceMemo.scan(file)
                VoiceMemo.scan(target)
                r.hideKeyboard(field)
                say(getString(R.string.rec_renamed, typed))
                show(Page.Details(target))
            } else {
                Log.i(VoiceMemo.TAG, "rename failed")
                error.text = getString(R.string.rec_rename_failed)
                error.visibility = View.VISIBLE
            }
        }, getString(R.string.cancel), { r.hideKeyboard(field); show(Page.Details(file)) }, big = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        setPage(ScrollView(this).apply { addView(form) })
        field.requestFocus()
    }

    // ------------------------------------------------------------ the parts

    /** A raised button with its icon over its word (the music player's ◄◄ ► ■ size). */
    private fun iconButton(icon: Int, word: String, enabled: Boolean = true, onClick: () -> Unit): View =
        LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = word
            isClickable = enabled
            isEnabled = enabled
            alpha = if (enabled) 1f else DIM
            if (enabled) setOnClickListener { onClick() }
            addView(ImageView(context).apply { setImageResource(icon) },
                    LinearLayout.LayoutParams(r.dp(UiScale.ICON_M), r.dp(UiScale.ICON_M)))
            addView(r.bold(word, UiScale.TEXT_BASE).apply {
                gravity = Gravity.CENTER
                maxLines = 1
                ellipsize = TextUtils.TruncateAt.END
                if (!enabled) setTextColor(r.color(R.color.retro_dim))
            })
        }

    /** "ขนาด | 240 KB": a label over its value. */
    private fun fact(name: String, value: String): View = r.column().apply {
        setPadding(0, r.dp(UiScale.SPACE_S), 0, 0)
        addView(r.text(name, UiScale.TEXT_NOTE, dim = true))
        addView(r.text(value, UiScale.TEXT_ITEM))
    }

    /** The notice from the last action, once, at the top of the page. */
    private fun addNotice(box: LinearLayout) {
        val problem = if (page !is Page.Main) null else when (VoiceMemo.problem) {
            VoiceMemo.Problem.NONE -> null
            VoiceMemo.Problem.NO_FOLDER -> getString(R.string.rec_problem_no_folder)
            VoiceMemo.Problem.WRITE_FAILED -> getString(R.string.rec_problem_write)
            VoiceMemo.Problem.NO_SPACE -> getString(R.string.rec_problem_space)
        }
        // "Saved" while that file is still there and nothing newer was said.
        val savedLine = VoiceMemo.saved
            ?.takeIf { (name, _) -> notice == null && page is Page.Main &&
                VoiceMemo.flow.state == RecorderFlow.State.IDLE && File(VoiceMemo.folder(), name).exists() }
            ?.let { (name, ms) -> getString(R.string.rec_saved, RecorderNames.base(name), RecorderNames.length(ms)) }
        val lines = listOfNotNull(notice?.let { it to noticeBad }, problem?.let { it to true }, savedLine?.let { it to false })
        notice = null
        for ((line, bad) in lines) {
            box.addView(r.text(line, UiScale.TEXT_BASE).apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
                if (bad) setTextColor(r.color(R.color.retro_bad))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = r.dp(UiScale.SPACE_S) })
        }
    }

    private fun say(message: String, bad: Boolean = false) {
        notice = message
        noticeBad = bad
    }

    /** "เครื่อง › Recordings › Kiosk": where the file manager finds them. */
    private fun where(): String =
        (listOf(getString(R.string.folder_crumb_phone)) + RecorderNames.FOLDER.split('/')).joinToString(" › ")

    /** "Thu 24/09/2026 8:53 AM": the date and the 12-hour time used everywhere on screen. */
    private fun when_(ms: Long): String {
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

    /** The level: a navy bar across a white well, as long as the sound is loud (RecorderNames.level). */
    private class LevelMeter(context: Context) : View(context) {
        private val paint = Paint().apply { color = context.getColor(R.color.retro_title) }
        var fraction = 0f
            set(value) {
                if (value != field) { field = value; invalidate() }
            }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)
            val inset = resources.displayMetrics.density * UiScale.BEVEL
            val w = (width - 2 * inset) * fraction
            if (w > 0) canvas.drawRect(inset, inset, inset + w, height - inset, paint)
        }
    }

    companion object {
        private const val TICK_MS = 200L
        /** No frame for this long while recording: the microphone is not reaching the recorder. */
        private const val SILENT_MS = 2_000L
        private const val DIM = 0.5f
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
