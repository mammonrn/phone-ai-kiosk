package com.mammonrn.phoneaikiosk.settings

import android.content.BroadcastReceiver
import android.content.Context
import com.mammonrn.phoneaikiosk.R

/**
 * The Control Panel's Bluetooth tile (0.68), beside WiFi: its picture and words are
 * the radio's real state (BluetoothModel.look), kept current while the panel is in
 * front — the way the torch's label follows the LED. [onChange] redraws the panel.
 */
class BluetoothTile(private val context: Context, private val onChange: () -> Unit) {

    private var link: BluetoothLink? = null
    private var receiver: BroadcastReceiver? = null
    var look: BluetoothModel.Look = BluetoothModel.look(BluetoothModel.Radio.OFF, emptyList())
        private set

    fun watch(yes: Boolean) {
        if (yes) {
            BluetoothLink.ensurePermissions(context)
            val l = link ?: BluetoothLink(context).also { link = it }
            if (receiver == null) receiver = l.listen { update() }
            l.openProxies { update() }
            update(notify = false)
        } else {
            link?.stopListening(receiver)
            receiver = null
            link?.close()
            link = null
        }
    }

    private fun update(notify: Boolean = true) {
        val l = link ?: return
        val radio = l.radio()
        val devices = if (radio == BluetoothModel.Radio.ON) l.paired(context.getString(R.string.bt_unnamed)) else emptyList()
        val next = BluetoothModel.look(radio, devices)
        if (next != look) {
            look = next
            if (notify) onChange()
        }
    }

    fun label(): String = context.getString(look.label)
}
