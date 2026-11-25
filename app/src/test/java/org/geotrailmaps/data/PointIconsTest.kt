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
    }

    @Test
    fun iconSurvivesKml() {
        val f = Feature(type = FeatureType.POINT, name = "Snake 1", points = listOf(LatLon(49.0, -120.0)), color = 0, icon = "🐍")
        val plain = f.copy(name = "Cabin", icon = null, points = listOf(LatLon(49.1, -120.0)))
        val kml = FeatureExport.kml("Trip", listOf(f, plain))
        val back = FeatureImport.parse("trip.kml", kml.toByteArray(), 0).features
        assertEquals(listOf("🐍", null), back.map { it.icon })
    }
}
