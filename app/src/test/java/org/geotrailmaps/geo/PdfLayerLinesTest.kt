package org.geotrailmaps.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.geotrailmaps.pdf.PdfFile
import org.geotrailmaps.pdf.PdfRef
import org.geotrailmaps.pdf.PdfStream
import java.io.ByteArrayOutputStream
import java.util.zip.Deflater

class PdfLayerLinesTest {
    private fun stream(dict: String, content: String) = "<< $dict /Length ${content.length} >>\nstream\n$content\nendstream"

    private fun map(content: String, form: String = "5 5 m 10 5 l S", resources: String = "") = PdfFile.fromBytes(
        TestPdf.classic(
            mapOf(
                1 to "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [4 0 R 5 0 R] /D << /ON [4 0 R 5 0 R] >> >> >>",
                2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                3 to "<< /Type /Page /Parent 2 0 R /Contents 6 0 R " +
                    "/Resources << /Properties << /oc1 4 0 R /oc2 5 0 R >> /XObject << /Fm1 7 0 R >> $resources >> >>",
                4 to "<< /Type /OCG /Name (Trails) >>",
                5 to "<< /Type /OCG /Name (Streams) >>",
                6 to stream("", content),
                7 to stream("/Type /XObject /Subtype /Form /BBox [0 0 100 100] /OC 4 0 R", form),
            ),
        ),
    )

    private fun trails(pdf: PdfFile) = PdfLayerLines.read(pdf) { it.contains("trail", ignoreCase = true) }

    @Test
    fun readsStrokedLinesOfTheWantedLayerOnly() {
        val lines = trails(
            map(
                """
                q 1 0 0 RG 2 w
                /OC /oc1 BDC 10 10 m 100 10 l S EMC
                /OC /oc2 BDC 10 50 m 100 50 l S EMC
                20 20 m 30 30 l S
                /OC /oc1 BDC 0 0 1 rg 10 60 50 50 re f 10 70 m 50 70 l 90 90 l n 10 80 m 20 80 l f EMC
                BI /W 1 /H 1 /BPC 8 /CS /G ID x EI
                2 0 0 2 0 0 cm /Fm1 Do
                Q
                """.trimIndent(),
            ),
        )
        // y flips: the page's top is user y 200.
        assertEquals(listOf(listOf(PagePoint(10.0, 190.0), PagePoint(100.0, 190.0)), listOf(PagePoint(10.0, 190.0), PagePoint(20.0, 190.0))), lines)
    }

    @Test
    fun flattensCurves() {
        val line = trails(map("/OC /oc1 BDC 0 100 m 50 150 100 150 100 100 c S EMC")).single()
        assertEquals(9, line.size)
        assertEquals(PagePoint(100.0, 100.0), line.last())
        assertEquals(62.5, line[4].y, 1e-9) // the curve's top: user y 137.5
    }

    @Test
    fun closingStrokeClosesOnlyTheLastSubpath() {
        val lines = trails(map("/OC /oc1 BDC 0 0 m 10 0 l 10 10 l 50 0 m 60 0 l 60 10 l h h s EMC"))
        assertEquals(3, lines[0].size)
        assertEquals(lines[1].first(), lines[1].last())
        assertEquals(4, lines[1].size) // closed once, however often it's asked
    }

    @Test
    fun readsEveryLineWithHowItsDrawn() {
        val lines = PdfLayerLines.readAll(
            map(
                """
                /OC /oc2 BDC 10 50 m 100 50 l S EMC
                q 2 0 0 2 0 0 cm 1 0 0 RG 1.5 w [3 2] 0 d 10 10 m 20 10 l S Q
                0 1 0 0 K /gs1 gs 5 5 m 5 20 l S
                """.trimIndent(),
                resources = "/ExtGState << /gs1 << /LW 4 >> >>",
            ),
        )
        assertEquals(3, lines.size)
        assertEquals(PdfLayerLines.Style(0x000000, 1f, emptyList()), lines[0].style)
        // Width and dashes scale with the transform.
        assertEquals(PdfLayerLines.Style(0xFF0000, 3f, listOf(6f, 4f)), lines[1].style)
        assertEquals(listOf(PagePoint(20.0, 180.0), PagePoint(40.0, 180.0)), lines[1].points)
        assertEquals(PdfLayerLines.Style(0xFF00FF, 4f, emptyList()), lines[2].style)
    }

