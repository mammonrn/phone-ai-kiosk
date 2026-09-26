package com.mammonrn.phoneaikiosk.settings

import android.Manifest
import android.annotation.SuppressLint
import android.app.admin.DevicePolicyManager
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.SystemClock
import android.util.Log
import androidx.core.content.ContextCompat
import com.mammonrn.phoneaikiosk.KioskDeviceAdminReceiver
import com.mammonrn.phoneaikiosk.R
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * The phone's Bluetooth, through the PUBLIC API only (0.68, Poom). What an app can
 * and cannot do on Android 16 (API 36) decided the whole design:
 *
 *  - ON / OFF: BluetoothAdapter.enable() and disable() are deprecated from API 33
 *    and "always fail" for an app targeting 33+ — with a documented exemption:
 *    "Deprecation Exemptions: Device Owner (DO), Profile Owner (PO) and system
 *    apps" (BluetoothAdapter reference, enable()/disable()). This kiosk is the
 *    device owner, so it switches Bluetooth directly; if the phone refuses anyway
 *    the page offers the system's Bluetooth screen instead ([BluetoothSystem]).
 *  - PAIRED DEVICES, their names and whether they are connected: getBondedDevices,
 *    getAlias/getName, and getConnectionState on the public profile proxies
 *    (A2DP, HEADSET, LE_AUDIO) — BLUETOOTH_CONNECT.
 *  - SEARCHING and PAIRING a new device: startDiscovery (BLUETOOTH_SCAN) and
 *    BluetoothDevice.createBond (BLUETOOTH_CONNECT); the confirmation the system
 *    shows needs its package on the lock task allowlist for that moment only.
 *  - CONNECT / DISCONNECT a paired device, FORGET one, its BATTERY: NOT public.
 *    BluetoothA2dp.connect/disconnect, BluetoothDevice.removeBond, isConnected,
 *    getBatteryLevel and BATTERY_LEVEL_CHANGED are all @hide / @SystemApi. No
 *    reflection is used for them: connecting, disconnecting and forgetting are
 *    done on the system's own Bluetooth screen, and the battery is not shown.
 *
 * PERMISSIONS, AND WHY THE DEVICE OWNER GRANTS THEM ITSELF. BLUETOOTH_CONNECT and
 * BLUETOOTH_SCAN are runtime permissions ("nearby devices"); in lock task a
 * permission dialog is a modal nobody can answer, so the device owner grants them
 * to THIS app only, like the microphone. BLUETOOTH_SCAN is declared
 * neverForLocation: the app does not learn where the phone is from what it finds.
 * What the permissions reach stays on the phone: device names and addresses are
 * never logged or sent (the log has counts and states only).
 */
class BluetoothLink(context: Context) {

    private val app = context.applicationContext
    val adapter: BluetoothAdapter? =
        app.getSystemService(BluetoothManager::class.java)?.adapter
            ?.takeIf { app.packageManager.hasSystemFeature(PackageManager.FEATURE_BLUETOOTH) }

    /** The public audio profiles whose connection state says "connected". */
    private val proxies = java.util.concurrent.ConcurrentHashMap<Int, BluetoothProfile>()

    val supported: Boolean get() = adapter != null

    fun permitted(): Boolean = permitted(app)

    fun radio(): BluetoothModel.Radio {
        val a = adapter
        val ok = permitted()
        val state = if (a != null && ok) runCatching { a.state }.getOrDefault(BluetoothAdapter.STATE_OFF) else BluetoothAdapter.STATE_OFF
        return BluetoothModel.radioOf(a != null, ok, state)
    }

    /** Opens the profile proxies; [onReady] (main thread) each time one arrives. */
    fun openProxies(onReady: () -> Unit) {
        val a = adapter ?: return
        if (!permitted()) return
        for (profile in PROFILES) {
            if (proxies.containsKey(profile)) continue
            runCatching {
                a.getProfileProxy(app, object : BluetoothProfile.ServiceListener {
                    override fun onServiceConnected(p: Int, proxy: BluetoothProfile) {
                        proxies[p] = proxy
                        onReady()
                    }
                    override fun onServiceDisconnected(p: Int) { proxies.remove(p) }
                }, profile)
            }
        }
    }

