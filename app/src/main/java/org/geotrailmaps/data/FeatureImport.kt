package org.geotrailmaps.data

import org.geotrailmaps.geo.LatLon
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import java.time.OffsetDateTime
import java.util.zip.ZipInputStream
import javax.xml.parsers.DocumentBuilderFactory

class FeatureImportException(message: String) : Exception(message)

/**
 * Reads GPX, KML and KMZ files into one layer of features.
 *
 * GPX: waypoints become placemarks, each track segment and route becomes a line.
 * KML: placemarks with Point, LineString, Polygon (outer ring), gx:Track and
 * MultiGeometry, anywhere in the folder tree (each feature remembers its folder). Styles
 * are not read; the layer's colour is used.
 */
object FeatureImport {
    private const val MAX_KML_BYTES = 64 * 1024 * 1024

    /** Parses [bytes]; [fileName] gives the layer a name when the file has none. */
    fun parse(fileName: String, bytes: ByteArray, color: Int): MapFeatures = try {
        parseUnguarded(fileName, bytes, color)
    } catch (e: StackOverflowError) {
        // Parsing and the folder/geometry walks recurse, so a small file of deeply nested
        // elements can run out of stack. That unwinds cleanly, so report it as a bad file.
        throw FeatureImportException("The file is nested too deeply to import.")
    }

    private fun parseUnguarded(fileName: String, bytes: ByteArray, color: Int): MapFeatures {
        val xml = if (bytes.size > 2 && bytes[0] == 'P'.code.toByte() && bytes[1] == 'K'.code.toByte()) kmlFromKmz(bytes) else bytes
        val root = parseXml(xml).documentElement ?: throw FeatureImportException("The file is empty.")
        // Hidden at first: a whole file of lines often covers up the map's own colours.
        val layer = FeatureLayer(name = fileName.substringBeforeLast('.').ifBlank { "Imported" }, color = color, visible = false)
        val (name, features) = when (root.local) {
            "gpx" -> readGpx(root, layer.id, color)
            "kml" -> readKml(root, layer.id, color)
            else -> throw FeatureImportException("Not a GPX or KML file.")
        }
        if (features.isEmpty()) throw FeatureImportException("No placemarks, tracks or areas found in the file.")
        return MapFeatures(listOf(if (name.isNullOrBlank()) layer else layer.copy(name = name.trim())), features)
    }

    // ---------------------------------------------------------------- GPX

    private fun readGpx(root: Element, layerId: String, color: Int): Pair<String?, List<Feature>> {
        val out = ArrayList<Feature>()
        /** One line from [segments] of points (a track's trksegs; gaps between them aren't joined). */
        fun line(name: String, desc: String, segments: List<List<Element>>) {
            val parts = segments.map { pts ->
                val kept = pts.mapNotNull { e -> gpxPoint(e)?.let { e to it } }
                Geometry(
                    FeatureType.LINE, kept.map { it.second },
                    trackData(kept.map { (e, _) -> e.childText("time")?.let(::parseTime) }, kept.map { (e, _) -> elevation(e.childText("ele")) }),
                )
            }.filter { it.points.isNotEmpty() }
            val g = if (parts.size > 1) joinParts(parts) else parts.firstOrNull() ?: return
            if (g.points.size < 2) return
            out += Feature(type = FeatureType.LINE, name = name, description = desc, points = g.points, color = color, layerId = layerId, track = g.track)
        }
        for (wpt in root.children("wpt")) {
            val p = gpxPoint(wpt) ?: continue
            out += Feature(
                type = FeatureType.POINT, name = wpt.childText("name") ?: "Waypoint", description = gpxDescription(wpt), points = listOf(p), color = color, layerId = layerId,
                createdAt = wpt.childText("time")?.let(::parseTime) ?: System.currentTimeMillis(),
            )
        }
        for ((i, trk) in root.children("trk").withIndex()) {
            // One <trk> is one track, even in several <trkseg>s (e.g. paused while moving).
            line(trk.childText("name") ?: "Track ${i + 1}", gpxDescription(trk), trk.children("trkseg").map { it.children("trkpt") })
        }
        for ((i, rte) in root.children("rte").withIndex()) {
            line(rte.childText("name") ?: "Route ${i + 1}", gpxDescription(rte), listOf(rte.children("rtept")))
        }
        val name = root.children("metadata").firstOrNull()?.childText("name")
        return name to out
    }

    private fun gpxPoint(e: Element): LatLon? =
        latLon(e.getAttribute("lat").toDoubleOrNull(), e.getAttribute("lon").toDoubleOrNull())

