package org.geotrailmaps.ui.map

import org.geotrailmaps.data.Feature
import org.geotrailmaps.geo.GeoReference
import java.util.concurrent.ConcurrentHashMap

/**
 * Page coordinates of each feature, computed once per point list. Projecting lat/lon is the
 * costly part of drawing, so with this cache a frame only applies the pan/zoom transform,
 * which keeps imported tracks with thousands of points smooth. A list that only grew (a
 * track being recorded) has just its new points projected.
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

    fun forget(id: String) {
        shapes.remove(id)
    }

    /** Drops the shapes of features not in [ids] (deleted, or moved off this map). */
    fun retain(ids: Set<String>) {
        shapes.keys.retainAll(ids)
    }

    fun shape(f: Feature): Shape {
        val cached = shapes[f.id]
        if (cached != null && cached.points === f.points) return cached
        val n = f.points.size
        // Grown since it was cached: keep the projected points and add the new ones.
        val kept = cached?.takeIf { it.points.size in 1 until n && f.points.subList(0, it.points.size) == it.points }
        val from = kept?.points?.size ?: 0
        val xy = kept?.xy?.copyOf(n * 2) ?: FloatArray(n * 2)
        var minX = kept?.minX ?: Float.MAX_VALUE; var minY = kept?.minY ?: Float.MAX_VALUE
        var maxX = kept?.maxX ?: -Float.MAX_VALUE; var maxY = kept?.maxY ?: -Float.MAX_VALUE
        for (i in from until n) {
            val page = geo.toPage(f.points[i])
            val x = page.x.toFloat()
            val y = page.y.toFloat()
            xy[2 * i] = x
            xy[2 * i + 1] = y
            minX = minOf(minX, x); maxX = maxOf(maxX, x)
            minY = minOf(minY, y); maxY = maxOf(maxY, y)
        }
        val starts = f.track?.segmentStarts(f.points.size).orEmpty().toIntArray()
        return Shape(f.points, xy, minX, minY, maxX, maxY, starts).also { shapes[f.id] = it }
    }
}
