package org.geotrailmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.geotrailmaps.geo.LatLon

class CoordinateParserTest {

    private fun check(text: String, lat: Double, lon: Double, tol: Double = 1e-5) {
        val p = CoordinateParser.parse(text)
        assertNotNull("couldn't parse '$text'", p)
        assertEquals("lat of '$text'", lat, p!!.lat, tol)
        assertEquals("lon of '$text'", lon, p.lon, tol)
    }

    @Test
    fun decimal() {
        check("49.5, -123.25", 49.5, -123.25)
        check("49.5 -123.25", 49.5, -123.25)
        check("49.5N 123.25W", 49.5, -123.25)
        check("123.25W 49.5N", 49.5, -123.25)
        check("-33.9,151.2", -33.9, 151.2)
        check("10.324082°, -84.021457°", 10.324082, -84.021457)
    }

    @Test
    fun degreesMinutesSeconds() {
        check("49°30'00.0\"N 123°15'00.0\"W", 49.5, -123.25)
        check("49 30 0 N 123 15 0 W", 49.5, -123.25)
        check("N49 30.5 W123 15.2", 49 + 30.5 / 60, -(123 + 15.2 / 60))
        check("49°30.000'N 123°15.000'W", 49.5, -123.25)
        check("S 33 54 E 151 12", -33.9, 151.2)
        // An explicit minus on zero degrees must not be lost.
        check("-0°30' -78°15'", -0.5, -78.25)
        check("-0 30 -78 15", -0.5, -78.25)
    }

    @Test
    fun utm() {
        check("10U 500000 5483000", 49.4996, -123.0, 1e-3)
        check("10U 500000E 5483000N", 49.4996, -123.0, 1e-3)
    }

    @Test
    fun everyDisplayFormatRoundTrips() {
        for (p in listOf(LatLon(10.324082, -84.021457), LatLon(-33.87, 151.21), LatLon(64.1, -21.9))) {
            for (f in CoordinateFormat.entries) {
                val back = CoordinateParser.parse(Formats.coordinate(p, f))
                assertNotNull("$f: ${Formats.coordinate(p, f)}", back)
                // Display precision: 0.1" (DMS), 0.001' (DDM), 1 m (UTM).
                assertEquals("$f lat", p.lat, back!!.lat, 2e-5)
                assertEquals("$f lon", p.lon, back.lon, 2e-5)
            }
        }
    }

    @Test
    fun rejectsNonCoordinates() {
        assertNull(CoordinateParser.parse("Cabin"))
        assertNull(CoordinateParser.parse("49.5"))
        assertNull(CoordinateParser.parse("95, 10"))
        assertNull(CoordinateParser.parse("49 61 0 N 123 0 0 W"))
        assertNull(CoordinateParser.parse("trail 12"))
        assertNull(CoordinateParser.parse(""))
    }
}
