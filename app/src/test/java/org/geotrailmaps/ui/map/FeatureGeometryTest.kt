package org.geotrailmaps.ui.map

import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.data.TrackData
import org.geotrailmaps.geo.GeoReference
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.PagePoint
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test

class FeatureGeometryTest {
    private val geo = GeoReference(
        listOf(
            LatLon(10.0, -84.0) to PagePoint(0.0, 1000.0),
            LatLon(10.0, -83.9) to PagePoint(1000.0, 1000.0),
            LatLon(10.1, -83.9) to PagePoint(1000.0, 0.0),
            LatLon(10.1, -84.0) to PagePoint(0.0, 0.0),
        ),
        listOf(PagePoint(0.0, 0.0), PagePoint(1000.0, 0.0), PagePoint(1000.0, 1000.0), PagePoint(0.0, 1000.0)),
        null,
    )

    private fun track(points: List<LatLon>, starts: List<Int> = emptyList()) = Feature(
        id = "t", type = FeatureType.LINE, name = "", points = points, color = 0,
        track = TrackData(points.map { null }, points.map { null }, segmentStarts = starts),
    )

    private fun assertSameShape(expected: FeatureGeometry.Shape, actual: FeatureGeometry.Shape) {
        assertArrayEquals(expected.xy, actual.xy, 0f)
        assertEquals(expected.minX, actual.minX, 0f)
        assertEquals(expected.minY, actual.minY, 0f)
        assertEquals(expected.maxX, actual.maxX, 0f)
        assertEquals(expected.maxY, actual.maxY, 0f)
        assertArrayEquals(expected.segmentStarts, actual.segmentStarts)
    }

    @Test
    fun grownTrackMatchesOneProjectedFromScratch() {
        val all = List(50) { LatLon(10.0 + it * 0.001, -84.0 + (it % 7) * 0.003) }
        val cache = FeatureGeometry(geo)
        cache.shape(track(all.take(20)))
        val grown = cache.shape(track(all.toList(), starts = listOf(30)))
        assertSameShape(FeatureGeometry(geo).shape(track(all.toList(), starts = listOf(30))), grown)
    }

    @Test
    fun changedPointsAreProjectedAgain() {
        val before = List(10) { LatLon(10.0 + it * 0.001, -84.0) }
        val after = before.mapIndexed { i, p -> if (i == 3) LatLon(10.05, -83.95) else p } + LatLon(10.02, -83.98)
        val cache = FeatureGeometry(geo)
        cache.shape(track(before))
        assertSameShape(FeatureGeometry(geo).shape(track(after)), cache.shape(track(after)))
    }
}
