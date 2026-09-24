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
import com.mammonrn.phoneaikiosk.media.fx.Eq

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
    private var seek: SeekBar? = null
    private var shuffleView: TextView? = null
    private var repeatView: TextView? = null
    private var errorView: TextView? = null
    /** The library adds to the list instead of replacing it (the list's "เพิ่ม" and the open button). */
    private var addMode = false
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
        MusicPlayer.restore(this)
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
                setOnClickListener { addMode = false; show(t) }
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

    // ------------------------------------------------------------ "กำลังเล่น" (0.55.0)
    //
    // Three windows stacked, in the mood of the late-90s player Poom showed
    // (our own drawing): the player — time and bars, the scrolling title and
    // what the file is, volume and balance, EQ and the list's buttons, the
    // position, the six buttons, shuffle and repeat; then the equalizer and
    // the list, each shown or hidden by its button. Every control is 48dp.

    private var showEq = false
    private var showList = true
    private var remaining = false
    private var selecting = false
    private val selected = HashSet<Int>()
    private var listFilter = ""
    /** The search box shows only when asked for (or while it holds words): five songs fit then. */
    private var searching = false
    private var confirmClear = false
    private var sorting = false
    /** While a slider is dragged, the title line says its value, as the reference's did. */
    private var sliderNote: String? = null
    private var eqNote: String? = null

    private var titleLine: TextView? = null
    private var kbpsView: TextView? = null
    private var khzView: TextView? = null
    private var monoView: TextView? = null
    private var stereoView: TextView? = null
    private var tagsLine: TextView? = null
    private var coverView: ImageView? = null
    private var coverFor: Any? = null
    private var eqToggle: TextView? = null
    private var plToggle: TextView? = null
    private var volumeSeek: SeekBar? = null
    private var balanceSeek: SeekBar? = null
    private var eqView: EqView? = null
    private var eqCurve: EqCurveView? = null
    private var eqOnToggle: TextView? = null
    private var eqNoteView: TextView? = null
    private var listAdapter: QueueAdapter? = null
    private var listTitle: TextView? = null
    private var listTools: LinearLayout? = null

    private fun showNow() {
        drawnFor = MusicPlayer.queue.tracks
        val page = column()
        page.addView(playerWindow(), LinearLayout.LayoutParams(MATCH, WRAP))
        if (showEq) page.addView(eqWindow(), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        if (showList) page.addView(listWindow(), LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        setPage(page)
        refreshNow()
    }

    /** The player: read-outs, sliders, buttons. */
    private fun playerWindow(): View {
        val body = column().apply {
            background = AmpSkin.body(this@MusicActivity)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        // Row 1: time and bars at the left; title, file facts, tags and cover at the right.
        val top = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val left = column().apply {
            background = AmpSkin.lcd(this@MusicActivity)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        timeView = TextView(this).apply {
            typeface = pixel; textSize = UiScale.AMP_TIME; setTextColor(color(R.color.retro_lcd))
            gravity = Gravity.END; maxLines = 1
            // Tap the time: elapsed or remaining, like the reference.
            isClickable = true
            setOnClickListener { remaining = !remaining; refreshTime() }
        }
        left.addView(timeView, LinearLayout.LayoutParams(MATCH, WRAP))
        left.addView(SpectrumView(this), LinearLayout.LayoutParams(MATCH, dp(UiScale.SPECTRUM_H)).apply { topMargin = dp(UiScale.SPACE_XS) })
        top.addView(left, LinearLayout.LayoutParams(dp(UiScale.AMP_LCD_W), MATCH))

        val right = column()
        titleLine = TextView(this).apply {
            typeface = thai; textSize = UiScale.TEXT_BASE; setTextColor(color(R.color.retro_lcd))
            background = AmpSkin.lcd(this@MusicActivity)
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
            maxLines = 1; setHorizontallyScrolling(true)
            if (AmpSkin.calm(this@MusicActivity)) ellipsize = TextUtils.TruncateAt.END
            else { ellipsize = TextUtils.TruncateAt.MARQUEE; marqueeRepeatLimit = -1; isSelected = true }
        }
        right.addView(titleLine, LinearLayout.LayoutParams(MATCH, dp(UiScale.AMP_LINE)))
        val facts = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        kbpsView = lcdBox(); khzView = lcdBox()
        facts.addView(kbpsView, LinearLayout.LayoutParams(WRAP, dp(UiScale.AMP_LINE)))
        facts.addView(ampText(getString(R.string.music_kbps)).apply { setPadding(dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_S), 0) })
        facts.addView(khzView, LinearLayout.LayoutParams(WRAP, dp(UiScale.AMP_LINE)))
        facts.addView(ampText(getString(R.string.music_khz)).apply { setPadding(dp(UiScale.SPACE_XS), 0, 0, 0) })
        right.addView(facts, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        // mono / stereo under the bars, in the black read-out: beside kHz they ran off the
        // edge, and on a line of their own they cost the list a song (Poom: five songs showing).
        val channels = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        monoView = ampText(getString(R.string.music_mono)); stereoView = ampText(getString(R.string.music_stereo))
        channels.addView(monoView)
        channels.addView(stereoView, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(UiScale.SPACE_S) })
        left.addView(channels, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        val tagsRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        coverView = ImageView(this).apply {
            scaleType = ImageView.ScaleType.CENTER_CROP; contentDescription = getString(R.string.music_cover); visibility = View.GONE
        }
        tagsRow.addView(coverView, LinearLayout.LayoutParams(dp(UiScale.ICON_L), dp(UiScale.ICON_L)).apply { marginEnd = dp(UiScale.SPACE_S) })
        tagsLine = ampText("").apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END }
        tagsRow.addView(tagsLine, LinearLayout.LayoutParams(0, WRAP, 1f))
        right.addView(tagsRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        top.addView(right, LinearLayout.LayoutParams(0, WRAP, 1f).apply { marginStart = dp(UiScale.SPACE_S) })
        body.addView(top, LinearLayout.LayoutParams(MATCH, WRAP))

        // Row 2: volume, balance, and the two windows' buttons.
        val sliders = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        volumeSeek = ampSeek(R.color.amp_gold).apply {
            max = 100; progress = (MusicPlayer.volume * 100).toInt(); contentDescription = getString(R.string.music_volume_slider)
            setOnSeekBarChangeListener(slider({ v ->
                MusicPlayer.setVolume(this@MusicActivity, v / 100f)
                getString(R.string.music_volume, v) }))
        }
        sliders.addView(volumeSeek, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 3f))
        balanceSeek = ampSeek(R.color.retro_lcd).apply {
            max = 200; progress = ((MusicPlayer.balance + 1) * 100).toInt(); contentDescription = getString(R.string.music_balance_slider)
            setOnSeekBarChangeListener(slider({ v ->
                MusicPlayer.setBalance(this@MusicActivity, (v - 100) / 100f)
                balanceWords(MusicPlayer.balance) }, done = { progress = ((MusicPlayer.balance + 1) * 100).toInt() }))
        }
        sliders.addView(balanceSeek, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 2f).apply { marginStart = dp(UiScale.SPACE_XS) })
        // The equalizer and the list share the space under the player, one at a
        // time: both at once left the list a title bar high on this screen.
        eqToggle = led(getString(R.string.music_eq_button), showEq) { showEq = !showEq; if (showEq) showList = false; showNow() }
        plToggle = led(getString(R.string.music_pl_button), showList) { showList = !showList; if (showList) showEq = false; showNow() }
        sliders.addView(eqToggle, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_XS) })
        sliders.addView(plToggle, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_XS) })
        body.addView(sliders, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })

        // Row 3: where in the song, full width, to drag.
        seek = ampSeek(R.color.amp_gold).apply {
            contentDescription = getString(R.string.music_seek_slider)
            setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
                    if (fromUser) { sliderNote = getString(R.string.music_seek_to, clock(value.toLong())); titleLine?.text = sliderNote }
                }
                override fun onStartTrackingTouch(bar: SeekBar) { seeking = true }
                override fun onStopTrackingTouch(bar: SeekBar) {
                    seeking = false; sliderNote = null
                    MusicPlayer.seekTo(this@MusicActivity, bar.progress.toLong())
                }
            })
        }
        body.addView(seek, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })

        // Row 4: back, play, pause, stop, next, open — each with its word.
        val controls = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        controls.addView(control(R.drawable.ic_pixel_prev, R.string.music_prev) { MusicPlayer.previous(this) }, tight(0))
        controls.addView(control(R.drawable.ic_pixel_play, R.string.music_play) { MusicPlayer.resume(this) }, tight(1))
        controls.addView(control(R.drawable.ic_pixel_pause, R.string.music_pause_short) { MusicPlayer.pause(this) }, tight(1))
        controls.addView(control(R.drawable.ic_pixel_stop, R.string.music_stop) { MusicPlayer.stop(this) }, tight(1))
        controls.addView(control(R.drawable.ic_pixel_next, R.string.music_next) { MusicPlayer.next(this) }, tight(1))
        controls.addView(control(R.drawable.ic_pixel_eject, R.string.music_open) { addMode = true; show(Tab.LIBRARY) }, tight(1))
        body.addView(controls, LinearLayout.LayoutParams(MATCH, dp(UiScale.ICON_BUTTON)).apply { topMargin = dp(UiScale.SPACE_XS) })

        // Row 5: shuffle and repeat, lit when on.
        val modes = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        shuffleView = led(getString(R.string.music_shuffle), false) {
            MusicPlayer.setShuffle(this, !MusicPlayer.queue.shuffle)
        }
        repeatView = led(getString(R.string.music_repeat_off_short), false) { MusicPlayer.cycleRepeat(this) }
        modes.addView(shuffleView, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f))
        modes.addView(repeatView, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { marginStart = dp(UiScale.SPACE_XS) })
        body.addView(modes, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })

        errorView = ampText("").apply { setTextColor(color(R.color.amp_bar_high)); visibility = View.GONE }
        body.addView(errorView)
        return body
    }

    /** The equalizer: on, the curve, presets; then the eleven sliders. */
    private fun eqWindow(): View {
        val body = column().apply {
            background = AmpSkin.body(this@MusicActivity)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        body.addView(windowTitle(getString(R.string.music_eq_title)))
        val head = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        eqOnToggle = led(getString(R.string.music_eq_on), MusicPlayer.eq.on) {
            MusicPlayer.setEq(this, MusicPlayer.eq.copy(on = !MusicPlayer.eq.on))
        }
        head.addView(eqOnToggle, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        eqCurve = EqCurveView(this).apply { background = AmpSkin.lcd(this@MusicActivity) }
        head.addView(eqCurve, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply {
            marginStart = dp(UiScale.SPACE_S); marginEnd = dp(UiScale.SPACE_S) })
        head.addView(ampButton(getString(R.string.music_eq_presets) + " ▼") { choosePreset() },
                     LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
        body.addView(head, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        eqView = EqView(this, onChange = { MusicPlayer.setEq(this, it) },
                        onMove = { eqNote = it; eqNoteView?.text = it ?: eqStatus() })
        body.addView(eqView, LinearLayout.LayoutParams(MATCH, dp(UiScale.EQ_H)).apply { topMargin = dp(UiScale.SPACE_XS) })
        eqNoteView = ampText("")
        body.addView(eqNoteView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        return body
    }

    private fun eqStatus(): String {
        val s = MusicPlayer.eq
        return when {
            !s.on -> getString(R.string.music_eq_off_note)
            MusicPlayer.hasMedia && !MusicPlayer.fx.working -> getString(R.string.music_eq_not_working)
            else -> getString(R.string.music_eq_presets) + ": " + s.preset.ifEmpty { getString(R.string.music_eq_custom) }
        }
    }

    /** The presets, in a menu under the note: one tap sets all eleven sliders and turns the equalizer on. */
    private fun choosePreset() {
        val names = Eq.PRESETS.keys.toList()
        val menu = android.widget.PopupMenu(this, eqNoteView ?: return)
        names.forEachIndexed { i, n -> menu.menu.add(0, i, i, n) }
        menu.setOnMenuItemClickListener { item ->
            MusicPlayer.setEq(this, Eq.preset(names[item.itemId], on = true)); true
        }
        menu.show()
    }

    /** The list: numbered, lengths at the right, the one playing lit; search, and the edit buttons. */
    private fun listWindow(): View {
        val body = column().apply {
            background = AmpSkin.body(this@MusicActivity)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        listTitle = windowTitle("")
        body.addView(listTitle)
        if (MusicPlayer.queue.isEmpty) {
            body.addView(ampText(getString(R.string.music_queue_empty)).apply { setPadding(0, dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_S)) })
            body.addView(ampButton(getString(R.string.music_go_library)) { addMode = true; show(Tab.LIBRARY) },
                         LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)))
            return body
        }
        val search = EditText(this).apply {
            typeface = thai; textSize = UiScale.TEXT_BASE; setSingleLine(); hint = getString(R.string.music_list_search)
            setText(listFilter)
            imeOptions = EditorInfo.IME_ACTION_SEARCH
            background = AmpSkin.lcd(this@MusicActivity); setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
            setTextColor(color(R.color.retro_lcd)); setHintTextColor(color(R.color.retro_lcd_dim))
            addTextChangedListener(object : TextWatcher {
                override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) = Unit
                override fun afterTextChanged(s: Editable?) { listFilter = s.toString(); listAdapter?.refilter() }
            })
        }
        if (searching || listFilter.isNotEmpty()) {
            body.addView(search, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
            if (searching && listFilter.isEmpty()) search.post { search.requestFocus() }
        }
        val adapter = QueueAdapter()
        listAdapter = adapter
        body.addView(ListView(this).apply {
            this.adapter = adapter
            background = AmpSkin.lcd(this@MusicActivity)
            divider = null
            setOnItemClickListener { _, _, position, _ ->
                val index = adapter.shown[position]
                if (selecting) { if (!selected.add(index)) selected.remove(index); refreshTools(); adapter.notifyDataSetChanged() }
                else MusicPlayer.jumpTo(this@MusicActivity, index)
            }
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
        listTools = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        body.addView(listTools, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
        refreshTools()
        return body
    }

    /** The row of list buttons: the usual five, or the choices of whatever is under way. */
    private fun refreshTools() {
        val row = listTools ?: return
        row.removeAllViews()
        fun add(word: String, onClick: () -> Unit) = row.addView(ampButton(word, onClick),
            LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { if (row.childCount > 0) marginStart = dp(UiScale.SPACE_XS) })
        when {
            confirmClear -> {
                row.addView(ampText(getString(R.string.music_clear_ask)).apply { maxLines = 2 }, LinearLayout.LayoutParams(0, WRAP, 2f))
                add(getString(R.string.music_list_clear)) { confirmClear = false; selected.clear(); MusicPlayer.clear(this); showNow() }
                add(getString(R.string.music_cancel)) { confirmClear = false; refreshTools() }
            }
            sorting -> {
                add(getString(R.string.music_sort_title)) { sort(compareBy { MusicLibrary.key(it.title) }) }
                add(getString(R.string.music_sort_artist)) {
                    sort(compareBy<Track> { MusicLibrary.key(it.artist) }.thenBy { MusicLibrary.key(it.title) })
                }
                add(getString(R.string.music_sort_length)) { sort(compareBy { it.durationMs }) }
                add(getString(R.string.music_cancel)) { sorting = false; refreshTools() }
            }
            selecting -> {
                add(getString(R.string.music_list_select_all)) {
                    selected.addAll(MusicPlayer.queue.tracks.indices); listAdapter?.notifyDataSetChanged(); refreshTools()
                }
                add(getString(R.string.music_list_select_none)) { selected.clear(); listAdapter?.notifyDataSetChanged(); refreshTools() }
                add(getString(R.string.music_list_remove_selected, selected.size)) {
                    if (selected.isEmpty()) { titleLine?.text = getString(R.string.music_list_pick_first) }
                    else { MusicPlayer.remove(this, selected.toSet()); selected.clear(); selecting = false; showNow() }
                }
                add(getString(R.string.music_list_done)) {
                    selecting = false; selected.clear(); listAdapter?.notifyDataSetChanged(); refreshTools()
                }
            }
            else -> {
                add(getString(R.string.music_list_add)) { addMode = true; show(Tab.LIBRARY) }
                add(getString(R.string.music_list_remove)) { selecting = true; refreshTools(); listAdapter?.notifyDataSetChanged() }
                add(getString(if (searching || listFilter.isNotEmpty()) R.string.music_list_search_close else R.string.music_list_search_button)) {
                    if (searching || listFilter.isNotEmpty()) { searching = false; listFilter = "" } else searching = true
                    showNow()
                }
                add(getString(R.string.music_list_sort)) { sorting = true; refreshTools() }
                add(getString(R.string.music_list_clear)) { confirmClear = true; refreshTools() }
            }
        }
    }

    private fun sort(by: Comparator<Track>) {
        sorting = false
        selected.clear()
        MusicPlayer.sort(this, by)
        showNow()
    }

    private fun refreshNow() {
        val q = MusicPlayer.queue
        // A song chosen in the library sets the list a moment after this page
        // was drawn (the command runs on the main thread's next turn).
        if (drawnFor !== q.tracks) { showNow(); return }
        val track = q.current
        val tags = MusicPlayer.tags
        val title = tags?.title?.toString()?.ifBlank { null } ?: track?.title
        val artist = tags?.artist?.toString()?.ifBlank { null } ?: track?.artist.orEmpty()
        val album = tags?.albumTitle?.toString()?.ifBlank { null } ?: track?.album.orEmpty()
        val length = MusicPlayer.durationMs.takeIf { it > 0 } ?: track?.durationMs ?: 0
        titleLine?.text = sliderNote ?: if (track == null) "—" else
            "${q.currentIndex + 1}. " + listOf(artist, title.orEmpty()).filter { it.isNotEmpty() }.joinToString(" - ") +
                (if (length > 0) " (${clock(length)})" else "")
        tagsLine?.text = listOf(artist, album).filter { it.isNotEmpty() }.joinToString(" · ")
        // The cover the file carries, if it has one.
        val art = tags?.artworkData
        if (art !== coverFor) {
            coverFor = art
            val bmp = art?.let { runCatching { android.graphics.BitmapFactory.decodeByteArray(it, 0, it.size) }.getOrNull() }
            coverView?.setImageBitmap(bmp)
            coverView?.visibility = if (bmp == null) View.GONE else View.VISIBLE
        }
        val info = MusicPlayer.fileInfo()
        kbpsView?.text = info.kbps?.toString() ?: "—"
        khzView?.text = info.khz?.toString() ?: "—"
        lit(monoView, info.channels == 1)
        lit(stereoView, info.channels != null && info.channels >= 2)
        ledState(shuffleView, q.shuffle, getString(R.string.music_shuffle))
        ledState(repeatView, q.repeat != PlayQueue.Repeat.OFF, getString(when (q.repeat) {
            PlayQueue.Repeat.OFF -> R.string.music_repeat_off_short
            PlayQueue.Repeat.ALL -> R.string.music_repeat_all_short
            PlayQueue.Repeat.ONE -> R.string.music_repeat_one_short
        }))
        ledState(eqToggle, showEq, getString(R.string.music_eq_button))
        ledState(plToggle, showList, getString(R.string.music_pl_button))
        eqView?.let { if (it.settings != MusicPlayer.eq) it.settings = MusicPlayer.eq }
        eqCurve?.settings = MusicPlayer.eq
        ledState(eqOnToggle, MusicPlayer.eq.on, getString(R.string.music_eq_on))
        if (eqNote == null) eqNoteView?.text = eqStatus()
        errorView?.apply {
            text = MusicPlayer.error.orEmpty()
            visibility = if (MusicPlayer.error == null) View.GONE else View.VISIBLE
        }
        listTitle?.text = getString(R.string.music_list_title, q.tracks.size)
        listAdapter?.refilter()
        refreshTime()
    }

    private fun refreshTime() {
        val duration = MusicPlayer.durationMs
        seek?.apply {
            max = duration.toInt().coerceAtLeast(1)
            if (!seeking) progress = MusicPlayer.positionMs.toInt()
            isEnabled = MusicPlayer.hasMedia
        }
        if (seeking) return
        val pos = MusicPlayer.positionMs
        timeView?.text = when {
            !MusicPlayer.hasMedia -> "0:00"
            remaining && duration > 0 -> "-" + clock(duration - pos)
            else -> clock(pos)
        }
    }

    private fun balanceWords(b: Float): String = when {
        b == 0f -> getString(R.string.music_balance_center)
        b < 0 -> getString(R.string.music_balance_left, (-b * 100).toInt())
        else -> getString(R.string.music_balance_right, (b * 100).toInt())
    }

    /** A slider that says its value on the title line while it moves. */
    private fun slider(onValue: (Int) -> String, done: SeekBar.() -> Unit = {}) = object : SeekBar.OnSeekBarChangeListener {
        override fun onProgressChanged(bar: SeekBar, value: Int, fromUser: Boolean) {
            if (fromUser) { sliderNote = onValue(value); titleLine?.text = sliderNote }
        }
        override fun onStartTrackingTouch(bar: SeekBar) = Unit
        override fun onStopTrackingTouch(bar: SeekBar) { sliderNote = null; bar.done(); refreshNow() }
    }

    /** The list's rows: "12. Artist - Title" and the length at the right; the one playing lit. */
    private inner class QueueAdapter : BaseAdapter() {
        var shown: List<Int> = emptyList()
        fun refilter() {
            val q = MusicLibrary.key(listFilter)
            val tracks = MusicPlayer.queue.tracks
            shown = tracks.indices.filter {
                q.isEmpty() || MusicLibrary.key(tracks[it].title + tracks[it].artist + tracks[it].album).contains(q)
            }
            notifyDataSetChanged()
        }
        override fun getCount() = shown.size
        override fun getItem(position: Int) = shown[position]
        override fun getItemId(position: Int) = shown[position].toLong()
        override fun getView(position: Int, convert: View?, parent: ViewGroup?): View {
            val row = (convert as? LinearLayout) ?: LinearLayout(this@MusicActivity).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                minimumHeight = dp(UiScale.TOUCH)
                setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
                addView(TextView(context).apply { typeface = thai; textSize = UiScale.TEXT_BASE; maxLines = 1; ellipsize = TextUtils.TruncateAt.END },
                        LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(TextView(context).apply { typeface = thai; textSize = UiScale.TEXT_BASE; setPadding(dp(UiScale.SPACE_S), 0, 0, 0) })
                layoutParams = ViewGroup.LayoutParams(MATCH, WRAP)
            }
            val index = shown[position]
            val t = MusicPlayer.queue.tracks[index]
            val now = index == MusicPlayer.queue.currentIndex
            val mark = when { selecting && index in selected -> "■ "; selecting -> "□ "; now -> "► "; else -> "" }
            (row.getChildAt(0) as TextView).apply {
                text = mark + "${index + 1}. " + listOf(t.artist, t.title).filter { it.isNotEmpty() }.joinToString(" - ")
                setTextColor(color(if (now) R.color.amp_text else R.color.retro_lcd))
                typeface = Typeface.create(thai, if (now) Typeface.BOLD else Typeface.NORMAL)
            }
            (row.getChildAt(1) as TextView).apply {
                text = if (t.durationMs > 0) clock(t.durationMs) else ""
                setTextColor(color(if (now) R.color.amp_text else R.color.retro_lcd))
            }
            row.setBackgroundColor(if (now || (selecting && index in selected)) color(R.color.amp_select) else 0)
            row.contentDescription = "${index + 1}. ${t.title}" + when {
                selecting && index in selected -> " (เลือกอยู่)"; now -> " (กำลังเล่น)"; else -> "" }
            return row
        }
    }

    // ------------------------------------------------------------ the skin's parts

    private fun ampText(value: String) = TextView(this).apply {
        text = value; typeface = thai; textSize = UiScale.TEXT_NOTE; setTextColor(color(R.color.amp_text))
    }

    private fun lcdBox() = TextView(this).apply {
        typeface = pixel; textSize = UiScale.TEXT_NOTE; setTextColor(color(R.color.retro_lcd))
        background = AmpSkin.lcd(this@MusicActivity); gravity = Gravity.CENTER
        setPadding(dp(UiScale.SPACE_XS), 0, dp(UiScale.SPACE_XS), 0)
        minWidth = dp(UiScale.AMP_FACT_W)
    }

    /** "mono" / "stereo": bright when it is this file's, dim when not. */
    private fun lit(view: TextView?, on: Boolean) {
        view?.setTextColor(color(if (on) R.color.retro_lcd else R.color.amp_led_off))
        view?.typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
    }

    private fun windowTitle(value: String) = TextView(this).apply {
        text = value; typeface = Typeface.create(thai, Typeface.BOLD); textSize = UiScale.TEXT_NOTE
        setTextColor(color(R.color.amp_text)); gravity = Gravity.CENTER
    }

    private fun ampButton(value: String, onClick: () -> Unit) = TextView(this).apply {
        text = value; typeface = Typeface.create(thai, Typeface.BOLD); textSize = UiScale.TEXT_BASE
        setTextColor(color(R.color.amp_text)); gravity = Gravity.CENTER; maxLines = 1
        background = AmpSkin.button(this@MusicActivity)
        setPadding(dp(UiScale.SPACE_S), 0, dp(UiScale.SPACE_S), 0)
        minWidth = dp(UiScale.TOUCH)
        isClickable = true; setOnClickListener { onClick() }
    }

    /** A button with a small light in it: green when on, dark when off, and it says so to a screen reader. */
    private fun led(value: String, on: Boolean, onClick: () -> Unit) = ampButton(value, onClick).also { ledState(it, on, value) }

    private fun ledState(view: TextView?, on: Boolean, value: String) {
        view ?: return
        val dot = android.text.SpannableString("■ $value")
        dot.setSpan(android.text.style.ForegroundColorSpan(color(if (on) R.color.retro_lcd else R.color.amp_led_off)), 0, 1, 0)
        view.text = dot
        view.contentDescription = value + if (on) " (เปิดอยู่)" else " (ปิดอยู่)"
    }

    /** A slider in the skin: dark track, [fillColor] to the thumb, a raised metal thumb. */
    private fun ampSeek(fillColor: Int) = SeekBar(this).apply {
        val track = GradientDrawable().apply { setColor(color(R.color.amp_dark)); setStroke(dp(UiScale.HAIRLINE), color(R.color.amp_light)) }
        val fill = ClipDrawable(GradientDrawable().apply { setColor(color(fillColor)) }, Gravity.START, ClipDrawable.HORIZONTAL)
        progressDrawable = LayerDrawable(arrayOf(track, fill)).apply {
            setId(0, android.R.id.background); setId(1, android.R.id.progress)
        }
        thumb = GradientDrawable().apply {
            setColor(color(R.color.amp_text)); setStroke(dp(UiScale.BEVEL), color(R.color.amp_dark)); setSize(dp(UiScale.THUMB_W), dp(UiScale.THUMB_H))
        }
        splitTrack = false
        minimumHeight = dp(UiScale.SPACE_M)
        setPadding(dp(UiScale.SPACE_M), 0, dp(UiScale.SPACE_M), 0)
    }

    private fun tight(index: Int) =
        LinearLayout.LayoutParams(0, MATCH, 1f).apply { if (index > 0) marginStart = dp(UiScale.SPACE_XS) }

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
        if (addMode) {
            // Adding to the list: said at the top, with the way back.
            page.addView(LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL
                addView(label(getString(R.string.music_add_mode)), LinearLayout.LayoutParams(0, WRAP, 1f))
                addView(button(getString(R.string.music_list_done)) { addMode = false; show(Tab.NOW) },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
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
        val playAll = button(getString(if (addMode) R.string.music_add_all_shown else R.string.music_play_all)) {
            if (shown.isEmpty()) return@button
            if (addMode) { MusicPlayer.add(this, shown); addMode = false; show(Tab.NOW) }
            else { MusicPlayer.play(this, shown); show(Tab.NOW) }
        }
        page.addView(playAll, LinearLayout.LayoutParams(MATCH, dp(UiScale.TOUCH)).apply { topMargin = dp(UiScale.SPACE_XS) })
        page.addView(list(adapter) { index ->
            if (addMode) { MusicPlayer.add(this, listOf(shown[index])); status.text = getString(R.string.music_added, 1) }
            else { MusicPlayer.play(this, shown, index); show(Tab.NOW) }
        }, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_XS) })
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
                MusicLibrary.playable(entry.name) && addMode -> {
                    MusicPlayer.add(this, listOf(nasTrack(entry))); status.text = getString(R.string.music_added, 1)
                }
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

    /** A player button in the skin: the picture, and its word under it. */
    private fun control(icon: Int, word: Int, onClick: () -> Unit) = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        gravity = Gravity.CENTER
        background = AmpSkin.button(this@MusicActivity)
        isClickable = true
        contentDescription = getString(word)
        setOnClickListener { onClick() }
        addView(ImageView(context).apply {
            setImageResource(icon)
            imageTintList = android.content.res.ColorStateList.valueOf(color(R.color.amp_text))
        }, LinearLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M)))
        addView(TextView(context).apply {
            text = getString(word); typeface = thai; textSize = UiScale.TEXT_NOTE; setTextColor(color(R.color.amp_text))
            gravity = Gravity.CENTER; maxLines = 1; ellipsize = TextUtils.TruncateAt.END
        })
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
