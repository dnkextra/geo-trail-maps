package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class CoverageTest {
    private val map = LatLonBox(south = 49.0, west = -124.0, north = 49.5, east = -123.0)
    private fun line(vararg p: Pair<Double, Double>) =
        Feature(type = FeatureType.LINE, name = "t", points = p.map { LatLon(it.first, it.second) }, color = 0)

    @Test fun boxOfPoints() {
        assertEquals(LatLonBox(1.0, -3.0, 2.0, 4.0), LatLonBox.of(listOf(LatLon(2.0, -3.0), LatLon(1.0, 4.0))))
        assertNull(LatLonBox.of(emptyList()))
    }

    @Test fun trackInsideOverlaps() = assertTrue(overlaps(line(49.2 to -123.5, 49.3 to -123.4), map))

    @Test fun trackLeavingTheMapStillOverlaps() = assertTrue(overlaps(line(49.2 to -123.5, 50.5 to -121.0), map))

    @Test fun trackCrossingWithoutAPointOnTheMapOverlaps() = assertTrue(overlaps(line(48.0 to -123.5, 50.0 to -123.5), map))

    @Test fun trackElsewhereDoesNot() = assertFalse(overlaps(line(51.0 to -120.0, 51.1 to -120.1), map))

    @Test fun noMapAreaNeverOverlaps() = assertFalse(overlaps(line(49.2 to -123.5, 49.3 to -123.4), null))

    @Test fun diagonalTrackPassingBesideTheMapDoesNot() =
        // Its bounding box covers the map, but no step comes near it.
        assertFalse(overlaps(line(48.0 to -125.0, 48.0 to -122.0, 50.0 to -122.0), map))

    @Test fun jumpBetweenSegmentsIsNotCounted() {
        val flight = line(45.0 to -130.0, 45.1 to -130.1, 53.0 to -118.0, 53.1 to -118.1)
        assertFalse(overlaps(flight.copy(track = TrackData(List(4) { null }, List(4) { null }, segmentStarts = listOf(2))), map))
        assertTrue(overlaps(flight, map)) // joined, the straight line crosses it
    }

    @Test fun sparseLineMissingTheCornerDoesNot() {
        // The step's box covers the map's corner, but the line passes beside it.
        val corner = LatLonBox(south = 0.0, west = 1.8, north = 0.2, east = 2.0)
        assertFalse(overlaps(line(0.0 to 0.0, 2.0 to 2.0), corner))
        assertTrue(overlaps(line(0.0 to 0.0, 0.1 to 2.0), corner))
    }

    @Test fun stepThroughTheMapWithEndsOutside() = assertTrue(crosses(LatLon(48.0, -123.5), LatLon(50.0, -123.4), map))
}
