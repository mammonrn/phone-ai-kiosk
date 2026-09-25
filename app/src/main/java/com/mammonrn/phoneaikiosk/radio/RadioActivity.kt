package com.mammonrn.phoneaikiosk.radio

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.view.Gravity
import android.view.View
import android.widget.EditText
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
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.WRAP
import com.mammonrn.phoneaikiosk.ui.UiScale
import java.util.concurrent.Executors

/**
 * วิทยุ (0.61.0, DESIGN.md 5ฏ), from the Control Panel. The radio itself is in
 * [RadioService]: closing this screen — Back, the X, or Hey Jarvis — never
 * stops it, as with the music.
 *
 * One window: a green-on-black read-out (the station, its state in words, the
 * song when the station says it), one big play/stop button, and two tabs —
 * "สถานี", the list (starred first; tap a station to play it, tap it again to
 * stop), and "ค้นหาสถานีไทย", Radio Browser's Thai stations to add from.
 * Editing the list (rename, change the address, take a station off) is a mode
 * of the list page; taking a station off asks once in the same row. It deletes
 * nothing of anybody's, so there is no identity check.
 */
class RadioActivity : Activity() {

    private lateinit var thai: Typeface
    private lateinit var retro: Retro
    private lateinit var content: FrameLayout
    private lateinit var nameView: TextView
    private lateinit var stateView: TextView
    private lateinit var songView: TextView
    private lateinit var playButton: LinearLayout
    private lateinit var playIcon: ImageView
    private lateinit var playWord: TextView
    private val tabs = ArrayList<TextView>()
    private val handler = Handler(Looper.getMainLooper())
    private val worker = Executors.newSingleThreadExecutor { r -> Thread(r, "kiosk-radio-search") }

    private enum class Tab { LIST, SEARCH }
    private var tab = Tab.LIST
    private var editing = false
    private var confirmRemove: String? = null
    /** The add/edit form is open: for a station's id, or "" for a new one. */
    private var form: String? = null

    // The search, kept while the screen is open: one request per tap on "ค้นหา".
    private var searchWords = ""
    private var searching = false
    private var searchFailed = false
    private var found: List<RadioBrowser.Found>? = null
    private var searchGeneration = 0

    /** What the list's rows show of the player: redrawn only when this changes (not for a new song title). */
    private var drawnFor: Pair<String?, RadioPlayer.State>? = null
    private var listScroll: ScrollView? = null

    private val playerListener: () -> Unit = {
        refreshTop()
        if (tab == Tab.LIST && form == null && drawnFor != (RadioPlayer.stationId to RadioPlayer.state)) showList()
    }
    private val storeListener: () -> Unit = { refreshTop() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        thai = ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT
        retro = Retro(this, thai)
        setContentView(buildWindow())
        hideSystemBars()
        show(Tab.LIST)
    }

    override fun onResume() {
        super.onResume()
        hideSystemBars()
        RadioPlayer.listeners.add(playerListener)
        RadioStore.listeners.add(storeListener)
        refreshTop()
        if (tab == Tab.LIST && form == null) showList()
    }

    override fun onPause() {
        super.onPause()
        RadioPlayer.listeners.remove(playerListener)
        RadioStore.listeners.remove(storeListener)
    }

    override fun onDestroy() {
        worker.shutdownNow()
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** Back: out of the form, then out of editing, then out to the Control Panel. The radio plays on. */
    private fun goBack() {
        when {
            form != null -> { form = null; show(Tab.LIST) }
            editing -> { editing = false; confirmRemove = null; showList() }
            else -> finish()
        }
    }

    /** The close button: back where this screen was opened from (ui/Origin, Poom 2026-09-25). */
    private fun closeApp() {
        com.mammonrn.phoneaikiosk.ui.Origin.close(this, "radio-close")
    }

    private fun goHome() {
        KioskScreens.leaveAllButHome("radio-home")
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
        val root = retro.column().apply {
            setBackgroundColor(retro.color(R.color.retro_desktop))
            setPadding(dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME), dp(UiScale.FRAME))
        }
        val window = retro.column().apply {
            setBackgroundResource(R.drawable.retro_raised)
            setPadding(dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET), dp(UiScale.WINDOW_INSET))
        }
        root.addView(window, LinearLayout.LayoutParams(MATCH, 0, 1f))

