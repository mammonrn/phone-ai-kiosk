package com.mammonrn.phoneaikiosk

import com.mammonrn.phoneaikiosk.home.HomeDevice
import com.mammonrn.phoneaikiosk.home.HomeSummary
import com.mammonrn.phoneaikiosk.home.HomeSummary.Kind
import org.junit.Assert.assertEquals
import org.junit.Test

/** The read-only list of the home: order, words, and the never-list (2026-09-23). */
class HomeSummaryTest {

    private val devices = listOf(
        HomeDevice("c1", "กล้องหน้าบ้าน", "CameraDevice", null, true),
        HomeDevice("p1", "ปลั๊กพัดลม", "OnOffPluginUnitDevice", false, true),
        HomeDevice("l2", "ไฟห้องนอน", "DimmableLightDevice", true, true, "ห้องนอน"),
        HomeDevice("l1", "ไฟห้องนั่งเล่น", "OnOffLightDevice", false, false, "ห้องนั่งเล่น"),
        HomeDevice("d1", "ประตูรั้ว", "OnOffPluginUnitDevice", true, true),
        HomeDevice("t1", "แอร์", "ThermostatDevice", null, true),
    )

    @Test
    fun `lights first, then plugs, the rest, and what may never be switched last`() {
        val kinds = HomeSummary.rows(devices).map { it.device.id to it.kind }
        assertEquals(listOf("l2" to Kind.LIGHT, "l1" to Kind.LIGHT, "p1" to Kind.PLUG,
                            "c1" to Kind.FORBIDDEN, "d1" to Kind.FORBIDDEN, "t1" to Kind.FORBIDDEN)
                         .sortedBy { it.second.ordinal }.map { it.second },
                     kinds.map { it.second })
    }

    @Test
    fun `a plug named for the gate is forbidden whatever it claims to be`() {
        assertEquals(Kind.FORBIDDEN, HomeSummary.kind(devices[4]))
    }

    @Test
    fun `each line says name, kind and state in words`() {
        val lines = HomeSummary.rows(devices).associate { it.device.id to it.label }
        assertEquals("ไฟห้องนอน · ไฟ · เปิด", lines["l2"])
        assertEquals("ไฟห้องนั่งเล่น · ไฟ · ออฟไลน์", lines["l1"])
        assertEquals("ปลั๊กพัดลม · ปลั๊ก · ปิด", lines["p1"])
        assertEquals("กล้องหน้าบ้าน · ห้ามควบคุม · ไม่มีสถานะเปิดปิด", lines["c1"])
    }
}
