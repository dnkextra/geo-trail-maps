package org.geotrailmaps.ui.map

import androidx.compose.ui.geometry.Offset
import org.junit.Assert.assertEquals
import org.junit.Test
import org.geotrailmaps.geo.PagePoint
import org.geotrailmaps.geo.PdfLayerLines

class MapLinesTest {
    private val style = PdfLayerLines.Style(0, 1f, emptyList())
    private fun across(y: Double) = PdfLayerLines.Line(listOf(PagePoint(0.0, y), PagePoint(100.0, y)), style)
    private val viewport = MapViewport(200f, 200f) // scale 1, no offset: page points are pixels

    @Test
    fun tapPicksTheClosestLineOrTheTopmostOfEquallyCloseOnes() {
        val lines = MapLines((0..4).map { across(50.0 + it) }) { true }
        // Within a pixel of the closest counts as a tie, but ties don't add up to reach line 4.
        assertEquals(1, lines.hitTest(Offset(50f, 50f), viewport, 16f) { false })
        val casing = MapLines(listOf(across(50.0), across(50.0))) { true }
        assertEquals(1, casing.hitTest(Offset(50f, 52f), viewport, 16f) { false })
        assertEquals(null, casing.hitTest(Offset(50f, 80f), viewport, 16f) { false })
    }

    @Test
    fun pickedLinesAreFoundDespiteRoundOff() {
        val lines = MapLines(listOf(across(20.25), across(40.0))) { true }
        val back = Triple(PagePoint(0.0, 20.249999999994), PagePoint(100.000000001, 20.25), 2)
        assertEquals(setOf(0), lines.indicesOf(listOf(back)))
        // A line and another drawn along the same path (its casing) are both used.
        val twice = MapLines(listOf(across(20.0), across(20.0))) { true }
        assertEquals(setOf(0, 1), twice.indicesOf(listOf(Triple(PagePoint(0.0, 20.0), PagePoint(100.0, 20.0), 2))))
    }
}
