package com.mammonrn.phoneaikiosk.settings

import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothClass
import android.bluetooth.BluetoothProfile
import com.mammonrn.phoneaikiosk.R

/**
 * Bluetooth in the Control Panel (0.68, Poom): what the screen and the voice say,
 * worked out from plain numbers, with no Android object in it (BluetoothTest).
 * The Android side is [BluetoothLink]; the page is [BluetoothActivity].
 *
 * EVERY STATE HAS ITS OWN MEANING (ux-ui-design: one empty value is never two
 * meanings): no Bluetooth on this phone, no permission, off, turning on, on,
 * turning off — six different things to do, so six different values, decided
 * here and never guessed later from a null.
 *
 * WHAT IS NOT SAID, BECAUSE THE PHONE CANNOT KNOW IT: a paired device that is not
 * connected is "not connected", never "not found" — whether it is in range is
 * not something a public API tells an app, and the screen does not claim it.
 * Its battery is not shown either: BluetoothDevice.getBatteryLevel and the
 * BATTERY_LEVEL_CHANGED broadcast are @SystemApi (hidden), so there is no
 * documented way to read it.
 */
object BluetoothModel {

    enum class Radio { UNSUPPORTED, NO_PERMISSION, OFF, TURNING_ON, ON, TURNING_OFF }

    /** A paired device's link, from the public profile proxies (A2DP, headset, LE audio) and ACL events. */
    enum class Link { CONNECTED, CONNECTING, DISCONNECTING, NOT_CONNECTED }

    /** What kind of thing it is, from its Bluetooth class, for the word under its name. */
    enum class Kind { HEADPHONES, SPEAKER, AUDIO, CAR, INPUT, PHONE, COMPUTER, WEARABLE, OTHER }

    /**
     * One paired device. [key] is its hardware address: used to find it again,
     * NEVER shown, spoken or logged (a MAC address identifies a device).
     */
    data class Device(val key: String, val name: String, val kind: Kind, val link: Link)

    /** The Control Panel tile: the picture (by shape) and the words, one per state. */
    data class Look(val icon: Int, val label: Int)

    fun radioOf(supported: Boolean, permitted: Boolean, adapterState: Int): Radio = when {
        !supported -> Radio.UNSUPPORTED
        !permitted -> Radio.NO_PERMISSION
        else -> when (adapterState) {
            BluetoothAdapter.STATE_ON -> Radio.ON
            BluetoothAdapter.STATE_TURNING_ON -> Radio.TURNING_ON
            BluetoothAdapter.STATE_TURNING_OFF -> Radio.TURNING_OFF
            else -> Radio.OFF
        }
    }

    /**
     * The device's link from each profile's connection state, and whether an ACL
     * link was seen up ([aclUp]; null = not seen either way). Connected on any
     * profile wins; then connecting; then disconnecting.
     */
    fun linkOf(profileStates: List<Int>, aclUp: Boolean? = null): Link = when {
        BluetoothProfile.STATE_CONNECTED in profileStates -> Link.CONNECTED
        BluetoothProfile.STATE_CONNECTING in profileStates -> Link.CONNECTING
        BluetoothProfile.STATE_DISCONNECTING in profileStates -> Link.DISCONNECTING
        // A keyboard or a watch has no audio profile: its ACL link is all there is.
        aclUp == true -> Link.CONNECTED
        else -> Link.NOT_CONNECTED
    }

    fun kindOf(major: Int, device: Int): Kind = when (major) {
        BluetoothClass.Device.Major.AUDIO_VIDEO -> when (device) {
            BluetoothClass.Device.AUDIO_VIDEO_HEADPHONES,
            BluetoothClass.Device.AUDIO_VIDEO_WEARABLE_HEADSET,
            BluetoothClass.Device.AUDIO_VIDEO_HANDSFREE -> Kind.HEADPHONES
            BluetoothClass.Device.AUDIO_VIDEO_LOUDSPEAKER,
            BluetoothClass.Device.AUDIO_VIDEO_PORTABLE_AUDIO,
            BluetoothClass.Device.AUDIO_VIDEO_HIFI_AUDIO -> Kind.SPEAKER
            BluetoothClass.Device.AUDIO_VIDEO_CAR_AUDIO -> Kind.CAR
            else -> Kind.AUDIO
        }
        BluetoothClass.Device.Major.PERIPHERAL -> Kind.INPUT
        BluetoothClass.Device.Major.PHONE -> Kind.PHONE
        BluetoothClass.Device.Major.COMPUTER -> Kind.COMPUTER
        BluetoothClass.Device.Major.WEARABLE -> Kind.WEARABLE
        else -> Kind.OTHER
    }

