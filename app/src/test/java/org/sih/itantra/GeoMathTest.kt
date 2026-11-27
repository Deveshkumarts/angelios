package org.sih.itantra

import org.junit.Assert.assertEquals
import org.junit.Test
import org.sih.itantra.emergency.GeoMath

class GeoMathTest {
    @Test
    fun knownDistances() {
        // Chennai Central -> Egmore railway stations: about 1.7 km apart
        val d = GeoMath.distanceMeters(13.0827, 80.2757, 13.0780, 80.2610)
        assertEquals(1680.0, d, 40.0)
        // One degree of latitude ~ 111.2 km
        assertEquals(111_195.0, GeoMath.distanceMeters(10.0, 77.0, 11.0, 77.0), 100.0)
    }

    @Test
    fun bearingsAndLabels() {
        assertEquals(0.0, GeoMath.bearingDegrees(10.0, 77.0, 11.0, 77.0), 0.01)     // due north
        assertEquals(90.0, GeoMath.bearingDegrees(0.0, 77.0, 0.0, 78.0), 0.01)      // due east on the equator
        assertEquals(180.0, GeoMath.bearingDegrees(11.0, 77.0, 10.0, 77.0), 0.01)   // due south
        assertEquals("NE", GeoMath.compassPoint(40.0))
        assertEquals("N", GeoMath.compassPoint(355.0))
        assertEquals("SW", GeoMath.compassPoint(225.0))
    }

    @Test
    fun distanceFormatting() {
        assertEquals("340 m", GeoMath.formatDistance(338.0))
        assertEquals("1.2 km", GeoMath.formatDistance(1234.0))
        assertEquals("18 km", GeoMath.formatDistance(18_400.0))
    }
}
