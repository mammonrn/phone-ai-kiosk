package com.mammonrn.phoneaikiosk.ui

/**
 * The taskbar's battery: a percentage, and which of three pictures goes with it.
 *
 * Small on purpose — it sits in the clock tray and has to leave the date and
 * time readable beside it. The number carries the meaning; the picture only
 * backs it up (charging bolt, or a red bar when nearly empty), so nothing is
 * said by colour alone. Pure, so the thresholds are testable.
 */
object BatteryLabel {

    enum class Icon { NORMAL, CHARGING, LOW }

    /** At or under this, and not charging, the picture turns to the red bar. */
    const val LOW_PERCENT = 15

    /** 0-100, from BatteryManager's level and scale; -1 when unknown. */
    fun percent(level: Int, scale: Int): Int =
        if (level < 0 || scale <= 0) -1 else (level * 100 + scale / 2) / scale

    fun text(percent: Int): String = if (percent < 0) "--%" else "$percent%"

    fun icon(percent: Int, charging: Boolean): Icon = when {
        charging -> Icon.CHARGING
        percent in 0..LOW_PERCENT -> Icon.LOW
        else -> Icon.NORMAL
    }
}