    /** Opens the proxies and waits for them, off the main thread (the voice). */
    fun openProxiesAndWait(ms: Long) {
        val wanted = PROFILES.size
        val latch = CountDownLatch(wanted)
        openProxies { latch.countDown() }
        latch.await(ms, TimeUnit.MILLISECONDS)
    }

    fun close() {
        val a = adapter
        for ((p, proxy) in proxies) runCatching { a?.closeProfileProxy(p, proxy) }
        proxies.clear()
    }

    @SuppressLint("MissingPermission")  // permitted() is checked first
    fun paired(unnamed: String): List<BluetoothModel.Device> {
        val a = adapter ?: return emptyList()
        if (!permitted()) return emptyList()
        val bonded = runCatching { a.bondedDevices.toList() }.getOrDefault(emptyList())
        return BluetoothModel.ordered(bonded.map { device(it, unnamed) })
    }

    @SuppressLint("MissingPermission")
    fun device(d: BluetoothDevice, unnamed: String): BluetoothModel.Device {
        val states = proxies.values.map { p -> runCatching { p.getConnectionState(d) }.getOrDefault(BluetoothProfile.STATE_DISCONNECTED) }
        val cls = runCatching { d.bluetoothClass }.getOrNull()
        return BluetoothModel.Device(
            key = d.address,
            name = BluetoothModel.nameOf(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) runCatching { d.alias }.getOrNull() else null, runCatching { d.name }.getOrNull(), unnamed),
            kind = BluetoothModel.kindOf(cls?.majorDeviceClass ?: -1, cls?.deviceClass ?: -1),
            link = BluetoothModel.linkOf(states, acl[d.address]))
    }

    /**
     * On or off, directly: the device owner's exemption (see the class notes).
     * True when the change has begun; false when the phone refused.
     */
    @SuppressLint("MissingPermission")
    @Suppress("DEPRECATION")
    fun switch(on: Boolean): Boolean {
        val a = adapter ?: return false
        if (!permitted()) return false
        val started = runCatching { if (on) a.enable() else a.disable() }.getOrDefault(false)
        Log.i(TAG, "switch on=$on started=$started owner=${isOwner(app)}")
        return started
    }

    /** Waits (off the main thread) until the radio is [want], at most [ms]. */
    fun await(want: BluetoothModel.Radio, ms: Long): Boolean {
        val end = SystemClock.elapsedRealtime() + ms
        while (SystemClock.elapsedRealtime() < end) {
            if (radio() == want) return true
            SystemClock.sleep(100)
        }
        return radio() == want
    }

    @SuppressLint("MissingPermission")
    fun startSearch(): Boolean {
        val a = adapter ?: return false
        if (!permitted()) return false
        runCatching { if (a.isDiscovering) a.cancelDiscovery() }
        val ok = runCatching { a.startDiscovery() }.getOrDefault(false)
        Log.i(TAG, "search started=$ok")
        return ok
    }

    @SuppressLint("MissingPermission")
    fun stopSearch() {
        val a = adapter ?: return
        if (!permitted()) return
        runCatching { if (a.isDiscovering) a.cancelDiscovery() }
    }

    /** Starts pairing with [device]. The search stops first (Android's advice: it slows the link). */
    @SuppressLint("MissingPermission")
    fun pair(device: BluetoothDevice): Boolean {
        stopSearch()
        val ok = runCatching { device.createBond() }.getOrDefault(false)
        Log.i(TAG, "pair started=$ok")
        return ok
    }

    /** Every Bluetooth broadcast the page listens to, to [onEvent] (main thread). */
    fun listen(onEvent: (Intent) -> Unit): BroadcastReceiver {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(c: Context, i: Intent) {
                noteAcl(i)
                onEvent(i)
            }
        }
        val filter = IntentFilter().apply {
            addAction(BluetoothAdapter.ACTION_STATE_CHANGED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_STARTED)
            addAction(BluetoothAdapter.ACTION_DISCOVERY_FINISHED)
            addAction(BluetoothDevice.ACTION_FOUND)
            addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED)
            addAction(BluetoothDevice.ACTION_ACL_CONNECTED)
            addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED)
            addAction(BluetoothDevice.ACTION_NAME_CHANGED)
            addAction(android.bluetooth.BluetoothA2dp.ACTION_CONNECTION_STATE_CHANGED)
            addAction(android.bluetooth.BluetoothHeadset.ACTION_CONNECTION_STATE_CHANGED)
        }
        // Protected system broadcasts: no other app can send these actions, and
        // NOT_EXPORTED still receives the system's.
        ContextCompat.registerReceiver(app, receiver, filter, ContextCompat.RECEIVER_NOT_EXPORTED)
        return receiver
    }

    fun stopListening(receiver: BroadcastReceiver?) {
        receiver?.let { runCatching { app.unregisterReceiver(it) } }
    }

    companion object {
        private const val TAG = "KioskBluetooth"

        /** The public profiles asked for a device's connection state. LE audio exists from API 33. */
        val PROFILES: List<Int> = buildList {
            add(BluetoothProfile.A2DP)
            add(BluetoothProfile.HEADSET)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) add(BluetoothProfile.LE_AUDIO)
        }

        /** The runtime permissions Bluetooth needs from API 31; none are runtime below it. */
        val RUNTIME: List<String> =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S)
                listOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN)
            else emptyList()

        /** ACL links seen up or down while this process lives (a keyboard has no audio profile). By address, in memory only. */
        private val acl = java.util.concurrent.ConcurrentHashMap<String, Boolean>()

        private fun noteAcl(i: Intent) {
            val up = when (i.action) {
                BluetoothDevice.ACTION_ACL_CONNECTED -> true
                BluetoothDevice.ACTION_ACL_DISCONNECTED -> false
                else -> return
            }
            @Suppress("DEPRECATION")
            val d = i.getParcelableExtra<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            acl[d.address] = up
        }

        fun permitted(context: Context): Boolean = RUNTIME.all {
            ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED
        }

        fun isOwner(context: Context): Boolean =
            context.getSystemService(DevicePolicyManager::class.java)?.isDeviceOwnerApp(context.packageName) == true

        /**
         * The device owner grants THIS app the two Bluetooth permissions, with no dialog
         * (a dialog in lock task cannot be answered). Read back afterwards: the grant
         * call returns nothing that says it worked. Cheap; idempotent.
         */
        fun ensurePermissions(context: Context): Boolean {
            if (permitted(context)) return true
            val dpm = context.getSystemService(DevicePolicyManager::class.java) ?: return false
            if (!dpm.isDeviceOwnerApp(context.packageName)) return false
            val admin = KioskDeviceAdminReceiver.componentName(context)
            for (p in RUNTIME) runCatching {
                dpm.setPermissionGrantState(admin, context.packageName, p, DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED)
            }
            val ok = permitted(context)
            Log.i(TAG, "permissions granted=$ok")
            return ok
        }

        /** For the voice: a deck on the real Bluetooth, used once and closed. Worker thread only. */
        fun voiceDeck(context: Context): Pair<BluetoothVoice.Deck, () -> Unit> {
            ensurePermissions(context)
            val link = BluetoothLink(context)
            link.openProxiesAndWait(1_500)
            val unnamed = context.getString(R.string.bt_unnamed)
            val deck = object : BluetoothVoice.Deck {
                override val radio get() = link.radio()
                override val paired get() = link.paired(unnamed)
                override fun turn(on: Boolean): Boolean {
                    if (!link.switch(on)) return false
                    return link.await(if (on) BluetoothModel.Radio.ON else BluetoothModel.Radio.OFF, WAIT_MS)
                }
            }
            return deck to { link.close() }
        }

        /** How long the voice waits for the radio to finish turning on or off. */
        const val WAIT_MS = 6_000L
    }
}
