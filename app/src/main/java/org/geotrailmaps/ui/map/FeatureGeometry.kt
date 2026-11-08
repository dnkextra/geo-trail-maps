package org.geotrailmaps.ui.map

import org.geotrailmaps.data.Feature
import org.geotrailmaps.geo.GeoReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Page coordinates of each feature, computed once per point list. Projecting lat/lon is the
 * costly part of drawing, so with this cache a frame only applies the pan/zoom transform,
 * which keeps imported tracks with thousands of points smooth.
 */
class FeatureGeometry(private val geo: GeoReference) {
    class Shape(
        val points: List<*>,
        /** x0, y0, x1, y1, ... in page units. */
        val xy: FloatArray,
        val minX: Float,
        val minY: Float,
        val maxX: Float,
        val maxY: Float,
        /** Point indices where a new segment starts (no line from the point before), ascending. */
        val segmentStarts: IntArray = IntArray(0),
    ) {
        fun startsSegment(i: Int) = segmentStarts.isNotEmpty() && segmentStarts.binarySearch(i) >= 0
    }

    private val shapes = ConcurrentHashMap<String, Shape>()

    fun forget(ids: Collection<String>) = ids.forEach { shapes.remove(it) }

    fun shape(f: Feature): Shape {
        shapes[f.id]?.let { if (it.points === f.points) return it }
        val xy = FloatArray(f.points.size * 2)
        f.points.forEachIndexed { i, p ->
            val page = geo.toPage(p)
            xy[2 * i] = page.x.toFloat()
            xy[2 * i + 1] = page.y.toFloat()
        }
        var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
        var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
        for (i in xy.indices step 2) {
            minX = minOf(minX, xy[i]); maxX = maxOf(maxX, xy[i])
            minY = minOf(minY, xy[i + 1]); maxY = maxOf(maxY, xy[i + 1])
        }
        val starts = f.track?.segmentStarts.orEmpty().filter { it in 1 until f.points.size }.sorted().toIntArray()
        return Shape(f.points, xy, minX, minY, maxX, maxY, starts).also { shapes[f.id] = it }
    }
}
