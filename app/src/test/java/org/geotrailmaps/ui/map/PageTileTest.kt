package org.geotrailmaps.ui.map

import org.junit.Assert.assertEquals
import org.junit.Test

class PageTileTest {

    @Test
    fun tilesAreShownAtNearTheirOwnSize() {
        var s = 0.3f
        while (s < 100f) {
            val ratio = Math.scalb(1f, PageTile.levelFor(s)) / s
            assert(ratio in 0.8f..1.63f) { "scale $s: tiles drawn at ${1 / ratio}x" }
            s *= 1.07f
        }
    }

    @Test
    fun edgeTilesStopAtThePage() {
        val tiles = PageTiles(1548f, 1541.52f)
        val t = PageTile(1, 6, 0) // 2 px/pt: starts at 1536 pt, 12 pt before the right edge
        assertEquals(1536f, t.left)
        assertEquals(Pair(24, 512), tiles.pixelSize(t))
    }
}
