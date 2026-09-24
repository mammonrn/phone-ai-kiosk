package com.mammonrn.phoneaikiosk.media

import android.app.Activity
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.util.Log
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
import com.mammonrn.phoneaikiosk.files.FilesActivity
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.util.concurrent.Executors

/**
 * The video player's screen (0.56.0, Poom: "หน้าตาใกล้ PowerDVD"), from the
 * Control Panel. Two pages:
 *
 *  * THE LIST: the videos of the playlist chosen (0.59.0 — no longer every
 *    video on the phone), in the Control Panel's window, each with its length
 *    and where it was left; "Playlist" to choose, make and edit the lists, and
 *    "เพิ่มวิดีโอ" to add through the shared folder browser.
 *  * THE PLAYER: the picture on black, a bar at the top (back to the list,
 *    the name, close) and the floating silver panel at the bottom — volume
 *    at the left, the teal read-out, the ring with play in its middle, open
 *    and stop at the right, and a row of speed, sound track, subtitles and
 *    fill-the-screen. Both float over the picture and hide 4 s after the
 *    last touch while it plays; a tap on the picture brings them back.
 *
 * The playing is in [VideoService]; since 0.58.0 leaving the player stops it.
 *
 * ONE FILE ON ITS OWN (0.59.0): opened from the file manager with
 * FilesActivity.SINGLE, the video plays alone, never added to a playlist;
 * leaving it goes back to the file manager and the playlist's place comes back.
 */
class VideoActivity : Activity() {

    private lateinit var thai: Typeface
    private lateinit var pixel: Typeface
    private lateinit var root: FrameLayout
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-video") }
    private var generation = 0