    private fun gpxDescription(e: Element) = listOfNotNull(e.childText("desc"), e.childText("cmt")).distinct().joinToString("\n")

    // ---------------------------------------------------------------- KML

    private fun readKml(root: Element, layerId: String, color: Int): Pair<String?, List<Feature>> {
        val out = ArrayList<Feature>()
        for ((pm, folder) in placemarks(root)) {
            val name = pm.childText("name") ?: "Placemark"
            val desc = pm.childText("description")?.let(::stripHtml).orEmpty()
            // A point's icon, as this app exports it.
            val icon = pm.children("ExtendedData").flatMap { it.children("Data") }
                .firstOrNull { it.getAttribute("name") == "icon" }?.childText("value")?.let(PointIcons::first)
            // A point's time, as this app exports it.
            val time = pm.children("TimeStamp").firstOrNull()?.childText("when")?.let(::parseTime)
            val geometries = ArrayList<Geometry>()
            pm.children().forEach { collectGeometry(it, geometries) }
            // Several lines in one placemark (a MultiGeometry) are one line with gaps, e.g. a track
            // paused while moving; it goes where the first of them was.
            val lines = geometries.filter { it.type == FeatureType.LINE }
            if (lines.size > 1) {
                val at = geometries.indexOf(lines.first())
                geometries.removeAll(lines)
                geometries.add(at, joinParts(lines))
            }
            geometries.forEachIndexed { i, g ->
                out += Feature(
                    type = g.type,
                    name = if (geometries.size > 1) "$name (${i + 1})" else name,
                    description = desc,
                    points = g.points,
                    color = color,
                    layerId = layerId,
                    track = g.track,
                    folder = folder,
                    icon = if (g.type == FeatureType.POINT) icon else null,
                    createdAt = time?.takeIf { g.type == FeatureType.POINT } ?: System.currentTimeMillis(),
                )
            }
        }
        val document = root.children("Document").firstOrNull() ?: root.children("Folder").firstOrNull()
        return document?.childText("name") to out
    }

    /** Every placemark with the path of folders it's in, "Outer / Inner", or null if none. */
    private fun placemarks(root: Element): List<Pair<Element, String?>> {
        val out = ArrayList<Pair<Element, String?>>()
        fun walk(e: Element, folder: String?) {
            for (c in e.children()) {
                when (c.local) {
                    "Placemark" -> out += c to folder
                    "Folder" -> {
                        val name = c.childText("name") ?: "Folder"
                        walk(c, if (folder == null) name else "$folder / $name")
                    }
                    else -> walk(c, folder)
                }
            }
        }
        walk(root, null)
        return out
    }

    private class Geometry(val type: FeatureType, val points: List<LatLon>, val track: TrackData? = null)

    private fun collectGeometry(e: Element, out: MutableList<Geometry>) {
        when (e.local) {
            "Point" -> e.childText("coordinates")?.let(::kmlCoordinates)?.firstOrNull()?.let { out += Geometry(FeatureType.POINT, listOf(it.first)) }
            "LineString" -> e.childText("coordinates")?.let(::kmlCoordinates)?.takeIf { it.size >= 2 }?.let { c ->
                out += Geometry(FeatureType.LINE, c.map { it.first }, trackData(c.map { null }, c.map { it.second }))
            }
            "Polygon" -> {
                val ring = e.children("outerBoundaryIs").firstOrNull()?.children("LinearRing")?.firstOrNull()
                    ?.childText("coordinates")?.let(::kmlCoordinates)?.map { it.first }
                    ?.let { if (it.size > 1 && it.first() == it.last()) it.dropLast(1) else it }
                if (ring != null && ring.size >= 3) out += Geometry(FeatureType.AREA, ring)
            }
            "Track" -> { // gx:Track: <when> times paired with <gx:coord>lon lat alt</gx:coord>
                val whens = e.children("when").map { parseTime(it.textContent.trim()) }
                val kept = e.children("coord").mapIndexedNotNull { i, c ->
                    val v = c.textContent.trim().split(Regex("\\s+"))
                    latLon(v.getOrNull(1)?.toDoubleOrNull(), v.getOrNull(0)?.toDoubleOrNull())
                        ?.let { Triple(it, whens.getOrNull(i), elevation(v.getOrNull(2))) }
                }
                if (kept.size >= 2) out += Geometry(FeatureType.LINE, kept.map { it.first }, trackData(kept.map { it.second }, kept.map { it.third }))
            }
            "MultiGeometry", "MultiTrack" -> e.children().forEach { collectGeometry(it, out) }
        }
    }

