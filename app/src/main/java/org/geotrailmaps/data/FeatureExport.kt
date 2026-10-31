package org.geotrailmaps.data

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.TimeZone

/** Writes features as KML or GPX, the formats Avenza and most GIS tools exchange. */
object FeatureExport {

    /** Features drawn in the app go at the top level; each imported layer becomes a Folder. */
    fun kml(documentName: String, features: List<Feature>, layers: List<FeatureLayer> = emptyList()): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<kml xmlns="http://www.opengis.net/kml/2.2">""")
        appendLine("<Document>")
        appendLine("<name>${esc(documentName)}</name>")
        val known = layers.map { it.id }.toSet()
        placemarks(features.filter { it.layerId == null || it.layerId !in known })
        for (layer in layers) {
            appendLine("<Folder><name>${esc(layer.name)}</name>")
            placemarks(features.filter { it.layerId == layer.id })
            appendLine("</Folder>")
        }
        appendLine("</Document>")
        appendLine("</kml>")
    }

    private fun StringBuilder.placemarks(features: List<Feature>) {
        for (f in features) {
            val color = kmlColor(f.color)
            appendLine("<Placemark>")
            appendLine("<name>${esc(f.name)}</name>")
            if (f.description.isNotBlank()) appendLine("<description>${esc(f.description)}</description>")
            appendLine("<Style><IconStyle><color>$color</color></IconStyle>")
            appendLine("<LineStyle><color>$color</color><width>3</width></LineStyle>")
            appendLine("<PolyStyle><color>${"55" + color.substring(2)}</color></PolyStyle></Style>")
            // Include elevations when known (KML's default altitude mode keeps them on the ground).
            val coords = f.points.indices.joinToString(" ") { i ->
                val p = f.points[i]
                f.track?.elevations?.getOrNull(i)?.let { "${p.lon},${p.lat},$it" } ?: "${p.lon},${p.lat}"
            }
            when (f.type) {
                FeatureType.POINT -> appendLine("<Point><coordinates>$coords</coordinates></Point>")
                FeatureType.LINE -> appendLine("<LineString><tessellate>1</tessellate><coordinates>$coords</coordinates></LineString>")
                FeatureType.AREA -> {
                    // KML wants the outer ring counterclockwise (positive shoelace area in lon/lat).
                    val signedArea = (f.points + f.points.first()).zipWithNext().sumOf { (a, b) -> a.lon * b.lat - b.lon * a.lat }
                    val ccw = if (signedArea < 0) f.points.reversed() else f.points
                    val ring = (ccw + ccw.first()).joinToString(" ") { "${it.lon},${it.lat}" }
                    appendLine("<Polygon><outerBoundaryIs><LinearRing><coordinates>$ring</coordinates></LinearRing></outerBoundaryIs></Polygon>")
                }
            }
            appendLine("</Placemark>")
        }
    }

    /** GPX has no polygons, so areas are written as closed tracks. */
    fun gpx(documentName: String, features: List<Feature>): String = buildString {
        appendLine("""<?xml version="1.0" encoding="UTF-8"?>""")
        appendLine("""<gpx version="1.1" creator="Geo Trail Maps" xmlns="http://www.topografix.com/GPX/1/1">""")
        appendLine("<metadata><name>${esc(documentName)}</name></metadata>")
        for (f in features.filter { it.type == FeatureType.POINT }) {
            val p = f.points.first()
            appendLine("""<wpt lat="${p.lat}" lon="${p.lon}">""")
            appendLine("<time>${isoTime(f.createdAt)}</time>")
            appendLine("<name>${esc(f.name)}</name>")
            if (f.description.isNotBlank()) appendLine("<desc>${esc(f.description)}</desc>")
            appendLine("</wpt>")
        }
        for (f in features.filter { it.type != FeatureType.POINT }) {
            val pts = if (f.type == FeatureType.AREA) f.points + f.points.first() else f.points
            appendLine("<trk><name>${esc(f.name)}</name>")
            if (f.description.isNotBlank()) appendLine("<desc>${esc(f.description)}</desc>")
            appendLine("<trkseg>")
            pts.forEachIndexed { i, p ->
                // Areas repeat the first point to close the ring; it has the first point's data.
                val k = if (i < f.points.size) i else 0
                val ele = f.track?.elevations?.getOrNull(k)
                val time = f.track?.times?.getOrNull(k)
                if (ele == null && time == null) {
                    appendLine("""<trkpt lat="${p.lat}" lon="${p.lon}"/>""")
                } else {
                    append("""<trkpt lat="${p.lat}" lon="${p.lon}">""")
                    ele?.let { append("<ele>$it</ele>") }
                    time?.let { append("<time>${isoTime(it)}</time>") }
                    appendLine("</trkpt>")
                }
            }
            appendLine("</trkseg></trk>")
        }
        appendLine("</gpx>")
    }

    /** KML colours are aabbggrr. */
    private fun kmlColor(argb: Int): String {
        val a = (argb ushr 24) and 0xFF
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        return "%02x%02x%02x%02x".format(a, b, g, r)
    }

    private fun isoTime(millis: Long): String =
        SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss'Z'", Locale.US).apply { timeZone = TimeZone.getTimeZone("UTC") }
            .format(Date(millis))

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")
}
