package org.geotrailmaps.ui.map

import org.geotrailmaps.ui.theme.MapStyle
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class LineWidthTest {
    @Test
    fun linesAreThinZoomedInAndFullerZoomedOut() {
        val (zoomedIn, zoomedOut) = MapStyle.quietLineZoomRange
        assertEquals(MapStyle.quietLineIn, quietLineWidthDp(zoomedIn / 4), 0.001f)
        assertEquals(MapStyle.quietLineIn, quietLineWidthDp(zoomedIn), 0.001f)
        assertEquals(MapStyle.quietLineOut, quietLineWidthDp(zoomedOut), 0.001f)
        assertEquals(MapStyle.quietLineOut, quietLineWidthDp(zoomedOut * 100), 0.001f)
        val between = quietLineWidthDp(kotlin.math.sqrt(zoomedIn * zoomedOut))
        assertEquals((MapStyle.quietLineIn + MapStyle.quietLineOut) / 2, between, 0.001f)
        assertTrue(quietLineWidthDp(10.0) < quietLineWidthDp(20.0))
    }

    @Test
    fun badScaleFallsBackToTheMiddleWidth() {
        assertEquals(MapStyle.quietLineMid, quietLineWidthDp(Double.NaN), 0f)
        assertEquals(MapStyle.quietLineMid, quietLineWidthDp(0.0), 0f)
    }
}
