package com.mammonrn.phoneaikiosk.ui

import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.telephony.TelephonyCallback
import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager

/**
 * The connection the kiosk is on, as one short word for the taskbar tray
 * (0.42.0, Poom): "WiFi", "5G", "4G", "3G", "มือถือ" or "ออฟไลน์".
 *
 * WiFi or mobile comes from the default network's transport, which needs no
 * permission beyond the ordinary network state. WHICH mobile generation needs
 * READ_PHONE_STATE (granted by the device owner, like the microphone): the
 * data network type, and — because 5G next to LTE (NSA, what most Thai 5G is)
 * reports itself as LTE there — the display info's override, which is what the
 * phone's own status bar shows. Without the permission the word is "มือถือ".
 *
 * Nothing about the network is logged or sent: no name, no address, no signal.
 */
class NetworkWatch(private val context: Context, private val onChange: (String) -> Unit) {

    private val main = Handler(Looper.getMainLooper())
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val telephony = context.getSystemService(TelephonyManager::class.java)

    @Volatile private var transport = Transport.NONE
    @Volatile private var override = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE
    private var shown: String? = null

    enum class Transport { NONE, WIFI, CELLULAR, ETHERNET, OTHER }

    private val networkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) {
            transport = when {
                caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
                caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
                caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> Transport.ETHERNET
                else -> Transport.OTHER
            }
            publish()
        }

        override fun onLost(network: Network) {
            transport = Transport.NONE
            publish()
        }
    }

    private var displayCallback: Any? = null

    fun start() {
        runCatching { connectivity?.registerDefaultNetworkCallback(networkCallback, main) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S && canReadPhone()) {
            val callback = object : TelephonyCallback(), TelephonyCallback.DisplayInfoListener {
                override fun onDisplayInfoChanged(info: TelephonyDisplayInfo) {
                    override = info.overrideNetworkType
                    publish()
                }
            }
            runCatching {
                telephony?.registerTelephonyCallback(context.mainExecutor, callback)
                displayCallback = callback
            }
        }
        publish()
    }

    fun stop() {
        runCatching { connectivity?.unregisterNetworkCallback(networkCallback) }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            (displayCallback as? TelephonyCallback)?.let { runCatching { telephony?.unregisterTelephonyCallback(it) } }
        }
        displayCallback = null
    }

    private fun canReadPhone() =
        context.checkSelfPermission(android.Manifest.permission.READ_PHONE_STATE) ==
            PackageManager.PERMISSION_GRANTED

    private fun publish() {
        val dataType = if (transport == Transport.CELLULAR && canReadPhone()) {
            runCatching { telephony?.dataNetworkType }.getOrNull() ?: TelephonyManager.NETWORK_TYPE_UNKNOWN
        } else {
            TelephonyManager.NETWORK_TYPE_UNKNOWN
        }
        val word = word(transport, dataType, override)
        if (word != shown) {
            shown = word
            main.post { onChange(word) }
        }
    }

    companion object {
        /** The word for the tray. Pure, so the JVM test can hold every case. */
        fun word(transport: Transport, dataNetworkType: Int, overrideNetworkType: Int): String = when (transport) {
            Transport.NONE -> "ออฟไลน์"
            Transport.WIFI -> "WiFi"
            Transport.ETHERNET -> "LAN"
            Transport.OTHER -> "เน็ต"
            Transport.CELLULAR -> when {
                dataNetworkType == TelephonyManager.NETWORK_TYPE_NR ||
                    overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA ||
                    overrideNetworkType == TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_ADVANCED -> "5G"
                dataNetworkType == TelephonyManager.NETWORK_TYPE_LTE ||
                    dataNetworkType == TelephonyManager.NETWORK_TYPE_IWLAN -> "4G"
                dataNetworkType in THREE_G -> "3G"
                else -> "มือถือ"
            }
        }

        private val THREE_G = setOf(
            TelephonyManager.NETWORK_TYPE_UMTS, TelephonyManager.NETWORK_TYPE_HSDPA,
            TelephonyManager.NETWORK_TYPE_HSUPA, TelephonyManager.NETWORK_TYPE_HSPA,
            TelephonyManager.NETWORK_TYPE_HSPAP, TelephonyManager.NETWORK_TYPE_TD_SCDMA,
            TelephonyManager.NETWORK_TYPE_EVDO_0, TelephonyManager.NETWORK_TYPE_EVDO_A,
            TelephonyManager.NETWORK_TYPE_EVDO_B,
        )
    }
}
