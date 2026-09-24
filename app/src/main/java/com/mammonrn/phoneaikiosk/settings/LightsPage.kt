package com.mammonrn.phoneaikiosk.settings

import android.graphics.Typeface
import android.text.InputFilter
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.InputMethodManager
import android.widget.EditText
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.mammonrn.phoneaikiosk.R
import com.mammonrn.phoneaikiosk.home.HomeSettings
import com.mammonrn.phoneaikiosk.voice.Broker
import com.mammonrn.phoneaikiosk.voice.TokenStore
import com.mammonrn.phoneaikiosk.voice.VoiceState
import com.mammonrn.phoneaikiosk.ui.UiScale

/**
 * The Control Panel's "ไฟในบ้าน" page (0.47.0, Poom 2026-09-24): where the
 * lights are SET UP — named, allowed or stopped, their state seen. Using them
 * is by voice; this is not a remote control, so nothing here switches a light.
 *
 * Everything is asked of the broker (GET /v1/home/devices, POST
 * /v1/home/name and /v1/home/allow) and drawn from its answer: a name or a
 * permission is shown as saved only after the VPS says it is. The page
 * holds no id; the broker's opaque key and a channel number are all it sends.
 *
 * Built from SettingsActivity's parts (text, label, button) so it looks like
 * every other page of the panel. DESIGN.md 5ง, "ไฟในบ้าน".
 */
internal class LightsPage(private val a: SettingsActivity) {

    private var page: HomeSettings.Page? = null
    private var loading = false

    /** One line under the heading: what the last change did, in the broker's words. */
    private var note: String? = null

    /** Bumped on every request, so an answer that arrives after a newer one is dropped. */
    private var generation = 0

    /**
     * The list's scroller, so a redraw after a change keeps the place: on the
     * A07 every tick box and icon sent the page back to the top (0.48.0).
     */
    private var scroller: ScrollView? = null

    fun open() {
        note = null
        page = null
        scroller = null
        showList()
        load()
    }

    private fun broker(): Broker? {
        val token = TokenStore(a).token()
        return if (token.isNullOrEmpty()) null else Broker(VoiceState.brokerBaseUrl, token)
    }

    private fun load() {
        HomeSettings.override?.let { sample ->
            page = HomeSettings.parse(sample)
            showList()
            return
        }
        val broker = broker() ?: run {
            page = HomeSettings.Page(emptyList(), false, "no-token")
            showList()
            return
        }
        loading = true
        val mine = ++generation
        Thread {
            val body = runCatching { broker.homeDevices() }.getOrDefault("")
            val parsed = HomeSettings.parse(body)
            a.runOnUiThread {
                if (mine != generation) return@runOnUiThread
                loading = false
                page = parsed
                android.util.Log.i(TAG, "lights page devices=${parsed.devices.size} error=${parsed.error.ifEmpty { "none" }}")
                if (a.page == SettingsActivity.Page.LIGHTS) showList()
            }
        }.start()
    }

    /** A change, then the page the broker answered with — never our guess of it. */
    private fun change(call: (Broker) -> String, after: (HomeSettings.Changed) -> Unit) {
        val broker = broker() ?: return after(HomeSettings.Changed(false, HomeSettings.FAILED, null))
        val mine = ++generation
        Thread {
            val changed = HomeSettings.parseChanged(runCatching { call(broker) }.getOrDefault(""))
            a.runOnUiThread {
                if (mine != generation) return@runOnUiThread
                android.util.Log.i(TAG, "lights change ok=${changed.ok}")
                changed.page?.let { page = it }
                after(changed)
            }
        }.start()
    }

    // ------------------------------------------------------------ the list

