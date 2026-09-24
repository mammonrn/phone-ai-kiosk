package com.mammonrn.phoneaikiosk.media

import android.app.Activity
import android.content.Intent
import android.graphics.Typeface
import android.graphics.drawable.ClipDrawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.Editable
import android.text.TextUtils
import android.text.TextWatcher
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
import android.widget.SeekBar
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.files.NasEntry
import com.mammonrn.phoneaikiosk.files.NasSession
import com.mammonrn.phoneaikiosk.files.NasStore
import java.util.concurrent.Executors
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The music player's screen (0.53.0, Poom: "หน้าตาคล้าย Winamp"), from the
 * Control Panel. The music itself is in [MusicService]: closing this screen —
 * Back, the X, or Hey Jarvis (KioskScreens) — never stops it.
 *
 * "กำลังเล่น": a green-on-black read-out (time, title, artist), the buttons
 * with their words under the pictures, shuffle and repeat in words, the
 * player's volume, and the list. "คลังเพลง": the phone's songs with a search
 * box, and the NAS read only — play a folder, or add it to the library that
 * voice commands search. Every list is a recycling ListView.
 */
class MusicActivity : Activity() {

    private lateinit var thai: Typeface
    private lateinit var pixel: Typeface
    private lateinit var content: FrameLayout
    private val tabs = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-music") }

    private enum class Tab { NOW, LIBRARY }
    private var tab = Tab.NOW
    private var onNas = false
    private var nasPath = ""
    private var generation = 0

    // The "กำลังเล่น" views, while that tab is shown.
    private var timeView: TextView? = null
    private var stateView: TextView? = null
    private var titleView: TextView? = null
    private var artistView: TextView? = null
    private var seek: SeekBar? = null
    private var playLabel: TextView? = null
    private var playIcon: ImageView? = null
    private var shuffleView: TextView? = null
    private var repeatView: TextView? = null
    private var volumeView: TextView? = null
    private var errorView: TextView? = null
    private var queueTitle: TextView? = null
    private var queueAdapter: TrackAdapter? = null
    private var seeking = false
    /** The list the "กำลังเล่น" page was drawn for: a new one draws the page again. */
    private var drawnFor: List<Track>? = null

    private val listener: () -> Unit = { if (tab == Tab.NOW) refreshNow() }

