package org.geotrailmaps.geo

import org.geotrailmaps.pdf.PdfFile
import org.geotrailmaps.pdf.PdfName
import org.geotrailmaps.pdf.PdfRef
import org.geotrailmaps.pdf.PdfString
import java.io.File
import kotlin.math.abs

/** What we learn about a map PDF at import time. Sizes are in points, after page rotation. */
data class MapPdfInfo(
    val pageWidth: Double,
    val pageHeight: Double,
    val pageCount: Int,
    val geo: GeoReference?,
)

/**
 * Reads the georeferencing of the first page of a geospatial PDF.
 *
 * Supports the two encodings found in the wild:
 *  - ISO 32000 / Adobe geospatial extension: Page /VP -> Viewport /Measure (GPTS, LPTS).
 *    This is what Avenza, ArcGIS, QGIS/GDAL and USGS produce.
 *  - OGC "best practice" /LGIDict (older TerraGo files), for geographic and UTM projections.
 */
object GeoPdfParser {

    fun parse(file: File): MapPdfInfo = parse(PdfFile.open(file))

    fun parse(pdf: PdfFile): MapPdfInfo {
        val pages = pdf.dict(pdf.dict(pdf.trailer["Root"])?.get("Pages")) ?: error("No page tree")
        val pageCount = pdf.number(pages["Count"])?.toInt() ?: 1
        val (page, space) = firstPage(pdf)
        val geo = runCatching { readIsoViewports(pdf, page, space) }.getOrNull()
            ?: runCatching { readLgiDict(pdf, page, space) }.getOrNull()
        return MapPdfInfo(space.width, space.height, pageCount, geo)
    }

    /** The first page (with inherited attributes) and its coordinate space. */
    internal fun firstPage(pdf: PdfFile): Pair<Map<String, Any?>, PageSpace> {
        val catalog = pdf.dict(pdf.trailer["Root"]) ?: error("No catalog")
        val pages = pdf.dict(catalog["Pages"]) ?: error("No page tree")
        val page = firstPage(pdf, pages, emptyMap()) ?: error("No pages")

        // Like PDFium: the visible page is the CropBox clipped to the MediaBox.
        val media = normalizedBox(pdf.numbers(page["MediaBox"])) ?: doubleArrayOf(0.0, 0.0, 612.0, 792.0)
        val crop = normalizedBox(pdf.numbers(page["CropBox"]))
        val box = crop?.let {
            doubleArrayOf(maxOf(it[0], media[0]), maxOf(it[1], media[1]), minOf(it[2], media[2]), minOf(it[3], media[3]))
        }?.takeIf { it[2] > it[0] && it[3] > it[1] } ?: media
        val space = PageSpace(
            x0 = box[0], y0 = box[1], x1 = box[2], y1 = box[3],
            rotation = (((pdf.number(page["Rotate"])?.toInt() ?: 0) % 360) + 360) % 360,
        )
        return page to space
    }

    private fun normalizedBox(b: DoubleArray?): DoubleArray? = b?.takeIf { it.size == 4 }?.let {
        doubleArrayOf(minOf(it[0], it[2]), minOf(it[1], it[3]), maxOf(it[0], it[2]), maxOf(it[1], it[3]))
    }

    /** Converts PDF user space (origin bottom-left) to PdfRenderer page space (top-left, rotated). */
    internal class PageSpace(val x0: Double, val y0: Double, val x1: Double, val y1: Double, val rotation: Int) {
        private val w = x1 - x0
        private val h = y1 - y0
        val width = if (rotation % 180 == 0) w else h
        val height = if (rotation % 180 == 0) h else w

        fun toPage(x: Double, y: Double): PagePoint {
            val ux = x - x0
            val uy = y1 - y
            return when (rotation) {
                90 -> PagePoint(h - uy, ux)
                180 -> PagePoint(w - ux, h - uy)
                270 -> PagePoint(uy, w - ux)
                else -> PagePoint(ux, uy)
            }
        }
    }

    private val inheritable = listOf("MediaBox", "CropBox", "Rotate", "Resources")

    /** [seen] holds the page-tree objects already walked: a broken tree can share or loop back to nodes. */
    private fun firstPage(
        pdf: PdfFile,
        node: Map<String, Any?>,
        inherited: Map<String, Any?>,
        seen: MutableSet<Int> = HashSet(),
    ): Map<String, Any?>? {
        val attrs = inherited + inheritable.mapNotNull { k -> node[k]?.let { k to it } }
        val kids = pdf.array(node["Kids"]) ?: return attrs + node
        for (kid in kids) {
            if (kid is PdfRef && !seen.add(kid.num)) continue
            val child = pdf.dict(kid) ?: continue
            firstPage(pdf, child, attrs, seen)?.let { return it }
        }
        return null
    }

    // ---------------------------------------------------------------- ISO 32000

    private fun readIsoViewports(pdf: PdfFile, page: Map<String, Any?>, space: PageSpace): GeoReference? {
        val viewports = pdf.array(page["VP"]) ?: return null
        // A page can carry several viewports (main map, insets); use the largest.
        return viewports.mapNotNull { vp ->
            val vpDict = pdf.dict(vp) ?: return@mapNotNull null
            val measure = pdf.dict(vpDict["Measure"]) ?: return@mapNotNull null
            if ((pdf.resolve(measure["Subtype"]) as? PdfName)?.value != "GEO") return@mapNotNull null
            val bbox = pdf.numbers(vpDict["BBox"])?.takeIf { it.size == 4 }
                ?: doubleArrayOf(space.x0, space.y0, space.x1, space.y1)
            val area = abs((bbox[2] - bbox[0]) * (bbox[3] - bbox[1]))
            runCatching { area to geoFromMeasure(pdf, measure, bbox, space) }.getOrNull()
        }.maxByOrNull { it.first }?.second
    }

