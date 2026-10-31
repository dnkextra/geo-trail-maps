package org.geotrailmaps.data

import org.junit.Assert.assertEquals
import org.junit.Test
import org.geotrailmaps.geo.LatLon

class TrailsTest {
    private val pts = listOf(LatLon(10.0, -84.0), LatLon(10.001, -84.0))
    private fun line(name: String, layer: FeatureLayer? = null, folder: String? = null, trail: Boolean = false) =
        Feature(type = FeatureType.LINE, name = name, points = pts, color = 0, layerId = layer?.id, folder = folder, trail = trail)

    @Test
    fun linesCountByTheirLayerOrByThemselves() {
        val trails = FeatureLayer(name = "Trails", color = 0, trails = true, trailFolders = setOf("Trails"))
        val other = FeatureLayer(name = "Other", color = 0)
        val features = listOf(
            line("in folder", trails, "Trails"),
            line("other folder", trails, "Streams"),
            line("marked", other, trail = true),
            line("unmarked", other),
            line("mine", trail = true),
            Feature(type = FeatureType.POINT, name = "point", points = pts.take(1), color = 0, trail = true),
        )
        assertEquals(listOf("in folder", "marked", "mine"), Trails.features(listOf(trails, other), features).map { it.name })
    }
}
