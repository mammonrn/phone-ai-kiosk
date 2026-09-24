package com.mammonrn.phoneaikiosk

import android.app.Activity
import android.app.ActivityManager
import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.BatteryManager
import android.os.SystemClock
import android.util.Log
import android.view.WindowManager
import android.widget.ImageView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.home.HomeCard
import com.mammonrn.phoneaikiosk.alarm.AlarmBook
import com.mammonrn.phoneaikiosk.alarm.AlarmScheduler
import com.mammonrn.phoneaikiosk.alarm.AlarmStore
import com.mammonrn.phoneaikiosk.ui.BatteryLabel
import com.mammonrn.phoneaikiosk.ui.CardBoard
import com.mammonrn.phoneaikiosk.ui.FadingLine
import com.mammonrn.phoneaikiosk.ui.RetroType
import com.mammonrn.phoneaikiosk.ui.SpeechFollow
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.DashboardState
import com.mammonrn.phoneaikiosk.voice.KioskLocation
import com.mammonrn.phoneaikiosk.voice.MapsLauncher
import com.mammonrn.phoneaikiosk.voice.TokenStore
import com.mammonrn.phoneaikiosk.voice.VoiceService
import com.mammonrn.phoneaikiosk.voice.VoiceState
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Phase 1: the kiosk shell.
 *
 * A clock, a label, and the machinery that keeps the device on this screen —
 * lock task mode plus being the persistent HOME activity. No AI yet.
 */
class MainActivity : Activity() {

    private lateinit var status: TextView
    private lateinit var voiceStatus: TextView
    private lateinit var transcript: TextView
    private lateinit var transcriptScroll: android.widget.ScrollView  // a RestingScrollView

    /** What the transcript shows now, so it is only set when it changes — a
     *  setText every second would fight the scroll position. */
    private var shownTranscript = ""

    /** The answer the window is following, and whether a finger took over. */
    private var followedReply = ""
    private var followTakenOver = false

    /** The last touch on the answer, which keeps it on screen while read. */
    private var transcriptTouchedAt = 0L
    private lateinit var taskbarClock: TextView
    private lateinit var taskbarDate: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryText: TextView
    private var ticks = 0
    /** The house's lights, one page per system (0.45.0, home/HomeCard). */
    private lateinit var homePages: com.mammonrn.phoneaikiosk.ui.PagedPanel
    private lateinit var homePage: android.widget.LinearLayout
    private lateinit var weatherTitle: TextView
    private lateinit var weatherBody: TextView
    private lateinit var goldHeader: TextView
    private lateinit var goldTable: android.widget.TableLayout
    private lateinit var oilHeader: TextView
    private lateinit var oilTable: android.widget.TableLayout
    private lateinit var cryptoBody: TextView
    private lateinit var cryptoBodyRight: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var goldTitle: TextView
    private lateinit var jarvisState: TextView
    private lateinit var sunRow: android.view.View
    private lateinit var weatherStats: android.widget.TableLayout
    private lateinit var weatherOutlook: TextView

    /** News for the price windows: a move of 3% (crypto) or 1% (gold and fuel). */
    private val cryptoMoves = com.mammonrn.phoneaikiosk.ui.MoveTracker(
        com.mammonrn.phoneaikiosk.ui.MoveTracker.CRYPTO_PCT)
    // Gold and fuel are two PAGES of one card (0.38), so each has its own
    // tracker: news on the fuel page is the fuel's own 1% move.
    private val goldMoves = com.mammonrn.phoneaikiosk.ui.MoveTracker(
        com.mammonrn.phoneaikiosk.ui.MoveTracker.COMMODITIES_PCT)
    private val oilMoves = com.mammonrn.phoneaikiosk.ui.MoveTracker(
        com.mammonrn.phoneaikiosk.ui.MoveTracker.COMMODITIES_PCT)
    private lateinit var commodityPages: com.mammonrn.phoneaikiosk.ui.PagedPanel

    // ---------------------------------------------------------- the card stack
    // Which window is open, folded or first: ui/CardBoard decides, this moves
    // the views. The Jarvis window and the taskbar are not in the stack.
    /** Fixed order since 0.36.0: news opens a card, it never moves one (DESIGN.md, ก). */
    private val board = CardBoard(fixedOrder = true)
    private lateinit var cardStack: android.widget.LinearLayout
    private lateinit var cardJarvis: android.view.View
    private lateinit var netText: TextView
    private var networkWatch: com.mammonrn.phoneaikiosk.ui.NetworkWatch? = null

    private class Card(val id: String, val root: android.view.View, val body: android.view.View,
                       val badge: TextView, val openWeight: Float,
                       val titlebar: android.view.View)

    private val cards = LinkedHashMap<String, Card>()

    /** Each window's one-line summary, shown in its title bar when folded. */
    private val summaries = HashMap<String, String>()
    private var shownOrder: List<String> = emptyList()

    private lateinit var alarmsBody: TextView
    private lateinit var alarmStop: TextView
    private var shownAlarmsVersion = -1
    private var alarmsVisible = false
    private lateinit var sunriseText: TextView
    private lateinit var sunsetText: TextView

    /**
     * Press Start 2P, loaded once.
     *
     * Every panel is redrawn every second, and each redraw asks RetroType to
     * span the digits into this face. Resolving the font resource on each of
     * those would be a file lookup a second for the life of the kiosk.
     */
    private lateinit var pixelFace: android.graphics.Typeface

    /**
     * Where the kiosk is, coarse and rounded. See KioskLocation for what that
     * costs and what it deliberately does not do.
     */
    private lateinit var location: KioskLocation

    private val handler = Handler(Looper.getMainLooper())

    /**
     * One thread, off the main one, for the dashboard fetch.
     *
     * Separate from the voice executors on purpose: the screen must never be
     * able to queue behind a question, and a question must never wait for the
     * weather.
     */
    private val dashboardThread =
        java.util.concurrent.Executors.newSingleThreadExecutor { r ->
            Thread(r, "kiosk-dashboard").apply { isDaemon = true }
        }
    private val tapGate = TapGate()