    private fun geoFromMeasure(pdf: PdfFile, measure: Map<String, Any?>, bbox: DoubleArray, space: PageSpace): GeoReference {
        val unitSquare = doubleArrayOf(0.0, 1.0, 0.0, 0.0, 1.0, 0.0, 1.0, 1.0)
        val gpts = pdf.numbers(measure["GPTS"]) ?: error("No GPTS")
        val bounds = pdf.numbers(measure["Bounds"])?.takeIf { it.size >= 6 } ?: unitSquare
        val lpts = pdf.numbers(measure["LPTS"]) ?: bounds

        // LPTS and Bounds are fractions of the viewport BBox, taken in the order the BBox is written.
        fun toPage(u: Double, v: Double) =
            space.toPage(bbox[0] + u * (bbox[2] - bbox[0]), bbox[1] + v * (bbox[3] - bbox[1]))

        val n = minOf(gpts.size, lpts.size) / 2
        val controlPoints = (0 until n).map { i ->
            LatLon(lat = gpts[2 * i], lon = gpts[2 * i + 1]) to toPage(lpts[2 * i], lpts[2 * i + 1])
        }
        val neatline = (0 until bounds.size / 2).map { i -> toPage(bounds[2 * i], bounds[2 * i + 1]) }
        return GeoReference(controlPoints, neatline, crsName(pdf, pdf.dict(measure["GCS"])))
    }

    private fun crsName(pdf: PdfFile, gcs: Map<String, Any?>?): String? {
        if (gcs == null) return null
        val wkt = (pdf.resolve(gcs["WKT"]) as? PdfString)?.text
        val wktName = wkt?.let { Regex("""^\s*\w+\["([^"]+)"""").find(it)?.groupValues?.get(1) }
        val epsg = pdf.number(gcs["EPSG"])?.toInt()
        return when {
            wktName != null && epsg != null -> "$wktName (EPSG:$epsg)"
            wktName != null -> wktName
            epsg != null -> "EPSG:$epsg"
            else -> null
        }
    }

    // ---------------------------------------------------------------- OGC LGIDict

    private fun readLgiDict(pdf: PdfFile, page: Map<String, Any?>, space: PageSpace): GeoReference? {
        val entries = when (val lgi = pdf.resolve(page["LGIDict"])) {
            is List<*> -> lgi.mapNotNull { pdf.dict(it) }
            null -> return null
            else -> listOfNotNull(pdf.dict(lgi))
        }
        return entries.firstNotNullOfOrNull { runCatching { geoFromLgi(pdf, it, space) }.getOrNull() }
    }

    private fun geoFromLgi(pdf: PdfFile, lgi: Map<String, Any?>, space: PageSpace): GeoReference {
        val projection = pdf.dict(lgi["Projection"]) ?: error("No projection")
        val type = text(pdf, projection["ProjectionType"])?.uppercase()
        val toLatLon: (Double, Double) -> LatLon = when (type) {
            "GEOGRAPHIC", "GEO" -> { x, y -> LatLon(y, x) }
            "UT" -> {
                val zone = pdf.number(projection["Zone"])?.toInt() ?: error("No UTM zone")
                val north = text(pdf, projection["Hemisphere"])?.uppercase() != "S"
                val utm: (Double, Double) -> LatLon = { x, y -> Utm.toLatLon(Utm.Coord(zone, north, x, y)) }
                utm
            }
            else -> error("Unsupported LGIDict projection $type")
        }

        // Neatline is in PDF user space.
        val neat = pdf.numbers(lgi["Neatline"])?.takeIf { it.size >= 6 }
        val neatUser = neat?.let { n -> (0 until n.size / 2).map { n[2 * it] to n[2 * it + 1] } }
            ?: listOf(space.x0 to space.y1, space.x0 to space.y0, space.x1 to space.y0, space.x1 to space.y1)

        val ctm = pdf.numbers(lgi["CTM"])?.takeIf { it.size == 6 }
        val controlPoints = if (ctm != null) {
            neatUser.map { (x, y) ->
                toLatLon(ctm[0] * x + ctm[2] * y + ctm[4], ctm[1] * x + ctm[3] * y + ctm[5]) to space.toPage(x, y)
            }
        } else {
            val registration = pdf.array(lgi["Registration"]) ?: error("No CTM or Registration")
            registration.mapNotNull { pdf.numbers(it)?.takeIf { r -> r.size >= 4 } }
                .map { r -> toLatLon(r[2], r[3]) to space.toPage(r[0], r[1]) }
        }
        val name = text(pdf, lgi["Description"])
        return GeoReference(controlPoints, neatUser.map { (x, y) -> space.toPage(x, y) }, name)
    }

    private fun text(pdf: PdfFile, obj: Any?): String? = when (val o = pdf.resolve(obj)) {
        is PdfString -> o.text
        is PdfName -> o.value
        else -> null
    }
}
