package org.geotrailmaps.geo

import kotlin.math.hypot

/**
 * Links positions on the map page to positions on the earth.
 *
 * Built from control points (lat/lon <-> page position). Lat/lon is first flattened with a
 * transverse Mercator projection centred on the map, then a projective transform is fitted
 * to the page. For the projections maps normally use (UTM, state plane, Lambert) this is
 * accurate to well under the precision of a phone GPS.
 */
class GeoReference(
    val controlPoints: List<Pair<LatLon, PagePoint>>,
    /** Outline of the map area on the page (the "neatline"). */
    val neatline: List<PagePoint>,
    /** Human readable coordinate system name, if the file declares one. */
    val crsName: String?,
) {
    private val projection = LocalProjection(
        LatLon(controlPoints.map { it.first.lat }.average(), controlPoints.map { it.first.lon }.average()),
    )
    private val toPageTransform = Homography.fit(
        controlPoints.map { projection.forward(it.first) },
        controlPoints.map { it.second },
    )
    private val toGroundTransform = toPageTransform.inverse()

    fun toPage(p: LatLon): PagePoint = projection.forward(p).let { toPageTransform.map(it.x, it.y) }

    fun toLatLon(p: PagePoint): LatLon = toGroundTransform.map(p.x, p.y).let { projection.inverse(it) }

    fun contains(p: PagePoint): Boolean {
        // Ray casting point-in-polygon.
        var inside = false
        var j = neatline.size - 1
        for (i in neatline.indices) {
            val a = neatline[i]
            val b = neatline[j]
            if ((a.y > p.y) != (b.y > p.y) && p.x < (b.x - a.x) * (p.y - a.y) / (b.y - a.y) + a.x) {
                inside = !inside
            }
            j = i
        }
        return inside
    }

    fun contains(p: LatLon): Boolean = contains(toPage(p))

    /** The point of the map area nearest to [p] (p itself when it lies on the map). */
    fun nearestPointOnMap(p: LatLon): LatLon {
        val page = toPage(p)
        if (contains(page)) return p
        var best = neatline.first()
        var bestDist = Double.MAX_VALUE
        for (i in neatline.indices) {
            val a = neatline[i]
            val b = neatline[(i + 1) % neatline.size]
            val dx = b.x - a.x
            val dy = b.y - a.y
            val len2 = dx * dx + dy * dy
            val t = if (len2 == 0.0) 0.0 else (((page.x - a.x) * dx + (page.y - a.y) * dy) / len2).coerceIn(0.0, 1.0)
            val q = PagePoint(a.x + t * dx, a.y + t * dy)
            val d = hypot(q.x - page.x, q.y - page.y)
            if (d < bestDist) { bestDist = d; best = q }
        }
        return toLatLon(best)
    }

    /** Ground distance from [p] to the map area, 0 when on the map. */
    fun distanceFromMap(p: LatLon): Double = Geodesy.distance(p, nearestPointOnMap(p))

    val center: LatLon
        get() = toLatLon(PagePoint(neatline.map { it.x }.average(), neatline.map { it.y }.average()))
}