    /**
     * Whether the phone is on its charger, as the power broadcasts last said.
     *
     * THE SCREEN NO LONGER SIMPLY FOLLOWS THE CABLE. It used to stay on for as
     * long as the phone was charging; Poom changed that, and now it goes off
     * after five idle minutes whatever the cable says — see IdleScreen, which
     * owns the rule. Charging still matters in one place: while somebody is
     * using it, a charging kiosk holds its screen on and one on battery lets
     * the system's own timeout decide, which is what it always did.
     */
    private val powerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            charging = intent.action == Intent.ACTION_POWER_CONNECTED
            // The bolt appears the moment the cable goes in, not ten seconds later.
            ticks = 0
        }
    }

    @Volatile
    private var charging = false

    private var keepingScreenOn = false

    private val idleScreen = IdleScreen()

    /** The last turn on screen, for a minute. */
    private val recentTurn = FadingLine()

    /** What the Google Home button does. Not connected this phase. */
    /** What the "อุปกรณ์ในบ้าน" card shows; null keeps it hidden. */
    private var homeCard: HomeCard.Card? = null

    /**
     * 12-hour with AM/PM, as Poom asked, and in Locale.US on purpose: under the
     * phone's Thai locale "a" is "ก่อนเที่ยง"/"หลังเที่ยง", which is correct Thai
     * and not what was asked for. No leading zero on the hour, like any
     * 12-hour clock: "7:05 AM", not "07:05 AM".
     */
    private val taskbarFormat = SimpleDateFormat("h:mm a", Locale.US)

    private val tick = object : Runnable {
        override fun run() {
            val now = Date()
            applyScreenRule()
            taskbarClock.text = taskbarFormat.format(now)
            // Thai, "พ. 23 ก.ย.": the time stays AM/PM as asked, the date is
            // in the language of everything else on the screen.
            taskbarDate.text = com.mammonrn.phoneaikiosk.ui.ScreenDate.format(
                java.util.Calendar.getInstance().apply { time = now })
            // Every ten seconds: a battery moves a percent in minutes, and the
            // sticky broadcast is cheap but not free.
            if (ticks++ % 10 == 0) showBattery()
            VoiceState.locationState = location.describe()
            // Always written to VoiceState, so dumpsys has it; only DRAWN in
            // debug mode. The household's screen shows data and Jarvis's
            // state in words, not mic= and taps=.
            VoiceState.kioskLine = statusLine()
            showDiagnostics(VoiceState.showDiagnostics)
            // An empty box says nothing; the invitation says what to do with
            // the kiosk. Display only — transcriptLine() is untouched.
            // What was heard and answered stays up for a minute after it last
            // changed, then gives way to the invitation again — see FadingLine.
            val nowMs = SystemClock.elapsedRealtime()
            // Somebody scrolling back through an answer is still reading it:
            // their touch holds it up the same way a turn in progress does.
            val busy = IdleScreen.voiceBusy(
                VoiceState.wake, VoiceState.stt, VoiceState.chat, VoiceState.tts) ||
                nowMs - transcriptTouchedAt < READING_HOLD_MS
            // Nothing asked (room noise the broker's gate stopped, or silence):
            // the short notice goes after a few seconds, not a minute.
            val noticeOnly = VoiceState.lastCancel.isNotEmpty() && VoiceState.heard.isEmpty()
            // Media playing (WakePause): the wake word is off, so the
            // invitation says what does work — the button — and why.
            val resting = com.mammonrn.phoneaikiosk.voice.WakePause.reason()
            val line = recentTurn.visible(transcriptLine(), nowMs, busy,
                    if (noticeOnly) FadingLine.NOTICE_HOLD_MS else FadingLine.HOLD_MS)
                .ifEmpty {
                    if (resting != null) getString(R.string.kiosk_prompt_resting, resting.word)
                    else getString(R.string.kiosk_prompt)
                }
            if (line != shownTranscript) showTranscript(line)
            if (VoiceState.alarmsVersion != shownAlarmsVersion) showAlarms(nowMs)
            renderCards(nowMs)
            jarvisState.text = DashboardState.jarvisState(
                VoiceState.mic, VoiceState.stt, VoiceState.chat, VoiceState.tts,
                getString(R.string.jarvis_ready),
                getString(R.string.jarvis_listening),
                getString(R.string.jarvis_thinking),
                getString(R.string.jarvis_speaking),
                getString(R.string.jarvis_offline),
                resting?.let { getString(R.string.jarvis_resting, it.word) },
            )
            handler.postDelayed(this, 1_000L)
        }
    }

    /**
     * Asks the broker what to put in the three data windows.
     *
     * The broker caches every source, so polling this often costs one local
     * request and no outside call at all — which is why the interval is about
     * the screen looking current rather than about anybody's rate limit.
     *
     * A failure here changes NOTHING on screen. The last values stay, the clock
     * keeps ticking, and the next attempt is a minute away: a kiosk that blanks
     * itself because one request timed out is worse than one showing numbers
     * from a minute ago.
     */
    private val refreshDashboard = object : Runnable {
        override fun run() {
            // Cheap on almost every call: KioskLocation only goes and looks
            // when the last fix is over half an hour old.
            location.refreshIfStale()
            val fix = location.coordinates()
            // For Maps: search near the kiosk, not the whole world (MapsLauncher).
            if (fix != null) VoiceState.near = fix

            dashboardThread.execute {
                val token = TokenStore(this@MainActivity).token()
                val broker = if (token.isNullOrEmpty()) null
                             else Broker(VoiceState.brokerBaseUrl, token)
                var attempt = broker?.let {
                    runCatching { it.dashboard(fix?.first, fix?.second) }
                }

                // A BROKER THAT HAS NOT BEEN DEPLOYED YET ROUTES ON THE PATH
                // ALONE. Before this phase its handler compared self.path to
                // "/v1/dashboard" exactly, so the moment a query string is
                // appended the route stops matching and the answer is 404 —
                // checked against the live host, which returns 401 without the
                // query string and 404 with it.
                //
                // The APK lands over adb in seconds and the VPS is a separate
                // step by hand afterwards, so without this the kiosk sits on
                // "กำลังโหลด…" in every window until somebody SSHes in. One
                // retry without the position covers that gap: the weather is
                // the fallback town's for a while, which is what it was for
                // the whole of the last phase anyway, and everything else is
                // correct. Costs one extra request a minute and only while the
                // first one is failing.
                var withoutPosition = fix == null
                if (attempt?.isFailure == true && fix != null) {
                    attempt = broker?.let { runCatching { it.dashboard() } }
                    withoutPosition = true
                }
                // Whether it worked, and nothing else. NOT the payload: it is
                // a few hundred bytes of numbers today, and a log line that
                // prints whatever the server sent is a log line that prints
                // whatever the server sends tomorrow. A failure is reported as
                // the exception TYPE for the reason the broker uses too — a URL
                // inside an exception message can carry a query string.
                when {
                    attempt == null -> Log.i(DASHBOARD_TAG, "refresh skipped: no token")
                    attempt.isSuccess -> Log.i(DASHBOARD_TAG, "refresh ok")
                    else -> {
                        com.mammonrn.phoneaikiosk.voice.SoakProbe.dashboardFailures += 1
                        Log.w(DASHBOARD_TAG, "refresh failed: " +
                            Broker.describe(attempt.exceptionOrNull()))
                    }
                }
                val payload = attempt?.getOrNull()
                if (payload != null) {
                    handler.post { applyDashboard(payload, withoutPosition) }
                }
            }
            handler.postDelayed(this, DASHBOARD_INTERVAL_MILLIS)
        }
    }

    /**
     * @param withoutPosition true when this answer was asked for with no
     *   coordinates — no fix, or the retry above. The phone knows that even
     *   when the broker is too old to say `location_fallback`, and a status
     *   field that reads "phone" while the weather is the university's would
     *   be a small quiet lie in the one place somebody looks to catch one.
     */
    private fun applyDashboard(payload: String, withoutPosition: Boolean = false) {
        val screen = DashboardState.parse(payload, getString(R.string.data_unavailable))
        // What counts as news for each window: DashboardState.cardFacts.
        val nowMs = SystemClock.elapsedRealtime()
        // The price windows' news is a MOVE (MoveTracker): crypto 3%, gold and
        // fuel 1%, against the prices at their last news. Weather keeps its own
        // rule (a whole degree or a new sky word). DESIGN.md, "Cards".
        val prices = DashboardState.cardPrices(payload)
        prices["crypto"]?.let { cryptoMoves.update(it) }
        prices["gold"]?.let { all ->
            all.filterKeys { !it.startsWith("oil:") }.takeIf { it.isNotEmpty() }?.let { goldMoves.update(it) }
            all.filterKeys { it.startsWith("oil:") }.takeIf { it.isNotEmpty() }?.let { oilMoves.update(it) }
        }
        // Each page's own news, for its tab: the first prices are a baseline
        // (generation 1), every later generation is a 1% move on that page.
        if (goldMoves.generation > 0) commodityPages.news("gold", "g${goldMoves.generation}", nowMs)
        if (oilMoves.generation > 0) commodityPages.news("oil", "o${oilMoves.generation}", nowMs)
        for ((id, fact) in DashboardState.cardFacts(payload)) {
            val signature = when (id) {
                "crypto" -> if (cryptoMoves.generation > 0) "move:${cryptoMoves.generation}" else fact.first
                // The card's news is either page's news. Counted from each
                // tracker's first prices, so fuel arriving after gold is not news.
                "gold" -> if (goldMoves.generation > 0 || oilMoves.generation > 0)
                              "move:${(goldMoves.generation - 1).coerceAtLeast(0)}:" +
                                  "${(oilMoves.generation - 1).coerceAtLeast(0)}"
                          else fact.first
                else -> fact.first
            }
            board.report(id, signature, nowMs)
            summaries[id] = fact.second
        }
        showWeatherStats(screen.weatherStats)
        if (screen.outlook.isEmpty()) {
            weatherOutlook.visibility = android.view.View.GONE
        } else {
            weatherOutlook.text = screen.outlook
            weatherOutlook.visibility = android.view.View.VISIBLE
        }
        // Numbers into the pixel face, the freshness note turned down. The
        // strings themselves are DashboardState's business and are not touched
        // here — this only decides what they look like.
        val dim = ContextCompat.getColor(this, R.color.retro_dim)
        weatherBody.text = RetroType.pixelifyHeadline(screen.weather.text, pixelFace, dim)
        showCommodities(DashboardState.commodities(payload, getString(R.string.data_unavailable)))
        // The house's lights (0.45.0): hidden until the broker has devices to
        // show; news is a light going on or off, never the age of the reading.
        val home = HomeCard.parse(payload)
        if (home != homeCard) showHome(home)
        if (home != null) {
            board.report("home", HomeCard.signature(home), nowMs)
            summaries["home"] = HomeCard.summary(home.systems.first())
        }
        // The blank line between two coins at under half height: enough to
        // tell the pairs apart, not the full empty line that spread four coins
        // over the whole window.
        cryptoBody.text = RetroType.tightenBlankLines(
            withCoinIcons(RetroType.pixelifyWithAge(screen.crypto.text, pixelFace, dim),
                          cryptoBody.textSize),
            COIN_GAP)
        cryptoBodyRight.text = RetroType.tightenBlankLines(
            withCoinIcons(RetroType.pixelify(screen.crypto.text2, pixelFace),
                          cryptoBodyRight.textSize),
            COIN_GAP)

        // Sunrise and sunset, or no line at all. Both or neither: half a pair
        // on a screen looks like a fault, and the weather above it is complete
        // without them.
        if (screen.sunrise.isNotEmpty() && screen.sunset.isNotEmpty()) {
            sunriseText.text = RetroType.pixelify(getString(R.string.sunrise_at, screen.sunrise),
                                                  pixelFace)
            sunsetText.text = RetroType.pixelify(getString(R.string.sunset_at, screen.sunset),
                                                 pixelFace)
            sunRow.visibility = android.view.View.VISIBLE
        } else {
            sunRow.visibility = android.view.View.GONE
        }

        // Sun or moon, from the same `is_day` the broker chose the word from.
        // Deciding it here from the phone's own clock would be a second opinion
        // about the sky, and two opinions disagree the week one of them is
        // wrong about the timezone.
        weatherIcon.setImageResource(
            if (screen.isDay) R.drawable.ic_pixel_sun else R.drawable.ic_pixel_moon,
        )

        // "อากาศ · เชียงราย", or "อากาศ · ตำแหน่งปัจจุบัน" when the position is
        // known and its name is not. Never a coordinate: there is nothing a
        // person standing in front of a kiosk does with one, and a screen faces
        // a room.
        VoiceState.weatherFallback = screen.locationFallback || withoutPosition

        // WHICH POSITION (0.42.0, Poom): the real one the phone reported, or the
        // university's fallback — said in a word, never a coordinate.
        val source = getString(if (VoiceState.weatherFallback == true) R.string.weather_source_fallback
                               else R.string.weather_source_phone)
        weatherTitle.text = if (screen.place.isEmpty()) "${getString(R.string.window_weather)} · $source"
                            else "${getString(R.string.window_weather)} · ${screen.place} · $source"

        // The gold title carries the PURITY, as a word and a number:
        // "ราคาทอง · ความบริสุทธิ์ 96.5%". What the price move is measured
        // against moved down into the panel's footnote, next to the moves it
        // explains, so each percentage on this window sits beside its label.
        // "ทองและน้ำมัน · ทอง 96.5%" once fuel is there; the purity stays in the
        // title as a word and a number, so it is never read as a price move.
        val name = getString(if (screen.oil != null) R.string.window_commodities else R.string.window_gold)
        goldTitle.text = when {
            screen.goldPurity.isEmpty() -> name
            screen.oil != null -> "$name · " + getString(R.string.gold_purity_short, screen.goldPurity)
            else -> "$name · " + getString(R.string.gold_purity, screen.goldPurity)
        }
    }

    /**
     * The windows of the card stack, their rules (DESIGN.md, "Cards"):
     *   weather  folds after 60 min without news
     *   gold     folds after 2 h (the shop announces a few times a day)
     *   crypto   folds after 60 min
     *   alarms   folds 10 min after a change; pinned open while one rings;
     *            not shown at all while there are no alarms
     *   home     the Google Home placeholder: never news, so a bar from the
     *            start — its room goes to the windows with something to say
     * Registration order is the order of windows that have never had news.
     */
    private fun setUpCards() {
        fun card(id: String, root: Int, body: Int, badge: Int, titlebar: Int, weight: Float,
                 foldAfterMs: Long, alwaysOpen: Boolean = false, openOnFirst: Boolean = true) {
            cards[id] = Card(id, findViewById(root), findViewById(body), findViewById(badge), weight,
                             findViewById(titlebar))
            board.register(CardBoard.Spec(id, foldAfterMs, alwaysOpen, openOnFirst))
            // Tapping a folded window's bar opens it for two minutes.
            findViewById<android.view.View>(titlebar).setOnClickListener {
                board.touch(id, SystemClock.elapsedRealtime())
                renderCards(SystemClock.elapsedRealtime())
            }
        }
        // Registered in the order the screen keeps (DESIGN.md, ก): the weather,
        // always open; the alarms, the only thing a person acts on; the
        // commodities; crypto, folded until a 3% move or a tap (ข); Google
        // Home, hidden until it can really do something (ค).
        val minute = CardBoard.MINUTE
        card("weather", R.id.card_weather, R.id.weather_panel, R.id.weather_badge,
             R.id.weather_titlebar, 1f, 60 * minute, alwaysOpen = true)
        card("alarms", R.id.card_alarms, R.id.alarms_panel, R.id.alarms_badge,
             R.id.alarms_titlebar, 0.5f, 10 * minute)
        card("gold", R.id.card_gold, R.id.commodity_panel, R.id.gold_badge, R.id.gold_titlebar,
             1f, 120 * minute)
        card("crypto", R.id.card_crypto, R.id.crypto_panel, R.id.crypto_badge,
             R.id.crypto_titlebar, 0.25f, 60 * minute, openOnFirst = false)
        // The house's lights, last: read at a glance, acted on by nobody yet.
        card("home", R.id.card_home, R.id.home_body, R.id.home_badge, R.id.home_titlebar,
             0.7f, 60 * minute)
        alarmStop.setOnClickListener {
            VoiceService.start(this, VoiceService.ACTION_ALARM_STOP)
        }
    }

    /**
     * Puts the stack in CardBoard's order and folds or opens each window.
     * Touches a view only when its state changes, so nothing redraws for
     * nothing — the "no flicker" rule is here as much as in CardBoard.
     */
    private fun renderCards(nowMs: Long) {
        // A card with nothing to be yet is not on the screen at all: the alarms
        // with no alarm set, Google Home until it can control something (ค —
        // it comes back by itself the day the broker has devices to show).
        fun hidden(id: String) = (id == "alarms" && !alarmsVisible) ||
            (id == "home" && homeCard == null)
        val slots = fitToStack(board.layout(nowMs).filterNot { hidden(it.id) }, nowMs)
        val ids = slots.map { it.id }
        if (ids != shownOrder) {
            for ((index, id) in ids.withIndex()) {
                val view = cards.getValue(id).root
                if (cardStack.getChildAt(index) !== view) {
                    cardStack.removeView(view)
                    cardStack.addView(view, index)
                }
            }
            shownOrder = ids
        }
        for (id in listOf("alarms", "home")) {
            val root = cards.getValue(id).root
            val state = if (hidden(id)) android.view.View.GONE else android.view.View.VISIBLE
            if (root.visibility != state) root.visibility = state
        }

        for (slot in slots) {
            val card = cards.getValue(slot.id)
            val bodyState = if (slot.open) android.view.View.VISIBLE else android.view.View.GONE
            if (card.body.visibility != bodyState) card.body.visibility = bodyState
            // No window takes a share of spare height any more (0.42.0): each
            // is as tall as its content, and the rest is Jarvis's.
            val params = card.root.layoutParams as android.widget.LinearLayout.LayoutParams
            val weight = 0f
            if (params.weight != weight) {
                params.weight = weight
                card.root.layoutParams = params
            }
            val badge = when {
                !slot.open -> summaries[slot.id].orEmpty()
                slot.fresh -> getString(R.string.card_fresh)
                else -> ""
            }
            if (card.badge.text.toString() != badge) card.badge.text = badge
        }
    }

    /**
     * CardBoard.fit with each card's real height, open and folded, measured
     * at the stack's width now: an open card's last lines are never cut off
     * by the card below (DESIGN.md, "ห้ามตัดข้อมูล"). Before the first layout
     * there is nothing to measure against, and the slots pass unchanged.
     */
    private fun fitToStack(slots: List<CardBoard.Slot>, nowMs: Long): List<CardBoard.Slot> {
        val width = cardStack.width - cardStack.paddingLeft - cardStack.paddingRight
        // The stack is as tall as its content now (0.42.0), so the room it
        // may use is measured from Jarvis, whose bottom never moves: all the
        // way down to it, less Jarvis's 156dp floor and the gap above it.
        val jarvisLp = cardJarvis.layoutParams as android.view.ViewGroup.MarginLayoutParams
        val height = cardJarvis.bottom - cardStack.top - cardJarvis.minimumHeight - jarvisLp.topMargin -
            cardStack.paddingTop - cardStack.paddingBottom
        if (width <= 0 || height <= 0 || cardJarvis.bottom <= 0) return slots
        val unspecified = android.view.View.MeasureSpec.makeMeasureSpec(0, android.view.View.MeasureSpec.UNSPECIFIED)
        val openPx = HashMap<String, Int>()
        val barPx = HashMap<String, Int>()
        for (slot in slots) {
            val card = cards.getValue(slot.id)
            val rootLp = card.root.layoutParams as android.view.ViewGroup.MarginLayoutParams
            val inner = width - card.root.paddingLeft - card.root.paddingRight
            val exact = android.view.View.MeasureSpec.makeMeasureSpec(inner, android.view.View.MeasureSpec.EXACTLY)
            // Past sizes are cached per measure spec (View.mMeasureCache), and
            // this spec is not the one layout uses: without forcing, the first
            // answer ever measured — "กำลังโหลด…", one line — kept coming back
            // (0.38.0 log: weather measured 161 px open, 495 px on screen).
            forceLayoutTree(card.root)
            card.titlebar.measure(exact, unspecified)
            val titleLp = card.titlebar.layoutParams as android.view.ViewGroup.MarginLayoutParams
            val bar = rootLp.topMargin + rootLp.bottomMargin + card.root.paddingTop +
                card.root.paddingBottom + card.titlebar.measuredHeight + titleLp.topMargin + titleLp.bottomMargin
            card.body.measure(exact, unspecified)
            val bodyLp = card.body.layoutParams as android.view.ViewGroup.MarginLayoutParams
            barPx[slot.id] = bar
            openPx[slot.id] = bar + card.body.measuredHeight + bodyLp.topMargin + bodyLp.bottomMargin
        }
        // Our measuring left the views holding sizes for a spec layout does not
        // use; the next layout pass must measure them again for real.
        cardStack.requestLayout()
        val fitted = board.fit(slots, openPx, barPx, height, nowMs)
        val report = "avail=$height " + slots.joinToString(" ") { s ->
            "${s.id}:${openPx[s.id]}/${barPx[s.id]}${if (s.open) "o" else "f"}" +
                "${if (fitted.first { it.id == s.id }.open) "O" else "F"}"
        }
        if (report != lastFitReport) {
            lastFitReport = report
            android.util.Log.i(SCREEN_TAG, "fit $report")
        }
        return fitted
    }

    private var lastFitReport = ""

    private fun forceLayoutTree(view: android.view.View) {
        view.forceLayout()
        if (view is android.view.ViewGroup) {
            for (i in 0 until view.childCount) forceLayoutTree(view.getChildAt(i))
        }
    }

    /** The alarms window: the list, the stop button while one rings, its news. */
    private fun showAlarms(nowMs: Long) {
        shownAlarmsVersion = VoiceState.alarmsVersion
        val book = AlarmStore.load(this)
        val ringing = VoiceState.alarmRinging
        alarmsVisible = book.alarms.isNotEmpty() || ringing.isNotEmpty()
        val on = getString(R.string.alarm_on)
        val off = getString(R.string.alarm_off)
        val lines = book.alarms.joinToString("\n") { alarm ->
            val name = if (alarm.label.isNotEmpty()) "  ${alarm.label}" else ""
            // Every day or once, in words, on every row (0.42.0, Poom).
            "${DashboardState.clock12(alarm.time)}$name  · ${AlarmBook.repeatText(alarm)}" +
                "  · ${if (alarm.enabled) on else off}"
        }
        alarmsBody.text = RetroType.pixelify(lines, pixelFace)
        alarmStop.visibility = if (ringing.isNotEmpty()) android.view.View.VISIBLE
                               else android.view.View.GONE
        val next = book.next(System.currentTimeMillis(), java.util.TimeZone.getDefault())
        summaries["alarms"] = when {
            ringing.isNotEmpty() -> getString(R.string.alarm_ringing)
            next != null -> getString(R.string.alarm_next, DashboardState.clock12(next.first.time))
            else -> off
        }
        board.report("alarms", book.toJson() + "|" + ringing, nowMs)
        board.pin("alarms", ringing.isNotEmpty())
    }

    /**
     * The "อุปกรณ์ในบ้าน" card (0.45.0): one row per light or light switch —
     * name and room on the left, the state in words on the right ("เปิด",
     * "ปิด", "เปิด 1 จาก 3", "ออฟไลน์"), never a colour alone. At most
     * HomeCard.MAX_ROWS rows; the rest is counted. A dim line under them says
     * where it came from, that it is read only, and how old it is when stale.
     */
    private fun showHome(card: HomeCard.Card?) {
        homeCard = card
        homePage.removeAllViews()
        val system = card?.systems?.firstOrNull() ?: return
        val plex = ResourcesCompat.getFont(this, R.font.plex_thai)
        val (rows, more) = HomeCard.rows(system)
        for ((index, device) in rows.withIndex()) {
            homePage.addView(android.widget.LinearLayout(this).apply {
                orientation = android.widget.LinearLayout.HORIZONTAL
                isBaselineAligned = true
                if (index > 0) setPadding(0, dp(3), 0, 0)
                addView(TextView(this@MainActivity).apply {
                    text = HomeCard.label(device)
                    textSize = sp(R.dimen.type_secondary)
                    typeface = plex
                    setTextColor(ContextCompat.getColor(this@MainActivity, R.color.retro_text))
                    maxLines = 1
                    ellipsize = android.text.TextUtils.TruncateAt.END
                    includeFontPadding = false
                }, android.widget.LinearLayout.LayoutParams(0, android.view.ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                val lit = HomeCard.onCount(device) > 0
                addView(TextView(this@MainActivity).apply {
                    text = RetroType.pixelify(HomeCard.stateWord(device), pixelFace)
                    textSize = sp(R.dimen.type_secondary)
                    typeface = if (lit) android.graphics.Typeface.create(plex, android.graphics.Typeface.BOLD) else plex
                    setTextColor(ContextCompat.getColor(this@MainActivity,
                        if (lit) R.color.retro_text else R.color.retro_dim))
                    maxLines = 1
                    includeFontPadding = false
                    setPadding(dp(8), 0, 0, 0)
                })
            })
        }
        val foot = buildList {
            if (more > 0) add(getString(R.string.home_more, more))
            add(getString(R.string.home_source, system.name))
            if (card.stale && card.ageSeconds >= 60) add(getString(R.string.home_stale, card.ageSeconds / 60))
        }.joinToString(" · ")
        homePage.addView(TextView(this).apply {
            text = RetroType.pixelify(foot, pixelFace)
            textSize = sp(R.dimen.type_label)
            typeface = plex
            setTextColor(ContextCompat.getColor(this@MainActivity, R.color.retro_dim))
            setPadding(0, dp(5), 0, 0)
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
        })
        homePages.news("ewelink", HomeCard.signature(card), SystemClock.elapsedRealtime())
    }

    /** A size from res/values/type_scale.xml, in sp — the one type scale. */
    private fun sp(id: Int): Float {
        val value = android.util.TypedValue()
        resources.getValue(id, value, true)
        return android.util.TypedValue.complexToFloat(value.data)
    }

    private fun dp(value: Int): Int = (value * resources.displayMetrics.density).toInt()

    /**
     * Today's weather: labels over values, three even columns, as many rows as
     * it takes (0.36.0 — five numbers with PM2.5 did not fit four columns).
     * The last cell of a short row spans what is left, so a long level word
     * ("เริ่มมีผลต่อสุขภาพ") has room instead of being cut.
     */
    private var shownStats: List<Pair<String, String>> = emptyList()

    private fun showWeatherStats(stats: List<Pair<String, String>>) {
        if (stats == shownStats) return
        shownStats = stats
        weatherStats.removeAllViews()
        weatherStats.visibility = if (stats.isEmpty()) android.view.View.GONE else android.view.View.VISIBLE
        if (stats.isEmpty()) return
        val plex = ResourcesCompat.getFont(this, R.font.plex_thai)
        fun cell(text: CharSequence, sp: Float, color: Int) = TextView(this).apply {
            this.text = text
            textSize = sp
            typeface = plex
            setTextColor(ContextCompat.getColor(this@MainActivity, color))
            maxLines = 1
            ellipsize = android.text.TextUtils.TruncateAt.END
            includeFontPadding = false
        }
        val labelSp = sp(R.dimen.type_label)
        val valueSp = sp(R.dimen.type_secondary)
        for ((rowIndex, row) in stats.chunked(STAT_COLUMNS).withIndex()) {
            fun spanned(view: TextView, index: Int) = view.apply {
                if (index == row.lastIndex && row.size < STAT_COLUMNS) {
                    layoutParams = android.widget.TableRow.LayoutParams().apply {
                        span = STAT_COLUMNS - row.lastIndex
                    }
                }
            }
            weatherStats.addView(android.widget.TableRow(this).apply {
                if (rowIndex > 0) setPadding(0, dp(4), 0, 0)
                for ((index, stat) in row.withIndex()) {
                    addView(spanned(cell(stat.first, labelSp, R.color.retro_dim), index))
                }
            })
            weatherStats.addView(android.widget.TableRow(this).apply {
                isBaselineAligned = true
                for ((index, stat) in row.withIndex()) {
                    addView(spanned(cell(RetroType.pixelify(stat.second, pixelFace), valueSp,
                                         R.color.retro_text), index))
                }
            })
        }
    }

    /**
     * The commodities window as a table: a header line per section, then rows
     * of name | price | what follows. Prices are right-aligned in their own
     * column so they line up (DESIGN.md, "การ์ดทองและน้ำมัน"). Fuel is GONE with
     * a broker too old to send it. Rebuilt only when the rows change.
     */
    private var shownCommodities = ""

    private fun showCommodities(c: DashboardState.Commodities) {
        val key = listOf(c.goldHeader, c.gold.joinToString { "${it.label}${it.price}${it.extra}" },
                         c.oilHeader, c.oil?.joinToString { "${it.label}${it.price}${it.extra}" })
            .joinToString("|")
        if (key == shownCommodities) return
        shownCommodities = key

        goldHeader.text = RetroType.pixelify(c.goldHeader, pixelFace)
        fillTable(goldTable, c.gold, labelSp = sp(R.dimen.type_primary), extraSp = sp(R.dimen.type_minor),
                  extraDim = true)
        // No fuel data (an older broker): no fuel page and, with one page left,
        // no tabs — the card is the gold table alone, as before.
        commodityPages.setPageAvailable("oil", c.oilHeader != null)
        if (c.oilHeader != null) {
            oilHeader.text = RetroType.pixelify(c.oilHeader, pixelFace)
            fillTable(oilTable, c.oil.orEmpty(), labelSp = sp(R.dimen.type_secondary),
                      extraSp = sp(R.dimen.type_minor), extraDim = false)
        }
    }

    private fun fillTable(table: android.widget.TableLayout, rows: List<DashboardState.Row>,
                          labelSp: Float, extraSp: Float, extraDim: Boolean) {
        table.removeAllViews()
        val density = resources.displayMetrics.density
        fun cell(text: CharSequence, sp: Float, end: Boolean, color: Int, padEndDp: Int) =
            TextView(this).apply {
                this.text = text
                textSize = sp
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.plex_thai)
                setTextColor(ContextCompat.getColor(this@MainActivity, color))
                maxLines = 1
                ellipsize = android.text.TextUtils.TruncateAt.END
                gravity = if (end) android.view.Gravity.END else android.view.Gravity.START
                // No top padding: the row's own line height is the spacing, so
                // the window is no taller than the paragraph it replaced.
                setPadding(0, 0, (padEndDp * density).toInt(), 0)
                includeFontPadding = false
            }
        for (row in rows) {
            // Baseline-aligned, not centred: a Thai label and pixel digits
            // have different heights, and centring them left the digits
            // floating above the words (seen on the A07, 0.34.0).
            table.addView(android.widget.TableRow(this).apply {
                isBaselineAligned = true
                addView(cell(row.label, labelSp, false, R.color.retro_text, 10))
                addView(cell(RetroType.pixelify(row.price, pixelFace), labelSp, true, R.color.retro_text, 10))
                addView(cell(RetroType.pixelify(row.extra, pixelFace), extraSp, false,
                             if (extraDim) R.color.retro_dim else R.color.retro_text, 0))
            })
        }
    }

    /**
     * New text in the Jarvis window. A new answer starts at the top with the
     * voice-following switched back on; long text reads from the left, since
     * a centred paragraph of Thai is hard to follow line to line.
     */
    private fun showTranscript(line: String) {
        shownTranscript = line
        transcript.text = RetroType.pixelify(line, pixelFace)
        if (VoiceState.reply != followedReply) {
            followedReply = VoiceState.reply
            followTakenOver = false
            transcriptScroll.scrollTo(0, 0)
        }
        transcript.post {
            transcript.gravity = if (transcript.height > transcriptScroll.height)
                android.view.Gravity.START or android.view.Gravity.TOP
            else android.view.Gravity.CENTER
        }
    }

    /**
     * While an answer plays, keeps the line being said a third of the way
     * down the window — until a finger takes over. See ui/SpeechFollow.
     */
    private val followSpeech = object : Runnable {
        override fun run() {
            val layout = transcript.layout
            val offset = SpeechFollow.spokenOffset(
                transcript.text, VoiceState.speakingSinceMs, VoiceState.speakingDurationMs,
                SystemClock.elapsedRealtime())
            if (layout != null && offset != null && !followTakenOver &&
                transcript.height > transcriptScroll.height) {
                val lineTop = layout.getLineTop(layout.getLineForOffset(offset)) +
                    transcript.paddingTop
                val raw = SpeechFollow.scrollTarget(
                    lineTop, transcriptScroll.height, transcript.height)
                // Snapped to the top of a line, so the window never opens on half
                // a line of Thai cut through its vowels (seen on 0.30.2).
                val max = (transcript.height - transcriptScroll.height).coerceAtLeast(0)
                val line = layout.getLineForVertical((raw - transcript.paddingTop).coerceAtLeast(0))
                val target = (if (line == 0) 0
                              else layout.getLineTop(line) + transcript.paddingTop).coerceIn(0, max)
                if (target != transcriptScroll.scrollY) transcriptScroll.smoothScrollTo(0, target)
            }
            handler.postDelayed(this, FOLLOW_INTERVAL_MILLIS)
        }
    }

    /**
     * Each coin's own pixel icon in front of its ticker, the height of the
     * text. Inserted from the end so the earlier indices stay true. A coin
     * with no icon of its own gets the generic coin: the top four can change.
     */
    private fun withCoinIcons(text: CharSequence, textSizePx: Float): CharSequence {
        val out = android.text.SpannableStringBuilder(text)
        val size = (textSizePx * COIN_ICON_SCALE).toInt()
        for ((index, ticker) in RetroType.coinTickers(text).asReversed()) {
            val icon = ContextCompat.getDrawable(this, COIN_ICONS[ticker] ?: R.drawable.ic_pixel_coin)
                ?: continue
            icon.setBounds(0, 0, size, size)
            out.insert(index, "\uFFFC ")
            val align = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q)
                android.text.style.DynamicDrawableSpan.ALIGN_CENTER
            else android.text.style.DynamicDrawableSpan.ALIGN_BASELINE
            out.setSpan(android.text.style.ImageSpan(icon, align), index, index + 1,
                        android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        }
        return out
    }

    private val dpm: DevicePolicyManager
        get() = getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager

    private val isDeviceOwner: Boolean
        get() = dpm.isDeviceOwnerApp(packageName)

    // The transcript's touch listener only notes the touch and returns false, so
    // the ScrollView's own handling — and its accessibility — are untouched.
    @android.annotation.SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        com.mammonrn.phoneaikiosk.voice.SoakProbe.noteCreate(this, "activity")
        setContentView(R.layout.activity_main)

        pixelFace = ResourcesCompat.getFont(this, R.font.press_start_2p)
            ?: android.graphics.Typeface.MONOSPACE

        location = KioskLocation(this)

        status = findViewById(R.id.status)
        voiceStatus = findViewById(R.id.voice_status)
        transcript = findViewById(R.id.transcript)
        transcriptScroll = findViewById(R.id.transcript_scroll)
        // A finger on the answer: stop following the voice for this answer,
        // and keep it on screen while it is being read. Returns false, so the
        // ScrollView still gets the touch and scrolls.
        transcriptScroll.setOnTouchListener { _, event ->
            if (event.actionMasked == android.view.MotionEvent.ACTION_DOWN ||
                event.actionMasked == android.view.MotionEvent.ACTION_MOVE) {
                followTakenOver = true
                transcriptTouchedAt = SystemClock.elapsedRealtime()
            }
            false
        }
        taskbarClock = findViewById(R.id.taskbar_clock)
        taskbarDate = findViewById(R.id.taskbar_date)
        cardJarvis = findViewById(R.id.card_jarvis)
        netText = findViewById(R.id.net_text)
        batteryIcon = findViewById(R.id.battery_icon)
        batteryText = findViewById(R.id.battery_text)
        homePages = findViewById(R.id.home_pages)
        homePage = findViewById(R.id.home_page_ewelink)
        weatherTitle = findViewById(R.id.weather_title)
        weatherBody = findViewById(R.id.weather_body)
        goldHeader = findViewById(R.id.gold_header)
        goldTable = findViewById(R.id.gold_table)
        oilHeader = findViewById(R.id.oil_header)
        oilTable = findViewById(R.id.oil_table)
        // The Control Panel: our own activity, so it stays inside lock task.
        findViewById<android.view.View>(R.id.settings_button).setOnClickListener {
            startActivity(Intent(this, com.mammonrn.phoneaikiosk.settings.SettingsActivity::class.java))
        }
        cryptoBody = findViewById(R.id.crypto_body)
        cryptoBodyRight = findViewById(R.id.crypto_body_right)
        weatherIcon = findViewById(R.id.weather_icon)
        goldTitle = findViewById(R.id.gold_title)
        jarvisState = findViewById(R.id.jarvis_state)
        sunRow = findViewById(R.id.sun_row)
        weatherStats = findViewById(R.id.weather_stats)
        weatherOutlook = findViewById(R.id.weather_outlook)
        cardStack = findViewById(R.id.card_stack)
        commodityPages = findViewById(R.id.commodity_pages)
        // Turning a page is a touch on the card: it stays open two minutes and
        // CardBoard.fit will not fold it under the finger.
        commodityPages.onTurned = { board.touch("gold", SystemClock.elapsedRealtime()) }
        alarmsBody = findViewById(R.id.alarms_body)
        alarmStop = findViewById(R.id.alarm_stop)
        setUpCards()
        // fitToStack needs the stack's height, which exists only after the
        // first layout: render again whenever that height changes.
        cardStack.addOnLayoutChangeListener { _, _, top, _, bottom, _, oldTop, _, oldBottom ->
            if (bottom - top != oldBottom - oldTop) {
                cardStack.post { renderCards(SystemClock.elapsedRealtime()) }
            }
        }
        sunriseText = findViewById(R.id.sunrise_text)
        sunsetText = findViewById(R.id.sunset_text)

        // The system bars are already off via Samsung's gesture setting, but a
        // setting is somebody's preference and this is the app's own statement.
        // Belt and braces: an update, a guest mode or a reset could put them
        // back, and a navigation bar on a kiosk is an exit nobody chose.
        hideSystemBars()

        findViewById<android.view.View>(R.id.exit_corner).setOnClickListener {
            onCornerTap()
        }

        // "จาร์วิส": the same as saying Hey Jarvis. The service decides whether
        // a question can start now (never on top of one already running).
        findViewById<android.view.View>(R.id.jarvis_button).setOnClickListener {
            VoiceService.start(this, VoiceService.ACTION_BUTTON_LISTEN)
        }

        // If the platform ever puts a keyguard between a dark screen and this
        // one, the kiosk is still what lights up. In lock task mode without
        // LOCK_TASK_FEATURE_KEYGUARD there is no keyguard to show over, so
        // today this changes nothing; it is here so that "Hey Jarvis" wakes to
        // the kiosk rather than to a lock screen if that ever stops being true.
        setShowWhenLocked(true)

        // Back is swallowed unconditionally. There is nothing behind this
        // screen to go back to: the activity is the root of its task and the
        // HOME activity, so finishing it just launches it again.
        //
        // Registered through OnBackInvokedDispatcher on API 33+ because the
        // legacy onBackPressed() path is not guaranteed to be called once an
        // app opts in to predictive back, and through the override below on
        // older releases.
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT,
            ) { /* stay put */ }
        }

        applyDeviceOwnerPolicies()
        // Android forgets alarms on reboot and on update; this activity is HOME
        // and starts after both, so the next alarm is booked again here.
        AlarmScheduler.schedule(this)
        ringIfAsked(intent)
        grantMicrophoneToSelf()
        grantLocationToSelf()
        // After the grants: 4G or 5G needs READ_PHONE_STATE, granted just above.
        networkWatch = com.mammonrn.phoneaikiosk.ui.NetworkWatch(this) { word ->
            netText.text = word
            netText.contentDescription = getString(R.string.net_description, word)
        }.also { it.start() }

        // A fix on every fresh start, so a kiosk that was carried somewhere and
        // plugged back in does not show the old town's weather while it waits
        // out the half-hour. After this, KioskLocation decides.
        location.refreshIfStale(force = true)
    }

    /**
     * Immersive, sticky.
     *
     * Re-applied on every resume as well, because the flags are cleared by a
     * dialog, by the screen turning off and by some system interactions —
     * setting them once at creation lasts until the first time anything else
     * happens.
     */
    private fun hideSystemBars() {
        val controller = WindowCompat.getInsetsController(window, window.decorView)
        controller.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controller.hide(WindowInsetsCompat.Type.systemBars())
        WindowCompat.setDecorFitsSystemWindows(window, false)
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION", "MissingSuperCall")
    override fun onBackPressed() {
        // Deliberately no super call — see onCreate.
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        ringIfAsked(intent)
    }

    /** Brought up by AlarmReceiver because the voice service was not running. */
    private fun ringIfAsked(intent: Intent?) {
        val id = intent?.getIntExtra(VoiceService.EXTRA_ALARM_ID, -1) ?: -1
        if (id >= 0) {
            intent?.removeExtra(VoiceService.EXTRA_ALARM_ID)
            VoiceService.ringFromActivity(this, id)
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(tick)
        handler.post(followSpeech)
        hideSystemBars()
        handler.post(refreshDashboard)

        // NOT_EXPORTED is the right answer even though these are protected
        // system broadcasts: it is what Android 14+ wants declared, and the
        // system still delivers its own broadcasts to a receiver registered
        // this way.
        ContextCompat.registerReceiver(
            this,
            powerReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_POWER_CONNECTED)
                addAction(Intent.ACTION_POWER_DISCONNECTED)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        // The receiver only reports changes, so the state at this moment has
        // to be read directly — otherwise a kiosk that was already plugged in
        // when it launched (which is every reboot on a charger) would sit
        // there letting its screen time out.
        charging = isCharging()
        // Coming back on — a touch, a wake word, the power key — is use, and
        // restarts the five minutes.
        idleScreen.used(SystemClock.elapsedRealtime())
        applyScreenRule()

        // The Control Panel's WiFi button opens the system's WiFi panel by
        // allowing the settings app for that one visit; back here, it is the
        // kiosk's own list again, whatever happened in between.
        com.mammonrn.phoneaikiosk.settings.WifiPanel.restore(this)
        enterLockTaskIfWanted()

        // Started from here and only from here. A microphone foreground service
        // cannot be launched from the background or from a BOOT_COMPLETED
        // receiver on Android 15, and this activity is the HOME activity, so the
        // system opens it at boot and on every press of Home — which makes this
        // both the legal place to start it and the one that runs most often.
        // Also the recovery path: if the service dies, the next resume restarts
        // it without anything else having to notice.
        VoiceService.start(this)
    }

    override fun onDestroy() {
        networkWatch?.stop()
        networkWatch = null
        super.onDestroy()
    }

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
        handler.removeCallbacks(followSpeech)
        // Stopped with the clock: a paused kiosk polling the broker every
        // minute forever is a background job nobody asked for.
        handler.removeCallbacks(refreshDashboard)
        unregisterReceiver(powerReceiver)
    }

    /** Reads the sticky battery broadcast for the plugged-in state right now. */
    private fun isCharging(): Boolean {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val plugged = status?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        return plugged != 0
    }

    private fun applyKeepScreenOn(on: Boolean) {
        if (on == keepingScreenOn) return
        keepingScreenOn = on
        if (on) {
            window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        } else {
            window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        }
    }

    /**
     * The two diagnostic areas: the Jarvis window's three small lines and the
     * taskbar's middle. Hidden unless debug mode was switched on over adb.
     * The taskbar's goes INVISIBLE rather than GONE so the clock tray keeps
     * its place on the right.
     */
    private fun showDiagnostics(on: Boolean) {
        if (on) {
            status.text = VoiceState.kioskLine
            voiceStatus.text = VoiceState.statusLine() + "\n" + VoiceState.secondLine() +
                "\n" + VoiceState.thirdLine()
        }
        val lines = if (on) android.view.View.VISIBLE else android.view.View.GONE
        val middle = if (on) android.view.View.VISIBLE else android.view.View.INVISIBLE
        if (voiceStatus.visibility != lines) voiceStatus.visibility = lines
        if (status.visibility != middle) status.visibility = middle
    }

    /** The tray's battery, from the sticky ACTION_BATTERY_CHANGED broadcast. */
    private fun showBattery() {
        val status = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED)) ?: return
        val percent = BatteryLabel.percent(
            status.getIntExtra(BatteryManager.EXTRA_LEVEL, -1),
            status.getIntExtra(BatteryManager.EXTRA_SCALE, -1),
        )
        val plugged = status.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
        batteryText.text = BatteryLabel.text(percent)
        batteryIcon.setImageResource(when (BatteryLabel.icon(percent, plugged)) {
            BatteryLabel.Icon.CHARGING -> R.drawable.ic_pixel_battery_charging
            BatteryLabel.Icon.LOW -> R.drawable.ic_pixel_battery_low
            BatteryLabel.Icon.NORMAL -> R.drawable.ic_pixel_battery
        })
        batteryIcon.contentDescription = getString(R.string.battery_description) + " " +
            batteryText.text + if (plugged) " กำลังชาร์จ" else ""
    }

    /** Every touch anywhere is use. Seen here, before any view can eat it. */
    override fun dispatchTouchEvent(event: android.view.MotionEvent): Boolean {
        idleScreen.used(SystemClock.elapsedRealtime())
        return super.dispatchTouchEvent(event)
    }

    /**
     * IdleScreen's decision for this second, carried out. Runs from the tick,
     * so only while this activity is resumed: when Maps or another launcher is
     * on top, their screen is the system's business, not this timer's.
     */
    private fun applyScreenRule() {
        val now = SystemClock.elapsedRealtime()
        val busy = IdleScreen.voiceBusy(
            VoiceState.wake, VoiceState.stt, VoiceState.chat, VoiceState.tts)
        when (idleScreen.decide(now, charging, busy)) {
            IdleScreen.Action.KEEP_ON -> applyKeepScreenOn(true)
            IdleScreen.Action.RELEASE -> applyKeepScreenOn(false)
            IdleScreen.Action.SLEEP -> {
                applyKeepScreenOn(false)
                sleepNow()
            }
        }
        VoiceState.screenIdleSeconds = idleScreen.idleFor(now) / 1000
    }

    /**
     * Turns the screen off. The Device Owner's lockNow() is the one call an app
     * can make that does it immediately — PowerManager.goToSleep is a system
     * permission — and it needs `force-lock` in device_admin.xml, which is the
     * one policy that file now asks for.
     *
     * "LOCK" IS A MISNOMER HERE. In lock task mode the keyguard is off unless
     * LOCK_TASK_FEATURE_KEYGUARD is set, which this kiosk never sets, so this
     * is the display going dark and nothing else: the task stays locked, the
     * microphone keeps listening, and the next thing on screen is this activity.
     *
     * If the platform refuses, the flag is already cleared and the system's own
     * timeout turns the screen off later instead; the status line says
     * `lock-refused` so the difference is visible rather than silent.
     */
    private fun sleepNow() {
        if (!isDeviceOwner) {
            VoiceState.screenNote = "not-owner"
            return
        }
        try {
            dpm.lockNow()
            VoiceState.screenSleeps += 1
            VoiceState.screenNote = "slept"
            Log.i(SCREEN_TAG, "screen off after ${IdleScreen.IDLE_MS / 1000}s idle")
        } catch (e: SecurityException) {
            VoiceState.screenNote = "lock-refused"
            Log.w(SCREEN_TAG, "lockNow refused: ${e.javaClass.simpleName}")
        }
    }

    /**
     * Grants this app the microphone, with no dialog.
     *
     * A Device Owner can set a runtime permission's grant state for any app,
     * itself included, and the user is never asked. That matters here because
     * there is nobody to ask: the kiosk runs in lock task mode where a
     * permission dialog would be a modal the household cannot dismiss, and on a
     * device with no Google account there is nobody logged in to dismiss it.
     *
     * If this silently fails, the service reports `mic=no-permission` rather
     * than crashing, and `adb shell pm grant` is the way in until it is fixed.
     */
    /**
     * The file manager's NAS (0.44.0): Android 16 gates the house's network
     * behind NEARBY_WIFI_DEVICES while local network protection is opt-in,
     * and API 37 names it ACCESS_LOCAL_NETWORK. Each is asked for only where
     * the platform has it, so a phone without one records no refusal.
     */
    private fun localNetworkPermissions(): List<String> = buildList {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(android.Manifest.permission.NEARBY_WIFI_DEVICES)
        if (Build.VERSION.SDK_INT >= 37) add("android.permission.ACCESS_LOCAL_NETWORK")
    }

    private fun grantMicrophoneToSelf() {
        if (!isDeviceOwner) return

        // POST_NOTIFICATIONS as well as RECORD_AUDIO. The foreground service ran
        // on the A07 but the system logged "Suppressing notification ... by user
        // request": since Android 13 posting one is a runtime permission, and
        // nobody granted it because there is nobody to ask. The service works
        // either way — it was foreground with type 0x80 — but a microphone
        // foreground service whose notification is suppressed is a kiosk holding
        // the mic with no visible sign of it, which is the wrong default for a
        // device in somebody's living room.
        // CAMERA since 0.37.0, for the identity check: the same "nobody can
        // answer a dialog" reason. Used only while VerifyActivity is open.
        val permissions = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
            android.Manifest.permission.CAMERA,
            // 0.42.0: which mobile network — 4G or 5G — for the taskbar tray
            // (ui/NetworkWatch). Read for that word only; nothing is logged.
            android.Manifest.permission.READ_PHONE_STATE,
        ) + localNetworkPermissions()
        for (permission in permissions) {
            try {
                dpm.setPermissionGrantState(
                    KioskDeviceAdminReceiver.componentName(this),
                    packageName,
                    permission,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            } catch (e: SecurityException) {
                VoiceState.lastError = "grant refused: ${permission.substringAfterLast('.')}"
            } catch (e: IllegalArgumentException) {
                // Thrown for a permission the platform will not let a device
                // owner set. Recorded rather than fatal.
                VoiceState.lastError = "grant rejected: ${permission.substringAfterLast('.')}"
            }
        }
    }

    /**
     * Grants this app coarse location, with no dialog, and then checks.
     *
     * Same reasoning as the microphone: in lock task mode a permission prompt
     * is a modal the household cannot dismiss, and on a device with no Google
     * account there is nobody logged in to dismiss it.
     *
     * THE READ-BACK IS THE POINT. setPermissionGrantState returns nothing —
     * not a boolean, not a state — so "we asked for it" and "we have it" are
     * two different facts, and only the second one decides whether the weather
     * is right. checkSelfPermission is asked immediately afterwards and the
     * answer goes on the status line, where `loc=no-permission` is a visible
     * symptom rather than a silent fallback to the university.
     *
     * COARSE ONLY. ACCESS_FINE_LOCATION is not requested here and is not in the
     * manifest; granting it would be a change to what this kiosk knows about
     * the household, not a bug fix.
     */
    private fun grantLocationToSelf() {
        if (!isDeviceOwner) return
        val permission = android.Manifest.permission.ACCESS_COARSE_LOCATION
        try {
            dpm.setPermissionGrantState(
                KioskDeviceAdminReceiver.componentName(this),
                packageName,
                permission,
                DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
            )
        } catch (e: SecurityException) {
            VoiceState.lastError = "grant refused: COARSE_LOCATION"
        } catch (e: IllegalArgumentException) {
            VoiceState.lastError = "grant rejected: COARSE_LOCATION"
        }
        if (checkSelfPermission(permission) != PackageManager.PERMISSION_GRANTED) {
            // Not fatal, and deliberately not retried in a loop: the screen
            // still works, the weather is the university's, and this says why.
            VoiceState.lastError = "location not granted"
        }
    }

    /**
     * What the phone heard and what it answered.
     *
     * Shown because misheard Thai is the most common failure in the voice path,
     * and the person standing there is the only one who can catch it.
     */
    private fun transcriptLine(): String = buildString {
        // THE WAKE WORD HAS TO BE VISIBLE THE MOMENT IT LANDS. Before this, the
        // only sign the kiosk had heard you was the answer several seconds
        // later, so anyone unsure said it again — and on versionCode 7 that
        // started a second turn on top of the first.
        if (VoiceState.wakeOnly) {
            append("โหมดทดสอบคำปลุก · ได้ยินแล้ว ${VoiceState.detections} ครั้ง")
            append("  (คะแนนล่าสุด %.3f)".format(VoiceState.wakeScore))
            return@buildString
        }
        when {
            VoiceState.wake == "heard" && VoiceState.heard.isEmpty() ->
                append("ฟังอยู่ครับ เชิญถามได้เลย")
            VoiceState.lastCancel.isNotEmpty() && VoiceState.heard.isEmpty() ->
                // Short on purpose: a kiosk that explains itself at length every
                // time somebody says its name and then changes their mind is
                // worse than one that just goes quiet.
                append("ไม่ได้ยินคำถามครับ")
        }
        if (VoiceState.heard.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("ได้ยิน: ${VoiceState.heard}")
        }
        if (VoiceState.reply.isNotEmpty()) {
            if (isNotEmpty()) append("\n")
            append("ตอบ: ${VoiceState.reply}")
        }
    }

    /**
     * Policies that make this app the thing the device comes back to.
     *
     * Both are idempotent and both are re-applied on every launch, so a
     * device that was provisioned before this code existed picks them up by
     * being opened once.
     */
    private fun applyDeviceOwnerPolicies() {
        if (!isDeviceOwner) return
        val admin = KioskDeviceAdminReceiver.componentName(this)

        // Without the allowlist, startLockTask() falls back to screen pinning,
        // which shows a "hold Back and Overview to unpin" prompt — an exit
        // route the kiosk is not supposed to have.
        //
        // Google Maps is on the list because phase 4 opens it, and an app that
        // is not on the list cannot appear at all while the task is locked.
        // THREE PACKAGES, NAMED. Not "every Google app", not a prefix: the list
        // is the boundary of what this kiosk can ever put on screen, and it is
        // worth having to edit it deliberately. Xiaomi Home is the third, for
        // "ขอดูกล้อง" — Poom's decision, one package, nothing Xiaomi else.
        dpm.setLockTaskPackages(admin, LockTaskAllowlist.packages(packageName))

        // Re-checked before every action too: Maps can be installed while the
        // kiosk is already running, which is exactly what happened on the A07.
        VoiceState.mapsState = MapsLauncher.refreshState(this)

        // Makes this the HOME activity the system resolves to without a
        // chooser, which is also what puts the kiosk back on screen after a
        // reboot: the system launches HOME on boot, long before any app code
        // of ours could ask it to.
        val home = IntentFilter(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addCategory(Intent.CATEGORY_DEFAULT)
        }
        dpm.clearPackagePersistentPreferredActivities(admin, packageName)
        dpm.addPersistentPreferredActivity(
            admin,
            home,
            ComponentName(this, MainActivity::class.java),
        )
    }

    private fun enterLockTaskIfWanted() {
        if (!isDeviceOwner || unlockedUntilRestart) return
        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) return

        try {
            startLockTask()
        } catch (e: IllegalStateException) {
            // Reported on screen rather than swallowed: a kiosk that silently
            // failed to lock looks exactly like one that locked.
            Toast.makeText(this, getString(R.string.lock_failed, e.message), Toast.LENGTH_LONG)
                .show()
        }
    }

    /**
     * Ten taps in the bottom-right corner leaves the kiosk.
     *
     * Stops lock task mode and hands the screen to whatever other HOME app the
     * device has (One UI Home, on the target phone). This app stays the
     * *preferred* HOME, so the Home button still returns here — but it returns
     * here unlocked, and stays unlocked until the process restarts or the
     * device reboots. That is the deliberate trade: a reboot has to put the
     * kiosk back, which it can only do if this app is still HOME.
     */
    private fun onCornerTap() {
        if (!tapGate.onTap(SystemClock.elapsedRealtime())) {
            VoiceState.kioskLine = statusLine()
            return
        }

        unlockedUntilRestart = true

        if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) {
            stopLockTask()
        }

        val otherHome = otherHomeActivity()
        if (otherHome == null) {
            Toast.makeText(this, R.string.no_other_launcher, Toast.LENGTH_LONG).show()
            VoiceState.kioskLine = statusLine()
            return
        }

        startActivity(
            Intent(Intent.ACTION_MAIN)
                .addCategory(Intent.CATEGORY_HOME)
                .setComponent(otherHome)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
        )
    }

    /** A HOME activity that is not this app, or null if the device has none. */
    private fun otherHomeActivity(): ComponentName? {
        val intent = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager
            .queryIntentActivities(intent, PackageManager.MATCH_DEFAULT_ONLY)
            .firstOrNull { it.activityInfo.packageName != packageName }
            ?.let { ComponentName(it.activityInfo.packageName, it.activityInfo.name) }
    }

    private fun lockTaskState(): Int =
        (getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager).lockTaskModeState

    /**
     * One line of state, on screen.
     *
     * This is the only way to tell from the phone itself whether provisioning
     * worked, and it is what the manual test steps read.
     */
    private fun statusLine(): String {
        val owner = getString(if (isDeviceOwner) R.string.yes else R.string.no)
        val locked = getString(
            if (lockTaskState() != ActivityManager.LOCK_TASK_MODE_NONE) R.string.on else R.string.off,
        )
        val awake = getString(if (keepingScreenOn) R.string.on else R.string.off)
        val taps = tapGate.progress
        // Deliberately no token here, not even a fingerprint of it: this screen
        // faces a room. Whether one is installed is the only part that helps.
        val hasToken = getString(if (TokenStore(this).hasToken()) R.string.yes else R.string.no)
        return getString(R.string.status_line, owner, locked, awake, hasToken, taps,
                         TapGate.TAPS_REQUIRED)
    }

    companion object {
        /**
         * How often the screen asks the broker.
         *
         * The broker caches each source on its own schedule, so this is not a
         * rate limit question: it is how stale the numbers on a wall are
         * allowed to look. A minute, matching the fastest source's own
         * lifetime.
         */
        const val DASHBOARD_INTERVAL_MILLIS = 60_000L

        /** Its own logcat tag, so `adb logcat -s KioskDashboard:*`
         *  shows the screen refreshing without the voice pipeline's
         *  traffic on top of it. */
        const val DASHBOARD_TAG = "KioskDashboard"

        /** `adb logcat -s KioskScreen:*` for the idle rule on its own. */
        const val SCREEN_TAG = "KioskScreen"

        /** How long "ยังไม่เปิดใช้งาน" stays under the Google Home button. */

        /** A blank line between two coins, as a fraction of a full one. */
        const val COIN_GAP = 0.4f

        /** Today's weather numbers per row. */
        const val STAT_COLUMNS = 3

        /** How often the Jarvis window checks where the voice has got to. */
        const val FOLLOW_INTERVAL_MILLIS = 250L

        /** A touch on the answer keeps it up this long past the touch. */
        const val READING_HOLD_MS = 30_000L

        /** A coin icon's side, as a share of the text size: level with the digits. */
        const val COIN_ICON_SCALE = 0.95f

        /** Our own drawings, one per coin (DESIGN.md, "Icons"). */
        val COIN_ICONS = mapOf(
            "BTC" to R.drawable.ic_pixel_btc,
            "ETH" to R.drawable.ic_pixel_eth,
            "BNB" to R.drawable.ic_pixel_bnb,
            "XRP" to R.drawable.ic_pixel_xrp,
        )


        /**
         * Set by the escape hatch, cleared by the process dying.
         *
         * Deliberately not persisted. A reboot must always come back locked,
         * and anything written to disk would have to be cleared at boot by
         * code that races the system launching HOME — a race the kiosk would
         * lose by staying unlocked. In memory, the question cannot come up.
         */
        @Volatile
        private var unlockedUntilRestart = false
    }
}
