package org.geotrailmaps.data

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.InputStream

class CopyPdfTest {

    @Test
    fun copiesWholePdf() {
        val bytes = "%PDF-1.4\n".toByteArray() + ByteArray(200_000) { it.toByte() }
        val out = ByteArrayOutputStream()
        copyPdf(ByteArrayInputStream(bytes), out, "map.pdf")
        assertArrayEquals(bytes, out.toByteArray())
    }

    @Test
    fun rejectsNonPdfBeforeCopying() {
        val out = ByteArrayOutputStream()
        val e = runCatching { copyPdf(ByteArrayInputStream(ByteArray(5000)), out, "x.jpg") }.exceptionOrNull()
        assertTrue(e is ImportException && e.message!!.contains("isn't a PDF"))
        assertEquals(0, out.size())
    }

    @Test
    fun stopsEndlessStream() {
        val endless = object : InputStream() {
            var header = "%PDF-1.4\n".toByteArray()
            var pos = 0
            override fun read(): Int = if (pos < header.size) header[pos++].toInt() else 0
        }
        val e = runCatching { copyPdf(endless, ByteArrayOutputStream(), "big.pdf", maxBytes = 1_000_000) }.exceptionOrNull()
        assertTrue(e is ImportException && e.message!!.contains("too large"))
    }
}
