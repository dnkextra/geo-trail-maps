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
    /**
     * True when the coordinate system names a datum that differs from WGS84 by more than GPS
     * accuracy (e.g. NAD27). Datum shifts aren't applied, so positions on such maps are off.
     */
    val oldDatum: Boolean
        get() = crsName?.replace('_', ' ')?.let { n -> OLD_DATUMS.any { n.contains(it, ignoreCase = true) } } == true

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

    fun contains(p: PagePoint): Boolean = pointInPolygon(p.x, p.y, neatline.size, { neatline[it].x }, { neatline[it].y })

    fun contains(p: LatLon): Boolean = contains(toPage(p))

    /**
     * Where the page row at height [y] crosses the neatline, ascending: a point on the row is
     * on the map when an odd number of crossings lie to its right (as [contains] decides).
     */
    fun neatlineCrossings(y: Double): DoubleArray {
        val xs = ArrayList<Double>(4)
        var j = neatline.size - 1
        for (i in neatline.indices) {
            val a = neatline[i]
            val b = neatline[j]
            if ((a.y > y) != (b.y > y)) xs += (b.x - a.x) * (y - a.y) / (b.y - a.y) + a.x
            j = i
        }
        return xs.toDoubleArray().apply { sort() }
    }

    /** The point of the map area nearest to [p] (p itself when it lies on the map). */
    fun nearestPointOnMap(p: LatLon): LatLon {
        val page = toPage(p)
        if (contains(page)) return p
        var best = neatline.first()
        var bestDist = Double.MAX_VALUE
        for (i in neatline.indices) {
            val a = neatline[i]
            val b = neatline[(i + 1) % neatline.size]
            val t = closestOnSegment(page.x, page.y, a.x, a.y, b.x, b.y)
            val q = PagePoint(a.x + t * (b.x - a.x), a.y + t * (b.y - a.y))
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

private val OLD_DATUMS = listOf("NAD27", "NAD 27", "NAD 1927", "North American 1927", "ED50", "European 1950", "OSGB", "Tokyo", "Pulkovo")
