package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class PointIconsTest {

    @Test
    fun recentIcons() {
        assertEquals(listOf("🐍"), PointIcons.used(emptyList(), "🐍"))
        // Used again: moves to the front, not repeated.
        assertEquals(listOf("🐻", "🐍", "🌸"), PointIcons.used(listOf("🐍", "🐻", "🌸"), "🐻"))
        assertEquals(listOf("💧", "🐍", "🐻"), PointIcons.used(listOf("🐍", "🐻", "🌸"), "💧", max = 3))
    }

    @Test
    fun firstCharacterAsSeen() {
        assertEquals("🐍", PointIcons.first("🐍🐻"))
        assertEquals("🐿️", PointIcons.first(" 🐿️ ")) // with its variation selector
        assertEquals("👍🏽", PointIcons.first("👍🏽!")) // skin tone
        assertEquals("🧑‍🌾", PointIcons.first("🧑‍🌾x")) // joined with ZWJ
        assertEquals("🇨🇦", PointIcons.first("🇨🇦🇺🇸")) // flag
        assertEquals("A", PointIcons.first("Abc"))
        assertNull(PointIcons.first("  "))
        assertNull(PointIcons.first("\u0001"))
    }

    @Test
    fun iconSurvivesKml() {
        val f = Feature(type = FeatureType.POINT, name = "Snake 1", points = listOf(LatLon(49.0, -120.0)), color = 0, icon = "🐍")
        val plain = f.copy(name = "Cabin", icon = null, points = listOf(LatLon(49.1, -120.0)))
        val kml = FeatureExport.kml("Trip", listOf(f, plain))
        val back = FeatureImport.parse("trip.kml", kml.toByteArray(), 0).features
        assertEquals(listOf("🐍", null), back.map { it.icon })
    }

    @Test
    fun otherAppsIconDataIsIgnored() {
        val kml = """<kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark><name>P</name>
            <ExtendedData><Data name="icon"><value>http://example.com/pin.png</value></Data></ExtendedData>
            <Point><coordinates>-120,49</coordinates></Point></Placemark></Document></kml>"""
        assertNull(FeatureImport.parse("other.kml", kml.toByteArray(), 0).features.single().icon)
    }
}
