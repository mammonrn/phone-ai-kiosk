package com.mammonrn.phoneaikiosk.calendar

import android.app.Activity
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.auth.AuthStore
import com.mammonrn.phoneaikiosk.auth.VerifyActivity
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.WRAP
import com.mammonrn.phoneaikiosk.ui.ToolWindow
import com.mammonrn.phoneaikiosk.ui.UiScale
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.TokenStore
import com.mammonrn.phoneaikiosk.voice.VoiceState
import org.json.JSONObject
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId
import java.util.concurrent.Executors

/**
 * ปฏิทิน (0.63.0, Poom 2026-09-25): Poom's Google Calendar on the kiosk — a month
 * with its days marked, a day with its appointments, adding, changing and
 * deleting them — and the Thai holidays from Google's holiday calendar.
 *
 * PRIVATE, AS EVERY CALENDAR REQUEST: the broker holds the Google token and
 * answers only with a live identity grant, so seeing, adding and changing ask
 * for the identity check when the grant is gone, and DELETING always asks for a
 * fresh one (the kiosk's rule for every delete). What was fetched lives in this
 * screen only: closed, it is gone. Nothing of it goes to a model or a log (the
 * log has counts). The year is the Buddhist year.
 *
 * States (ux-ui-design): loading · needs the identity check (and why) · not
 * connected · failed (and what to do) · partly there (the holidays missing, the
 * appointments shown) · an empty day (an invitation to add) · done.
 */
class CalendarActivity : Activity() {

    private enum class Page { MONTH, DAY, EDIT }
    private enum class State { LOADING, VERIFY, NOT_CONNECTED, NO_IDENTITY, ERROR, READY }

    private lateinit var r: Retro
    private lateinit var frame: ToolWindow
    private val main = Handler(Looper.getMainLooper())
    private val net = Executors.newSingleThreadExecutor()

    private var page = Page.MONTH
    private var state = State.LOADING
    private var errorWords = ""
    private var notice = ""
    private var month: YearMonth = YearMonth.now(ZONE)
    private var day: LocalDate = LocalDate.now(ZONE)
    /** What was fetched, while this screen is open — and only then. */
    private val loaded = HashMap<YearMonth, CalendarModel.Month>()

    // The appointment being written: null id = a new one.
    private var editId: String? = null
    private var editTitle = ""
    private var editDate: LocalDate = LocalDate.now(ZONE)
    private var editAllDay = false
    private var editStart = "09:00"
    private var editEnd = "10:00"
    private var editError = ""
    private var saving = false
    private var confirmDelete: String? = null

