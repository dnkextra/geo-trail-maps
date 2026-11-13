package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.Utm
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToLong

enum class CoordinateFormat(val label: String) {
    DECIMAL("Decimal degrees"),
    DMS("Degrees minutes seconds"),
    DDM("Degrees decimal minutes"),
    UTM("UTM"),
}

enum class Units(val label: String) { METRIC("Metric"), IMPERIAL("Imperial") }

/** Which GPS fixes a recording keeps: those accurate to within [maxMeters], or all of them (null). */
enum class TrackAccuracy(val label: String, val detail: String, val maxMeters: Float?) {
    NORMAL("Within 35 m", "Skips points that may be off by more than 35 m. Best in the open.", 35f),
    LOOSE("Within 100 m", "Fewer gaps under trees or in canyons, but a rougher line", 100f),
    ALL("Keep every point", "Even ones far off, so tracks may zigzag and overstate the distance", null),
}

enum class MapSort(val label: String) { NAME("Name"), DISTANCE("Distance from you"), RECENT("Recently added") }

object Formats {
    private fun f(pattern: String, vararg args: Any) = String.format(Locale.US, pattern, *args)

    fun coordinate(p: LatLon, format: CoordinateFormat): String = when (format) {
        CoordinateFormat.DECIMAL -> f("%.6f°, %.6f°", p.lat, p.lon)
        CoordinateFormat.DMS -> "${dms(p.lat, "N", "S")} ${dms(p.lon, "E", "W")}"
        CoordinateFormat.DDM -> "${ddm(p.lat, "N", "S")} ${ddm(p.lon, "E", "W")}"
        CoordinateFormat.UTM -> Utm.fromLatLon(p).let {
            f("%d%c %d E %d N", it.zone, Utm.bandLetter(p.lat), it.easting.roundToLong(), it.northing.roundToLong())
        }
    }

    // Round the whole value first, so 59.96" shows as the next minute rather than 60.0".
    private fun dms(v: Double, pos: String, neg: String): String {
        val tenths = Math.round(abs(v) * 36000)
        val d = tenths / 36000
        val m = tenths % 36000 / 600
        val s = tenths % 600 / 10.0
        return f("%d°%02d'%04.1f\"%s", d, m, s, if (v >= 0) pos else neg)
    }

    private fun ddm(v: Double, pos: String, neg: String): String {
        val thousandths = Math.round(abs(v) * 60000)
        val d = thousandths / 60000
        val m = thousandths % 60000 / 1000.0
        return f("%d°%06.3f'%s", d, m, if (v >= 0) pos else neg)
    }

    /** "42:05" or "1:02:05". */
    fun duration(millis: Long): String {
        val total = millis / 1000
        val h = total / 3600
        val m = total % 3600 / 60
        val s = total % 60
        return if (h > 0) f("%d:%02d:%02d", h, m, s) else f("%d:%02d", m, s)
    }

    fun distance(meters: Double, units: Units): String = when (units) {
        Units.METRIC -> when {
            meters < 1000 -> f("%.0f m", meters)
            meters < 100_000 -> f("%.2f km", meters / 1000)
            else -> f("%.0f km", meters / 1000)
        }
        Units.IMPERIAL -> {
            val miles = meters / 1609.344
            when {
                miles < 0.1 -> f("%.0f ft", meters / 0.3048)
                miles < 100 -> f("%.2f mi", miles)
                else -> f("%.0f mi", miles)
            }
        }
    }

    fun area(squareMeters: Double, units: Units): String = when (units) {
        Units.METRIC -> when {
            squareMeters < 10_000 -> f("%.0f m²", squareMeters)
            squareMeters < 1_000_000 -> f("%.2f ha", squareMeters / 10_000)
            else -> f("%.2f km²", squareMeters / 1_000_000)
        }
        Units.IMPERIAL -> {
            val acres = squareMeters / 4046.8564224
            when {
                acres < 0.25 -> f("%.0f ft²", squareMeters / 0.09290304)
                acres < 640 -> f("%.2f ac", acres)
                else -> f("%.2f mi²", acres / 640)
            }
        }
    }
}