    fun kindWords(kind: Kind): Int = when (kind) {
        Kind.HEADPHONES -> R.string.bt_kind_headphones
        Kind.SPEAKER -> R.string.bt_kind_speaker
        Kind.AUDIO -> R.string.bt_kind_audio
        Kind.CAR -> R.string.bt_kind_car
        Kind.INPUT -> R.string.bt_kind_input
        Kind.PHONE -> R.string.bt_kind_phone
        Kind.COMPUTER -> R.string.bt_kind_computer
        Kind.WEARABLE -> R.string.bt_kind_wearable
        Kind.OTHER -> R.string.bt_kind_other
    }

    /** The link in words, with a mark whose SHAPE differs (● ◐ ○), never colour alone. */
    fun linkWords(link: Link): Int = when (link) {
        Link.CONNECTED -> R.string.bt_link_connected
        Link.CONNECTING -> R.string.bt_link_connecting
        Link.DISCONNECTING -> R.string.bt_link_disconnecting
        Link.NOT_CONNECTED -> R.string.bt_link_not_connected
    }

    /**
     * The tile: off (a slashed pair of headphones), on (the headphones), busy
     * (the headphones over a dotted line: turning on, or a device connecting),
     * connected (over a solid line). Four shapes; the label says it too.
     */
    fun look(radio: Radio, devices: List<Device>): Look = when (radio) {
        Radio.UNSUPPORTED -> Look(R.drawable.ic_pixel_bt_off, R.string.bt_tile_unsupported)
        Radio.NO_PERMISSION -> Look(R.drawable.ic_pixel_bt_off, R.string.bt_tile_no_permission)
        Radio.OFF -> Look(R.drawable.ic_pixel_bt_off, R.string.bt_tile_off)
        Radio.TURNING_OFF -> Look(R.drawable.ic_pixel_bt_busy, R.string.bt_tile_turning_off)
        Radio.TURNING_ON -> Look(R.drawable.ic_pixel_bt_busy, R.string.bt_tile_turning_on)
        Radio.ON -> when {
            devices.any { it.link == Link.CONNECTED } -> Look(R.drawable.ic_pixel_bt_linked, R.string.bt_tile_connected)
            devices.any { it.link == Link.CONNECTING } -> Look(R.drawable.ic_pixel_bt_busy, R.string.bt_tile_connecting)
            else -> Look(R.drawable.ic_pixel_bt_on, R.string.bt_tile_on)
        }
    }

    /** Connected first, then connecting, then the rest; by name within each. */
    fun ordered(devices: List<Device>): List<Device> =
        devices.sortedWith(compareBy<Device> { it.link.ordinal }.thenBy { it.name.lowercase() })

    // ------------------------------------------------------------ pairing

    /**
     * Why pairing did not end with a bond, in words for the screen. The system's own
     * reason (EXTRA_UNBOND_REASON) is a hidden extra, so these are what the phone
     * itself saw: the bond state going back to none, nothing within the time, or
     * the start being refused.
     */
    enum class PairFail { NOT_STARTED, REFUSED, TIMEOUT, RADIO_OFF }

    fun pairFailWords(why: PairFail): Int = when (why) {
        PairFail.NOT_STARTED -> R.string.bt_pair_fail_not_started
        PairFail.REFUSED -> R.string.bt_pair_fail_refused
        PairFail.TIMEOUT -> R.string.bt_pair_fail_timeout
        PairFail.RADIO_OFF -> R.string.bt_pair_fail_radio_off
    }

    /** How long a pairing may take before it is called failed (the confirmation included). */
    const val PAIR_TIMEOUT_MS = 60_000L
    /** How long a search runs before it stops by itself (Android's own inquiry is ~12 s, repeated). */
    const val SCAN_MS = 30_000L

    /**
     * A bond state change, as a pairing outcome: true bonded, false failed, null still
     * going. BONDING → NONE is a refusal (the other side said no, or the confirmation
     * was cancelled); NONE with no BONDING before it is the start that never happened.
     */
    fun pairOutcome(previous: Int, now: Int): Boolean? = when (now) {
        android.bluetooth.BluetoothDevice.BOND_BONDED -> true
        android.bluetooth.BluetoothDevice.BOND_NONE ->
            if (previous == android.bluetooth.BluetoothDevice.BOND_BONDING) false else null
        else -> null
    }

    /** A device name, never empty and never longer than a line. */
    fun nameOf(alias: String?, name: String?, unnamed: String): String =
        (alias?.trim().takeUnless { it.isNullOrEmpty() } ?: name?.trim().takeUnless { it.isNullOrEmpty() } ?: unnamed).take(60)
}
