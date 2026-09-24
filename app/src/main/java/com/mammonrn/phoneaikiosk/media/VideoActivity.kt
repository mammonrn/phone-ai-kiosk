package com.mammonrn.phoneaikiosk.media

import android.app.Activity
import android.content.Intent
import android.content.res.ColorStateList
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.SurfaceView
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.BaseAdapter
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ListView
import android.widget.PopupMenu
import android.widget.TextView
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.media3.common.C
import com.mammonrn.phoneaikiosk.KioskScreens
import com.mammonrn.phoneaikiosk.MainActivity
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.files.NasEntry
import com.mammonrn.phoneaikiosk.files.NasSession
import com.mammonrn.phoneaikiosk.files.NasStore
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.util.concurrent.Executors

/**
 * The video player's screen (0.56.0, Poom: "หน้าตาใกล้ PowerDVD"), from the
 * Control Panel. Two pages:
 *
 *  * THE LIST: the phone's videos, or the NAS's, in the Control Panel's
 *    window, each with its length and where it was left.
 *  * THE PLAYER: the picture on black, a bar at the top (back to the list,
 *    the name, close) and the floating silver panel at the bottom — volume
 *    at the left, the teal read-out, the ring with play in its middle, open
 *    and stop at the right, and a row of speed, sound track, subtitles and
 *    fill-the-screen. Both float over the picture and hide 4 s after the
 *    last touch while it plays; a tap on the picture brings them back.
 *
 * The playing is in [VideoService]: closing this screen — Back, the X, Hey
 * Jarvis — leaves the sound playing (DESIGN.md 12).
 */
class VideoActivity : Activity() {

    private lateinit var thai: Typeface
    private lateinit var pixel: Typeface
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-video") }
    private var generation = 0

    private enum class Page { LIST, PLAYER }
    private var page = Page.LIST
    private var onNas = false
    private var nasPath = ""
    private var fill = false

    // The player page's views.
    private var surface: SurfaceView? = null
    private var frame: AspectFrame? = null
    private var topBar: View? = null
    private var panel: View? = null
    private var cueView: TextView? = null
    private var noteView: TextView? = null
    private var titleView: TextView? = null
    private var lcdIndex: TextView? = null
    private var lcdTime: TextView? = null
    private var lcdLength: TextView? = null
    private var lcdFacts: TextView? = null
    private var volumeView: TextView? = null
    private var ring: RingView? = null
    private var speedButton: TextView? = null
    private var fillButton: TextView? = null
    private var scrubbing: Float? = null

