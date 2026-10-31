package org.geotrailmaps.geo

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.asin
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.cosh
import kotlin.math.ln
import kotlin.math.sin
import kotlin.math.sinh
import kotlin.math.sqrt
import kotlin.math.tan

data class LatLon(val lat: Double, val lon: Double)

/** A point on the PDF page, in points, origin top-left, y down (PdfRenderer's convention). */
data class PagePoint(val x: Double, val y: Double)

const val EARTH_RADIUS_M = 6371008.8

private fun Double.rad() = this * PI / 180.0
private fun Double.deg() = this * 180.0 / PI

object Geodesy {
    fun distance(a: LatLon, b: LatLon): Double {
        val dLat = (b.lat - a.lat).rad()
        val dLon = (b.lon - a.lon).rad()
        val h = sin(dLat / 2).let { it * it } +
            cos(a.lat.rad()) * cos(b.lat.rad()) * sin(dLon / 2).let { it * it }
        return 2 * EARTH_RADIUS_M * asin(sqrt(h.coerceIn(0.0, 1.0)))
    }

    fun pathLength(points: List<LatLon>): Double =
        points.zipWithNext().sumOf { (a, b) -> distance(a, b) }

    /** Initial bearing from a to b, degrees clockwise from true north. */
    fun bearing(a: LatLon, b: LatLon): Double {
        val dLon = (b.lon - a.lon).rad()
        val y = sin(dLon) * cos(b.lat.rad())
        val x = cos(a.lat.rad()) * sin(b.lat.rad()) - sin(a.lat.rad()) * cos(b.lat.rad()) * cos(dLon)
        return (atan2(y, x).deg() + 360) % 360
    }

    fun destination(from: LatLon, bearingDeg: Double, meters: Double): LatLon {
        val d = meters / EARTH_RADIUS_M
        val b = bearingDeg.rad()
        val lat1 = from.lat.rad()
        val lat2 = asin(sin(lat1) * cos(d) + cos(lat1) * sin(d) * cos(b))
        val lon2 = from.lon.rad() + atan2(sin(b) * sin(d) * cos(lat1), cos(d) - sin(lat1) * sin(lat2))
        return LatLon(lat2.deg(), (lon2.deg() + 540) % 360 - 180)
    }

    /** Area of a simple polygon on the sphere, square metres. */
    fun area(ring: List<LatLon>): Double {
        if (ring.size < 3) return 0.0
        var sum = 0.0
        for (i in ring.indices) {
            val p1 = ring[i]
            val p2 = ring[(i + 1) % ring.size]
            sum += (p2.lon - p1.lon).rad() * (2 + sin(p1.lat.rad()) + sin(p2.lat.rad()))
        }
        return abs(sum * EARTH_RADIUS_M * EARTH_RADIUS_M / 2)
    }

    fun compassPoint(bearingDeg: Double): String {
        val names = arrayOf("N", "NE", "E", "SE", "S", "SW", "W", "NW")
        return names[(((bearingDeg % 360) + 360 + 22.5) / 45).toInt() % 8]
    }
}

/**
 * Spherical transverse Mercator centred on a point. Used to flatten lat/lon before fitting
 * the page transform, so the fit stays accurate on maps that span tens of kilometres.
 */
class LocalProjection(private val center: LatLon) {
    private val lat0 = center.lat.rad()
    private val lon0 = center.lon.rad()

    fun forward(p: LatLon): PagePoint {
        val lat = p.lat.rad()
        val dLon = p.lon.rad() - lon0
        val b = (cos(lat) * sin(dLon)).coerceIn(-0.999999, 0.999999)
        val x = EARTH_RADIUS_M * 0.5 * ln((1 + b) / (1 - b))
        val y = EARTH_RADIUS_M * (atan2(tan(lat), cos(dLon)) - lat0)
        return PagePoint(x, y)
    }

    fun inverse(p: PagePoint): LatLon {
        val d = p.y / EARTH_RADIUS_M + lat0
        val xr = p.x / EARTH_RADIUS_M
        val lat = asin((sin(d) / cosh(xr)).coerceIn(-1.0, 1.0))
        val lon = lon0 + atan2(sinh(xr), cos(d))
        return LatLon(lat.deg(), lon.deg())
    }
}

/** 2D projective transform (3x3 matrix, row-major, h[8] normalised to 1). */
class Homography(private val h: DoubleArray) {

    fun map(x: Double, y: Double): PagePoint {
        val w = h[6] * x + h[7] * y + h[8]
        return PagePoint((h[0] * x + h[1] * y + h[2]) / w, (h[3] * x + h[4] * y + h[5]) / w)
    }

