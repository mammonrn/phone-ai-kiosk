package com.mammonrn.phoneaikiosk.weather

import com.mammonrn.phoneaikiosk.weather.PlaceName.Fields
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The weather title's place name from the Geocoder's Thai fields, as they vary. */
class PlaceNameTest {

    @Test
    fun `a province with every field prefixed`() {
        val names = PlaceName.candidates(Fields(
            subLocality = "ตำบลท่าสุด", locality = null,
            subAdminArea = "อำเภอเมืองเชียงราย", adminArea = "จังหวัดเชียงราย"))
        assertEquals(listOf(
            "ต.ท่าสุด อ.เมืองเชียงราย เชียงราย",
            "อ.เมืองเชียงราย เชียงราย",
            "อ.เมืองเชียงราย",
            "เชียงราย"), names)
    }

    @Test
    fun `the sub-district in locality and the province without its prefix`() {
        val names = PlaceName.candidates(Fields(
            locality = "ตำบลแม่สาย", subAdminArea = "อำเภอแม่สาย", adminArea = "เชียงราย"))
        assertEquals("ต.แม่สาย อ.แม่สาย เชียงราย", names.first())
        assertEquals("อ.แม่สาย เชียงราย", names[1])
    }

    @Test
    fun `a district without its prefix is read from subAdminArea`() {
        val names = PlaceName.candidates(Fields(subAdminArea = "เมืองเชียงใหม่", adminArea = "เชียงใหม่"))
        assertEquals(listOf("อ.เมืองเชียงใหม่ เชียงใหม่", "อ.เมืองเชียงใหม่", "เชียงใหม่"), names)
    }

    @Test
    fun `abbreviated prefixes are read too`() {
        val names = PlaceName.candidates(Fields(locality = "ต.บ้านดู่", subAdminArea = "อ.เมืองเชียงราย",
                                                adminArea = "จ.เชียงราย"))
        assertEquals("ต.บ้านดู่ อ.เมืองเชียงราย เชียงราย", names.first())
    }

    @Test
    fun `Bangkok has khet and khwaeng wherever the Geocoder put them`() {
        // The khet in subLocality, Bangkok as the locality and the province.
        val a = PlaceName.candidates(Fields(subLocality = "เขตบางรัก", locality = "กรุงเทพมหานคร",
                                            adminArea = "กรุงเทพมหานคร"))
        assertEquals(listOf("เขตบางรัก กรุงเทพฯ", "เขตบางรัก", "กรุงเทพฯ"), a)
        // The khwaeng too; and a bare district in subAdminArea becomes a khet.
        val b = PlaceName.candidates(Fields(subLocality = "แขวงสีลม", subAdminArea = "บางรัก",
                                            adminArea = "กรุงเทพมหานคร"))
        assertEquals("แขวงสีลม เขตบางรัก กรุงเทพฯ", b.first())
        assertEquals("เขตบางรัก กรุงเทพฯ", b[1])
    }

    @Test
    fun `only a province`() {
        assertEquals(listOf("ลำปาง"), PlaceName.candidates(Fields(adminArea = "จังหวัดลำปาง")))
    }

    @Test
    fun `a sub-district and a province without a district`() {
        val names = PlaceName.candidates(Fields(subLocality = "ตำบลริมกก", adminArea = "เชียงราย"))
        assertEquals(listOf("ต.ริมกก เชียงราย", "เชียงราย"), names)
    }

    @Test
    fun `nothing usable is no name, never a number`() {
        assertTrue(PlaceName.candidates(Fields()).isEmpty())
        assertTrue(PlaceName.candidates(Fields(" ", "", null, "  ")).isEmpty())
        // A postcode or a coordinate-looking string is never a name.
        assertTrue(PlaceName.candidates(Fields(locality = "57100", adminArea = "19.91")).isEmpty())
        // A municipality is neither an amphoe nor a tambon: left out.
        assertEquals(listOf("เชียงราย"),
            PlaceName.candidates(Fields(locality = "เทศบาลนครเชียงราย", adminArea = "เชียงราย")))
    }

    @Test
    fun `an unprefixed locality is not trusted as a district`() {
        // The Geocoder often puts the town (or the province again) here.
        assertEquals(listOf("เชียงราย"),
            PlaceName.candidates(Fields(locality = "เชียงราย", adminArea = "จังหวัดเชียงราย")))
    }

    @Test
    fun `no repeats and no empty parts`() {
        val names = PlaceName.candidates(Fields(subLocality = "ตำบลเชียงราย", subAdminArea = "อำเภอเชียงราย",
                                                adminArea = "เชียงราย"))
        // A district or sub-district named like the province adds nothing.
        assertEquals(listOf("เชียงราย"), names)
        assertEquals(names.distinct(), names)
    }
}