    private enum class Page { LIST, LISTS, PICKER, PLAYER }
    private var page = Page.LIST
    private lateinit var retro: Retro
    /** A video from the file manager is playing on its own. */
    private var singleMode = false
    /**
     * FULL SCREEN (0.57.0, Poom approved): the one place in the kiosk that may
     * turn sideways. On: the phone's turning decides portrait or landscape,
     * unless [rotationLocked] holds it where it is. Off, leaving the player,
     * or the video ending: back to portrait at once (DESIGN.md 1 and 5ซ).
     */
    private var fill = false
    private var rotationLocked = false
    /** A video was loaded at the last refresh: its ending is what ends full screen. */
    private var hadMedia = false
    private var lockButton: TextView? = null

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
        retro = Retro(this, thai)
        val single = intent.getStringExtra(FilesActivity.SINGLE)
        if (single != null) {
            singleMode = true
            val name = single.substringAfter(':').substringAfterLast('/').substringAfterLast('\\')
            VideoPlayer.playSingle(this, Video(Track(single, MusicLibrary.titleFromFile(name))))
            showPlayer()
        } else if (VideoPlayer.hasMedia) showPlayer() else showList()
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        VideoPlayer.listeners.add(listener)
        HeatWatch.listeners.add(heat)
        applyDim(HeatWatch.step)
        handler.post(tick)
        if (page == Page.PLAYER) { attach(); refresh() }
        applyOrientation()
        debugTurn = { landscape -> debugRotate(landscape) }
    }

    override fun onPause() {
        super.onPause()
        debugTurn = null
        // Leaving the player (Back, X, Hey Jarvis, the home screen): the video stops, portrait again.
        if (isFinishing) {
            stopForLeaving("closed")
            requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            // 0.59.0: the playlist and its place come back after a video played on its own.
            if (singleMode) VideoPlayer.endSingle()
        }
        VideoPlayer.listeners.remove(listener)
        HeatWatch.listeners.remove(heat)
        handler.removeCallbacks(tick)
        surface?.let { VideoPlayer.detachSurface(it) }
        attachedTo = null
    }

    /** Turned (configChanges in the manifest): the same views, laid out again; the video never stops. */
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        hideSystemBars()
        frame?.fill = fill && newConfig.orientation != Configuration.ORIENTATION_LANDSCAPE
        frame?.requestLayout()
        (panel?.layoutParams as? FrameLayout.LayoutParams)?.let { panel?.layoutParams = panelParams() }
        panel?.post { placeCue(panel?.visibility == View.VISIBLE) }
        Log.i(VideoService.TAG, "turned ${if (newConfig.orientation == Configuration.ORIENTATION_LANDSCAPE) "landscape" else "portrait"}")
    }

    /** Portrait, unless full screen: then the phone's turning, or held where it is when locked. */
    private fun applyOrientation() {
        val wanted = when {
            page != Page.PLAYER || !fill -> ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            rotationLocked -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            else -> ActivityInfo.SCREEN_ORIENTATION_SENSOR
        }
        if (requestedOrientation != wanted) {
            requestedOrientation = wanted
            // 0.58.0: said in the log, so a turn that does not happen can be traced to what was asked.
            Log.i(VideoService.TAG, "orientation asked=" + when (wanted) {
                ActivityInfo.SCREEN_ORIENTATION_SENSOR -> "sensor"
                ActivityInfo.SCREEN_ORIENTATION_LOCKED -> "locked"
                else -> "portrait"
            } + " page=${page.name.lowercase()} full=$fill")
        }
    }

    /** Debug only (TEST_VIDEO_ROTATE): what the phone's sensor would say, for a test without turning it. */
    private fun debugRotate(landscape: Boolean) {
        if (page != Page.PLAYER || !fill || rotationLocked) {
            Log.i(VideoService.TAG, "test turn ignored: full=$fill locked=$rotationLocked")
            return
        }
        requestedOrientation = if (landscape) ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
                               else ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
    }

    private fun panelParams(): FrameLayout.LayoutParams {
        val landscape = resources.configuration.orientation == Configuration.ORIENTATION_LANDSCAPE
        // Sideways the panel keeps a phone's width, centred, so the picture stays in view.
        return FrameLayout.LayoutParams(if (landscape) dp(UiScale.VIDEO_PANEL_W) else MATCH, WRAP,
                                        Gravity.BOTTOM or Gravity.CENTER_HORIZONTAL).apply {
            setMargins(dp(UiScale.FRAME), 0, dp(UiScale.FRAME), dp(UiScale.FRAME))
        }
    }

    /** The screen went off (or something covered it) while the video played: paused, the place kept. */
    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations && VideoPlayer.state == VideoPlayer.State.PLAYING) {
            VideoPlayer.pause(this)
            Log.i(VideoService.TAG, "paused: screen off")
        }
    }

    /**
     * 0.58.0 (Poom): no video in the background. Leaving the player — X, Back to the
     * list, closed by a question — stops it; the place it was left is kept
     * (VideoService.stopAll saves it) and the wake word comes back at once.
     */
    private fun stopForLeaving(why: String) {
        // Left on purpose: the next video's loading gap must not read as "this one ended"
        // (0.58.0: that turned full screen off as the next video began, on the A07).
        hadMedia = false
        if (!VideoPlayer.hasMedia) return
        VideoPlayer.stop(this)
        Log.i(VideoService.TAG, "stopped: left the player ($why)")
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** Back: the player to the list (a file on its own: out), the playlists or choosing to the list, then out. */
    private fun goBack() {
        when {
            page == Page.PLAYER && singleMode -> finish()
            page != Page.LIST -> showList()
            else -> finish()
        }
    }

    private fun goHome() {
        stopForLeaving("home")
        KioskScreens.leaveAllButHome("video-home")
        startActivity(Intent(this, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP))
        finish()
    }

    // ------------------------------------------------------------ the list

    private fun showList() {
        // A video on its own has no list here: leaving it goes back to the file manager.
        if (singleMode) { finish(); return }
        // Back to the list stops the video: nothing plays where it cannot be seen (0.58.0).
        stopForLeaving("list")
        page = Page.LIST
        generation += 1
        applyOrientation()
        val body = listWindow()

        VideoPlayer.error?.let {
            body.addView(text(it, UiScale.TEXT_BASE).apply { setTextColor(color(R.color.retro_bad)) },
                         LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
        }
        listNote?.let {
            body.addView(text(it, UiScale.TEXT_BASE).apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { bottomMargin = dp(UiScale.SPACE_S) })
            listNote = null
        }
        // 0.59.0: the playlist chosen, and the way to the others.
        val current = PlaylistStore.read(this) { it.current(Playlist.Kind.VIDEO) }
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        head.addView(label(getString(R.string.video_playlist)))
        head.addView(text(current?.name ?: getString(R.string.video_no_playlist), UiScale.TEXT_ITEM).apply {
            typeface = Typeface.create(thai, Typeface.BOLD); maxLines = 1; ellipsize = TextUtils.TruncateAt.END
            setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        head.addView(button(getString(R.string.video_playlists)) { showLists() }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        body.addView(head, LinearLayout.LayoutParams(MATCH, WRAP))
        val videos = current?.items.orEmpty().map { Video(it) }
        body.addView(text(when {
            current == null -> getString(R.string.video_make_first)
            videos.isEmpty() -> getString(R.string.video_playlist_empty)
            else -> getString(R.string.video_count, videos.size)
        }, UiScale.TEXT_BASE, dim = true), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        val adapter = VideoAdapter()
        adapter.set(videos.map { Row(it) })
        body.addView(ListView(this).apply {
            this.adapter = adapter
            setBackgroundResource(R.drawable.retro_field)
            divider = null
            setOnItemClickListener { _, _, position, _ -> play(adapter.rows, position) }
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        body.addView(button(getString(if (current == null) R.string.playlist_create else R.string.video_add), big = true) {
            if (current == null) showLists() else startPicker(current)
        }, LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_XS) })
    }

    /** The list's window in the Control Panel's style; returns its body to fill. */
    private fun listWindow(): LinearLayout {
        surface?.let { VideoPlayer.detachSurface(it) }
        surface = null
        attachedTo = null
        root.removeAllViews()
        root.setOnTouchListener(null)
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
        shell.addView(button(getString(R.string.settings_home), big = true) { goHome() },
                      LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        root.addView(shell, FrameLayout.LayoutParams(MATCH, MATCH))
        return body
    }

    // ------------------------------------------------------------ playlists (0.59.0)

    /** What the last "add" did, said once at the top of the list. */
    private var listNote: String? = null

    private val playlists by lazy {
        PlaylistsPage(retro, Playlist.Kind.VIDEO, object : PlaylistsPage.Host {
            override fun play(list: Playlist) {
                PlaylistStore.edit(this@VideoActivity) { it.setCurrent(Playlist.Kind.VIDEO, list.id) }
                if (list.items.isEmpty()) startPicker(list) else showList()
            }
            override fun addTo(list: Playlist) = startPicker(list)
        })
    }

    private val picker by lazy {
        MediaPicker(retro, worker, Playlist.Kind.VIDEO, object : MediaPicker.Host {
            override fun added(count: Int, words: String) { listNote = words; showList() }
            override fun cancelled() = showList()
        })
    }

    private fun showLists() {
        page = Page.LISTS
        generation += 1
        val body = listWindow()
        playlists.draw()
        body.addView(detached(playlists.view), LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun startPicker(list: Playlist) {
        PlaylistStore.edit(this) { it.setCurrent(Playlist.Kind.VIDEO, list.id) }
        page = Page.PICKER
        generation += 1
        val body = listWindow()
        picker.start(list)
        body.addView(detached(picker.view), LinearLayout.LayoutParams(MATCH, 0, 1f))
    }

    private fun detached(view: View): View {
        (view.parent as? ViewGroup)?.removeView(view)
        return view
    }

    private fun play(rows: List<Row>, position: Int) {
        val videos = rows.map { it.video }
        VideoPlayer.play(this, videos, position)
        showPlayer()
    }

    private class Row(val video: Video)

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
        // 0.58.0, the bug Poom met: full screen stayed on (the button said เปิด) while
        // another video was chosen, and this page never asked for the sensor again —
        // the phone was turned and the app was still asking for portrait (A07 log, 20:58).
        applyOrientation()
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
            addView(dvdButton(getString(if (singleMode) R.string.video_to_files else R.string.video_to_list)) { showList() },
                    LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
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
        root.addView(panel, panelParams())

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
        right.addView(iconButton(R.drawable.ic_pixel_stop, R.string.music_stop) { VideoPlayer.stop(this); if (!singleMode) showList() },
                      LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.ICON_BUTTON)).apply { topMargin = dp(UiScale.SPACE_XS) })
        top.addView(right, LinearLayout.LayoutParams(dp(UiScale.TOUCH), WRAP).apply { marginStart = dp(UiScale.SPACE_XS) })
        body.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // The row of settings: two short lines each ("ความเร็ว / 1.0×"), so 64dp tall.
        // Not baseline-aligned: a two-line button sat lower than a one-line one (seen on the A07).
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; isBaselineAligned = false }
        speedButton = dvdButton("", small = true) { VideoPlayer.cycleSpeed(this); poke() }
        row.addView(speedButton, LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f))
        row.addView(dvdButton(getString(R.string.video_audio), small = true) { chooseTrack(C.TRACK_TYPE_AUDIO, it) },
                    LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        row.addView(dvdButton(getString(R.string.video_subtitles), small = true) { chooseTrack(C.TRACK_TYPE_TEXT, it) },
                    LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        fillButton = dvdButton("", small = true) { setFull(!fill); poke() }
        row.addView(fillButton, LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        lockButton = dvdButton("", small = true) {
            rotationLocked = !rotationLocked
            applyOrientation(); refresh(); poke()
        }
        row.addView(lockButton, LinearLayout.LayoutParams(0, dp(UiScale.ICON_BUTTON), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
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

    /** Full screen on or off: the picture fills, and the phone may turn (DESIGN.md 5ซ). */
    private fun setFull(on: Boolean) {
        if (fill == on) return
        fill = on
        Log.i(VideoService.TAG, "full screen ${if (on) "on" else "off"}")
        frame?.fill = on && resources.configuration.orientation != Configuration.ORIENTATION_LANDSCAPE
        applyOrientation()
        refresh()
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
        placeCue(on)
        if (on) poke() else handler.removeCallbacks(hide)
    }

    /**
     * Subtitles sit just above the panel while it shows, and near the bottom edge
     * when it hides — a fixed height put them mid-picture when turned sideways
     * (the screen is 384dp tall there; seen on the A07).
     */
    private fun placeCue(controlsShown: Boolean) {
        val cue = cueView ?: return
        val p = panel
        val above = if (controlsShown && p != null && p.height > 0) p.height + dp(UiScale.FRAME) * 2
                    else if (controlsShown) dp(UiScale.VIDEO_CUE_BOTTOM) else dp(UiScale.SPACE_L)
        (cue.layoutParams as? FrameLayout.LayoutParams)?.let {
            if (it.bottomMargin != above) { it.bottomMargin = above; cue.layoutParams = it }
        }
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
        lockButton?.text = getString(if (rotationLocked) R.string.video_rotation_locked else R.string.video_rotation_free)
        lockButton?.contentDescription = getString(if (rotationLocked) R.string.video_rotation_locked_words else R.string.video_rotation_free_words)
        lockButton?.alpha = if (fill) 1f else 0.5f
        // The video ended or was stopped: full screen ends, and portrait with it — at that
        // moment only, so full screen can still be chosen before pressing play again.
        if (fill && hadMedia && !VideoPlayer.hasMedia) setFull(false)
        hadMedia = VideoPlayer.hasMedia
        val size = VideoPlayer.player?.videoSize
        if (size != null && size.width > 0) frame?.ratio = size.width * size.pixelWidthHeightRatio / size.height
        lcdFacts?.text = listOfNotNull(size?.takeIf { it.height > 0 }?.let { "${minOf(it.width, it.height)}p" },
                                       VideoRules.speedWord(VideoPlayer.speed)).joinToString(" · ")
        // A refused or failed file shows black, not the last picture of the one before.
        frame?.visibility = if (VideoPlayer.error != null && !VideoPlayer.hasMedia) View.INVISIBLE else View.VISIBLE
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
        HeatLadder.Step.COOLER, HeatLadder.Step.COOLEST ->
            getString(R.string.video_heat_dim, ((step.brightness ?: 1f) * 100).toInt())
        HeatLadder.Step.PAUSE -> getString(R.string.video_heat_pause)
        HeatLadder.Step.STOP -> getString(R.string.video_heat_stop)
    }

    /** Heat, step 1 and above: the screen dimmer while the video is shown (70%, then 40%). */
    private fun applyDim(step: HeatLadder.Step) {
        window.attributes = window.attributes.apply {
            screenBrightness = step.brightness ?: WindowManager.LayoutParams.BRIGHTNESS_OVERRIDE_NONE
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

        /** Debug builds' adb switch (TEST_VIDEO_ROTATE) reaches the screen in front through this. */
        @Volatile var debugTurn: ((Boolean) -> Unit)? = null
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