    fun inverse(): Homography {
        val a = h[0]; val b = h[1]; val c = h[2]
        val d = h[3]; val e = h[4]; val f = h[5]
        val g = h[6]; val hh = h[7]; val i = h[8]
        val det = a * (e * i - f * hh) - b * (d * i - f * g) + c * (d * hh - e * g)
        require(abs(det) > 1e-300) { "Singular transform" }
        return Homography(
            doubleArrayOf(
                (e * i - f * hh) / det, (c * hh - b * i) / det, (b * f - c * e) / det,
                (f * g - d * i) / det, (a * i - c * g) / det, (c * d - a * f) / det,
                (d * hh - e * g) / det, (b * g - a * hh) / det, (a * e - b * d) / det,
            ),
        )
    }

    /** Composition: (this * o) applies o first. */
    operator fun times(o: Homography): Homography {
        val r = DoubleArray(9)
        for (row in 0..2) for (col in 0..2) {
            r[row * 3 + col] = (0..2).sumOf { k -> h[row * 3 + k] * o.h[k * 3 + col] }
        }
        return Homography(r)
    }

    companion object {
        /**
         * Least-squares fit mapping [from] onto [to]. Four or more points give a full
         * projective fit; three give an affine one.
         */
        fun fit(from: List<PagePoint>, to: List<PagePoint>): Homography {
            require(from.size == to.size && from.size >= 3) { "Need at least 3 control points" }
            val nf = normalizer(from)
            val nt = normalizer(to)
            val src = from.map { nf.map(it.x, it.y) }
            val dst = to.map { nt.map(it.x, it.y) }
            val unknowns = if (from.size >= 4) 8 else 6
            val ata = Array(unknowns) { DoubleArray(unknowns) }
            val atb = DoubleArray(unknowns)
            for (k in src.indices) {
                val (u, v) = src[k]
                val (x, y) = dst[k]
                val rows = if (unknowns == 8) {
                    listOf(
                        doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0, -u * x, -v * x) to x,
                        doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0, -u * y, -v * y) to y,
                    )
                } else {
                    listOf(
                        doubleArrayOf(u, v, 1.0, 0.0, 0.0, 0.0) to x,
                        doubleArrayOf(0.0, 0.0, 0.0, u, v, 1.0) to y,
                    )
                }
                for ((row, rhs) in rows) {
                    for (i in 0 until unknowns) {
                        atb[i] += row[i] * rhs
                        for (j in 0 until unknowns) ata[i][j] += row[i] * row[j]
                    }
                }
            }
            val s = solve(ata, atb)
            val hn = Homography(
                if (unknowns == 8) doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], s[6], s[7], 1.0)
                else doubleArrayOf(s[0], s[1], s[2], s[3], s[4], s[5], 0.0, 0.0, 1.0),
            )
            return nt.inverse() * hn * nf
        }

        /** Translates to the centroid and scales to unit average distance (Hartley normalisation). */
        private fun normalizer(pts: List<PagePoint>): Homography {
            val cx = pts.sumOf { it.x } / pts.size
            val cy = pts.sumOf { it.y } / pts.size
            val meanDist = pts.sumOf { sqrt((it.x - cx) * (it.x - cx) + (it.y - cy) * (it.y - cy)) } / pts.size
            require(meanDist > 0) { "Degenerate control points" }
            val s = sqrt(2.0) / meanDist
            return Homography(doubleArrayOf(s, 0.0, -s * cx, 0.0, s, -s * cy, 0.0, 0.0, 1.0))
        }

        /** Gaussian elimination with partial pivoting. */
        private fun solve(a: Array<DoubleArray>, b: DoubleArray): DoubleArray {
            val n = b.size
            for (col in 0 until n) {
                val pivot = (col until n).maxBy { abs(a[it][col]) }
                require(abs(a[pivot][col]) > 1e-12) { "Degenerate control points" }
                a[col] = a[pivot].also { a[pivot] = a[col] }
                b[col] = b[pivot].also { b[pivot] = b[col] }
                for (r in col + 1 until n) {
                    val f = a[r][col] / a[col][col]
                    for (c in col until n) a[r][c] -= f * a[col][c]
                    b[r] -= f * b[col]
                }
            }
            val x = DoubleArray(n)
            for (r in n - 1 downTo 0) {
                x[r] = (b[r] - (r + 1 until n).sumOf { a[r][it] * x[it] }) / a[r][r]
            }
            return x
        }
    }
}

/** WGS84 UTM conversions (Snyder's series; sub-millimetre within a zone). */
object Utm {
    private const val A = 6378137.0
    private const val F = 1 / 298.257223563
    private const val K0 = 0.9996
    private const val E2 = F * (2 - F)
    private const val EP2 = E2 / (1 - E2)

