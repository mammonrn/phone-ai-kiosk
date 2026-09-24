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

    fun open() {
        note = null
        page = null
        showList()
        load()
    }

    private fun broker(): Broker? {
        val token = TokenStore(a).token()
        return if (token.isNullOrEmpty()) null else Broker(VoiceState.brokerBaseUrl, token)
    }

    private fun load() {
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
                     LinearLayout.LayoutParams(WRAP, a.dp(48)))
        list.addView(a.text(a.getString(R.string.lights_intro), 13f, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(8) })

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
        note?.let { list.addView(a.text(it, 14f), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(8) }) }
        status?.let { list.addView(a.text(it, 14f), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(12) }) }
        if (current != null && current.devices.isNotEmpty() && !current.control) {
            list.addView(a.text(a.getString(R.string.lights_stopped), 13f).apply {
                setTextColor(a.color(R.color.retro_bad))
            }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(8) })
        }
        for (device in current?.devices.orEmpty()) {
            list.addView(deviceBox(device), LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(10) })
        }
        if (current != null && !loading) {
            list.addView(a.button(a.getString(R.string.lights_reload)) {
                note = null
                load()
                showList()
            }, LinearLayout.LayoutParams(MATCH, a.dp(48)).apply { topMargin = a.dp(12) })
        }
        a.setPage(ScrollView(a).apply { addView(list) })
    }

    private fun deviceBox(device: HomeSettings.Device): View {
        val box = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundResource(R.drawable.retro_sunken)
            setPadding(a.dp(8), a.dp(8), a.dp(8), a.dp(8))
        }
        box.addView(a.text(HomeSettings.describe(device), 13f, dim = true))
        if (device.channels.isEmpty()) {
            box.addView(item(device, null, device.name, device.ownName, device.ewelinkName, device.online,
                             device.on, device.allowed, active = true, clash = device.clash))
            return box
        }
        // A multi-way switch: its own name (which switches every channel by
        // voice), then one block per channel.
        box.addView(nameLine(device.name, bold = true, state = null))
        box.addView(a.text(HomeSettings.source(device.ownName, device.ewelinkName) + " · " +
                           a.getString(R.string.lights_switch_name_hint), 12f, dim = true))
        warning(box, device.clash)
        box.addView(a.button(a.getString(R.string.lights_rename_switch)) {
            showName(device, null, device.name, device.ownName, device.ewelinkName)
        }, LinearLayout.LayoutParams(WRAP, a.dp(48)).apply { topMargin = a.dp(6) })
        for (c in device.channels) {
            box.addView(item(device, c.index, c.name, c.ownName, c.ewelinkName, device.online, c.on, c.allowed,
                             c.active, c.clash),
                        LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(10) })
        }
        return box
    }

    /** One thing voice can switch: its name and state, where the name came from, allow, rename. */
    private fun item(device: HomeSettings.Device, channel: Int?, name: String, ownName: String,
                     ewelinkName: String, online: Boolean, on: Boolean?, allowed: Boolean,
                     active: Boolean, clash: List<String>): View {
        val block = LinearLayout(a).apply {
            orientation = LinearLayout.VERTICAL
            if (channel != null) setPadding(a.dp(8), 0, 0, 0)
        }
        val title = if (channel != null) a.getString(R.string.lights_channel, channel + 1) + " · " + name else name
        block.addView(nameLine(title, bold = channel == null, state = if (active) HomeSettings.state(online, on) else null,
                               lit = active && online && on == true))
        block.addView(a.text(if (active) HomeSettings.source(ownName, ewelinkName)
                             else a.getString(R.string.lights_inactive), 12f, dim = active).apply {
            if (!active) setTextColor(a.color(R.color.retro_bad))
        })
        warning(block, clash)
        block.addView(LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(allowBox(device, channel, allowed, enabled = active),
                    LinearLayout.LayoutParams(0, a.dp(48), 1f))
            addView(a.button(a.getString(R.string.lights_rename)) {
                showName(device, channel, name, ownName, ewelinkName)
            }, LinearLayout.LayoutParams(a.dp(112), a.dp(48)).apply { marginStart = a.dp(6) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(6) })
        return block
    }

    private fun nameLine(value: String, bold: Boolean, state: String?, lit: Boolean = false) =
        LinearLayout(a).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(0, a.dp(4), 0, 0)
            addView(a.text(value, 15f).apply {
                if (bold) typeface = Typeface.create(a.thai, Typeface.BOLD)
                maxLines = 2
                ellipsize = android.text.TextUtils.TruncateAt.END
            }, LinearLayout.LayoutParams(0, WRAP, 1f))
            if (state != null) addView(a.text(state, 14f, dim = !lit).apply {
                if (lit) typeface = Typeface.create(a.thai, Typeface.BOLD)
                setPadding(a.dp(8), 0, 0, 0)
            })
        }

    private fun warning(parent: LinearLayout, clash: List<String>) {
        val line = HomeSettings.clashWarning(clash)
        if (line.isNotEmpty()) parent.addView(a.text(line, 13f).apply { setTextColor(a.color(R.color.retro_bad)) })
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
            }, LinearLayout.LayoutParams(a.dp(32), a.dp(32)))
            addView(a.text(a.getString(if (enabled) R.string.lights_allowed else R.string.lights_name_first), 14f,
                           dim = !enabled).apply { setPadding(a.dp(6), 0, 0, 0) })
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
            setPadding(0, 0, 0, a.dp(8))
        }
        form.addView(a.button(a.getString(R.string.lights_back)) { back(null) },
                     LinearLayout.LayoutParams(WRAP, a.dp(48)))
        val what = if (channel == null) name else a.getString(R.string.lights_channel_of, channel + 1, device.name)
        form.addView(a.label(a.getString(R.string.lights_naming, what)),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(12) })
        form.addView(a.text(if (ewelinkName.isNotEmpty()) a.getString(R.string.lights_ewelink_name, ewelinkName)
                            else a.getString(R.string.lights_no_ewelink_name), 13f, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(4) })
        val field = EditText(a).apply {
            setText(ownName)
            setSelection(text.length)
            hint = name
            filters = arrayOf(InputFilter.LengthFilter(HomeSettings.MAX_NAME))
            typeface = a.thai
            textSize = 16f
            setSingleLine()
            imeOptions = EditorInfo.IME_ACTION_DONE
            setBackgroundResource(R.drawable.retro_field)
            setPadding(a.dp(10), a.dp(10), a.dp(10), a.dp(10))
            setTextColor(a.color(R.color.retro_text))
        }
        form.addView(field, LinearLayout.LayoutParams(MATCH, a.dp(52)).apply { topMargin = a.dp(8) })
        form.addView(a.text(a.getString(R.string.lights_name_hint), 13f, dim = true),
                     LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(6) })
        val error = a.text("", 14f).apply {
            setTextColor(a.color(R.color.retro_bad))
            visibility = View.GONE
        }
        form.addView(error, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(8) })

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
                    error.text = a.getString(R.string.lights_name_empty)
                    error.visibility = View.VISIBLE
                } else {
                    save(value, saveButton)
                }
            }
            addView(saveButton, LinearLayout.LayoutParams(0, a.dp(56), 1f))
            addView(a.button(a.getString(R.string.cancel), big = true) { back(null) },
                    LinearLayout.LayoutParams(0, a.dp(56), 1f).apply { marginStart = a.dp(8) })
        }, LinearLayout.LayoutParams(MATCH, WRAP).apply { topMargin = a.dp(12) })
        if (ownName.isNotEmpty()) {
            // Reversible (type it again), so no second question.
            form.addView(a.button(a.getString(R.string.lights_name_remove)) { save("", null) },
                         LinearLayout.LayoutParams(MATCH, a.dp(48)).apply { topMargin = a.dp(12) })
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
        private const val TAG = "KioskHome"
        private const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        private const val WRAP = ViewGroup.LayoutParams.WRAP_CONTENT
    }
}