    /** "lon,lat[,alt] lon,lat[,alt] ..." -> positions with their altitude, if given. */
    private fun kmlCoordinates(text: String): List<Pair<LatLon, Double?>> =
        text.trim().split(Regex("\\s+")).mapNotNull { tuple ->
            val v = tuple.split(',')
            latLon(v.getOrNull(1)?.toDoubleOrNull(), v.getOrNull(0)?.toDoubleOrNull())?.let { it to elevation(v.getOrNull(2)) }
        }

    /** Track data only when the file actually had times or elevations. */
    /** Lines joined into one, with where each began kept as [TrackData.segmentStarts]. */
    private fun joinParts(parts: List<Geometry>): Geometry = Geometry(
        FeatureType.LINE,
        parts.flatMap { it.points },
        TrackData(
            times = parts.flatMap { p -> p.track?.times ?: List(p.points.size) { null } },
            elevations = parts.flatMap { p -> p.track?.elevations ?: List(p.points.size) { null } },
            segmentStarts = parts.runningFold(0) { n, p -> n + p.points.size }.drop(1).dropLast(1),
        ),
    )

    private fun trackData(times: List<Long?>, elevations: List<Double?>): TrackData? =
        if (times.all { it == null } && elevations.all { it == null }) null else TrackData(times, elevations)

    /** ISO 8601 time, with "Z" or an offset. */
    private fun parseTime(text: String): Long? =
        runCatching { Instant.parse(text).toEpochMilli() }.getOrNull()
            ?: runCatching { OffsetDateTime.parse(text).toInstant().toEpochMilli() }.getOrNull()

    private fun stripHtml(s: String) =
        s.replace(Regex("(?i)<br\\s*/?>"), "\n").replace(Regex("<[^>]+>"), "").replace("&nbsp;", " ").trim()

    private fun kmlFromKmz(bytes: ByteArray): ByteArray {
        // A small KMZ can expand enormously, so everything inflated (including entries we
        // skip past) counts against one budget.
        var budget = MAX_KML_BYTES.toLong()
        val buffer = ByteArray(64 * 1024)
        fun readEntry(zip: ZipInputStream, keep: Boolean): ByteArray? {
            val out = if (keep) java.io.ByteArrayOutputStream() else null
            while (true) {
                val n = zip.read(buffer)
                if (n < 0) return out?.toByteArray()
                budget -= n
                if (budget < 0) throw FeatureImportException("The KMZ file is too large to import.")
                out?.write(buffer, 0, n)
            }
        }
        ZipInputStream(ByteArrayInputStream(bytes)).use { zip ->
            var fallback: ByteArray? = null
            while (true) {
                val entry = zip.nextEntry ?: break
                val isKml = entry.name.lowercase().endsWith(".kml")
                val data = readEntry(zip, keep = isKml) ?: continue
                if (entry.name.substringAfterLast('/').equals("doc.kml", ignoreCase = true)) return data
                if (fallback == null) fallback = data
            }
            return fallback ?: throw FeatureImportException("The KMZ file contains no KML document.")
        }
    }

    // ---------------------------------------------------------------- XML helpers

    private fun parseXml(bytes: ByteArray) = try {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            isExpandEntityReferences = false
            // Never fetch DTDs or external entities from an untrusted file (not every parser supports these flags).
            runCatching { setFeature("http://apache.org/xml/features/disallow-doctype-decl", true) }
            runCatching { setFeature("http://xml.org/sax/features/external-general-entities", false) }
        }
        factory.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    } catch (e: Exception) {
        throw FeatureImportException("The file isn't valid GPX or KML.")
    }

    /** NaN, infinity and overflow (1e309) can't be saved as JSON, so they count as unknown. */
    private fun elevation(text: String?): Double? = text?.toDoubleOrNull()?.takeIf { it.isFinite() }

    private fun latLon(lat: Double?, lon: Double?): LatLon? =
        if (lat != null && lon != null && lat in -90.0..90.0 && lon in -180.0..180.0) LatLon(lat, lon) else null

    /** Local element name, ignoring any namespace prefix. */
    private val Element.local: String get() = localName ?: nodeName.substringAfter(':')

    private fun Element.children(): List<Element> {
        val out = ArrayList<Element>()
        var n = firstChild
        while (n != null) {
            if (n.nodeType == Node.ELEMENT_NODE) out += n as Element
            n = n.nextSibling
        }
        return out
    }

    private fun Element.children(name: String) = children().filter { it.local == name }

    private fun Element.childText(name: String): String? =
        children(name).firstOrNull()?.textContent?.trim()?.takeIf { it.isNotEmpty() }
}
