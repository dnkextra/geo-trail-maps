package org.geotrailmaps.geo

import org.junit.Assert.assertEquals
import org.junit.Test

class GeoMathTest {

    @Test
    fun utmKnownValue() {
        // On the central meridian of zone 18 at 45N.
        val c = Utm.fromLatLon(LatLon(45.0, -75.0))
        assertEquals(18, c.zone)
        assertEquals(500000.0, c.easting, 0.01)
        assertEquals(4982950.4, c.northing, 0.1)
    }

    @Test
    fun utmRoundTrip() {
        for (p in listOf(LatLon(34.05, -117.6), LatLon(-33.9, 151.2), LatLon(60.1, 5.3), LatLon(5.9, 116.1))) {
            val back = Utm.toLatLon(Utm.fromLatLon(p))
            assertEquals(p.lat, back.lat, 1e-6)
            assertEquals(p.lon, back.lon, 1e-6)
        }
    }

    @Test
    fun localProjectionRoundTrip() {
        val proj = LocalProjection(LatLon(44.0, -110.0))
        val p = LatLon(44.6, -109.2)
        val back = proj.inverse(proj.forward(p))
        assertEquals(p.lat, back.lat, 1e-10)
        assertEquals(p.lon, back.lon, 1e-10)
    }

    @Test
    fun homographyFitsAndInverts() {
        val from = listOf(PagePoint(0.0, 0.0), PagePoint(10.0, 0.0), PagePoint(10.0, 10.0), PagePoint(0.0, 10.0))
        val to = listOf(PagePoint(100.0, 200.0), PagePoint(300.0, 210.0), PagePoint(320.0, 400.0), PagePoint(90.0, 390.0))
        val h = Homography.fit(from, to)
        for (i in from.indices) {
            val m = h.map(from[i].x, from[i].y)
            assertEquals(to[i].x, m.x, 1e-9)
            assertEquals(to[i].y, m.y, 1e-9)
        }
        val back = h.inverse().map(to[2].x, to[2].y)
        assertEquals(10.0, back.x, 1e-9)
        assertEquals(10.0, back.y, 1e-9)
    }

    /** A UTM-projected map 60 km across, georeferenced only by its four corners. */
    @Test
    fun cornerFitIsAccurateOnLargeUtmMap() {
        val zone = 11
        val e0 = 400000.0
        val n0 = 3700000.0
        val size = 60000.0
        val pagePerMetre = 1000.0 / size
        fun page(e: Double, n: Double) = PagePoint((e - e0) * pagePerMetre, (n0 + size - n) * pagePerMetre)
        fun ground(e: Double, n: Double) = Utm.toLatLon(Utm.Coord(zone, true, e, n))
        val corners = listOf(e0 to n0 + size, e0 to n0, e0 + size to n0, e0 + size to n0 + size)
        val geo = GeoReference(corners.map { (e, n) -> ground(e, n) to page(e, n) }, corners.map { (e, n) -> page(e, n) }, null)

        val e = e0 + 21000.0
        val n = n0 + 37000.0
        val err = Geodesy.distance(geo.toLatLon(page(e, n)), ground(e, n))
        assert(err < 5.0) { "error $err m" }
    }

    @Test
    fun distanceAndArea() {
        // One degree of latitude.
        assertEquals(111195.0, Geodesy.distance(LatLon(0.0, 0.0), LatLon(1.0, 0.0)), 1.0)
        // ~1 km square near the equator.
        val d = 1000.0 / 111195.0
        val area = Geodesy.area(listOf(LatLon(0.0, 0.0), LatLon(0.0, d), LatLon(d, d), LatLon(d, 0.0)))
        assertEquals(1_000_000.0, area, 1000.0)
    }
}
