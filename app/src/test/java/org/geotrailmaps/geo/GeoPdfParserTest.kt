package org.geotrailmaps.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.geotrailmaps.pdf.PdfException
import org.geotrailmaps.pdf.PdfFile
import org.geotrailmaps.pdf.PdfRef
import org.geotrailmaps.pdf.PdfStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.Deflater

class GeoPdfParserTest {

    private fun sample(name: String) = File(javaClass.getResource("/geopdf/$name")!!.toURI())

    private fun assertLatLon(expected: LatLon, actual: LatLon, tol: Double = 1e-6) {
        assertEquals("lat", expected.lat, actual.lat, tol)
        assertEquals("lon", expected.lon, actual.lon, tol)
    }

    @Test
    fun iso32000Sample() {
        val info = GeoPdfParser.parse(sample("test_iso32000.pdf"))
        assertEquals(20.0, info.pageWidth, 0.0)
        val geo = assertNotNullGeo(info)
        assertLatLon(LatLon(49.0, 2.0), geo.toLatLon(PagePoint(0.0, 0.0)))
        assertLatLon(LatLon(48.0, 3.0), geo.toLatLon(PagePoint(20.0, 20.0)))
        assertTrue(geo.contains(LatLon(48.5, 2.5)))
        assertFalse(geo.contains(LatLon(50.0, 2.5)))
        assertEquals("GCS_WGS_1984 (EPSG:4326)", geo.crsName)
    }

    @Test
    fun ogcBestPracticeSample() {
        val geo = assertNotNullGeo(GeoPdfParser.parse(sample("test_ogc_bp.pdf")))
        assertLatLon(LatLon(49.0, 2.0), geo.toLatLon(PagePoint(0.0, 0.0)))
        assertLatLon(LatLon(48.0, 3.0), geo.toLatLon(PagePoint(20.0, 20.0)))
    }

    @Test
    fun adobeStyleUsesLargestViewportAndBBoxOrder() {
        val info = GeoPdfParser.parse(sample("adobe_style_geospatial.pdf"))
        val geo = assertNotNullGeo(info)
        // BBox [57 713 561 297]: LPTS (0,0) is user (57,713) = page (57, 792-713).
        assertLatLon(LatLon(44.53593, -65.03521), geo.toLatLon(PagePoint(57.0, 79.0)))
        assertLatLon(LatLon(44.20536, -65.02379), geo.toLatLon(PagePoint(57.0, 495.0)))
        val p = geo.toPage(LatLon(44.4, -64.8))
        assertLatLon(LatLon(44.4, -64.8), geo.toLatLon(p), 1e-9)
    }

    @Test
    fun lptsOutsideUnitSquare() {
        val geo = assertNotNullGeo(GeoPdfParser.parse(sample("test_pdf_composition_raster_georeferenced.pdf")))
        // Fourth control point: LPTS (1, 1.0526316) of BBox [10 5 105 100] -> user (105, 105) -> page (105, 5)
        assertLatLon(LatLon(33.90242, -117.62819), geo.toLatLon(PagePoint(105.0, 5.0)), 1e-5)
    }

