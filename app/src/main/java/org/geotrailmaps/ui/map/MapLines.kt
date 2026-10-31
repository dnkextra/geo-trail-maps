package org.geotrailmaps.ui.map

import androidx.compose.ui.geometry.Offset
import org.geotrailmaps.geo.PagePoint
import org.geotrailmaps.geo.PdfLayerLines
import kotlin.math.hypot
import kotlin.math.abs

/**
 * The lines drawn in a map's PDF that can be picked as trails, in page units for fast drawing
 * and hit tests. Lines outside the map frame (legend samples) and tiny ones (lettering,
 * symbols) are left out.
 */
class MapLines(read: List<PdfLayerLines.Line>, inFrame: (PagePoint) -> Boolean) {
    class Line(val shape: FeatureGeometry.Shape, val points: List<PagePoint>, val style: PdfLayerLines.Style)

    val lines: List<Line>

    /** Indices of [lines], by how they're drawn. */
    val byStyle: Map<PdfLayerLines.Style, List<Int>>

    init {
        lines = read
            .filter { l -> l.points.zipWithNext { a, b -> hypot(b.x - a.x, b.y - a.y) }.sum() >= MIN_LENGTH && l.points.any(inFrame) }
            .map { l -> Line(shape(l.points), l.points, l.style) }
        byStyle = lines.indices.groupBy { lines[it].style }
    }

    /** The line under [tap] (screen pixels) within [reach], the topmost of equally close ones; null if none. */
    fun hitTest(tap: Offset, viewport: MapViewport, reach: Float, skip: (Int) -> Boolean): Int? {
        val s = viewport.scale
        val o = viewport.offset
        val near = ArrayList<Pair<Int, Float>>()
        for ((i, line) in lines.withIndex()) {
            val shape = line.shape
            if (tap.x < shape.minX * s + o.x - reach || tap.x > shape.maxX * s + o.x + reach ||
                tap.y < shape.minY * s + o.y - reach || tap.y > shape.maxY * s + o.y + reach || skip(i)
            ) continue
            val xy = shape.xy
            val d = (0 until xy.size / 2 - 1).minOf { k ->
                distanceToSegment(
                    tap,
                    Offset(xy[2 * k] * s + o.x, xy[2 * k + 1] * s + o.y),
                    Offset(xy[2 * k + 2] * s + o.x, xy[2 * k + 3] * s + o.y),
                )
            }
            if (d <= reach) near += i to d
        }
        // Later lines are drawn on top (a trail over its casing), so they win ties.
        val closest = near.minOfOrNull { it.second } ?: return null
        return near.last { it.second <= closest + 1f }.first
    }

    /**
     * The lines that picked lines were made from (or drawn along the same path), each given by its first and last points
     * and size. Positions match to a hundredth of a point: they've been through lat/lon.
     */
    fun indicesOf(picked: List<Triple<PagePoint, PagePoint, Int>>): Set<Int> {
        if (picked.isEmpty()) return emptySet()
        fun same(a: PagePoint, b: PagePoint) = abs(a.x - b.x) < 0.01 && abs(a.y - b.y) < 0.01
        val bySize = lines.indices.groupBy { lines[it].points.size }
        // Every line drawn along the same path (a trail over its casing) counts as used.
        return picked.flatMapTo(HashSet()) { (first, last, size) ->
            bySize[size].orEmpty().filter { i -> same(lines[i].points.first(), first) && same(lines[i].points.last(), last) }
        }
    }

    companion object {
        private const val MIN_LENGTH = 6.0 // page points, about 2 mm

        /** How a style is described as a picked line's folder, e.g. "Lines drawn #C62828, 2.2 pt, dashed". */
        fun describe(style: PdfLayerLines.Style) =
            "Lines drawn %s, %s pt%s".format(style.ink ?: "#%06X".format(style.rgb), style.width, if (style.dash.isEmpty()) "" else ", dashed")

        private fun shape(points: List<PagePoint>): FeatureGeometry.Shape {
            val xy = FloatArray(points.size * 2)
            points.forEachIndexed { i, p -> xy[2 * i] = p.x.toFloat(); xy[2 * i + 1] = p.y.toFloat() }
            var minX = Float.MAX_VALUE; var minY = Float.MAX_VALUE
            var maxX = -Float.MAX_VALUE; var maxY = -Float.MAX_VALUE
            for (i in xy.indices step 2) {
                minX = minOf(minX, xy[i]); maxX = maxOf(maxX, xy[i])
                minY = minOf(minY, xy[i + 1]); maxY = maxOf(maxY, xy[i + 1])
            }
            return FeatureGeometry.Shape(points, xy, minX, minY, maxX, maxY)
        }
    }
}