    private val hide = Runnable { if (VideoPlayer.state == VideoPlayer.State.PLAYING) showControls(false) }
    private val listener: () -> Unit = { if (page == Page.PLAYER) refresh() }
    private val tick = object : Runnable {
        override fun run() {
            if (page == Page.PLAYER) refreshTime()
            handler.postDelayed(this, 500)
        }
    }
    private val heat: (HeatLadder.Step) -> Unit = { applyDim(it) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        pixel = ResourcesCompat.getFont(this, R.font.press_start_2p) ?: Typeface.MONOSPACE
        root = FrameLayout(this).apply { setBackgroundColor(color(R.color.retro_dark)) }
        setContentView(root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        hideSystemBars()
        if (VideoPlayer.hasMedia) showPlayer() else showList()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        VideoPlayer.listeners.add(listener)
        HeatWatch.listeners.add(heat)
        applyDim(HeatWatch.step)
        handler.post(tick)
        if (page == Page.PLAYER) { attach(); refresh() }
    }

    override fun onPause() {
        super.onPause()
        VideoPlayer.listeners.remove(listener)
        HeatWatch.listeners.remove(heat)
        handler.removeCallbacks(tick)
        surface?.let { VideoPlayer.detachSurface(it) }
        attachedTo = null
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** Back: the player to the list (the video plays on), up a NAS folder, then out. */
    private fun goBack() {
        when {
            page == Page.PLAYER -> showList()
            onNas && nasPath.isNotEmpty() -> { nasPath = nasPath.substringBeforeLast('\\', ""); showList() }
            else -> finish()
        }
    }

    private fun goHome() {
        KioskScreens.leaveAllButHome("video-home")
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------ the list

    private fun showList() {
        page = Page.LIST
        generation += 1
        surface?.let { VideoPlayer.detachSurface(it) }
        surface = null
        attachedTo = null
        root.removeAllViews()
        window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        val shell = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(color(R.color.retro_desktop))
            setPadding(dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME))
        }
        val window = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET))
        }
        shell.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))
        window.addView(titleBar(), LinearLayout.LayoutParams(MATCH, WRAP))
        val body = column()
        window.addView(body, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.WINDOW_INSET) })

        if (VideoPlayer.hasMedia) {
            body.addView(button(getString(R.string.video_back_to_player, VideoPlayer.current?.track?.title.orEmpty())) { showPlayer() },
                         LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { bottomMargin = dp(UiScale.SPACE_S) })
        }
        VideoPlayer.error?.let {
            body.addView(text(it, UiScale.TEXT_BASE).apply { setTextColor(color(R.color.retro_bad)) },
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
        }
        val sources = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        sources.addView(label(getString(R.string.video_source)))
        sources.addView(option(getString(R.string.music_local), !onNas) { onNas = false; showList() },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        sources.addView(option(getString(R.string.music_nas), onNas) { onNas = true; showList() },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        body.addView(sources, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)))
        val status = text(getString(R.string.video_loading), UiScale.TEXT_BASE, dim = true)
        body.addView(status, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        val adapter = VideoAdapter()
        val list = ListView(this).apply {
            this.adapter = adapter
            setBackgroundResource(R.drawable.retro_field)
            divider = null
        }
        if (onNas) nasList(body, status, adapter, list) else localList(status, adapter, list)
        body.addView(list, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })

        shell.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                      LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    private fun localList(status: TextView, adapter: VideoAdapter, list: ListView) {
        list.setOnItemClickListener { _, _, position, _ -> play(adapter.rows, position) }
        val mine = generation
        worker.execute {
            val videos = VideoPlayer.local(this)
            handler.post {
                if (mine != generation) return@post
                adapter.set(videos.map { Row(it, null) })
                status.text = if (videos.isEmpty()) getString(R.string.video_local_empty) else getString(R.string.video_count, videos.size)
            }
        }
    }

    private fun nasList(body: LinearLayout, status: TextView, adapter: VideoAdapter, list: ListView) {
        if (NasStore.load(this) == null) { status.text = getString(R.string.music_nas_not_set); return }
        body.addView(text(("NAS › " + nasPath.replace("\\", " › ")).trimEnd(' ', '›'), UiScale.TEXT_BASE).apply {
            setBackgroundResource(R.drawable.retro_field); gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0); maxLines = 1; ellipsize = TextUtils.TruncateAt.START
        }, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
        list.setOnItemClickListener { _, _, position, _ ->
            val row = adapter.rows[position]
            val folder = row.folder
            if (folder != null) { nasPath = folder.path; showList() } else play(adapter.rows, position)
        }
        val mine = generation
        val path = nasPath
        worker.execute {
            val result = runCatching { NasSession.open(NasStore.load(this)!!).use { it.list(path) } }
            handler.post {
                if (mine != generation) return@post
                result.onSuccess { entries ->
                    val rows = entries.filter { it.folder }.map { Row(null, it) } +
                        entries.filter { !it.folder && VideoRules.playable(it.name) }
                            .map { Row(Video(Track(Track.NAS + it.path, MusicLibrary.titleFromFile(it.name))), null) }
                    adapter.set(rows)
                    status.text = getString(R.string.video_count, rows.count { it.video != null })
                }.onFailure { status.text = getString(R.string.music_nas_failed) }
            }
        }
    }

    private fun play(rows: List<Row>, position: Int) {
        val videos = rows.mapNotNull { it.video }
        val v = rows[position].video ?: return
        VideoPlayer.play(this, videos, videos.indexOf(v))
        showPlayer()
    }

    private class Row(val video: Video?, val folder: NasEntry?)

    /** A video: its name, then its length, its size and where it was left. */
    private inner class VideoAdapter : BaseAdapter() {
        var rows: List<Row> = emptyList()
        fun set(list: List<Row>) { rows = list; notifyDataSetChanged() }
        override fun getCount() = rows.size
        override fun getItem(position: Int) = rows[position]
        override fun getItemId(position: Int) = position.toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@VideoActivity).apply {
                orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(UiScale.ROW)
                setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS))
                addView(TextView(context).apply { typeface = thai; textSize = UiScale.TEXT_ITEM; maxLines = 1; ellipsize = TextUtils.TruncateAt.MIDDLE; setTextColor(color(R.color.retro_text)) })
                addView(TextView(context).apply { typeface = thai; textSize = UiScale.TEXT_NOTE; maxLines = 1; setTextColor(color(R.color.retro_dim)) })
                layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
            }
            val r = rows[position]
            val title = row.getChildAt(0) as TextView
            val sub = row.getChildAt(1) as TextView
            val v = r.video
            if (v == null) { title.text = r.folder?.name; sub.text = getString(R.string.music_folder); return row }
            title.text = v.track.title
            val parts = ArrayList<String>()
            if (v.track.durationMs > 0) parts += clock(v.track.durationMs)
            if (v.height > 0) parts += "${minOf(v.width, v.height)}p"
            VideoRules.refusal(v.width, v.height, v.mime)?.let { parts += getString(R.string.video_too_big, it) }
            VideoPlayer.savedPlace(this@VideoActivity, v).takeIf { it > 0 }?.let { parts += getString(R.string.video_left_at, clock(it)) }
            sub.text = parts.joinToString(" · ")
            sub.visibility = if (sub.text.isEmpty()) View.GONE else View.VISIBLE
            row.contentDescription = v.track.title + ", " + sub.text
            return row
        }
    }

    // ------------------------------------------------------------ the player

    private fun showPlayer() {
        page = Page.PLAYER
        generation += 1
        root.removeAllViews()
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        frame = AspectFrame(this).also { f ->
            surface = SurfaceView(this)
            f.addView(surface, FrameLayout.LayoutParams(MATCH, MATCH))
            f.fill = fill
        }
        root.addView(frame, FrameLayout.LayoutParams(MATCH, MATCH, Gravity.CENTER))
        // A tap on the picture shows or hides the controls.
        root.setOnTouchListener { _, e ->
            if (e.actionMasked == MotionEvent.ACTION_UP) showControls(topBar?.visibility != View.VISIBLE)
            true
        }

        cueView = TextView(this).apply {
            typeface = Typeface.create(thai, Typeface.BOLD); textSize = UiScale.TEXT_ITEM
            setTextColor(color(R.color.retro_light)); gravity = Gravity.CENTER
            setShadowLayer(dp(UiScale.SPACE_XS).toFloat(), 0f, 0f, color(R.color.retro_dark))
            setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
        }
        root.addView(cueView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply { bottomMargin = dp(UiScale.VIDEO_CUE_BOTTOM) })

        topBar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(color(R.color.video_scrim))
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            addView(dvdButton(getString(R.string.video_to_list)) { showList() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
            titleView = TextView(context).apply {
                typeface = Typeface.create(thai, Typeface.BOLD); textSize = UiScale.TEXT_BASE; setTextColor(color(R.color.retro_light))
                maxLines = 2; ellipsize = TextUtils.TruncateAt.END; setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
            }
            addView(titleView, LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(FrameLayout(context).apply {
                background = DvdSkin.button(context); isClickable = true
                contentDescription = getString(R.string.settings_home)
                setOnClickListener { goHome() }
                addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                        FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
            }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        }
        root.addView(topBar, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP))
        noteView = text("", UiScale.TEXT_BASE).apply {
            setBackgroundColor(color(R.color.video_scrim)); setTextColor(color(R.color.dvd_lcd_text))
            setPadding(dp(UiScale.SPACE_M), dp(UiScale.SPACE_S), dp(UiScale.SPACE_M), dp(UiScale.SPACE_S)); visibility = View.GONE
        }
        root.addView(noteView, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.TOP).apply { topMargin = dp(UiScale.VIDEO_NOTE_TOP) })

        panel = buildPanel()
        root.addView(panel, FrameLayout.LayoutParams(MATCH, WRAP, Gravity.BOTTOM).apply {
            setMargins(dp(UiScale.FRAME), 0, dp(UiScale.FRAME), dp(UiScale.FRAME)) })

        attach()
        refresh()
        showControls(true)
    }

    /** The floating panel: volume, the read-out, the ring, open and stop; then the row of settings. */
    private fun buildPanel(): View {
        val body = column().apply {
            background = DvdSkin.panel(this@VideoActivity)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            isClickable = true   // touches on the panel do not reach the picture
        }
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }

        // Volume: + , the speaker and its level, − .
        val vol = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        vol.addView(dvdButton("+") { VideoPlayer.setVolume(this, VideoPlayer.volume + 0.1f); poke() }.apply {
            contentDescription = getString(R.string.video_louder) }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        vol.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_speaker) },
                    LinearLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M)).apply { topMargin = dp(UiScale.SPACE_XS) })
        volumeView = text("", UiScale.TEXT_NOTE).apply { gravity = Gravity.CENTER }
        vol.addView(volumeView, LinearLayout.LayoutParams(WRAP, WRAP))
        vol.addView(dvdButton("−") { VideoPlayer.setVolume(this, VideoPlayer.volume - 0.1f); poke() }.apply {
            contentDescription = getString(R.string.video_quieter) }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        top.addView(vol, LinearLayout.LayoutParams(dp(UiScale.TOUCH), WRAP))

        // The teal read-out.
        val lcd = column().apply {
            background = DvdSkin.lcd(this@VideoActivity)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        lcdIndex = lcdText(UiScale.TEXT_NOTE, pixelFace = false)
        lcdTime = lcdText(UiScale.TEXT_NOTE, pixelFace = true)
        lcdLength = lcdText(UiScale.TEXT_NOTE, pixelFace = false)
        lcdFacts = lcdText(UiScale.TEXT_NOTE, pixelFace = false)
        for (v in listOf(lcdIndex, lcdTime, lcdLength, lcdFacts)) lcd.addView(v, LinearLayout.LayoutParams(MATCH, WRAP))
        top.addView(lcd, LinearLayout.LayoutParams(0, dp(UiScale.VIDEO_RING), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })

        // The ring.
        ring = RingView(this,
            onPlay = { VideoPlayer.toggle(this); poke() },
            onSkip = { VideoPlayer.skip(this, it); poke() },
            onScrub = { scrubbing = it; refreshTime(); poke() },
            onSeek = { VideoPlayer.seekTo(this, (it * VideoPlayer.durationMs).toLong()); poke() })
        top.addView(ring, LinearLayout.LayoutParams(dp(UiScale.VIDEO_RING), dp(UiScale.VIDEO_RING)).apply { marginStart = dp(UiScale.SPACE_XS) })

        // Open and stop.
        val right = column().apply { gravity = Gravity.CENTER_HORIZONTAL }
        right.addView(iconButton(R.drawable.ic_pixel_eject, R.string.video_open) { showList() },
                      LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.ICON_BUTTON)))
        right.addView(iconButton(R.drawable.ic_pixel_stop, R.string.music_stop) { VideoPlayer.stop(this); showList() },
                      LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.ICON_BUTTON)).apply { topMargin = dp(UiScale.SPACE_XS) })
        top.addView(right, LinearLayout.LayoutParams(dp(UiScale.TOUCH), WRAP).apply { marginStart = dp(UiScale.SPACE_XS) })
        body.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // The row of settings: two short lines each ("ความเร็ว / 1.0×"), so 64dp tall.
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        speedButton = dvdButton("", small = true) { VideoPlayer.cycleSpeed(this); poke() }
        row.addView(speedButton, LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f))
        row.addView(dvdButton(getString(R.string.video_audio), small = true) { chooseTrack(C.TRACK_TYPE_AUDIO, it) },
                    LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        row.addView(dvdButton(getString(R.string.video_subtitles), small = true) { chooseTrack(C.TRACK_TYPE_TEXT, it) },
                    LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        fillButton = dvdButton("", small = true) { fill = !fill; frame?.fill = fill; refresh(); poke() }
        row.addView(fillButton, LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        body.addView(row, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        return body
    }

    /** The sound tracks or subtitles the file has, in a menu; subtitles can also be turned off. */
    private fun chooseTrack(type: Int, anchor: View) {
        poke()
        val choices = VideoPlayer.choices(type)
        val menu = PopupMenu(this, anchor)
        if (choices.isEmpty()) {
            menu.menu.add(0, 0, 0, getString(if (type == C.TRACK_TYPE_TEXT) R.string.video_no_subtitles else R.string.video_no_audio_tracks))
                .isEnabled = false
        } else {
            choices.forEachIndexed { i, c -> menu.menu.add(0, i + 1, i, (if (c.selected) "● " else "○ ") + c.words) }
            if (type == C.TRACK_TYPE_TEXT) menu.menu.add(0, 99, 99, getString(R.string.video_subtitles_off))
        }
        menu.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                0 -> Unit
                99 -> VideoPlayer.choose(this, type, null)
                else -> VideoPlayer.choose(this, type, choices[item.itemId - 1])
            }
            true
        }
        menu.show()
    }

    /** The player this screen's picture is attached to: the service may start after the screen. */
    private var attachedTo: Any? = null

    private fun attach() {
        val s = surface ?: return
        val p = VideoPlayer.player ?: return
        if (attachedTo === p) return
        VideoPlayer.attachSurface(s)
        attachedTo = p
    }

    /** A touch keeps the controls up for another 4 s. */
    private fun poke() {
        handler.removeCallbacks(hide)
        handler.postDelayed(hide, HIDE_MS)
    }

    private fun showControls(on: Boolean) {
        val v = if (on) View.VISIBLE else View.GONE
        topBar?.visibility = v
        panel?.visibility = v
        if (on) poke() else handler.removeCallbacks(hide)
    }

    private fun refresh() {
        attach()
        val v = VideoPlayer.current
        titleView?.text = v?.track?.title ?: getString(R.string.window_video)
        val playing = VideoPlayer.state == VideoPlayer.State.PLAYING
        ring?.playing = playing
        ring?.contentDescription = getString(if (playing) R.string.video_ring_playing else R.string.video_ring)
        lcdIndex?.text = if (v == null) "" else getString(R.string.video_lcd_index, VideoPlayer.index + 1, VideoPlayer.list.size)
        volumeView?.text = "${(VideoPlayer.volume * 100).toInt()}%"
        speedButton?.text = getString(R.string.video_speed, VideoRules.speedWord(VideoPlayer.speed))
        fillButton?.text = getString(if (fill) R.string.video_fill_on else R.string.video_fill_off)
        val size = VideoPlayer.player?.videoSize
        if (size != null && size.width > 0) frame?.ratio = size.width * size.pixelWidthHeightRatio / size.height
        lcdFacts?.text = listOfNotNull(size?.takeIf { it.height > 0 }?.let { "${minOf(it.width, it.height)}p" },
                                       VideoRules.speedWord(VideoPlayer.speed)).joinToString(" · ")
        cueView?.text = VideoPlayer.cue
        cueView?.visibility = if (VideoPlayer.cue.isEmpty()) View.GONE else View.VISIBLE
        val note = VideoPlayer.error ?: heatNote(HeatWatch.step)
        noteView?.text = note
        noteView?.visibility = if (note == null) View.GONE else View.VISIBLE
        if (!playing) showControls(true)
        refreshTime()
    }

    private fun refreshTime() {
        val duration = VideoPlayer.durationMs
        val s = scrubbing
        val pos = if (s != null) (s * duration).toLong() else VideoPlayer.positionMs
        lcdTime?.text = VideoRules.hms(pos)
        lcdLength?.text = getString(R.string.video_lcd_length, VideoRules.hms(duration))
        if (s == null) ring?.progress = if (duration > 0) pos.toFloat() / duration else 0f
    }

    private fun heatNote(step: HeatLadder.Step): String? = when (step) {
        HeatLadder.Step.NORMAL -> null
        HeatLadder.Step.COOLER, HeatLadder.Step.COOLEST -> getString(R.string.video_heat_lower, step.maxHeight ?: 0)
        HeatLadder.Step.PAUSE -> getString(R.string.video_heat_pause)
        HeatLadder.Step.STOP -> getString(R.string.video_heat_stop)
    }

    /** Heat, step 2 and above: the screen at half brightness while the video is shown. */
    private fun applyDim(step: HeatLadder.Step) {
        window.attributes = window.attributes.apply {
            screenBrightness = if (step.dim) 0.5f else WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
        }
        if (page == Page.PLAYER) refresh()
    }

    // ------------------------------------------------------------ parts

    private fun titleBar() = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        setBackgroundResource(R.drawable.retro_titlebar)
        setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
        addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_video_light) },
                LinearLayout.LayoutParams(dp(UiScale.ICON_S), dp(UiScale.ICON_S)))
        addView(TextView(context).apply {
            text = getString(R.string.window_video)
            setTextColor(color(R.color.retro_title_text)); textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD); setPadding(dp(UiScale.SPACE_S), 0, 0, 0); maxLines = 1
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(FrameLayout(context).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = getString(R.string.settings_home)
            isClickable = true
            setOnClickListener { goHome() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
    }

    private fun lcdText(sp: Float, pixelFace: Boolean) = TextView(this).apply {
        typeface = if (pixelFace) pixel else thai; textSize = sp
        setTextColor(color(R.color.dvd_lcd_text)); maxLines = 1
    }

    private fun dvdButton(value: String, small: Boolean = false, onClick: (View) -> Unit) = TextView(this).apply {
        text = value; typeface = Typeface.create(thai, Typeface.BOLD); textSize = if (small) UiScale.TEXT_NOTE else UiScale.TEXT_BASE
        setTextColor(color(R.color.retro_text)); gravity = Gravity.CENTER; maxLines = if (small) 2 else 1
        background = DvdSkin.button(this@VideoActivity)
        setPadding(dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_XS), 0)
        minWidth = dp(UiScale.TOUCH)
        isClickable = true; setOnClickListener { onClick(it) }
    }

    private fun iconButton(icon: Int, word: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL; gravity = Gravity.CENTER
        background = DvdSkin.button(this@VideoActivity)
        isClickable = true; contentDescription = getString(word)
        setOnClickListener { onClick() }
        addView(ImageView(context).apply { setImageResource(icon) }, LinearLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M)))
        addView(TextView(context).apply {
            text = getString(word); typeface = thai; textSize = UiScale.TEXT_NOTE; setTextColor(color(R.color.retro_text))
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
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
        maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        isClickable = true
        setOnClickListener { onClick() }
    }

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

    private fun text(value: CharSequence, sp: Float, dim: Boolean = false) = TextView(this).apply {
        text = value; textSize = sp; typeface = thai
        setTextColor(color(if (dim) R.color.retro_dim else R.color.retro_text))
    }

    private fun label(value: String) = text(value, UiScale.TEXT_NOTE).apply { typeface = Typeface.create(thai, Typeface.BOLD) }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

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
        private const val HIDE_MS = 4_000L
    }
}

/**
 * The picture's frame: the video's own shape, centred and whole ([fill]
 * false), or filling the screen and cut at the sides ([fill] true).
 */
class AspectFrame(context: android.content.Context) : FrameLayout(context) {
    var ratio = 16f / 9f
        set(value) { if (value > 0 && value != field) { field = value; requestLayout() } }
    var fill = false
        set(value) { field = value; requestLayout() }

    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        var cw = w; var ch = (w / ratio).toInt()
        if ((ch > h) != fill) { ch = h; cw = (h * ratio).toInt() }
        super.onMeasure(MeasureSpec.makeMeasureSpec(cw, MeasureSpec.EXACTLY), MeasureSpec.makeMeasureSpec(ch, MeasureSpec.EXACTLY))
    }
}