    @Test
    fun plainPdfHasNoGeo() {
        val pdf = TestPdf.classic(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 300 400] >>",
                3 to "<< /Type /Page /Parent 2 0 R >>",
            ),
        )
        val info = GeoPdfParser.parse(PdfFile.fromBytes(pdf))
        assertNull(info.geo)
        assertEquals(300.0, info.pageWidth, 0.0) // inherited MediaBox
        assertEquals(400.0, info.pageHeight, 0.0)
    }

    @Test
    fun cropBoxIsClippedToMediaBox() {
        val pdf = TestPdf.classic(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 600 800] /CropBox [50 50 650 850] /VP [4 0 R] >>",
                4 to "<< /Type /Viewport /BBox [0 0 600 800] /Measure 5 0 R >>",
                5 to "<< /Type /Measure /Subtype /GEO /GPTS [10 20 9 20 9 22 10 22] /LPTS [0 1 0 0 1 0 1 1] >>",
            ),
        )
        val info = GeoPdfParser.parse(PdfFile.fromBytes(pdf))
        assertEquals(550.0, info.pageWidth, 0.0)
        assertEquals(750.0, info.pageHeight, 0.0)
        // Effective box is [50 50 600 800]; user (0, 800) = lat 10 lon 20 sits at page (-50, 0).
        assertLatLon(LatLon(10.0, 20.0), info.geo!!.toLatLon(PagePoint(-50.0, 0.0)))
    }

    private val geoObjects = mapOf(
        1 to "<< /Type /Catalog /Pages 2 0 R >>",
        2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
        3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 200 100] /Rotate 90 /VP [4 0 R] >>",
        4 to "<< /Type /Viewport /BBox [0 0 200 100] /Measure 5 0 R >>",
        5 to "<< /Type /Measure /Subtype /GEO /GPTS [10 20 9 20 9 22 10 22] /LPTS [0 1 0 0 1 0 1 1] " +
            "/GCS << /Type /PROJCS /WKT (PROJCS[\"Test \\(proj\\)\",GEOGCS[\"x\"]]) >> >>",
    )

    @Test
    fun compressedObjectStreamsAndRotation() {
        val info = GeoPdfParser.parse(PdfFile.fromBytes(TestPdf.compressed(geoObjects)))
        // Rotated 90 degrees: displayed page is 100 wide, 200 tall.
        assertEquals(100.0, info.pageWidth, 0.0)
        assertEquals(200.0, info.pageHeight, 0.0)
        val geo = assertNotNullGeo(info)
        assertEquals("Test (proj)", geo.crsName)
        // Unrotated top-left (lat 10, lon 20) is shown at the top-right after rotating clockwise.
        assertLatLon(LatLon(10.0, 20.0), geo.toLatLon(PagePoint(100.0, 0.0)))
        // Unrotated bottom-left (lat 9, lon 20) moves to the top-left.
        assertLatLon(LatLon(9.0, 20.0), geo.toLatLon(PagePoint(0.0, 0.0)))
    }

    @Test
    fun classicAndCompressedAgree() {
        val a = GeoPdfParser.parse(PdfFile.fromBytes(TestPdf.classic(geoObjects))).geo!!
        val b = GeoPdfParser.parse(PdfFile.fromBytes(TestPdf.compressed(geoObjects))).geo!!
        val p = PagePoint(33.0, 77.0)
        assertLatLon(a.toLatLon(p), b.toLatLon(p), 1e-12)
    }

    @Test
    fun damagedXrefFallsBackToScanning() {
        val bytes = TestPdf.classic(geoObjects)
        val text = String(bytes, Charsets.ISO_8859_1).replace(Regex("startxref\\s+\\d+"), "startxref\n999999")
        val geo = GeoPdfParser.parse(PdfFile.fromBytes(text.toByteArray(Charsets.ISO_8859_1))).geo
        assertNotNull(geo)
    }

    @Test(timeout = 5_000)
    fun sharedPageTreeNodesAreWalkedOnce() {
        // A real page after an empty subtree whose nodes each list the next one twice:
        // 2^36 paths if shared nodes were walked again.
        val objects = mutableMapOf(
            1 to "<< /Type /Catalog /Pages 2 0 R >>",
            2 to "<< /Type /Pages /Count 1 /Kids [3 0 R 40 0 R] >>",
            39 to "<< /Type /Pages /Count 0 /Kids [] >>",
            40 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 50] >>",
        )
        for (i in 3 until 39) objects[i] = "<< /Type /Pages /Count 0 /Kids [${i + 1} 0 R ${i + 1} 0 R] >>"
        val info = GeoPdfParser.parse(PdfFile.fromBytes(TestPdf.classic(objects)))
        assertEquals(100.0, info.pageWidth, 0.0)
    }

    @Test(timeout = 5_000)
    fun pageTreeLoopEnds() {
        val pdf = TestPdf.classic(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R >>",
                2 to "<< /Type /Pages /Count 1 /Kids [3 0 R 4 0 R] >>",
                3 to "<< /Type /Pages /Count 0 /Kids [2 0 R] >>", // loops back to the root
                4 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 50] >>",
            ),
        )
        assertEquals(100.0, GeoPdfParser.parse(PdfFile.fromBytes(pdf)).pageWidth, 0.0)
    }

    @Test
    fun flateBombIsRejected() {
        val zeros = ByteArray(PdfFile.MAX_INFLATED_BYTES + 1)
        val d = Deflater(Deflater.BEST_COMPRESSION)
        d.setInput(zeros)
        d.finish()
        val z = ByteArrayOutputStream()
        val buf = ByteArray(64 * 1024)
        while (!d.finished()) z.write(buf, 0, d.deflate(buf))
        d.end()
        val pdf = PdfFile.fromBytes(
            TestPdf.classic(
                mapOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 >>",
                    3 to "<< /Type /Page /Parent 2 0 R /MediaBox [0 0 100 100] >>",
                    4 to "<< /Length ${z.size()} /Filter /FlateDecode >>\nstream\n" + String(z.toByteArray(), Charsets.ISO_8859_1) + "\nendstream",
                ),
            ),
        )
        val stream = pdf.resolve(PdfRef(4, 0)) as PdfStream
        val e = runCatching { pdf.streamData(stream) }.exceptionOrNull()
        assertTrue("expected PdfException, got $e", e is PdfException)
    }

    private fun assertNotNullGeo(info: MapPdfInfo): GeoReference {
        assertNotNull("expected georeferencing", info.geo)
        return info.geo!!
    }
}

