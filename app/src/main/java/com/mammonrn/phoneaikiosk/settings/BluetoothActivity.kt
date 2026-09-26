package com.mammonrn.phoneaikiosk.settings

import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.text.TextUtils
import android.util.Log
import android.view.Gravity
import android.view.View
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.res.ResourcesCompat
import com.mammonrn.phoneaikiosk.BuildConfig
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.auth.IdentityGate
import com.mammonrn.phoneaikiosk.auth.VerifyActivity
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Device
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Link
import com.mammonrn.phoneaikiosk.settings.BluetoothModel.Radio
import com.mammonrn.phoneaikiosk.ui.Origin
import com.mammonrn.phoneaikiosk.ui.Retro
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.MATCH
import com.mammonrn.phoneaikiosk.ui.Retro.Companion.WRAP
import com.mammonrn.phoneaikiosk.ui.ToolWindow
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The Control Panel's Bluetooth page (0.68, Poom). What a person comes here to do:
 * turn Bluetooth on or off (the one big button), see whether the headphones or the
 * speaker are connected, and — less often — pair a new one or forget an old one.
 *
 * WHAT IS DONE HERE AND WHAT ON THE SYSTEM'S SCREEN is decided by Android's public
 * API (BluetoothLink): on/off directly (device owner), pairing directly (with the
 * system's confirmation allowed for that moment, BluetoothSystem); connecting,
 * disconnecting and forgetting on the system's Bluetooth screen, opened for one
 * visit inside the lock task. The page says so in words, rather than offering a
 * button that could not do what it says.
 *
 * THE IDENTITY CHECK (Poom): searching for and pairing a new device, and forgetting
 * one, need a pass (IdentityGate; the hour's grant applies), and so does opening the
 * system's screen (it can pair and forget by itself) — as the WiFi panel does (Poom
 * 2026-09-26: every system settings screen). Turning the radio on or off does not.
 *
 * EVERY STATE (ux-ui-design): no Bluetooth · no permission · off · turning on/off ·
 * on with nothing paired · the list · searching · found nothing · pairing · paired ·
 * pairing failed (why, and what to do) · the check refused. The debug build draws each
 * from sample data ([EXTRA_SAMPLE], scripts/ui-check) without touching the radio.
 */
class BluetoothActivity : Activity() {

    companion object {
        /** DEBUG ONLY (scripts/ui-check): one of [SAMPLES], drawn from made-up data; nothing is switched or paired. */
        const val EXTRA_SAMPLE = "bt_sample"
        val SAMPLES = listOf("off", "empty", "list", "selected", "forget", "searching", "found", "none-found",
                             "pairing", "paired", "pair-failed", "refused", "switch-refused", "turning-on",
                             "no-permission", "unsupported")
        private const val TAG = "KioskBluetooth"

        fun intent(context: Context): Intent = Intent(context, BluetoothActivity::class.java)
    }

    private enum class Search { NONE, RUNNING, DONE }
    private enum class Want { SEARCH, FORGET, SYSTEM }

    /** A device the search found: [device] is null only in the samples. */
    private class Candidate(val key: String, val name: String, val kind: BluetoothModel.Kind, val device: BluetoothDevice?)

    private lateinit var r: Retro
    private lateinit var frame: ToolWindow
    private var link: BluetoothLink? = null
    private var receiver: BroadcastReceiver? = null
    private val main = Handler(Looper.getMainLooper())
    private var sample: String? = null

    private var radio = Radio.OFF
    private var devices: List<Device> = emptyList()
    private var selected: String? = null
    private var confirmingForget: String? = null
    private var switchRefused = false
    private var systemFailed = false
    private var checkProblem: String? = null
    private var want = Want.SEARCH

    private var search = Search.NONE
    private val found = LinkedHashMap<String, Candidate>()
    private val hidden = HashSet<String>()
    private var pairingKey: String? = null
    private var pairingName = ""
    private var pairNote: String? = null
    private var pairOk = false

    /** Done in onResume, AFTER the allowlist is the kiosk's own again: a visit started from onActivityResult would lose its package at once. */
    private var afterResume: (() -> Unit)? = null

    private val stopSearch = Runnable { link?.stopSearch() }
    private val pairTimeout = Runnable { pairEnded(BluetoothModel.PairFail.TIMEOUT) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        r = Retro(this, ResourcesCompat.getFont(this, R.font.plex_thai) ?: Typeface.DEFAULT)
        frame = ToolWindow(this, r, R.drawable.ic_pixel_bt_light,
            onClose = { Origin.close(this, "bluetooth-close") }, onHome = { ToolWindow.goHome(this, "bluetooth-home") })
        frame.title.text = getString(R.string.window_bluetooth)
        setContentView(frame.root)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            onBackInvokedDispatcher.registerOnBackInvokedCallback(
                android.window.OnBackInvokedDispatcher.PRIORITY_DEFAULT) { goBack() }
        }
        ToolWindow.hideSystemBars(this)
        sample = intent.getStringExtra(EXTRA_SAMPLE)?.takeIf { BuildConfig.DEBUG && it in SAMPLES }
        if (sample != null) { loadSample(sample!!); draw(); return }
        BluetoothLink.ensurePermissions(this)
        val l = BluetoothLink(this)
        link = l
        receiver = l.listen(::onEvent)
        l.openProxies { refresh() }
        refresh()
    }

    override fun onResume() {
        super.onResume()
        ToolWindow.hideSystemBars(this)
        // Back from the system's screen or its pairing confirmation: the kiosk's own list.
        BluetoothSystem.endPairing(this)
        afterResume?.let { afterResume = null; it() }
        if (sample == null) refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        main.removeCallbacksAndMessages(null)
        link?.stopSearch()
        link?.stopListening(receiver)
        link?.close()
        if (sample == null) BluetoothSystem.endPairing(this)
    }

    @Deprecated("Superseded by OnBackInvokedDispatcher on API 33+, still the path below it.")
    @Suppress("DEPRECATION")
    override fun onBackPressed() = goBack()

    /** Back closes an open question first, then the page. */
    private fun goBack() {
        if (confirmingForget != null || selected != null) {
            confirmingForget = null; selected = null; draw(); return
        }
        Origin.close(this, "bluetooth-back")
    }

    // ------------------------------------------------------------ the radio

    private fun refresh() {
        val l = link ?: return
        radio = l.radio()
        devices = if (radio == Radio.ON) l.paired(getString(R.string.bt_unnamed)) else emptyList()
        if (radio != Radio.ON) { search = Search.NONE; found.clear(); hidden.clear() }
        draw()
    }

    private fun onEvent(i: Intent) {
        when (i.action) {
            BluetoothAdapter.ACTION_DISCOVERY_STARTED -> search = Search.RUNNING
            BluetoothAdapter.ACTION_DISCOVERY_FINISHED -> if (search == Search.RUNNING) search = Search.DONE
            BluetoothDevice.ACTION_FOUND -> deviceOf(i)?.let { addFound(it) }
            BluetoothDevice.ACTION_BOND_STATE_CHANGED -> bondChanged(i)
            BluetoothAdapter.ACTION_STATE_CHANGED -> {
                val state = i.getIntExtra(BluetoothAdapter.EXTRA_STATE, BluetoothAdapter.STATE_OFF)
                if (state != BluetoothAdapter.STATE_ON && pairingKey != null) pairEnded(BluetoothModel.PairFail.RADIO_OFF)
                if (state == BluetoothAdapter.STATE_ON) switchRefused = false
                Log.i(TAG, "radio state=$state")
            }
        }
        refresh()
    }

    @Suppress("DEPRECATION")
    private fun deviceOf(i: Intent): BluetoothDevice? = i.getParcelableExtra(BluetoothDevice.EXTRA_DEVICE)

    private fun switchRadio(on: Boolean) {
        val l = link ?: return
        switchRefused = !l.switch(on)
        refresh()
    }

    /**
     * The system Bluetooth screen can pair and forget devices itself, with no identity
     * check of ours. So every way to it passes the check first (the hour's grant applies);
     * turning the radio on/off and the paired list on this page do not need it. (Poom
     * 2026-09-26: the same for every system settings screen, WiFi included.)
     */
    private fun systemAfterCheck() = check(Want.SYSTEM)

    private fun openSystem() {
        systemFailed = !BluetoothSystem.openSettings(this)
        if (systemFailed) draw()
    }

    // ------------------------------------------------------------ search and pair

    private fun check(what: Want) {
        want = what
        checkProblem = null
        IdentityGate.ask(this)
    }

    @Deprecated("startActivityForResult's partner; this app has no androidx.activity.")
    @Suppress("DEPRECATION")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != IdentityGate.REQUEST) return
        if (!IdentityGate.passed(data)) {
            checkProblem = getString(when (data?.getStringExtra(VerifyActivity.EXTRA_OUTCOME)) {
                VerifyActivity.OUTCOME_NOTHING_ENROLLED -> R.string.bt_check_nothing_enrolled
                VerifyActivity.OUTCOME_CANCELLED, null -> R.string.bt_check_cancelled
                else -> R.string.bt_check_failed
            })
            Log.i(TAG, "check not passed want=${want.name.lowercase()}")
            draw()
            return
        }
        Log.i(TAG, "check passed want=${want.name.lowercase()}")
        afterResume = when (want) {
            Want.SEARCH -> { { startSearch() } }
            Want.FORGET -> { { confirmingForget = null; selected = null; openSystem() } }
            Want.SYSTEM -> { { openSystem() } }
        }
    }

    private fun startSearch() {
        val l = link ?: return
        found.clear(); hidden.clear(); pairNote = null
        if (l.startSearch()) {
            search = Search.RUNNING
            main.removeCallbacks(stopSearch)
            main.postDelayed(stopSearch, BluetoothModel.SCAN_MS)
        } else {
            search = Search.NONE
            pairNote = getString(R.string.bt_search_failed); pairOk = false
        }
        draw()
    }

    @android.annotation.SuppressLint("MissingPermission")
    private fun addFound(d: BluetoothDevice) {
        val bonded = runCatching { d.bondState == BluetoothDevice.BOND_BONDED }.getOrDefault(false)
        if (bonded || found.containsKey(d.address)) return
        val name = runCatching { d.name }.getOrNull()?.trim()
        if (name.isNullOrEmpty()) { hidden.add(d.address); return }
        hidden.remove(d.address)
        val cls = runCatching { d.bluetoothClass }.getOrNull()
        found[d.address] = Candidate(d.address, name.take(60),
            BluetoothModel.kindOf(cls?.majorDeviceClass ?: -1, cls?.deviceClass ?: -1), d)
    }

    private fun pair(c: Candidate) {
        val l = link ?: return
        val d = c.device ?: return
        main.removeCallbacks(stopSearch)
        // The system's confirmation may come up: allowed from just before the pairing starts.
        BluetoothSystem.allowPairingDialog(this)
        pairingKey = c.key; pairingName = c.name; pairNote = null
        if (!l.pair(d)) { pairEnded(BluetoothModel.PairFail.NOT_STARTED); return }
        main.removeCallbacks(pairTimeout)
        main.postDelayed(pairTimeout, BluetoothModel.PAIR_TIMEOUT_MS)
        draw()
    }

    private fun bondChanged(i: Intent) {
        val d = deviceOf(i) ?: return
        if (d.address != pairingKey) return
        val outcome = BluetoothModel.pairOutcome(
            i.getIntExtra(BluetoothDevice.EXTRA_PREVIOUS_BOND_STATE, BluetoothDevice.BOND_NONE),
            i.getIntExtra(BluetoothDevice.EXTRA_BOND_STATE, BluetoothDevice.BOND_NONE)) ?: return
        if (outcome) pairEnded(null) else pairEnded(BluetoothModel.PairFail.REFUSED)
    }

    /** The pairing is over: [why] null = bonded. The kiosk's own allowlist again. */
    private fun pairEnded(why: BluetoothModel.PairFail?) {
        if (pairingKey == null) return
        main.removeCallbacks(pairTimeout)
        pairOk = why == null
        pairNote = if (why == null) getString(R.string.bt_paired_ok, pairingName)
                   else getString(R.string.bt_pair_failed, pairingName, getString(BluetoothModel.pairFailWords(why)))
        Log.i(TAG, "pair ended ok=$pairOk reason=${why?.name?.lowercase() ?: "bonded"}")
        found.remove(pairingKey)
        pairingKey = null
        BluetoothSystem.endPairing(this)
        draw()
    }

    // ------------------------------------------------------------ the page

    private fun draw() {
        val body = r.column().apply { setPadding(r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_M), r.dp(UiScale.SPACE_M)) }
        val lp = { top: Int -> LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(top) } }
        if (sample != null) body.addView(r.text(getString(R.string.bt_sample), UiScale.TEXT_NOTE, dim = true), lp(0))

        // 1. The state, and the one big thing to do about it.
        body.addView(statusBox(), lp(if (sample != null) UiScale.SPACE_S else 0))
        primaryButton()?.let { body.addView(it, LinearLayout.LayoutParams(MATCH, r.dp(UiScale.PRIMARY)).apply { topMargin = r.dp(UiScale.SPACE_M) }) }
        if (switchRefused) body.addView(bad(getString(R.string.bt_switch_refused)), lp(UiScale.SPACE_S))

        // 2. The paired devices, while on.
        if (radio == Radio.ON) {
            body.addView(r.label(getString(R.string.bt_paired_title, devices.size)), lp(UiScale.SPACE_L))
            if (devices.isEmpty()) body.addView(r.text(getString(R.string.bt_paired_empty), UiScale.TEXT_BASE), lp(UiScale.SPACE_XS))
            for (d in devices) body.addView(deviceRow(d), lp(UiScale.SPACE_S))

            // 3. A new device: behind the identity check.
            body.addView(r.label(getString(R.string.bt_search)), lp(UiScale.SPACE_L))
            searchSection(body, lp)
        } else if (radio == Radio.OFF || radio == Radio.TURNING_ON) {
            body.addView(r.text(getString(R.string.bt_paired_when_off), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_M))
        }

        // 4. The system's own screen, always there as the way round, and where the sound goes.
        if (radio != Radio.UNSUPPORTED) {
            body.addView(r.button(getString(R.string.bt_open_system)) { systemAfterCheck() },
                LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_L) })
            if (systemFailed) body.addView(bad(getString(R.string.bt_open_system_failed)), lp(UiScale.SPACE_S))
            body.addView(r.text(getString(R.string.bt_system_back), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_XS))
            body.addView(r.text(getString(R.string.bt_audio_note), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_M))
        }
        val scroll = ScrollView(this).apply { addView(body) }
        frame.setPage(scroll)
    }

    private fun bad(words: String) = r.text(words, UiScale.TEXT_BASE).apply { setTextColor(r.color(R.color.retro_bad)) }

    private fun statusBox(): View {
        val look = BluetoothModel.look(radio, devices)
        val box = r.row().apply {
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        }
        box.addView(ImageView(this).apply {
            setImageResource(look.icon)
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(r.dp(UiScale.ICON_L), r.dp(UiScale.ICON_L)))
        val words = r.column().apply { setPadding(r.dp(UiScale.SPACE_S), 0, 0, 0) }
        val (head, note) = when (radio) {
            Radio.ON -> {
                val n = devices.count { it.link == Link.CONNECTED }
                R.string.bt_status_on to if (n > 0) getString(R.string.bt_note_connected, n) else getString(R.string.bt_note_none_connected)
            }
            Radio.OFF -> R.string.bt_status_off to getString(R.string.bt_note_off)
            Radio.TURNING_ON -> R.string.bt_status_turning_on to getString(R.string.bt_note_wait)
            Radio.TURNING_OFF -> R.string.bt_status_turning_off to getString(R.string.bt_note_wait)
            Radio.UNSUPPORTED -> R.string.bt_status_unsupported to getString(R.string.bt_note_unsupported)
            Radio.NO_PERMISSION -> R.string.bt_status_no_permission to getString(R.string.bt_note_no_permission)
        }
        words.addView(r.bold(getString(head), UiScale.TEXT_HEADING))
        words.addView(r.text(note, UiScale.TEXT_NOTE))
        box.addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        return box
    }

    /** The page's one primary action: the switch (or, without permission, the system's screen). */
    private fun primaryButton(): View? = when (radio) {
        Radio.ON -> r.button(getString(R.string.bt_turn_off), big = true) { act { switchRadio(false) } }
        Radio.OFF -> r.button(getString(R.string.bt_turn_on), big = true) { act { switchRadio(true) } }
        Radio.TURNING_ON, Radio.TURNING_OFF -> r.button(getString(R.string.bt_turn_wait), big = true, enabled = false) {}
        Radio.NO_PERMISSION -> r.button(getString(R.string.bt_open_system), big = true) { act { systemAfterCheck() } }
        Radio.UNSUPPORTED -> null
    }

    /** In the samples nothing is switched, opened or paired: a press only redraws. */
    private fun act(block: () -> Unit) { if (sample == null) block() else draw() }

    private fun deviceRow(d: Device): View {
        val box = r.column()
        val kind = getString(BluetoothModel.kindWords(d.kind))
        val state = getString(BluetoothModel.linkWords(d.link))
        val row = r.column().apply {
            setBackgroundResource(R.drawable.retro_button)
            setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            minimumHeight = r.dp(UiScale.ROW)
            isClickable = true
            contentDescription = getString(R.string.bt_row_desc, d.name, kind, state.drop(2))
            setOnClickListener {
                selected = if (selected == d.key) null else d.key
                confirmingForget = null
                draw()
            }
        }
        row.addView(r.bold(d.name, UiScale.TEXT_ITEM).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        row.addView(r.text("$kind · $state", UiScale.TEXT_NOTE).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        box.addView(row, LinearLayout.LayoutParams(MATCH, WRAP))
        if (selected == d.key) {
            val more = r.column().apply {
                setBackgroundResource(R.drawable.retro_sunken)
                setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
            }
            if (confirmingForget == d.key) {
                more.addView(r.text(getString(R.string.bt_forget_confirm, d.name), UiScale.TEXT_NOTE))
                more.addView(r.button(getString(R.string.bt_forget_go)) { act { check(Want.FORGET) } },
                    LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
                more.addView(r.button(getString(R.string.cancel)) { confirmingForget = null; draw() },
                    LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
            } else {
                more.addView(r.text(getString(R.string.bt_row_how), UiScale.TEXT_NOTE))
                more.addView(r.button(getString(R.string.bt_row_connect)) { act { systemAfterCheck() } },
                    LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
                more.addView(r.button(getString(R.string.bt_row_forget)) { confirmingForget = d.key; draw() },
                    LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
            }
            if (checkProblem != null && want == Want.FORGET) more.addView(bad(checkProblem!!),
                LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_S) })
            box.addView(more, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = r.dp(UiScale.SPACE_XS) })
        }
        return box
    }

    private fun searchSection(body: LinearLayout, lp: (Int) -> LinearLayout.LayoutParams) {
        val pairing = pairingKey != null
        when {
            pairing -> body.addView(r.text(getString(R.string.bt_pairing, pairingName), UiScale.TEXT_BASE), lp(UiScale.SPACE_XS))
            search == Search.RUNNING -> body.addView(r.text(getString(R.string.bt_searching, found.size), UiScale.TEXT_BASE), lp(UiScale.SPACE_XS))
            search == Search.DONE && found.isEmpty() -> body.addView(r.text(getString(R.string.bt_search_none), UiScale.TEXT_BASE), lp(UiScale.SPACE_XS))
            search == Search.DONE -> body.addView(r.text(getString(R.string.bt_search_done, found.size), UiScale.TEXT_BASE), lp(UiScale.SPACE_XS))
        }
        pairNote?.let { note ->
            body.addView(r.text(note, UiScale.TEXT_BASE).apply {
                setTextColor(r.color(if (pairOk) R.color.retro_good else R.color.retro_bad))
            }, lp(UiScale.SPACE_S))
        }
        if (!pairing) for (c in found.values) body.addView(foundRow(c), lp(UiScale.SPACE_S))
        if (hidden.isNotEmpty() && search != Search.NONE && !pairing)
            body.addView(r.text(getString(R.string.bt_search_hidden, hidden.size), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_S))
        if (pairing) return
        if (search == Search.RUNNING) {
            body.addView(r.button(getString(R.string.bt_search_stop)) { act { link?.stopSearch() } },
                LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
        } else {
            val words = getString(if (search == Search.DONE) R.string.bt_search_again else R.string.bt_search)
            // Inside the hour, the check passes at once (AccessGrant); outside it, the face.
            body.addView(r.button(words) { act { check(Want.SEARCH) } },
                LinearLayout.LayoutParams(MATCH, r.dp(UiScale.TOUCH)).apply { topMargin = r.dp(UiScale.SPACE_S) })
            body.addView(r.text(getString(R.string.bt_search_needs_check), UiScale.TEXT_NOTE, dim = true), lp(UiScale.SPACE_XS))
            if (checkProblem != null && want == Want.SEARCH) body.addView(bad(checkProblem!!), lp(UiScale.SPACE_S))
        }
    }

    private fun foundRow(c: Candidate): View = r.row().apply {
        setBackgroundResource(R.drawable.retro_button)
        setPadding(r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S), r.dp(UiScale.SPACE_S))
        minimumHeight = r.dp(UiScale.ROW)
        isClickable = true
        val kind = getString(BluetoothModel.kindWords(c.kind))
        contentDescription = getString(R.string.bt_found_desc, c.name, kind)
        setOnClickListener { act { pair(c) } }
        val words = r.column()
        words.addView(r.bold(c.name, UiScale.TEXT_ITEM).apply {
            maxLines = 2; ellipsize = TextUtils.TruncateAt.END
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        })
        words.addView(r.text(kind, UiScale.TEXT_NOTE).apply { importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO })
        addView(words, LinearLayout.LayoutParams(0, WRAP, 1f))
        addView(TextView(context).apply {
            text = getString(R.string.bt_pair)
            textSize = UiScale.TEXT_BASE
            typeface = Typeface.create(r.thai, Typeface.BOLD)
            setTextColor(r.color(R.color.retro_text))
            gravity = Gravity.CENTER
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        }, LinearLayout.LayoutParams(WRAP, WRAP).apply { marginStart = r.dp(UiScale.SPACE_S) })
    }

    // ------------------------------------------------------------ the samples (debug)

    private fun loadSample(which: String) {
        val speaker = Device("s1", "ลำโพงตัวอย่าง ห้องนั่งเล่น", BluetoothModel.Kind.SPEAKER, Link.NOT_CONNECTED)
        val phones = Device("s2", "หูฟังตัวอย่าง", BluetoothModel.Kind.HEADPHONES, Link.CONNECTED)
        val keys = Device("s3", "แป้นพิมพ์ตัวอย่างที่มีชื่อยาวมากเพื่อตรวจการขึ้นบรรทัดใหม่ของรายการอุปกรณ์", BluetoothModel.Kind.INPUT, Link.CONNECTING)
        radio = Radio.ON
        devices = BluetoothModel.ordered(listOf(speaker, phones, keys))
        fun candidates() {
            found["f1"] = Candidate("f1", "ลำโพงใหม่ตัวอย่าง", BluetoothModel.Kind.SPEAKER, null)
            found["f2"] = Candidate("f2", "หูฟังใหม่ตัวอย่าง", BluetoothModel.Kind.HEADPHONES, null)
            hidden.add("h1"); hidden.add("h2")
        }
        when (which) {
            "off" -> { radio = Radio.OFF; devices = emptyList() }
            "turning-on" -> { radio = Radio.TURNING_ON; devices = emptyList() }
            "switch-refused" -> { radio = Radio.OFF; devices = emptyList(); switchRefused = true }
            "no-permission" -> { radio = Radio.NO_PERMISSION; devices = emptyList() }
            "unsupported" -> { radio = Radio.UNSUPPORTED; devices = emptyList() }
            "empty" -> devices = emptyList()
            "selected" -> selected = "s1"
            "forget" -> { selected = "s1"; confirmingForget = "s1" }
            "searching" -> { search = Search.RUNNING; candidates() }
            "found" -> { search = Search.DONE; candidates() }
            "none-found" -> search = Search.DONE
            "pairing" -> { search = Search.DONE; pairingKey = "f1"; pairingName = "ลำโพงใหม่ตัวอย่าง" }
            "paired" -> { search = Search.DONE; pairOk = true; pairNote = getString(R.string.bt_paired_ok, "ลำโพงใหม่ตัวอย่าง") }
            "pair-failed" -> { search = Search.DONE; candidates(); pairOk = false
                pairNote = getString(R.string.bt_pair_failed, "หูฟังใหม่ตัวอย่าง",
                    getString(BluetoothModel.pairFailWords(BluetoothModel.PairFail.REFUSED))) }
            "refused" -> { want = Want.SEARCH; checkProblem = getString(R.string.bt_check_cancelled) }
        }
    }
}
