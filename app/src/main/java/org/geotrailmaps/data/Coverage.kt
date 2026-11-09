package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.PagePoint

/** A latitude/longitude box, for finding which maps a global feature shows on. */
data class LatLonBox(val south: Double, val west: Double, val north: Double, val east: Double) {
    fun intersects(o: LatLonBox) = south <= o.north && o.south <= north && west <= o.east && o.west <= east

    companion object {
        fun of(points: List<LatLon>): LatLonBox? = if (points.isEmpty()) null else LatLonBox(
            points.minOf { it.lat }, points.minOf { it.lon }, points.maxOf { it.lat }, points.maxOf { it.lon },
        )
    }
}

/** The area the map's whole page covers, margins included; null without georeferencing. */
fun MapEntry.coverage(): LatLonBox? {
    val g = geo ?: return null
    val w = pageWidth.toDouble()
    val h = pageHeight.toDouble()
    // Corners and edge midpoints: edges can bow a little once projected.
    val page = listOf(0.0 to 0.0, w / 2 to 0.0, w to 0.0, w to h / 2, w to h, w / 2 to h, 0.0 to h, 0.0 to h / 2)
    return runCatching { LatLonBox.of(page.map { (x, y) -> g.toLatLon(PagePoint(x, y)) }) }.getOrNull()
        ?.takeIf { listOf(it.south, it.west, it.north, it.east).all(Double::isFinite) }
}

/**
 * Whether [f] reaches into [area]. Lines are checked a step at a time, so a long track (or one
 * with a jump between segments, e.g. a flight) isn't counted on every map within its extent.
 */
fun overlaps(f: Feature, area: LatLonBox?): Boolean {
    if (area == null || f.points.isEmpty()) return false
    if (LatLonBox.of(f.points)?.intersects(area) != true) return false
    if (f.type != FeatureType.LINE || f.points.size == 1) return true
    // Plain comparisons, no allocation per step: tracks can have many thousands of points.
    for (seg in f.segments) {
        if (seg.size == 1 && seg[0].lat in area.south..area.north && seg[0].lon in area.west..area.east) return true
        for (i in 0 until seg.size - 1) if (crosses(seg[i], seg[i + 1], area)) return true
    }
    return false
}

/** Whether the straight step [a]–[b] touches [area] (Liang–Barsky clipping, in degrees). */
internal fun crosses(a: LatLon, b: LatLon, area: LatLonBox): Boolean {
    // Cheap rejection first: most steps of a long track are nowhere near.
    if (minOf(a.lat, b.lat) > area.north || maxOf(a.lat, b.lat) < area.south ||
        minOf(a.lon, b.lon) > area.east || maxOf(a.lon, b.lon) < area.west
    ) return false
    val dx = b.lon - a.lon
    val dy = b.lat - a.lat
    var t0 = 0.0
    var t1 = 1.0
    // Each edge: p is the direction across it, q the distance inside it at the start.
    for ((p, q) in arrayOf(-dx to a.lon - area.west, dx to area.east - a.lon, -dy to a.lat - area.south, dy to area.north - a.lat)) {
        if (p == 0.0) {
            if (q < 0) return false
        } else {
            val t = q / p
            if (p < 0) t0 = maxOf(t0, t) else t1 = minOf(t1, t)
            if (t0 > t1) return false
        }
    }
    return true
}