/** Builds tiny PDFs for tests, either with a classic xref table or fully compressed. */
object TestPdf {
    fun classic(objects: Map<Int, String>): ByteArray {
        val out = ByteArrayOutputStream()
        out.write("%PDF-1.4\n".toByteArray())
        val offsets = sortedMapOf<Int, Int>()
        for ((num, body) in objects) {
            offsets[num] = out.size()
            out.write("$num 0 obj\n$body\nendobj\n".toByteArray(Charsets.ISO_8859_1))
        }
        val xrefAt = out.size()
        val size = objects.keys.max() + 1
        val sb = StringBuilder("xref\n0 $size\n0000000000 65535 f \n")
        for (n in 1 until size) {
            sb.append(offsets[n]?.let { "%010d 00000 n \n".format(it) } ?: "0000000000 00000 f \n")
        }
        sb.append("trailer\n<< /Size $size /Root 1 0 R >>\nstartxref\n$xrefAt\n%%EOF\n")
        out.write(sb.toString().toByteArray())
        return out.toByteArray()
    }

    /** All objects in one object stream, indexed by a PNG-predicted xref stream. */
    fun compressed(objects: Map<Int, String>): ByteArray {
        val nums = objects.keys.sorted()
        val bodies = StringBuilder()
        val header = StringBuilder()
        for (n in nums) {
            header.append("$n ${bodies.length} ")
            bodies.append(objects.getValue(n)).append('\n')
        }
        val objStmContent = (header.toString() + bodies).toByteArray(Charsets.ISO_8859_1)
        val first = header.length
        val objStmNum = nums.max() + 1
        val xrefNum = objStmNum + 1

        val out = ByteArrayOutputStream()
        out.write("%PDF-1.5\n".toByteArray())
        val objStmAt = out.size()
        val z = deflate(objStmContent)
        out.write("$objStmNum 0 obj\n<< /Type /ObjStm /N ${nums.size} /First $first /Length ${z.size} /Filter /FlateDecode >>\nstream\n".toByteArray())
        out.write(z)
        out.write("\nendstream\nendobj\n".toByteArray())
        val xrefAt = out.size()

        // Rows: type(1) field2(4) field3(2)
        val rows = ArrayList<ByteArray>()
        fun row(type: Int, f2: Int, f3: Int) = rows.add(
            byteArrayOf(type.toByte(), (f2 shr 24).toByte(), (f2 shr 16).toByte(), (f2 shr 8).toByte(), f2.toByte(), (f3 shr 8).toByte(), f3.toByte()),
        )
        row(0, 0, 0)
        for (n in 1..xrefNum) {
            when (n) {
                objStmNum -> row(1, objStmAt, 0)
                xrefNum -> row(1, xrefAt, 0)
                else -> nums.indexOf(n).let { if (it >= 0) row(2, objStmNum, it) else row(0, 0, 0) }
            }
        }
        val predicted = ByteArrayOutputStream()
        var prev = ByteArray(7)
        for (r in rows) {
            predicted.write(2) // PNG "Up"
            predicted.write(ByteArray(7) { (r[it] - prev[it]).toByte() })
            prev = r
        }
        val xz = deflate(predicted.toByteArray())
        out.write(
            ("$xrefNum 0 obj\n<< /Type /XRef /Size ${xrefNum + 1} /W [1 4 2] /Root 1 0 R /Length ${xz.size} " +
                "/Filter /FlateDecode /DecodeParms << /Predictor 12 /Columns 7 >> >>\nstream\n").toByteArray(),
        )
        out.write(xz)
        out.write("\nendstream\nendobj\nstartxref\n$xrefAt\n%%EOF\n".toByteArray())
        return out.toByteArray()
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(4096)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        d.end()
        return out.toByteArray()
    }
}
