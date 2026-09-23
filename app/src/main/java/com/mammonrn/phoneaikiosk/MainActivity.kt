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
import com.mammonrn.phoneaikiosk.home.HomeControl
import com.mammonrn.phoneaikiosk.ui.BatteryLabel
import com.mammonrn.phoneaikiosk.ui.FadingLine
import com.mammonrn.phoneaikiosk.ui.RetroType
import com.mammonrn.phoneaikiosk.ui.ThaiDate
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
    private lateinit var taskbarClock: TextView
    private lateinit var taskbarDate: TextView
    private lateinit var batteryIcon: ImageView
    private lateinit var batteryText: TextView
    private var ticks = 0
    private lateinit var homeNote: TextView
    private lateinit var weatherTitle: TextView
    private lateinit var weatherBody: TextView
    private lateinit var goldBody: TextView
    private lateinit var cryptoBody: TextView
    private lateinit var cryptoBodyRight: TextView
    private lateinit var weatherIcon: ImageView
    private lateinit var goldTitle: TextView
    private lateinit var jarvisState: TextView
    private lateinit var sunRow: android.view.View
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
    private val homeControl: HomeControl = HomeControl.NotConnected

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
            taskbarDate.text = ThaiDate.short(java.util.Calendar.getInstance().apply { time = now })
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
            val busy = IdleScreen.voiceBusy(
                VoiceState.wake, VoiceState.stt, VoiceState.chat, VoiceState.tts)
            transcript.text = RetroType.pixelify(
                recentTurn.visible(transcriptLine(), SystemClock.elapsedRealtime(), busy)
                    .ifEmpty { getString(R.string.kiosk_prompt) },
                pixelFace,
            )
            jarvisState.text = DashboardState.jarvisState(
                VoiceState.mic, VoiceState.stt, VoiceState.chat, VoiceState.tts,
                getString(R.string.jarvis_ready),
                getString(R.string.jarvis_listening),
                getString(R.string.jarvis_thinking),
                getString(R.string.jarvis_speaking),
                getString(R.string.jarvis_offline),
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
                    else -> Log.w(DASHBOARD_TAG, "refresh failed: " +
                        Broker.describe(attempt.exceptionOrNull()))
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
        // Numbers into the pixel face, the freshness note turned down. The
        // strings themselves are DashboardState's business and are not touched
        // here — this only decides what they look like.
        val dim = ContextCompat.getColor(this, R.color.retro_dim)
        weatherBody.text = RetroType.pixelifyHeadline(screen.weather.text, pixelFace, dim)
        goldBody.text = RetroType.pixelifyWithAge(screen.gold.text, pixelFace, dim)
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

        val place = screen.place.ifEmpty { getString(R.string.place_unknown) }
        weatherTitle.text = "${getString(R.string.window_weather)} · $place"

        // The gold title carries the PURITY, as a word and a number:
        // "ราคาทอง · ความบริสุทธิ์ 96.5%". What the price move is measured
        // against moved down into the panel's footnote, next to the moves it
        // explains, so each percentage on this window sits beside its label.
        goldTitle.text = if (screen.goldPurity.isEmpty()) getString(R.string.window_gold)
                         else "${getString(R.string.window_gold)} · " +
                             getString(R.string.gold_purity, screen.goldPurity)
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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        pixelFace = ResourcesCompat.getFont(this, R.font.press_start_2p)
            ?: android.graphics.Typeface.MONOSPACE

        location = KioskLocation(this)

        status = findViewById(R.id.status)
        voiceStatus = findViewById(R.id.voice_status)
        transcript = findViewById(R.id.transcript)
        taskbarClock = findViewById(R.id.taskbar_clock)
        taskbarDate = findViewById(R.id.taskbar_date)
        batteryIcon = findViewById(R.id.battery_icon)
        batteryText = findViewById(R.id.battery_text)
        homeNote = findViewById(R.id.home_note)
        weatherTitle = findViewById(R.id.weather_title)
        weatherBody = findViewById(R.id.weather_body)
        goldBody = findViewById(R.id.gold_body)
        cryptoBody = findViewById(R.id.crypto_body)
        cryptoBodyRight = findViewById(R.id.crypto_body_right)
        weatherIcon = findViewById(R.id.weather_icon)
        goldTitle = findViewById(R.id.gold_title)
        jarvisState = findViewById(R.id.jarvis_state)
        sunRow = findViewById(R.id.sun_row)
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

        findViewById<android.view.View>(R.id.home_button).setOnClickListener {
            onHomeTap()
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
        grantMicrophoneToSelf()
        grantLocationToSelf()

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

    override fun onResume() {
        super.onResume()
        handler.post(tick)
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

    override fun onPause() {
        super.onPause()
        handler.removeCallbacks(tick)
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
     * The Google Home button. Says it is not switched on yet, in its own window
     * rather than a toast, and puts the old line back after a few seconds.
     */
    private fun onHomeTap() {
        if (homeControl.available) {
            homeControl.open(this)?.let { homeNote.text = it }
            return
        }
        homeNote.text = getString(R.string.home_disabled)
        handler.removeCallbacks(restoreHomeNote)
        handler.postDelayed(restoreHomeNote, HOME_NOTE_MILLIS)
    }

    private val restoreHomeNote = Runnable {
        homeNote.text = getString(R.string.home_not_connected)
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
        val permissions = listOf(
            android.Manifest.permission.RECORD_AUDIO,
            android.Manifest.permission.POST_NOTIFICATIONS,
        )
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
        const val HOME_NOTE_MILLIS = 4_000L

        /** A blank line between two coins, as a fraction of a full one. */
        const val COIN_GAP = 0.4f

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