    fun showList() {
        a.page = SettingsActivity.Page.LIGHTS
        a.titleText.text = a.getString(R.string.window_lights)
        val list = LinearLayout(a).apply { orientation = LinearLayout.VERTICAL }
        list.addView(a.button(a.getString(R.string.settings_back_to_panel)) { a.showHomeFromPage() },
                     LinearLayout.LayoutParams(WRAP, a.dp(UiScale.TOUCH)))
        list.addView(a.text(a.getString(R.string.lights_intro), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })

        val current = page
        val status = when {
            current == null || loading && current.devices.isEmpty() -> a.getString(R.string.lights_loading)
            current.error == "not-connected" -> a.getString(R.string.lights_not_connected)
            current.error == "no-token" -> a.getString(R.string.lights_no_token)
            current.error.isNotEmpty() && current.devices.isEmpty() ->
                if (current.error.any { it.code > 127 }) current.error else a.getString(R.string.lights_error)
            current.devices.isEmpty() -> a.getString(R.string.lights_empty)
            else -> null
        }
        note?.let { list.addView(a.text(it, UiScale.TEXT_BASE), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) }) }
        status?.let { list.addView(a.text(it, UiScale.TEXT_BASE), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_M) }) }
        if (current != null && current.devices.isNotEmpty() && !current.control) {
            list.addView(a.text(a.getString(R.string.lights_stopped), UiScale.TEXT_NOTE).apply {
                setTextColor(a.color(R.color.retro_bad))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        }
        for (device in current?.devices.orEmpty()) {
            list.addView(deviceBox(device), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        }
        if (current != null && !loading) {
            list.addView(a.button(a.getString(R.string.lights_reload)) {
                note = null
                load()
                showList()
            }, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_M) })
        }
        val keep = scroller?.scrollY ?: 0
        val view = ScrollView(a).apply { addView(list) }
        scroller = view
        a.setPage(view)
        if (keep > 0) view.post { view.scrollTo(0, keep) }
    }

    private fun deviceBox(device: HomeSettings.Device): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S))
        }
        box.addView(a.text(HomeSettings.describe(device), UiScale.TEXT_NOTE, dim = true))
        // 0.53.4: where the device sits in the WiFi, for deciding where to move it.
        HomeSettings.signalLine(device).takeIf { it.isNotEmpty() }?.let {
            box.addView(a.text(it, UiScale.TEXT_NOTE, dim = true))
        }
        if (device.channels.isEmpty()) {
            box.addView(item(device, null, device.name, device.ownName, device.ewelinkName, device.online,
                             device.on, device.allowed, active = true, clash = device.clash,
                             icon = device.icon, iconChosen = device.iconChosen, voice = device.voice))
            return box
        }
        // A multi-way switch: its own name (which switches every channel by
        // voice), then one block per channel.
        box.addView(nameLine(device.name, bold = true, state = null))
        box.addView(a.text(HomeSettings.source(device.ownName, device.ewelinkName) + " · " +
                           a.getString(R.string.lights_switch_name_hint), UiScale.TEXT_NOTE, dim = true))
        warning(box, device.clash)
        box.addView(a.button(a.getString(R.string.lights_rename_switch)) {
            showName(device, null, device.name, device.ownName, device.ewelinkName)
        }, LinearLayout.LayoutParams(WRAP, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_S) })
        for (c in device.channels) {
            box.addView(item(device, c.index, c.name, c.ownName, c.ewelinkName, device.online, c.on, c.allowed,
                             c.active, c.clash, c.icon, c.iconChosen, c.voice),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        }
        return box
    }

    /** One thing voice can switch: its name and state, where the name came from, allow, rename. */
    private fun item(device: HomeSettings.Device, channel: Int?, name: String, ownName: String,
                     ewelinkName: String, online: Boolean, on: Boolean?, allowed: Boolean,
                     active: Boolean, clash: List<String>, icon: String = "bulb",
                     iconChosen: Boolean = false, voice: Boolean = true): View {
        val block = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            if (channel != null) setPadding(a.dp(UiScale.SPACE_S), 0, 0, 0)
        }
        val title = if (channel != null) a.getString(R.string.lights_channel, channel + 1) + " · " + name else name
        block.addView(nameLine(title, bold = channel == null, state = if (active) HomeSettings.state(online, on) else null,
                               lit = active && online && on == true))
        block.addView(a.text(if (active) HomeSettings.source(ownName, ewelinkName)
                             else a.getString(R.string.lights_inactive), UiScale.TEXT_NOTE, dim = active).apply {
            if (!active) setTextColor(a.color(R.color.retro_bad))
        })
        warning(block, clash)
        if (active && !voice) {
            block.addView(a.text(HomeSettings.NOT_FOR_VOICE, UiScale.TEXT_NOTE).apply { setTextColor(a.color(R.color.retro_bad)) })
        }
        block.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(allowBox(device, channel, allowed, enabled = active),
                    LinearLayout.LayoutParams(0, a.dp(UiScale.TOUCH), 1f))
            addView(a.button(a.getString(R.string.lights_rename)) {
                showName(device, channel, name, ownName, ewelinkName)
            }, LinearLayout.LayoutParams(WRAP, a.dp(UiScale.TOUCH)).apply { marginStart = a.dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        if (active) block.addView(iconPicker(device, channel, icon, iconChosen),
                                  LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        return block
    }

    /**
     * The picture this light has on the home card (0.48.0): five 48dp
     * choices, the chosen one pressed in (navy, like the alarm days). Kept on
     * the VPS; the card follows at its next reading.
     */
    private fun iconPicker(device: HomeSettings.Device, channel: Int?, icon: String, iconChosen: Boolean) =
        LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            addView(a.text(a.getString(R.string.lights_icon) + " · " +
                           a.getString(if (iconChosen) R.string.lights_icon_chosen else R.string.lights_icon_default),
                           UiScale.TEXT_NOTE, dim = true))
            addView(LinearLayout(a).apply {
                orientation = LinearLayout.HORIZONTAL
                for ((name, picture, label) in PICTURES) {
                    val on = name == icon
                    addView(android.widget.FrameLayout(a).apply {
                        if (on) setBackgroundColor(a.color(R.color.retro_title))
                        else setBackgroundResource(R.drawable.retro_button)
                        isClickable = true
                        contentDescription = a.getString(label) + if (on) " (เลือกอยู่)" else ""
                        setOnClickListener {
                            if (on && iconChosen) return@setOnClickListener
                            note = a.getString(R.string.lights_saving)
                            showList()
                            change({ it.homeIcon(device.key, channel, name) }) { done ->
                                note = done.message
                                if (a.page == SettingsActivity.Page.LIGHTS) showList()
                            }
                        }
                        addView(ImageView(a).apply { setImageResource(picture) },
                                android.widget.FrameLayout.LayoutParams(a.dp(UiScale.ICON_L), a.dp(UiScale.ICON_L), Gravity.CENTER))
                    }, LinearLayout.LayoutParams(a.dp(UiScale.TOUCH), a.dp(UiScale.TOUCH)).apply { marginEnd = a.dp(UiScale.SPACE_XS) })
                }
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_XS) })
        }

    private fun nameLine(value: String, bold: Boolean, state: String?, lit: Boolean = false) =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dp(UiScale.SPACE_XS), 0, 0)
            addView(a.text(value, UiScale.TEXT_ITEM).apply {
                if (bold) typeface = Typeface.create(a.thai, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (state != null) addView(a.text(state, UiScale.TEXT_BASE, dim = !lit).apply {
                if (lit) typeface = Typeface.create(a.thai, Typeface.BOLD)
                setPadding(a.dp(UiScale.SPACE_S), 0, 0, 0)
            })
        }

    private fun warning(parent: LinearLayout, clash: List<String>) {
        val line = HomeSettings.clashWarning(clash)
        if (line.isNotEmpty()) parent.addView(a.text(line, UiScale.TEXT_NOTE).apply { setTextColor(a.color(R.color.retro_bad)) })
    }

    /** The tick box: "อนุญาตให้สั่ง", a 48dp target with the word beside it, like the alarms'. */
    private fun allowBox(device: HomeSettings.Device, channel: Int?, allowed: Boolean, enabled: Boolean) =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            isClickable = enabled
            isEnabled = enabled
            contentDescription = a.getString(R.string.lights_allowed) + if (allowed) " (เลือกอยู่)" else ""
            addView(ImageView(a).apply {
                setImageResource(if (allowed && enabled) R.drawable.ic_pixel_check_on else R.drawable.ic_pixel_check_off)
                alpha = if (enabled) 1f else 0.4f
            }, LinearLayout.LayoutParams(a.dp(UiScale.ICON_L), a.dp(UiScale.ICON_L)))
            addView(a.text(a.getString(if (enabled) R.string.lights_allowed else R.string.lights_name_first), UiScale.TEXT_BASE,
                           dim = !enabled).apply { setPadding(a.dp(UiScale.SPACE_S), 0, 0, 0) })
            if (enabled) setOnClickListener {
                note = a.getString(R.string.lights_saving)
                showList()
                change({ it.homeAllow(device.key, channel, !allowed) }) { done ->
                    note = done.message
                    if (a.page == SettingsActivity.Page.LIGHTS) showList()
                }
            }
        }

    // ------------------------------------------------------------ a name

    private fun showName(device: HomeSettings.Device, channel: Int?, name: String, ownName: String,
                         ewelinkName: String) {
        a.page = SettingsActivity.Page.LIGHT_NAME
        a.titleText.text = a.getString(R.string.lights_name_title)
        val form = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, a.dp(UiScale.SPACE_S))
        }
        form.addView(a.button(a.getString(R.string.lights_back)) { back(null) },
                     LinearLayout.LayoutParams(WRAP, a.dp(UiScale.TOUCH)))
        val what = if (channel == null) name else a.getString(R.string.lights_channel_of, channel + 1, device.name)
        form.addView(a.label(a.getString(R.string.lights_naming, what)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_M) })
        form.addView(a.text(if (ewelinkName.isNotEmpty()) a.getString(R.string.lights_ewelink_name, ewelinkName)
                            else a.getString(R.string.lights_no_ewelink_name), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_XS) })
        val field = EditText(a).apply {
            setText(ownName)
            setSelection(text.length)
            hint = name
            filters = arrayOf(InputFilter.LengthFilter(HomeSettings.MAX_NAME))
            typeface = a.thai
            textSize = UiScale.TEXT_HEADING
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.retro_field)
            setPadding(a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S), a.dp(UiScale.SPACE_S))
            setTextColor(a.color(R.color.retro_text))
        }
        form.addView(field, LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_S) })
        form.addView(a.text(a.getString(R.string.lights_name_hint), UiScale.TEXT_NOTE, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })
        val error = a.text("", UiScale.TEXT_BASE).apply {
            setTextColor(a.color(R.color.retro_bad))
            visibility = View.GONE
        }
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_S) })

        fun save(value: String, saveButton: TextView?) {
            error.visibility = View.GONE
            saveButton?.text = a.getString(R.string.lights_saving)
            saveButton?.isEnabled = false
            change({ it.homeName(device.key, channel, value) }) { done ->
                if (done.ok) {
                    back(done.message)
                } else {
                    saveButton?.text = a.getString(R.string.save)
                    saveButton?.isEnabled = true
                    error.text = done.message
                    error.visibility = View.VISIBLE
                }
            }
        }

        lateinit var saveButton: TextView
        form.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            saveButton = a.button(a.getString(R.string.save), big = true) {
                val value = field.text.toString().trim()
                if (value.isEmpty()) {
                    // Only point at "ลบชื่อที่ตั้งเอง" when that button is on the page.
                    error.text = a.getString(if (ownName.isNotEmpty()) R.string.lights_name_empty
                                             else R.string.lights_name_empty_new)
                    error.visibility = View.VISIBLE
                } else {
                    save(value, saveButton)
                }
            }
            addView(saveButton, LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 1f))
            addView(a.button(a.getString(R.string.cancel), big = true) { back(null) },
                    LinearLayout.LayoutParams(0, a.dp(UiScale.PRIMARY), 1f).apply { marginStart = a.dp(UiScale.SPACE_S) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(UiScale.SPACE_M) })
        if (ownName.isNotEmpty()) {
            // Reversible (type it again), so no second question.
            form.addView(a.button(a.getString(R.string.lights_name_remove)) { save("", null) },
                         LinearLayout.LayoutParams(MATCH, a.dp(UiScale.TOUCH)).apply { topMargin = a.dp(UiScale.SPACE_M) })
        }
        a.setPage(ScrollView(a).apply { addView(form) })
    }

    /** From the name page to the list, with what happened. */
    fun back(message: String?) {
        a.currentFocus?.let { view ->
            a.getSystemService(InputMethodManager::class.java)?.hideSoftInputFromWindow(view.windowToken, 0)
        }
        note = message
        showList()
    }

    companion object {
        /** (the broker's name, the picture shown — its ON state, the most telling — and its words). */
        private val PICTURES = listOf(
            Triple("bulb", R.drawable.ic_pixel_bulb_on, R.string.icon_bulb),
            Triple("fan", R.drawable.ic_pixel_fan_on, R.string.icon_fan),
            Triple("aircon", R.drawable.ic_pixel_aircon_on, R.string.icon_aircon),
            Triple("tv", R.drawable.ic_pixel_tv_on, R.string.icon_tv),
            Triple("switch", R.drawable.ic_pixel_switch_on, R.string.icon_switch),
        )
        private const val TAG = "KioskHome"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
