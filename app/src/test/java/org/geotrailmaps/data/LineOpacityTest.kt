package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.junit.Assert.assertEquals
import org.junit.Test

class LineOpacityTest {
    private fun line(opacity: Float?) = Feature(type = FeatureType.LINE, name = "l", points = listOf(LatLon(0.0, 0.0), LatLon(0.0, 1.0)), color = 0, opacity = opacity)

    @Test
    fun unsetIsHalf() = assertEquals(0.5f, line(null).lineOpacity, 0f)

    @Test
    fun setIsKept() = assertEquals(0.8f, line(0.8f).lineOpacity, 0f)

    @Test
    fun outOfRangeIsClamped() {
        assertEquals(Feature.MIN_OPACITY, line(0f).lineOpacity, 0f)
        assertEquals(1f, line(3f).lineOpacity, 0f)
    }
}