    private val tick = object : Runnable {
        override fun run() {
            if (tab == Tab.NOW) refreshTime()
            handler.postDelayed(this, 500)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        setContentView(buildWindow())
        hideSystemBars()
        show(if (MusicPlayer.queue.isEmpty) Tab.LIBRARY else Tab.NOW)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        MusicPlayer.listeners.add(listener)
        handler.post(tick)
        if (tab == Tab.NOW) refreshNow()
    }

    override fun onPause() {
        super.onPause()
        MusicPlayer.listeners.remove(listener)
        handler.removeCallbacks(tick)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** Back: up one NAS folder, then out to the Control Panel. The music plays on. */
    private fun goBack() {
        if (tab == Tab.LIBRARY && onNas && nasPath.isNotEmpty()) {
            nasPath = nasPath.substringBeforeLast('\\', "")
            showLibrary()
            return
        }
        finish()
    }

    private fun goHome() {
        KioskScreens.leaveAllButHome("music-home")
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
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_music_light) },
                    LinearLayout.LayoutParams(dp(UiScale.ICON_S), dp(UiScale.ICON_S)))
        bar.addView(TextView(this).apply {
            text = getString(R.string.window_music)
            setTextColor(color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = getString(R.string.settings_home)
            isClickable = true
            setOnClickListener { goHome() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        val tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for ((i, t) in Tab.entries.withIndex()) {
            val view = TextView(this).apply {
                text = getString(if (t == Tab.NOW) R.string.music_tab_now else R.string.music_tab_library)
                textSize = UiScale.TEXT_BASE
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { show(t) }
            }
            tabs.add(view)
            tabRow.addView(view, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { if (i > 0) marginStart = dp(UiScale.SPACE_S) })
        }
        window.addView(tabRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.WINDOW_INSET) })
        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_S) })
        root.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        return root
    }

    private fun show(next: Tab) {
        tab = next
        generation += 1
        for ((i, view) in tabs.withIndex()) styleChoice(view, Tab.entries[i] == next)
        if (next == Tab.NOW) showNow() else showLibrary()
    }

    private fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------ "กำลังเล่น"

    private fun showNow() {
        drawnFor = MusicPlayer.queue.tracks
        val page = column()
        // The read-out: green on black, like the player it is modelled on.
        val lcd = column().apply {
            setBackgroundColor(color(R.color.retro_dark))
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.BOTTOM }
        timeView = TextView(this).apply { typeface = pixel; textSize = UiScale.TEXT_VALUE; setTextColor(color(R.color.retro_lcd)) }
        top.addView(timeView, LinearLayout.LayoutParams(0, WRAP, 1f))
        stateView = TextView(this).apply { typeface = thai; textSize = UiScale.TEXT_NOTE; setTextColor(color(R.color.retro_lcd)) }
        top.addView(stateView)
        lcd.addView(top)
        titleView = TextView(this).apply {
            typeface = Typeface.create(thai, Typeface.BOLD); textSize = UiScale.TEXT_HEADING; setTextColor(color(R.color.retro_lcd))
            maxLines = 1; ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true
            setPadding(0, dp(UiScale.SPACE_S), 0, 0)
        }
        lcd.addView(titleView, LinearLayout.LayoutParams(MATCH, WRAP))
        artistView = TextView(this).apply {
            typeface = thai; textSize = UiScale.TEXT_NOTE; setTextColor(color(R.color.retro_lcd_dim)); maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lcd.addView(artistView, LinearLayout.LayoutParams(MATCH, WRAP))
        page.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS))
            addView(lcd)
        }, LinearLayout.LayoutParams(MATCH, WRAP))

        seek = retroSeek().apply {
            contentDescription = "ตำแหน่งในเพลง"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) timeView?.text = clock(value.toLong())
                }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false
                    MusicPlayer.seekTo(this@MusicActivity, bar.progress.toLong())
                }
            })
        }
        page.addView(seek, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })

        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(control(R.drawable.ic_pixel_prev, R.string.music_prev) { MusicPlayer.previous(this) }, weight(0))
        val play = control(R.drawable.ic_pixel_play, R.string.music_play) { MusicPlayer.toggle(this) }
        playIcon = play.getChildAt(0) as ImageView
        playLabel = play.getChildAt(1) as TextView
        controls.addView(play, weight(1))
        controls.addView(control(R.drawable.ic_pixel_stop, R.string.music_stop) { MusicPlayer.stop(this) }, weight(1))
        controls.addView(control(R.drawable.ic_pixel_next, R.string.music_next) { MusicPlayer.next(this) }, weight(1))
        page.addView(controls, LinearLayout.LayoutParams(MATCH, dp(UiScale.ICON_BUTTON)).apply { topMargin = dp(UiScale.SPACE_XS) })

        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shuffleView = choice("") { MusicPlayer.setShuffle(!MusicPlayer.queue.shuffle) }
        repeatView = choice("") { MusicPlayer.cycleRepeat() }
        modes.addView(shuffleView, weight(0, dp(UiScale.TOUCH)))
        modes.addView(repeatView, weight(1, dp(UiScale.TOUCH)))
        page.addView(modes, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_S) })

        val volumeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        volumeView = text("", UiScale.TEXT_NOTE)
        volumeRow.addView(volumeView, LinearLayout.LayoutParams(dp(UiScale.VOLUME_LABEL), WRAP))
        volumeRow.addView(retroSeek().apply {
            max = 100
            progress = (MusicPlayer.volume * 100).toInt()
            contentDescription = "ระดับเสียงเพลง"
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) MusicPlayer.setVolume(this@MusicActivity, value / 100f)
                }
                override fun onStartTrackingTouch(bar: SeekBar) = Unit
                override fun onStopTrackingTouch(bar: SeekBar) = Unit
            })
        }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
        page.addView(volumeRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })

        errorView = text("", UiScale.TEXT_NOTE).apply { setTextColor(color(R.color.retro_bad)); visibility = View.GONE }
        page.addView(errorView)

        queueTitle = label("")
        page.addView(queueTitle, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        if (MusicPlayer.queue.isEmpty) {
            page.addView(text(getString(R.string.music_queue_empty), UiScale.TEXT_BASE).apply { setPadding(0, dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_S)) })
            page.addView(button(getString(R.string.music_go_library)) { show(Tab.LIBRARY) },
                         LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)))
        } else {
            val adapter = TrackAdapter(MusicPlayer.queue.tracks) { MusicPlayer.queue.currentIndex }
            queueAdapter = adapter
            page.addView(list(adapter) { index -> MusicPlayer.jumpTo(this, index) },
                         LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        }
        setPage(page)
        refreshNow()
    }

    private fun refreshNow() {
        val q = MusicPlayer.queue
        // A song chosen in the library sets the list a moment after this page
        // was drawn (the command runs on the main thread's next turn): seen on
        // the A07 as "ยังไม่มีเพลงในรายการ" over a playing song.
        if (drawnFor !== q.tracks) { showNow(); return }
        val track = q.current
        titleView?.text = track?.title ?: "—"
        artistView?.text = listOf(track?.artist.orEmpty(), track?.album.orEmpty()).filter { it.isNotEmpty() }
            .joinToString(" · ").ifEmpty { if (track?.onNas == true) "NAS" else "" }
        val playing = MusicPlayer.state == MusicPlayer.State.PLAYING
        stateView?.text = getString(when (MusicPlayer.state) {
            MusicPlayer.State.PLAYING -> R.string.music_playing
            MusicPlayer.State.PAUSED -> R.string.music_paused
            MusicPlayer.State.STOPPED -> R.string.music_stopped
        })
        playIcon?.setImageResource(if (playing) R.drawable.ic_pixel_pause else R.drawable.ic_pixel_play)
        playLabel?.text = getString(if (playing) R.string.music_pause else R.string.music_play)
        shuffleView?.let { styleChoice(it, q.shuffle, getString(if (q.shuffle) R.string.music_shuffle_on else R.string.music_shuffle_off)) }
        repeatView?.let {
            styleChoice(it, q.repeat != PlayQueue.Repeat.OFF, getString(when (q.repeat) {
                PlayQueue.Repeat.OFF -> R.string.music_repeat_off
                PlayQueue.Repeat.ALL -> R.string.music_repeat_all
                PlayQueue.Repeat.ONE -> R.string.music_repeat_one
            }))
        }
        volumeView?.text = getString(R.string.music_volume, (MusicPlayer.volume * 100).toInt())
        errorView?.apply {
            text = MusicPlayer.error.orEmpty()
            visibility = if (MusicPlayer.error == null) View.GONE else View.VISIBLE
        }
        queueTitle?.text = getString(R.string.music_queue, q.tracks.size)
        queueAdapter?.notifyDataSetChanged()
        refreshTime()
    }

    private fun refreshTime() {
        val duration = MusicPlayer.durationMs
        seek?.apply {
            max = duration.toInt().coerceAtLeast(1)
            if (!seeking) progress = MusicPlayer.positionMs.toInt()
            isEnabled = MusicPlayer.hasMedia
        }
        if (!seeking) timeView?.text = clock(MusicPlayer.positionMs) + if (duration > 0) " /${clock(duration)}" else ""
    }

    // ------------------------------------------------------------ "คลังเพลง"

    private fun showLibrary() {
        val page = column()
        // 0.54.1 (Poom): where the songs come from is an option row, sized to
        // its words and flat — not a second row of navy tabs under the tabs.
        val sources = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }
        sources.addView(label(getString(R.string.music_source)))
        sources.addView(option(getString(R.string.music_local), !onNas) { onNas = false; showLibrary() },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        sources.addView(option(getString(R.string.music_nas), onNas) { onNas = true; showLibrary() },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        page.addView(sources, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)))
        if (onNas) nasPage(page) else localPage(page)
        setPage(page)
    }

    private fun localPage(page: LinearLayout) {
        val search = EditText(this).apply {
            typeface = thai; textSize = UiScale.TEXT_HEADING; setSingleLine(); hint = getString(R.string.music_search_hint)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            setBackgroundResource(R.drawable.retro_field); setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
            setTextColor(color(R.color.retro_text)); setHintTextColor(color(R.color.retro_dim))
        }
        page.addView(search, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_S) })
        val status = text(getString(R.string.music_loading), UiScale.TEXT_BASE, dim = true)
        page.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        val all = ArrayList<Track>()
        var shown: List<Track> = emptyList()
        val adapter = TrackAdapter(emptyList()) { -1 }
        val playAll = button(getString(R.string.music_play_all)) {
            if (shown.isNotEmpty()) { MusicPlayer.play(this, shown); show(Tab.NOW) }
        }
        page.addView(playAll, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
        page.addView(list(adapter) { index -> MusicPlayer.play(this, shown, index); show(Tab.NOW) },
                     LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        fun filter() {
            val q = MusicLibrary.key(search.text.toString())
            shown = if (q.isEmpty()) all else all.filter {
                MusicLibrary.key(it.title + it.artist + it.album).contains(q)
            }
            adapter.set(shown)
            status.text = when {
                all.isEmpty() -> getString(R.string.music_local_empty)
                shown.isEmpty() -> getString(R.string.music_search_none)
                else -> "${shown.size} เพลง"
            }
            playAll.visibility = if (shown.isEmpty()) View.GONE else View.VISIBLE
        }
        search.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
            override fun afterTextChanged(s: Editable?) = filter()
        })
        val mine = generation
        worker.execute {
            val tracks = MusicShelf.local(this)
            handler.post {
                if (mine != generation) return@post
                all.addAll(tracks)
                filter()
            }
        }
    }

    private fun nasPage(page: LinearLayout) {
        if (NasStore.load(this) == null) {
            page.addView(text(getString(R.string.music_nas_not_set), UiScale.TEXT_ITEM).apply { setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), 0) })
            return
        }
        val pathRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        pathRow.addView(button("◄") { if (nasPath.isNotEmpty()) { nasPath = nasPath.substringBeforeLast('\\', ""); showLibrary() } },
                        LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        pathRow.addView(text(("NAS › " + nasPath.replace("\\", " › ")).trimEnd(' ', '›'), UiScale.TEXT_BASE).apply {
            setBackgroundResource(R.drawable.retro_field); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        page.addView(pathRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        val status = text(getString(R.string.music_loading), UiScale.TEXT_BASE, dim = true)
        page.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        val entries = ArrayList<NasEntry>()
        val adapter = EntryAdapter()
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; visibility = View.GONE }
        actions.addView(button(getString(R.string.music_nas_play_folder)) {
            val songs = entries.filter { !it.folder && MusicLibrary.playable(it.name) }.map(::nasTrack)
            if (songs.isNotEmpty()) { MusicPlayer.play(this, songs); show(Tab.NOW) }
        }, weight(0, dp(UiScale.TOUCH)))
        actions.addView(button(getString(R.string.music_nas_add)) {
            status.text = getString(R.string.music_nas_adding)
            val path = nasPath
            val mine = generation
            worker.execute {
                val text = try { getString(R.string.music_nas_added, MusicShelf.addNasFolder(this, path)) }
                           catch (e: Exception) { getString(R.string.music_nas_failed) }
                handler.post { if (mine == generation) status.text = text + "  ·  " + libraryLine() }
            }
        }, weight(1, dp(UiScale.TOUCH)))
        page.addView(actions, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
        page.addView(list(adapter) { index ->
            val entry = entries[index]
            when {
                entry.folder -> { nasPath = entry.path; showLibrary() }
                MusicLibrary.playable(entry.name) -> {
                    val songs = entries.filter { !it.folder && MusicLibrary.playable(it.name) }
                    MusicPlayer.play(this, songs.map(::nasTrack), songs.indexOf(entry).coerceAtLeast(0))
                    show(Tab.NOW)
                }
            }
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        adapter.entries = entries
        val mine = generation
        val path = nasPath
        worker.execute {
            val result = runCatching { NasSession.open(NasStore.load(this)!!).use { it.list(path) } }
            handler.post {
                if (mine != generation) return@post
                result.onSuccess { list ->
                    entries.addAll(list.filter { it.folder || MusicLibrary.playable(it.name) || MusicLibrary.unsupportedReason(it.name) != null })
                    adapter.notifyDataSetChanged()
                    val songs = entries.count { !it.folder && MusicLibrary.playable(it.name) }
                    status.text = (if (songs == 0) getString(R.string.music_nas_no_music) else "$songs เพลง") + "  ·  " + libraryLine()
                    actions.visibility = View.VISIBLE
                }.onFailure { status.text = getString(R.string.music_nas_failed) }
            }
        }
    }

    private fun libraryLine(): String {
        val folders = MusicShelf.nasFolders(this)
        return getString(R.string.music_nas_library, folders.size, folders.sumOf { it.tracks.size })
    }

    private fun nasTrack(entry: NasEntry) =
        Track(Track.NAS + entry.path, MusicLibrary.titleFromFile(entry.name), nasPath.substringAfterLast('\\'), "")

    // ------------------------------------------------------------ lists

    private fun list(adapter: BaseAdapter, onTap: (Int) -> Unit) = ListView(this).apply {
        this.adapter = adapter
        setBackgroundResource(R.drawable.retro_field)
        divider = null
        setOnItemClickListener { _, _, position, _ -> onTap(position) }
    }

    /** Songs: the title, then the artist dimmer; the one playing in navy, and says so. */
    private inner class TrackAdapter(private var tracks: List<Track>, private val playing: () -> Int) : BaseAdapter() {
        fun set(list: List<Track>) { tracks = list; notifyDataSetChanged() }
        override fun getCount() = tracks.size
        override fun getItem(position: Int) = tracks[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: rowView()
            val t = tracks[position]
            val now = position == playing()
            val title = row.getChildAt(0) as TextView
            val sub = row.getChildAt(1) as TextView
            title.text = (if (now) "▶ " else "") + t.title
            sub.text = listOf(t.artist, t.album).filter { it.isNotEmpty() }.joinToString(" · ")
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            row.setBackgroundColor(if (now) color(R.color.retro_title) else 0)
            title.setTextColor(color(if (now) R.color.retro_title_text else R.color.retro_text))
            sub.setTextColor(color(if (now) R.color.retro_title_text else R.color.retro_dim))
            row.contentDescription = t.title + if (now) " (กำลังเล่น)" else ""
            return row
        }
    }

    /** NAS entries: folders first (the share's order), songs, and formats not played, dimmed with why. */
    private inner class EntryAdapter : BaseAdapter() {
        var entries: List<NasEntry> = emptyList()
        override fun getCount() = entries.size
        override fun getItem(position: Int) = entries[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun isEnabled(position: Int) = entries[position].let { it.folder || MusicLibrary.playable(it.name) }
        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: rowView()
            val e = entries[position]
            val title = row.getChildAt(0) as TextView
            val sub = row.getChildAt(1) as TextView
            title.text = e.name
            val refused = MusicLibrary.unsupportedReason(e.name)
            sub.text = when {
                e.folder -> getString(R.string.music_folder)
                refused != null -> getString(R.string.music_unsupported, refused)
                else -> ""
            }
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            title.setTextColor(color(if (refused != null) R.color.retro_dim else R.color.retro_text))
            sub.setTextColor(color(R.color.retro_dim))
            row.setBackgroundColor(0)
            return row
        }
    }

    private fun rowView() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(UiScale.ROW)
        // Two lines and 4dp above and below: 56dp, the ROW, more songs to a screen.
        setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS))
        addView(TextView(context).apply { typeface = thai; textSize = UiScale.TEXT_ITEM; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE })
        addView(TextView(context).apply { typeface = thai; textSize = UiScale.TEXT_NOTE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END })
        layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
    }

    // ------------------------------------------------------------ parts

    /** A player button: the picture, and its word under it. */
    private fun control(icon: Int, word: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        setBackgroundResource(R.drawable.retro_button)
        isClickable = true
        contentDescription = getString(word)
        setOnClickListener { onClick() }
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M)))
        addView(text(getString(word), UiScale.TEXT_NOTE).apply { gravity = Gravity.CENTER; maxLines = 1 })
    }

    /** A retro slider: a sunken grey track, navy to the thumb, a raised square thumb. */
    private fun retroSeek() = SeekBar(this).apply {
        val track = GradientDrawable().apply { setColor(color(R.color.retro_light)); setStroke(dp(UiScale.HAIRLINE), color(R.color.retro_shadow)) }
        val fill = ClipDrawable(GradientDrawable().apply { setColor(color(R.color.retro_title)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
        thumb = GradientDrawable().apply {
            setColor(color(R.color.retro_face)); setStroke(dp(UiScale.BEVEL), color(R.color.retro_dark)); setSize(dp(UiScale.THUMB_W), dp(UiScale.THUMB_H))
        }
        splitTrack = false
        minimumHeight = dp(UiScale.SPACE_M)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
    }

    /** A 1995 option button: ● chosen, ○ not, the word beside it; a 48dp target, no fill. */
    private fun option(value: String, on: Boolean, onClick: () -> Unit) = TextView(this).apply {
        text = (if (on) "● " else "○ ") + value
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color(R.color.retro_text))
        gravity = Gravity.CENTER_VERTICAL
        setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
        minWidth = dp(UiScale.TOUCH)
        contentDescription = value + if (on) " (เลือกอยู่)" else ""
        isClickable = true
        setOnClickListener { if (!on) onClick() }
    }

    private fun choice(value: String, on: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        gravity = Gravity.CENTER
        isClickable = true
        setOnClickListener { onClick() }
        styleChoice(this, on, value)
    }

    private fun styleChoice(view: TextView, on: Boolean, value: String = view.text.toString()) = view.apply {
        text = value
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(color(if (on) R.color.retro_title_text else R.color.retro_text))
        if (on) setBackgroundColor(color(R.color.retro_title)) else setBackgroundResource(R.drawable.retro_button)
        contentDescription = value + if (on) " (เลือกอยู่)" else ""
    }

    private fun button(value: String, big: Boolean = false, onClick: () -> Unit) = TextView(this).apply {
        text = value
        textSize = if (big) UiScale.TEXT_HEADING else UiScale.TEXT_BASE
        typeface = Typeface.create(thai, Typeface.BOLD)
        gravity = Gravity.CENTER
        setTextColor(color(R.color.retro_text))
        setBackgroundResource(R.drawable.retro_button)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
        minWidth = dp(UiScale.TOUCH)
        maxLines = 1
        isClickable = true
        setOnClickListener { onClick() }
    }

    private fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(this).apply {
        text = value
        textSize = sp
        typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    private fun label(value: String) = text(value, UiScale.TEXT_NOTE).apply { typeface = Typeface.create(thai, Typeface.BOLD) }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun weight(index: Int, height: Int = MATCH) =
        LinearLayout.LayoutParams(0, height, 1f).apply { if (index > 0) marginStart = dp(UiScale.SPACE_S) }

    private fun clock(ms: Long): String {
        val s = (ms / 1000).coerceAtLeast(0)
        return if (s >= 3600) "%d:%02d:%02d".format(s / 3600, s / 60 % 60, s % 60) else "%d:%02d".format(s / 60, s % 60)
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
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
