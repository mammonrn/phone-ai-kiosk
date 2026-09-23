package com.mammonrn.phoneaikiosk

import android.telephony.TelephonyDisplayInfo
import android.telephony.TelephonyManager
import com.mammonrn.phoneaikiosk.ui.NetworkWatch
import com.mammonrn.phoneaikiosk.ui.NetworkWatch.Transport
import org.junit.Assert.assertEquals
import org.junit.Test

/** The taskbar's connection word (0.42.0): WiFi, 4G, 5G — and 5G next to LTE too. */
class NetworkWatchTest {

    private val none = TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NONE

    @Test
    fun `wifi and offline`() {
        assertEquals("WiFi", NetworkWatch.word(Transport.WIFI, TelephonyManager.NETWORK_TYPE_UNKNOWN, none))
        assertEquals("ออฟไลน์", NetworkWatch.word(Transport.NONE, TelephonyManager.NETWORK_TYPE_LTE, none))
    }

    @Test
    fun `lte is 4G, nr is 5G, and lte with the 5G override is 5G`() {
        assertEquals("4G", NetworkWatch.word(Transport.CELLULAR, TelephonyManager.NETWORK_TYPE_LTE, none))
        assertEquals("5G", NetworkWatch.word(Transport.CELLULAR, TelephonyManager.NETWORK_TYPE_NR, none))
        assertEquals("5G", NetworkWatch.word(Transport.CELLULAR, TelephonyManager.NETWORK_TYPE_LTE,
                                             TelephonyDisplayInfo.OVERRIDE_NETWORK_TYPE_NR_NSA))
    }

    @Test
    fun `mobile without the phone permission says mobile, not a guess`() {
        assertEquals("มือถือ", NetworkWatch.word(Transport.CELLULAR, TelephonyManager.NETWORK_TYPE_UNKNOWN, none))
        assertEquals("3G", NetworkWatch.word(Transport.CELLULAR, TelephonyManager.NETWORK_TYPE_HSPAP, none))
    }
}
