package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.home.HomeGate
import com.mammonrn.phoneaikiosk.home.HomeGate.Command
import com.mammonrn.phoneaikiosk.home.HomeGate.Device
import com.mammonrn.phoneaikiosk.home.HomeGate.Verdict
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The next round's switching rules, held before anything can switch (DESIGN.md). */
class HomeGateTest {

    private val lamp = Device("dev-1", "OnOffLightDevice", "ไฟห้องนั่งเล่น")
    private val allow = setOf("dev-1", "dev-cam", "dev-plug-door")

    @Test
    fun `an allowlisted light may be switched on or off`() {
        assertEquals(Verdict.Allowed, HomeGate.check(lamp, Command.ON, allow))
        assertEquals(Verdict.Allowed, HomeGate.check(lamp, Command.OFF, allow))
    }

    @Test
    fun `a light that is not on the allowlist is refused`() {
        assertEquals(Verdict.Refused("not-on-allowlist"), HomeGate.check(lamp, Command.ON, setOf("other")))
    }

    @Test
    fun `cameras, locks, doors and alarms are refused even when allowlisted`() {
        for (type in listOf("CameraDevice", "DoorLockDevice", "WindowCoveringDevice", "AlarmSystemDevice",
                            "GarageDoorDevice", "DoorbellDevice")) {
            val v = HomeGate.check(Device("dev-cam", type, "อะไรก็ได้"), Command.ON, allow)
            assertTrue(type, v is Verdict.Refused && (v as Verdict.Refused).reason.startsWith("forbidden-type"))
        }
    }

    @Test
    fun `a plug named for a door is refused whatever type it claims`() {
        for (name in listOf("ประตูหน้าบ้าน", "กล้องหน้าบ้าน", "กุญแจรั้ว", "Garage opener", "สัญญาณกันขโมย")) {
            val v = HomeGate.check(Device("dev-plug-door", "OnOffPluginUnitDevice", name), Command.ON, allow)
            assertEquals(name, Verdict.Refused("forbidden-name"), v)
        }
    }

    @Test
    fun `only lights and plugs, and only on or off`() {
        assertEquals(Verdict.Refused("not-a-light-or-plug"),
                     HomeGate.check(Device("dev-1", "FanDevice", "พัดลม"), Command.ON, allow))
        assertEquals(Verdict.Refused("only-on-or-off"), HomeGate.check(lamp, null, allow))
    }
}
