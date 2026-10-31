package org.geotrailmaps.data

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.geotrailmaps.geo.LatLon

class FormatsAndExportTest {

    @Test
    fun coordinateFormats() {
        val p = LatLon(49.5, -123.25)
        assertEquals("49.500000°, -123.250000°", Formats.coordinate(p, CoordinateFormat.DECIMAL))
        assertEquals("49°30'00.0\"N 123°15'00.0\"W", Formats.coordinate(p, CoordinateFormat.DMS))
        assertEquals("49°30.000'N 123°15.000'W", Formats.coordinate(p, CoordinateFormat.DDM))
        assertTrue(Formats.coordinate(p, CoordinateFormat.UTM).startsWith("10U "))
        // 64.099999...° must not show 60 seconds or 60 minutes.
        assertEquals("64°06'00.0\"N 21°54'00.0\"W", Formats.coordinate(LatLon(64.099999, -21.9), CoordinateFormat.DMS))
        assertEquals("64°06.000'N 21°54.000'W", Formats.coordinate(LatLon(64.0999999, -21.9), CoordinateFormat.DDM))
    }

    @Test
    fun distancesAndAreas() {
        assertEquals("850 m", Formats.distance(850.0, Units.METRIC))
        assertEquals("1.23 km", Formats.distance(1234.0, Units.METRIC))
        assertEquals("328 ft", Formats.distance(100.0, Units.IMPERIAL))
        assertEquals("1.00 mi", Formats.distance(1609.344, Units.IMPERIAL))
        assertEquals("2.50 ha", Formats.area(25_000.0, Units.METRIC))
        assertEquals("1.00 ac", Formats.area(4046.8564224, Units.IMPERIAL))
    }

    private val features = listOf(
        Feature(type = FeatureType.POINT, name = "Camp <1>", description = "Water & shade", points = listOf(LatLon(49.0, -123.0)), color = 0xFFE53935.toInt()),
        Feature(type = FeatureType.LINE, name = "Trail", points = listOf(LatLon(49.0, -123.0), LatLon(49.1, -123.1)), color = 0xFF1E88E5.toInt()),
        Feature(type = FeatureType.AREA, name = "Lake", points = listOf(LatLon(49.0, -123.0), LatLon(49.1, -123.0), LatLon(49.1, -123.1)), color = 0xFF43A047.toInt()),
    )

    @Test
    fun kml() {
        val kml = FeatureExport.kml("Test map", features)
        assertTrue(kml.contains("<name>Camp &lt;1&gt;</name>"))
        assertTrue(kml.contains("<description>Water &amp; shade</description>"))
        assertTrue(kml.contains("<Point><coordinates>-123.0,49.0</coordinates></Point>"))
        assertTrue(kml.contains("<color>ff3539e5</color>")) // aabbggrr
        // Polygon ring is closed.
        assertTrue(kml.contains("<coordinates>-123.0,49.0 -123.0,49.1 -123.1,49.1 -123.0,49.0</coordinates>"))
    }

    @Test
    fun kmlAreaRingIsCounterclockwise() {
        val clockwise = listOf(LatLon(0.0, 0.0), LatLon(1.0, 0.0), LatLon(1.0, 1.0), LatLon(0.0, 1.0))
        val kml = FeatureExport.kml("m", listOf(Feature(type = FeatureType.AREA, name = "a", points = clockwise, color = 0)))
        assertTrue(kml.contains("<coordinates>1.0,0.0 1.0,1.0 0.0,1.0 0.0,0.0 1.0,0.0</coordinates>"))
    }

    @Test
    fun gpx() {
        val gpx = FeatureExport.gpx("Test map", features)
        assertTrue(gpx.contains("""<wpt lat="49.0" lon="-123.0">"""))
        assertEquals(2, Regex("<trk>").findAll(gpx).count())
    }
}