    @Test
    fun spotInksAndPalettesAreToldApart() {
        val lines = PdfLayerLines.readAll(
            map(
                "/CS0 CS 1 SCN 0 0 m 10 0 l S /CS1 CS 1 SCN 0 10 m 10 10 l S /CS2 CS 1 SCN 0 20 m 10 20 l S " +
                    "/CS3 CS 1 0 SCN 0 30 m 10 30 l S 1 1 SCN 0 40 m 10 40 l S",
                resources = "/ColorSpace << /CS0 [/Separation /Red /DeviceCMYK null] /CS1 [/Separation /Blue /DeviceCMYK null] " +
                    "/CS2 [/Indexed /DeviceRGB 1 <FF000000FF00>] /CS3 [/DeviceN [/Cyan /Magenta] /DeviceCMYK null] >>",
            ),
        )
        assertEquals(listOf("Red", "Blue", null, "Cyan + Magenta 1.00/0.00", "Cyan + Magenta 1.00/1.00"), lines.map { it.style.ink })
        assertEquals(lines[0].style.rgb, lines[1].style.rgb) // both shown as the same grey…
        assertTrue(lines[0].style != lines[1].style) // …but not the same style
        assertEquals(0x00FF00, lines[2].style.rgb) // palette entry 1
    }

    @Test
    fun readsContentsInAscii85OverFlate() {
        // As ReportLab writes pages: /Filter [/ASCII85Decode /FlateDecode].
        val encoded = ascii85(deflate("/OC /oc1 BDC 10 10 m 100 10 l S EMC".toByteArray()))
        val pdf = PdfFile.fromBytes(
            TestPdf.classic(
                mapOf(
                    1 to "<< /Type /Catalog /Pages 2 0 R /OCProperties << /OCGs [4 0 R] /D << /ON [4 0 R] >> >> >>",
                    2 to "<< /Type /Pages /Kids [3 0 R] /Count 1 /MediaBox [0 0 200 200] >>",
                    3 to "<< /Type /Page /Parent 2 0 R /Contents 6 0 R /Resources << /Properties << /oc1 4 0 R >> >> >>",
                    4 to "<< /Type /OCG /Name (Trails) >>",
                    6 to stream("/Filter [/ASCII85Decode /FlateDecode]", encoded),
                ),
            ),
        )
        assertEquals(listOf(listOf(PagePoint(10.0, 190.0), PagePoint(100.0, 190.0))), trails(pdf))
    }

    @Test
    fun decodesAscii85ZerosAndShortLastGroup() {
        val raw = byteArrayOf(0, 0, 0, 0) + "Trails!".toByteArray()
        val encoded = ascii85(raw)
        assertTrue(encoded.startsWith("z"))
        // The optional <~ prefix and whitespace anywhere are skipped.
        val spaced = "<~" + encoded.chunked(3).joinToString("\n ")
        val pdf = PdfFile.fromBytes(TestPdf.classic(mapOf(1 to "<< /Type /Catalog >>", 2 to stream("/Filter /A85", spaced))))
        val stream = pdf.resolve(PdfRef(2, 0)) as PdfStream
        assertEquals(raw.toList(), pdf.streamData(stream).toList())
    }

    private fun deflate(data: ByteArray): ByteArray {
        val d = Deflater()
        d.setInput(data)
        d.finish()
        val out = ByteArrayOutputStream()
        val buf = ByteArray(1024)
        while (!d.finished()) out.write(buf, 0, d.deflate(buf))
        return out.toByteArray()
    }

    /** ASCII85 with "z" for zero groups and the "~>" end marker. */
    private fun ascii85(data: ByteArray): String {
        val sb = StringBuilder()
        for (i in data.indices step 4) {
            val n = minOf(4, data.size - i)
            var group = 0L
            for (k in 0 until 4) group = (group shl 8) or (if (k < n) data[i + k].toLong() and 0xFF else 0L)
            if (n == 4 && group == 0L) {
                sb.append('z')
                continue
            }
            val chars = CharArray(5)
            for (k in 4 downTo 0) {
                chars[k] = '!' + (group % 85).toInt()
                group /= 85
            }
            sb.append(chars, 0, n + 1)
        }
        return sb.append("~>").toString()
    }

    @Test
    fun noMatchingLayerReadsNothing() {
        val pdf = map("/OC /oc2 BDC 10 50 m 100 50 l S EMC")
        assertTrue(PdfLayerLines.read(pdf) { it == "Roads" }.isEmpty())
    }
}