        val bar = retro.row().apply {
            setBackgroundResource(R.drawable.retro_titlebar)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
        }
        bar.addView(ImageView(this).apply { setImageResource(R.drawable.ic_pixel_radio_light) },
                    LinearLayout.LayoutParams(dp(UiScale.ICON_S), dp(UiScale.ICON_S)))
        bar.addView(TextView(this).apply {
            text = getString(R.string.window_radio)
            setTextColor(retro.color(R.color.retro_title_text))
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(thai, Typeface.BOLD)
            setPadding(dp(UiScale.SPACE_S), 0, 0, 0)
            maxLines = 1
        }, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(FrameLayout(this).apply {
            setBackgroundResource(R.drawable.retro_button)
            contentDescription = com.mammonrn.phoneaikiosk.ui.Origin.closeWords(this@RadioActivity)
            isClickable = true
            setOnClickListener { closeApp() }
            addView(ImageView(context).apply { setImageResource(R.drawable.ic_pixel_close) },
                    FrameLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M), Gravity.CENTER))
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        window.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP))

        // The read-out: the station, its state in words, the song if the station says it.
        val lcd = retro.column().apply {
            setBackgroundColor(retro.color(R.color.retro_dark))
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S), dp(UiScale.SPACE_S))
        }
        nameView = retro.bold("", UiScale.TEXT_VALUE).apply {
            setTextColor(retro.color(R.color.retro_lcd))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        stateView = retro.text("", UiScale.TEXT_BASE).apply { setTextColor(retro.color(R.color.retro_lcd)) }
        songView = retro.text("", UiScale.TEXT_NOTE).apply {
            setTextColor(retro.color(R.color.retro_lcd_dim))
            maxLines = 1
            ellipsize = TextUtils.TruncateAt.END
        }
        lcd.addView(nameView, LinearLayout.LayoutParams(MATCH, WRAP))
        lcd.addView(stateView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        lcd.addView(songView, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        window.addView(lcd, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.WINDOW_INSET) })

        // One big button: play the chosen station, or stop. Picture and word.
        playIcon = ImageView(this)
        playWord = retro.bold("", UiScale.TEXT_HEADING)
        playButton = retro.row().apply {
            gravity = Gravity.CENTER
            setBackgroundResource(R.drawable.retro_button)
            isClickable = true
            addView(playIcon, LinearLayout.LayoutParams(dp(UiScale.ICON_M), dp(UiScale.ICON_M)))
            addView(playWord, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = dp(UiScale.SPACE_S) })
            setOnClickListener { playOrStop() }
        }
        window.addView(playButton, LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.SPACE_S) })

        val tabRow = retro.row()
        for ((i, t) in Tab.entries.withIndex()) {
            val view = TextView(this).apply {
                text = getString(if (t == Tab.LIST) R.string.radio_tab_list else R.string.radio_tab_search)
                gravity = Gravity.CENTER
                isClickable = true
                setOnClickListener { form = null; show(t) }
            }
            tabs.add(view)
            tabRow.addView(view, LinearLayout.LayoutParams(0, dp(UiScale.TOUCH), 1f).apply { if (i > 0) marginStart = dp(UiScale.SPACE_S) })
        }
        window.addView(tabRow, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        content = FrameLayout(this)
        window.addView(content, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_S) })

        root.addView(retro.button(getString(R.string.settings_home), big = true) { goHome() },
                     LinearLayout.LayoutParams(MATCH, dp(UiScale.PRIMARY)).apply { topMargin = dp(UiScale.FRAME) })
        return root
    }

    private fun show(next: Tab) {
        if (next != tab) listScroll = null
        tab = next
        for ((i, view) in tabs.withIndex()) styleChoice(view, Tab.entries[i] == next)
        if (next == Tab.LIST) showList() else showSearch()
    }

    private fun setPage(view: View) {
        content.removeAllViews()
        content.addView(view, FrameLayout.LayoutParams(MATCH, MATCH))
    }

    // ------------------------------------------------------------ the read-out and the big button

    /** The station the big button plays: the one playing or last played, if it is still on the list. */
    private fun chosen(): Station? {
        val book = RadioStore.book(this)
        val id = RadioPlayer.stationId ?: prefs().getString(LAST, null)
        return id?.let(book::find)
    }

    private fun refreshTop() {
        val station = chosen()
        val state = RadioPlayer.state
        val here = station != null && station.id == RadioPlayer.stationId
        nameView.text = station?.name ?: RadioPlayer.stationName?.takeIf { RadioPlayer.active } ?: getString(R.string.window_radio)
        val words = when {
            station == null && !RadioPlayer.active -> getString(R.string.radio_none_chosen)
            !here -> getString(R.string.radio_state_stopped)
            state == RadioPlayer.State.CONNECTING -> getString(R.string.radio_state_connecting)
            state == RadioPlayer.State.PLAYING -> getString(R.string.radio_state_playing)
            state == RadioPlayer.State.FAILED -> RadioPlayer.error ?: getString(R.string.radio_state_failed)
            else -> getString(R.string.radio_state_stopped)
        }
        stateView.text = words
        // A failure is also said in a colour that stands out on black; the words carry it either way.
        stateView.setTextColor(retro.color(if (here && state == RadioPlayer.State.FAILED) R.color.amp_bar_high else R.color.retro_lcd))
        val song = RadioPlayer.nowTitle?.takeIf { here && state == RadioPlayer.State.PLAYING }
        songView.text = song?.let { getString(R.string.radio_song, it) } ?: ""
        songView.visibility = if (song != null) View.VISIBLE else View.GONE

        val stop = RadioPlayer.active
        playIcon.setImageResource(if (stop) R.drawable.ic_pixel_stop else R.drawable.ic_pixel_play)
        playWord.text = getString(if (stop) R.string.radio_stop else R.string.radio_play)
        val enabled = stop || station != null
        playButton.isEnabled = enabled
        playButton.isClickable = enabled
        playButton.alpha = if (enabled) 1f else DIM
        playButton.contentDescription = playWord.text.toString() + (station?.let { " " + it.name } ?: "")
    }

    private fun playOrStop() {
        if (RadioPlayer.active) { RadioPlayer.stop(this); return }
        chosen()?.let(::play)
    }

    private fun play(station: Station) {
        prefs().edit().putString(LAST, station.id).apply()
        RadioPlayer.play(this, station)
    }

    // ------------------------------------------------------------ "สถานี"

    private fun showList() {
        if (form != null) return
        drawnFor = RadioPlayer.stationId to RadioPlayer.state
        // Redrawn in place: the list stays where it was scrolled to.
        val keepY = listScroll?.scrollY ?: 0
        val book = RadioStore.book(this)
        val page = retro.column()
        page.addView(retro.pair(getString(if (editing) R.string.radio_edit_done else R.string.radio_edit_list),
                                { editing = !editing; confirmRemove = null; showList() },
                                getString(R.string.radio_add_own), { showForm("") }),
                     LinearLayout.LayoutParams(MATCH, WRAP))
        val list = retro.column()
        val stations = book.ordered()
        if (stations.isEmpty()) {
            list.addView(retro.text(getString(R.string.radio_list_empty), UiScale.TEXT_BASE).apply {
                setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_L), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_L))
            })
        }
        for (station in stations) {
            list.addView(stationRow(station), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        listScroll = scroll
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_S) })
        setPage(page)
        if (keepY > 0) scroll.post { scroll.scrollTo(0, keepY) }
    }

    private fun stationRow(station: Station): View {
        val current = station.id == RadioPlayer.stationId && RadioPlayer.state != RadioPlayer.State.STOPPED
        val column = retro.column()
        val row = retro.row().apply {
            minimumHeight = dp(UiScale.ROW)
            if (current) setBackgroundColor(retro.color(R.color.retro_title))
            else setBackgroundResource(R.drawable.retro_button)
        }
        // ★ / ☆: a 48dp target of its own, with its meaning in words for the screen reader.
        row.addView(TextView(this).apply {
            text = if (station.favourite) "★" else "☆"
            textSize = UiScale.TEXT_VALUE
            gravity = Gravity.CENTER
            setTextColor(retro.color(if (current) R.color.retro_badge else R.color.retro_text))
            contentDescription = getString(if (station.favourite) R.string.radio_star_on else R.string.radio_star_off) +
                " " + station.name
            isClickable = true
            setOnClickListener { RadioStore.edit(this@RadioActivity) { it.star(station.id, !station.favourite) }; showList() }
        }, LinearLayout.LayoutParams(dp(UiScale.TOUCH), dp(UiScale.TOUCH)))
        val words = retro.column()
        words.addView(retro.text((if (current) "► " else "") + station.name, UiScale.TEXT_ITEM).apply {
            typeface = Typeface.create(thai, if (current) Typeface.BOLD else Typeface.NORMAL)
            if (current) setTextColor(retro.color(R.color.retro_title_text))
            maxLines = 2
            ellipsize = TextUtils.TruncateAt.END
        })
        val note = when {
            current && RadioPlayer.state == RadioPlayer.State.CONNECTING -> getString(R.string.radio_state_connecting)
            current && RadioPlayer.state == RadioPlayer.State.PLAYING -> getString(R.string.radio_state_playing)
            current && RadioPlayer.state == RadioPlayer.State.FAILED -> getString(R.string.radio_state_failed)
            StreamKind.plainBlocked(station.url) -> getString(R.string.radio_plain_note)
            else -> null
        }
        if (note != null) words.addView(retro.text(note, UiScale.TEXT_NOTE, dim = !current).apply {
            if (current) setTextColor(retro.color(R.color.retro_title_text))
        })
        words.setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS))
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (editing) {
            row.addView(retro.button(getString(R.string.radio_rename)) { showForm(station.id) },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)))
            row.addView(retro.button(getString(R.string.radio_remove)) { confirmRemove = station.id; showList() },
                        LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        } else {
            row.isClickable = true
            row.contentDescription = station.name + if (station.favourite) " " + getString(R.string.radio_starred) else ""
            row.setOnClickListener {
                prefs().edit().putString(LAST, station.id).apply()
                RadioPlayer.toggle(this, station)
            }
        }
        column.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        if (editing && confirmRemove == station.id) {
            column.addView(retro.text(getString(R.string.radio_remove_confirm, station.name), UiScale.TEXT_BASE).apply {
                setPadding(dp(UiScale.SPACE_XS), dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS))
            })
            column.addView(retro.pair(getString(R.string.radio_remove), {
                if (RadioPlayer.stationId == station.id && RadioPlayer.active) RadioPlayer.stop(this)
                RadioStore.edit(this) { it.remove(station.id) }
                confirmRemove = null
                showList()
            }, getString(R.string.radio_cancel), { confirmRemove = null; showList() }), LinearLayout.LayoutParams(MATCH, WRAP))
        }
        return column
    }

    // ------------------------------------------------------------ add or edit one station

    private fun showForm(id: String) {
        form = id
        val old = if (id.isEmpty()) null else RadioStore.book(this).find(id)
        val page = retro.column()
        page.addView(retro.bold(getString(if (old == null) R.string.radio_add_title else R.string.radio_edit_title),
                                UiScale.TEXT_HEADING))
        page.addView(retro.label(getString(R.string.radio_name)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        val name = retro.field(old?.name.orEmpty())
        page.addView(name, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        page.addView(retro.label(getString(R.string.radio_url)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_M) })
        val url = retro.field(old?.url.orEmpty()).apply {
            inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_URI
        }
        page.addView(url, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        page.addView(retro.text(getString(R.string.radio_url_hint), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        val error = retro.errorLine()
        page.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })
        page.addView(retro.pair(getString(R.string.radio_save), { save(old, name, url, error) },
                                getString(R.string.radio_cancel), { retro.hideKeyboard(url); form = null; show(Tab.LIST) },
                                big = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_L) })
        setPage(ScrollView(this).apply { addView(page) })
    }

    private fun save(old: Station?, name: EditText, url: EditText, error: TextView) {
        val n = name.text.toString()
        val u = url.text.toString()
        val result = RadioStore.edit(this) { book ->
            if (old == null) book.add(n, u, RadioStore.newId()) else book.edit(old.id, n, u)
        }
        val why = result.refusal
        if (why != null) {
            error.text = getString(refusalWords(why))
            error.visibility = View.VISIBLE
            return
        }
        retro.hideKeyboard(url)
        form = null
        show(Tab.LIST)
        if (StreamKind.plainBlocked(u)) android.widget.Toast.makeText(this, R.string.radio_plain_warning, android.widget.Toast.LENGTH_LONG).show()
    }

    private fun refusalWords(why: RadioBook.Refusal): Int = when (why) {
        RadioBook.Refusal.NAME_EMPTY -> R.string.radio_refuse_name_empty
        RadioBook.Refusal.NAME_LONG -> R.string.radio_refuse_name_long
        RadioBook.Refusal.URL_BAD -> R.string.radio_refuse_url_bad
        RadioBook.Refusal.URL_TOO_LONG -> R.string.radio_refuse_url_long
        RadioBook.Refusal.DUPLICATE -> R.string.radio_refuse_duplicate
        RadioBook.Refusal.FULL -> R.string.radio_refuse_full
        RadioBook.Refusal.MISSING -> R.string.radio_refuse_missing
    }

    // ------------------------------------------------------------ "ค้นหาสถานีไทย"

    private fun showSearch() {
        val keepY = listScroll?.scrollY ?: 0
        val page = retro.column()
        page.addView(retro.text(getString(R.string.radio_search_intro), UiScale.TEXT_NOTE, dim = true))
        val bar = retro.row()
        val words = retro.field(searchWords).apply { hint = getString(R.string.radio_search_words) }
        bar.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        bar.addView(retro.button(getString(R.string.radio_search_go), enabled = !searching) {
            searchWords = words.text.toString()
            retro.hideKeyboard(words)
            search()
        }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        words.setOnEditorActionListener { _, _, _ -> searchWords = words.text.toString(); retro.hideKeyboard(words); search(); true }
        page.addView(bar, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })

        val results = found
        val status = when {
            searching -> getString(R.string.radio_searching)
            searchFailed -> getString(R.string.radio_search_failed)
            results == null -> null
            results.isEmpty() -> getString(R.string.radio_search_none)
            else -> getString(R.string.radio_search_count, results.count { it.secure }) +
                results.count { !it.secure }.takeIf { it > 0 }?.let { " · " + getString(R.string.radio_search_hidden, it) }.orEmpty()
        }
        if (status != null) page.addView(retro.text(status, UiScale.TEXT_NOTE).apply {
            if (searchFailed) setTextColor(retro.color(R.color.retro_bad))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_S) })

        val list = retro.column()
        val book = RadioStore.book(this)
        for (f in results.orEmpty().filter { it.secure }) {
            list.addView(foundRow(f, book.has(f.url)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = dp(UiScale.SPACE_XS) })
        }
        val scroll = ScrollView(this).apply { addView(list) }
        listScroll = scroll
        page.addView(scroll, LinearLayout.LayoutParams(MATCH, 0, 1f).apply { topMargin = dp(UiScale.SPACE_S) })
        setPage(page)
        if (keepY > 0 && !searching) scroll.post { scroll.scrollTo(0, keepY) }
        // The first visit searches once by itself; after that only a tap does.
        if (results == null && !searching && !searchFailed) search()
    }

    private fun foundRow(f: RadioBrowser.Found, added: Boolean): View {
        val row = retro.row().apply {
            minimumHeight = dp(UiScale.ROW)
            setBackgroundResource(R.drawable.retro_field)
            setPadding(dp(UiScale.SPACE_S), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS), dp(UiScale.SPACE_XS))
        }
        val words = retro.column()
        words.addView(retro.text(f.name, UiScale.TEXT_ITEM).apply { maxLines = 2; ellipsize = TextUtils.TruncateAt.END })
        val facts = listOfNotNull(f.codec.takeIf { it.isNotEmpty() && it != "UNKNOWN" },
                                  f.bitrate.takeIf { it > 0 }?.let { "$it kbps" },
                                  if (f.hls) "HLS" else null,
                                  getString(R.string.radio_votes, f.votes)).joinToString(" · ")
        words.addView(retro.text(facts, UiScale.TEXT_NOTE, dim = true))
        row.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        if (added) {
            row.addView(retro.text(getString(R.string.radio_added), UiScale.TEXT_NOTE, dim = true))
        } else {
            row.addView(retro.button(getString(R.string.radio_add)) {
                RadioStore.edit(this) { it.add(f.name, f.url, RadioStore.newId()) }
                showSearch()
            }, LinearLayout.LayoutParams(WRAP, dp(UiScale.TOUCH)).apply { marginStart = dp(UiScale.SPACE_S) })
        }
        return row
    }

    private fun search() {
        if (searching) return
        searching = true
        searchFailed = false
        val gen = ++searchGeneration
        val words = searchWords
        listScroll = null
        if (tab == Tab.SEARCH) showSearch()
        worker.execute {
            val result = runCatching { RadioBrowser.search(words) }
            handler.post {
                if (gen != searchGeneration || isDestroyed) return@post
                searching = false
                found = result.getOrNull()
                searchFailed = result.isFailure
                android.util.Log.i(RadioService.TAG, if (result.isSuccess) "search: ${found?.size} found" else "search failed")
                if (tab == Tab.SEARCH && form == null) showSearch()
            }
        }
    }

    // ------------------------------------------------------------ parts

    private fun styleChoice(view: TextView, on: Boolean) = view.apply {
        textSize = UiScale.TEXT_BASE
        typeface = Typeface.create(thai, if (on) Typeface.BOLD else Typeface.NORMAL)
        setTextColor(retro.color(if (on) R.color.retro_title_text else R.color.retro_text))
        if (on) setBackgroundColor(retro.color(R.color.retro_title)) else setBackgroundResource(R.drawable.retro_button)
        contentDescription = text.toString() + if (on) " (เลือกอยู่)" else ""
    }

    private fun prefs() = getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
    }

    private fun dp(value: Int): Int = retro.dp(value)

    companion object {
        private const val PREFS = "radio"
        private const val LAST = "last"
        /** A button that cannot be pressed now. */
        private const val DIM = 0.5f
    }
}