    data class Coord(val zone: Int, val north: Boolean, val easting: Double, val northing: Double)

    fun zoneFor(p: LatLon): Int {
        var zone = ((p.lon + 180) / 6).toInt().coerceIn(0, 59) + 1
        if (p.lat in 56.0..<64.0 && p.lon in 3.0..<12.0) zone = 32
        if (p.lat >= 72.0 && p.lat < 84.0) {
            zone = when {
                p.lon >= 0 && p.lon < 9 -> 31
                p.lon >= 9 && p.lon < 21 -> 33
                p.lon >= 21 && p.lon < 33 -> 35
                p.lon >= 33 && p.lon < 42 -> 37
                else -> zone
            }
        }
        return zone
    }

    fun bandLetter(lat: Double): Char =
        "CDEFGHJKLMNPQRSTUVWXX"[((lat.coerceIn(-80.0, 84.0) + 80) / 8).toInt().coerceIn(0, 20)]

    private fun centralMeridian(zone: Int) = (zone - 1) * 6 - 180 + 3.0

    private fun meridianArc(phi: Double): Double {
        val e4 = E2 * E2
        val e6 = e4 * E2
        return A * (
            (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256) * phi -
                (3 * E2 / 8 + 3 * e4 / 32 + 45 * e6 / 1024) * sin(2 * phi) +
                (15 * e4 / 256 + 45 * e6 / 1024) * sin(4 * phi) -
                (35 * e6 / 3072) * sin(6 * phi)
            )
    }

    fun fromLatLon(p: LatLon, zone: Int = zoneFor(p)): Coord {
        val phi = p.lat.rad()
        val n = A / sqrt(1 - E2 * sin(phi) * sin(phi))
        val t = tan(phi) * tan(phi)
        val c = EP2 * cos(phi) * cos(phi)
        val a = cos(phi) * (p.lon - centralMeridian(zone)).rad()
        val m = meridianArc(phi)
        val easting = K0 * n * (
            a + (1 - t + c) * a * a * a / 6 +
                (5 - 18 * t + t * t + 72 * c - 58 * EP2) * a * a * a * a * a / 120
            ) + 500000.0
        var northing = K0 * (
            m + n * tan(phi) * (
                a * a / 2 + (5 - t + 9 * c + 4 * c * c) * a * a * a * a / 24 +
                    (61 - 58 * t + t * t + 600 * c - 330 * EP2) * a * a * a * a * a * a / 720
                )
            )
        val north = p.lat >= 0
        if (!north) northing += 10000000.0
        return Coord(zone, north, easting, northing)
    }

    fun toLatLon(c: Coord): LatLon {
        val e4 = E2 * E2
        val e6 = e4 * E2
        val m = (if (c.north) c.northing else c.northing - 10000000.0) / K0
        val mu = m / (A * (1 - E2 / 4 - 3 * e4 / 64 - 5 * e6 / 256))
        val e1 = (1 - sqrt(1 - E2)) / (1 + sqrt(1 - E2))
        val phi1 = mu + (3 * e1 / 2 - 27 * e1 * e1 * e1 / 32) * sin(2 * mu) +
            (21 * e1 * e1 / 16 - 55 * e1 * e1 * e1 * e1 / 32) * sin(4 * mu) +
            (151 * e1 * e1 * e1 / 96) * sin(6 * mu) +
            (1097 * e1 * e1 * e1 * e1 / 512) * sin(8 * mu)
        val c1 = EP2 * cos(phi1) * cos(phi1)
        val t1 = tan(phi1) * tan(phi1)
        val n1 = A / sqrt(1 - E2 * sin(phi1) * sin(phi1))
        val r1 = A * (1 - E2) / Math.pow(1 - E2 * sin(phi1) * sin(phi1), 1.5)
        val d = (c.easting - 500000.0) / (n1 * K0)
        val lat = phi1 - (n1 * tan(phi1) / r1) * (
            d * d / 2 - (5 + 3 * t1 + 10 * c1 - 4 * c1 * c1 - 9 * EP2) * d * d * d * d / 24 +
                (61 + 90 * t1 + 298 * c1 + 45 * t1 * t1 - 252 * EP2 - 3 * c1 * c1) * d * d * d * d * d * d / 720
            )
        val lon = (
            d - (1 + 2 * t1 + c1) * d * d * d / 6 +
                (5 - 2 * c1 + 28 * t1 - 3 * c1 * c1 + 8 * EP2 + 24 * t1 * t1) * d * d * d * d * d / 120
            ) / cos(phi1)
        return LatLon(lat.deg(), centralMeridian(c.zone) + lon.deg())
    }
}
