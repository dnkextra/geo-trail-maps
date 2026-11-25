package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId
import java.time.ZonedDateTime

class PointNamesTest {

    @Test
    fun defaults() {
        assertTrue(PointNames.isDefault("Placemark 3"))
        assertTrue(PointNames.isDefault("Area 12"))
        assertFalse(PointNames.isDefault("Placemark"))
        assertFalse(PointNames.isDefault("Placemark 3 by the creek"))
    }

    @Test
    fun counting() {
        var c = emptyMap<String, Int>()
        for (n in listOf("Rattlesnake", " Rattlesnake ", "Bear", "Placemark 4", "  ")) c = PointNames.used(c, n)
        // Trimmed; defaults and blanks aren't counted.
        assertEquals(mapOf("Rattlesnake" to 2, "Bear" to 1), c)
        // Full: the least used goes, never the one just counted.
        val full = PointNames.used(mapOf("A" to 3, "B" to 1, "C" to 2), "D", max = 3)
        assertEquals(mapOf("A" to 3, "C" to 2, "D" to 1), full)
    }

    @Test
    fun suggestions() {
        val c = mapOf("Rattlesnake" to 5, "Garter snake" to 2, "Bear" to 3, "Bear den" to 2, "Elk" to 1)
        // Used more than once, most used first, then A–Z.
        assertEquals(listOf("Rattlesnake", "Bear", "Bear den", "Garter snake"), PointNames.suggestions(c, ""))
        // Narrowed by what's typed, anywhere in the name, any case.
        assertEquals(listOf("Rattlesnake", "Garter snake"), PointNames.suggestions(c, "SNAKE"))
        // Not the name already typed in full.
        assertEquals(listOf("Bear den"), PointNames.suggestions(c, "bear"))
        assertEquals(emptyList<String>(), PointNames.suggestions(c, "elk"))
    }

    @Test
    fun pickerTimes() {
        val zone = ZoneId.of("America/Vancouver")
        val t = ZonedDateTime.of(2026, 11, 25, 23, 40, 0, 0, zone).toInstant().toEpochMilli()
        // Late evening locally is already the next day in UTC; the picker must get the local day.
        val day = PointNames.pickerDate(t, zone)
        assertEquals(ZonedDateTime.of(2026, 11, 25, 0, 0, 0, 0, ZoneId.of("UTC")).toInstant().toEpochMilli(), day)
        assertEquals(t, PointNames.combine(day, 23, 40, zone))
    }

    @Test
    fun pointTimeSurvivesExport() {
        val t = 1_790_000_000_000L
        val f = Feature(type = FeatureType.POINT, name = "Rattlesnake", points = listOf(LatLon(49.0, -120.0)), color = 0, createdAt = t)
        assertEquals(t, FeatureImport.parse("a.kml", FeatureExport.kml("Trip", listOf(f)).toByteArray(), 0).features.single().createdAt)
        assertEquals(t, FeatureImport.parse("a.gpx", FeatureExport.gpx("Trip", listOf(f)).toByteArray(), 0).features.single().createdAt)
    }
}