    /** What to do when the identity check comes back passed. */
    private var afterVerify: (() -> Unit)? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        r = Retro(this, ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT)
        frame = ToolWindow(this, r, R.drawable.ic_pixel_calendar,
            onClose = { Origin.close(this, "calendar-close") }, onHome = { ToolWindow.goHome(this, "calendar-home") })
        frame.title.text = getString(R.string.window_calendar)
        setContentView(frame.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { back() }
        }
        ToolWindow.hideSystemBars(this)
        load(month)
    }

    override fun onResume() {
        super.onResume()
        ToolWindow.hideSystemBars(this)
        // Back from the identity check: the grant is asked for by the voice service as it
        // passes; give it a moment, then do what was waiting.
        afterVerify?.let { next -> afterVerify = null; next() }
    }

    override fun onDestroy() {
        net.shutdownNow()
        loaded.clear()
        super.onDestroy()
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = back()

    private fun back() {
        when (page) {
            Page.EDIT -> { page = if (editId != null || editDate == day) Page.DAY else Page.MONTH; draw() }
            Page.DAY -> { page = Page.MONTH; draw() }
            Page.MONTH -> Origin.close(this, "calendar-back")
        }
    }

    // ------------------------------------------------------------ the broker

    private fun broker(): Broker? = TokenStore(this).token()?.let { Broker(VoiceState.brokerBaseUrl, it) }

    /** Runs [call] off the main thread; a grant not there yet after a pass is waited for (up to [tries] s). */
    private fun <T> ask(tries: Int, call: (Broker) -> T, done: (T) -> Unit, failed: (Broker.Failure?) -> Unit) {
        net.execute {
            val b = broker()
            if (b == null) { main.post { failed(null) }; return@execute }
            var left = tries
            while (true) {
                try {
                    val v = call(b)
                    main.post { if (!isFinishing) done(v) }
                    return@execute
                } catch (e: Broker.Failure) {
                    if (e.status == 403 && e.code == "verify_identity" && left > 1) { left--; Thread.sleep(1000); continue }
                    main.post { if (!isFinishing) failed(e) }
                    return@execute
                } catch (e: Exception) {
                    main.post { if (!isFinishing) failed(null) }
                    return@execute
                }
            }
        }
    }

    private fun load(m: YearMonth, tries: Int = 1) {
        state = State.LOADING
        draw()
        val (from, to) = CalendarModel.range(m)
        ask(tries, { it.calendarList(from.toString(), to.toString()) }, { json ->
            val parsed = CalendarModel.parse(json)
            loaded[m] = parsed
            state = State.READY
            Log.i(TAG, "month shown events=${parsed.events.size} holidays=${parsed.holidays.size} holidays_ok=${parsed.holidaysOk}")
            draw()
        }, { e -> refused(e) { load(m, tries = 6) } })
    }

    /** A refusal in the screen's words; [retry] is what runs again after the identity check. */
    private fun refused(e: Broker.Failure?, retry: () -> Unit) {
        state = when {
            e?.status == 403 && e.code == "verify_identity" -> State.VERIFY
            e?.status == 409 -> State.NOT_CONNECTED
            else -> State.ERROR
        }
        errorWords = if (state == State.ERROR) (e?.message ?: getString(R.string.calendar_offline)) else ""
        pendingRetry = retry
        Log.i(TAG, "refused status=${e?.status ?: -1} state=$state")
        draw()
    }

    private var pendingRetry: (() -> Unit)? = null

    /** The identity check, then [next] (the grant is asked for as the check passes). */
    private fun verifyThen(next: () -> Unit) {
        if (AuthStore.identityId(this) == null) { state = State.NO_IDENTITY; draw(); return }
        afterVerify = next
        startActivity(VerifyActivity.intent(this, VerifyActivity.Mode.VERIFY))
    }

    // ------------------------------------------------------------ drawing

    private fun draw() {
        val body = r.column().apply { setPadding(0, 0, 0, r.dp(UiScale.SPACE_S)) }
        when (page) {
            Page.MONTH -> drawMonth(body)
            Page.DAY -> drawDay(body)
            Page.EDIT -> drawEdit(body)
        }
        frame.setPage(ScrollView(this).apply { addView(body) })
    }

    /** The one line that says why the calendar is not showing, and the one thing to do. */
    private fun drawState(body: LinearLayout): Boolean {
        val (words, button) = when (state) {
            State.READY -> return false
            State.LOADING -> getString(R.string.calendar_loading) to null
            State.VERIFY -> getString(R.string.calendar_verify_why) to
                (getString(R.string.calendar_verify_button) to { verifyThen { pendingRetry?.invoke() ?: load(month, 6) } })
            State.NO_IDENTITY -> getString(R.string.calendar_no_identity) to null
            State.NOT_CONNECTED -> getString(R.string.calendar_not_connected) to null
            State.ERROR -> errorWords to (getString(R.string.calendar_retry) to { pendingRetry?.invoke() ?: load(month) })
        }
        body.addView(r.text(words, UiScale.TEXT_BASE).apply { setPadding(0, r.dp(UiScale.SPACE_M), 0, r.dp(UiScale.SPACE_M)) },
            LinearLayout.LayoutParams(MATCH, WRAP))
        button?.let { (label, action) ->
            body.addView(r.button(label, big = true) { action() }, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)))
        }
        return true
    }

    private fun drawMonth(body: LinearLayout) {
        // ◄ ตุลาคม 2569 ►, and back to this month.
        body.addView(r.row().apply {
            gravity = Gravity.CENTER_VERTICAL
            addView(r.button("◄") { month = month.minusMonths(1); showMonth() }.apply {
                contentDescription = getString(R.string.calendar_prev_month)
            }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
            addView(r.bold(CalendarModel.monthTitle(month), UiScale.TEXT_HEADING).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP, 1f))
            addView(r.button("►") { month = month.plusMonths(1); showMonth() }.apply {
                contentDescription = getString(R.string.calendar_next_month)
            }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        }, LinearLayout.LayoutParams(MATCH, WRAP))
        if (month != YearMonth.now(ZONE)) {
            body.addView(r.button(getString(R.string.calendar_this_month)) { month = YearMonth.now(ZONE); showMonth() },
                LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        val m = loaded[month]
        if (m == null) { if (!drawState(body)) load(month); return }
        if (state != State.READY && drawState(body)) return

        // The week's heads, then six weeks.
        body.addView(r.row().apply {
            for (h in CalendarModel.WEEK_HEADS) addView(r.bold(h, UiScale.TEXT_NOTE).apply { gravity = Gravity.CENTER },
                LinearLayout.LayoutParams(0, WRAP, 1f))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        val today = LocalDate.now(ZONE)
        for (week in CalendarModel.gridDays(month).chunked(7)) {
            body.addView(r.row().apply {
                for (d in week) addView(dayCell(m, d, today), LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH + 8), 1f))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        body.addView(r.text(getString(R.string.calendar_legend), UiScale.TEXT_NOTE),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        if (!m.holidaysOk) {
            body.addView(r.text(getString(R.string.calendar_holidays_missing), UiScale.TEXT_NOTE).apply {
                setTextColor(r.color(R.color.retro_bad))
            }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        body.addView(r.button(getString(R.string.calendar_add), big = true) { startEdit(null, day.takeIf {
            YearMonth.from(it) == month } ?: month.atDay(1).takeIf { month != YearMonth.now(ZONE) } ?: today) },
            LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
        body.addView(r.text(getString(R.string.calendar_holidays_note), UiScale.TEXT_NOTE, dim = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
    }

    /** One day: its number (dim outside the month, boxed today) over its marks — shapes, not colours. */
    private fun dayCell(m: CalendarModel.Month, d: LocalDate, today: LocalDate): View = r.column().apply {
        gravity = Gravity.CENTER
        isClickable = true
        setBackgroundResource(if (d == today) R.drawable.retro_sunken else R.drawable.retro_button)
        val marks = CalendarModel.marks(m, d)
        val inMonth = YearMonth.from(d) == month
        addView(TextView(context).apply {
            text = d.dayOfMonth.toString()
            textSize = UiScale.TEXT_BASE
            gravity = Gravity.CENTER
            typeface = if (d == today) Typeface.DEFAULT_BOLD else Typeface.DEFAULT
            setTextColor(r.color(when {
                !inMonth -> R.color.retro_dim
                CalendarModel.Mark.HOLIDAY in marks -> R.color.retro_bad
                else -> R.color.retro_text
            }))
        })
        addView(TextView(context).apply {
            text = marks.joinToString("") { it.symbol }
            textSize = UiScale.TEXT_NOTE
            gravity = Gravity.CENTER
            maxLines = 1
            setTextColor(r.color(R.color.retro_text))
        })
        contentDescription = CalendarModel.daySaid(m, d) + if (d == today) " (วันนี้)" else ""
        setOnClickListener { day = d; if (!inMonth) month = YearMonth.from(d); page = Page.DAY; confirmDelete = null; draw() }
    }

    private fun showMonth() {
        page = Page.MONTH
        if (loaded[month] == null) load(month) else { state = State.READY; draw() }
    }

    private fun drawDay(body: LinearLayout) {
        body.addView(r.button(getString(R.string.calendar_back_to_month, CalendarModel.monthTitle(month))) { page = Page.MONTH; draw() },
            LinearLayout.LayoutParams(WRAP, r.dp(UiScale.TOUCH)))
        body.addView(r.bold(CalendarModel.dayTitle(day), UiScale.TEXT_HEADING),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        if (notice.isNotEmpty()) {
            body.addView(r.text(notice, UiScale.TEXT_NOTE), LinearLayout.LayoutParams(MATCH, WRAP))
            notice = ""
        }
        val m = loaded[month] ?: loaded[YearMonth.from(day)]
        if (m == null || (state != State.READY && drawState(body))) { if (m == null) load(YearMonth.from(day)); return }
        for (h in CalendarModel.holidaysOn(m, day)) {
            val mark = if (h.kind == CalendarModel.Kind.HOLIDAY) CalendarModel.Mark.HOLIDAY else CalendarModel.Mark.OBSERVANCE
            val kind = getString(when (h.kind) {
                CalendarModel.Kind.HOLIDAY -> R.string.calendar_kind_holiday
                CalendarModel.Kind.OBSERVANCE -> R.string.calendar_kind_observance
                CalendarModel.Kind.UNKNOWN -> R.string.calendar_kind_unknown
            })
            body.addView(r.text("${mark.symbol} ${h.title} · $kind", UiScale.TEXT_BASE),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        val events = CalendarModel.eventsOn(m, day)
        if (events.isEmpty()) {
            body.addView(r.text(getString(R.string.calendar_day_empty), UiScale.TEXT_BASE),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
        }
        for (e in events) body.addView(eventRow(e), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        body.addView(r.button(getString(R.string.calendar_add_on_day), big = true) { startEdit(null, day) },
            LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) })
    }

    private fun eventRow(e: CalendarModel.Appointment): View = r.column().apply {
        setBackgroundResource(R.drawable.retro_sunken)
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        addView(r.row().apply {
            addView(r.bold(CalendarModel.timeText(e), UiScale.TEXT_BASE), LinearLayout.LayoutParams(WRAP, WRAP))
            addView(r.text(e.title, UiScale.TEXT_ITEM).apply {
                setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0)
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
        })
        if (confirmDelete == e.id) {
            addView(r.text(getString(R.string.calendar_delete_confirm), UiScale.TEXT_NOTE),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
            addView(r.pair(getString(R.string.calendar_delete_verify), { verifyThen { delete(e) } },
                           getString(R.string.cancel), { confirmDelete = null; draw() }),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        } else {
            addView(r.pair(getString(R.string.edit), { startEdit(e, e.date) },
                           getString(R.string.calendar_delete), { confirmDelete = e.id; draw() }),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
    }

    private fun delete(e: CalendarModel.Appointment) {
        state = State.LOADING
        ask(6, { it.calendarDelete(e.id) }, {
            Log.i(TAG, "deleted")
            confirmDelete = null
            notice = getString(R.string.calendar_deleted)
            loaded.clear()
            page = Page.DAY
            load(YearMonth.from(day))
        }, { f -> refused(f) { verifyThen { delete(e) } } })
    }

    // ------------------------------------------------------------ writing one

    private fun startEdit(e: CalendarModel.Appointment?, date: LocalDate) {
        editId = e?.id
        editTitle = e?.title.orEmpty()
        editDate = e?.date ?: date
        editAllDay = e?.allDay ?: false
        editStart = e?.start?.takeIf { it.isNotEmpty() } ?: "09:00"
        editEnd = e?.end?.takeIf { it.isNotEmpty() } ?: "10:00"
        editError = ""
        page = Page.EDIT
        draw()
    }

    private var titleField: EditText? = null

    private fun drawEdit(body: LinearLayout) {
        body.addView(r.bold(getString(if (editId == null) R.string.calendar_add else R.string.calendar_edit), UiScale.TEXT_HEADING),
            LinearLayout.LayoutParams(MATCH, WRAP))
        body.addView(r.label(getString(R.string.calendar_title_label)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        titleField = r.field(editTitle).apply { hint = getString(R.string.calendar_title_hint) }
        body.addView(titleField, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)))

        body.addView(r.label(getString(R.string.calendar_date_label)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        body.addView(stepper(CalendarModel.dayTitle(editDate), getString(R.string.calendar_day_earlier), getString(R.string.calendar_day_later),
            { keepTitle(); editDate = editDate.minusDays(1); draw() }, { keepTitle(); editDate = editDate.plusDays(1); draw() }),
            LinearLayout.LayoutParams(MATCH, WRAP))

        body.addView(r.row().apply {
            addView(r.option(getString(R.string.calendar_timed), !editAllDay) { keepTitle(); editAllDay = false; draw() },
                LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
            addView(r.option(getString(R.string.calendar_all_day), editAllDay) { keepTitle(); editAllDay = true; draw() },
                LinearLayout.LayoutParams(0, r.dp(UiScale.TOUCH), 1f))
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
        if (!editAllDay) {
            body.addView(r.label(getString(R.string.calendar_start_label)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            body.addView(stepper(editStart, getString(R.string.calendar_earlier), getString(R.string.calendar_later),
                { keepTitle(); editStart = shift(editStart, -STEP_MIN); keepOrder(); draw() },
                { keepTitle(); editStart = shift(editStart, STEP_MIN); keepOrder(); draw() }), LinearLayout.LayoutParams(MATCH, WRAP))
            body.addView(r.label(getString(R.string.calendar_end_label)), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            body.addView(stepper(editEnd, getString(R.string.calendar_earlier), getString(R.string.calendar_later),
                { keepTitle(); editEnd = shift(editEnd, -STEP_MIN); draw() },
                { keepTitle(); editEnd = shift(editEnd, STEP_MIN); draw() }), LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (editError.isNotEmpty()) {
            body.addView(r.errorLine().apply { text = editError; visibility = View.VISIBLE }, LinearLayout.LayoutParams(MATCH, WRAP))
        }
        if (state == State.VERIFY || state == State.NO_IDENTITY || state == State.NOT_CONNECTED) drawState(body)
        body.addView(r.pair(getString(if (saving) R.string.calendar_saving else R.string.calendar_save), { if (!saving) save() },
                            getString(R.string.cancel), { back() }, big = true),
            LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_M) })
    }

    /** "◄  value  ►" with 48dp buttons that say what they do. */
    private fun stepper(value: String, lessWords: String, moreWords: String, less: () -> Unit, more: () -> Unit): View = r.row().apply {
        gravity = Gravity.CENTER_VERTICAL
        addView(r.button("◄") { less() }.apply { contentDescription = lessWords }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
        addView(r.bold(value, UiScale.TEXT_ITEM).apply { gravity = Gravity.CENTER; maxLines = 2; ellipsize = TextUtils.TruncateAt.END },
            LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(r.button("►") { more() }.apply { contentDescription = moreWords }, LinearLayout.LayoutParams(r.dp(UiScale.TOUCH), r.dp(UiScale.TOUCH)))
    }

    /** What was typed stays, whatever is pressed (ux-ui-design: never clear what was entered). */
    private fun keepTitle() { titleField?.let { editTitle = it.text.toString() } }

    private fun keepOrder() { if (minutes(editEnd) <= minutes(editStart)) editEnd = shift(editStart, 60) }

    private fun minutes(hhmm: String) = hhmm.substring(0, 2).toInt() * 60 + hhmm.substring(3, 5).toInt()

    private fun shift(hhmm: String, by: Int): String {
        val m = Math.floorMod(minutes(hhmm) + by, 24 * 60)
        return "%02d:%02d".format(m / 60, m % 60)
    }

    private fun save() {
        keepTitle()
        if (editTitle.isBlank()) { editError = getString(R.string.calendar_title_missing); draw(); return }
        val json = JSONObject().put("title", editTitle.trim()).put("date", editDate.toString()).put("allDay", editAllDay)
            .put("start", editStart).put("end", editEnd)
        editId?.let { json.put("id", it) }
        saving = true
        editError = ""
        draw()
        ask(6, { it.calendarSave(json) }, {
            saving = false
            Log.i(TAG, if (editId == null) "added" else "changed")
            notice = getString(R.string.calendar_saved)
            loaded.clear()
            day = editDate
            month = YearMonth.from(editDate)
            page = Page.DAY
            state = State.READY
            load(month)
        }, { f ->
            saving = false
            if (f?.status == 400) { editError = f.message ?: ""; state = State.READY; draw() }
            else refused(f) { save() }
        })
    }

    companion object {
        private const val TAG = "KioskCalendar"
        private const val STEP_MIN = 15
        private val ZONE: ZoneId = ZoneId.of("Asia/Bangkok")
    }
}
