package org.geotrailmaps.ui.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import org.geotrailmaps.geo.PagePoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class MapViewportTest {
    /** A 1000 x 1000 page on a 1000 x 1000 screen: scale 1 shows all of it. */
    private fun viewport() = MapViewport(1000f, 1000f).apply { onSizeChanged(IntSize(1000, 1000)) }

    private fun MapViewport.shows(x: Float, y: Float): Boolean {
        val s = toScreen(PagePoint(x.toDouble(), y.toDouble()))
        return s.x in 0f..size.width.toFloat() && s.y in 0f..size.height.toFloat()
    }

    @Test
    fun revealLeavesTheViewAloneWhenTheRectIsAlreadyVisible() {
        val v = viewport()
        v.centerOn(PagePoint(500.0, 500.0), 2f)
        val offset = v.offset
        v.reveal(400f, 400f, 600f, 600f)
        assertEquals(2f, v.scale, 0f)
        assertEquals(offset, v.offset)
    }

    @Test
    fun revealNeverZoomsIn() {
        val v = viewport()
        v.reveal(490f, 490f, 510f, 510f) // tiny, and already in view
        assertEquals(1f, v.scale, 0f)
        assertEquals(Offset.Zero, v.offset)
    }

    @Test
    fun revealMovesWithoutZoomingWhenTheRectFitsAtThisScale() {
        val v = viewport()
        v.centerOn(PagePoint(200.0, 200.0), 4f) // shows 75..325
        v.reveal(350f, 150f, 450f, 250f)
        assertEquals(4f, v.scale, 0f)
        assertTrue(v.shows(350f, 150f) && v.shows(450f, 250f))
    }

    @Test
    fun revealLeavesTheViewAloneWhenTheRectCannotFitAtTheSmallestScale() {
        val v = viewport()
        v.reveal(0f, 400f, 10000f, 600f) // needs 0.08; the smallest scale is 0.125
        assertEquals(1f, v.scale, 0f)
        assertEquals(Offset.Zero, v.offset)
    }

    @Test
    fun revealZoomsOutJustEnoughForARectThatDoesNotFit() {
        val v = viewport()
        v.centerOn(PagePoint(200.0, 200.0), 4f)
        v.reveal(100f, 100f, 600f, 900f)
        assertEquals(1f, v.scale, 1e-4f) // 800 high in 1000, with the 0.8 margin
        assertTrue(v.shows(100f, 100f) && v.shows(600f, 900f))
    }
}
