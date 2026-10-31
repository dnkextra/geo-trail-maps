package org.geotrailmaps.render

import org.junit.Assert.assertEquals
import org.junit.Test
import org.geotrailmaps.geo.LatLon

class TileTest {

    @Test
    fun tileAddressAndCornersAgree() {
        // Known tile: Yatama area, Costa Rica, zoom 15.
        val p = LatLon(10.3241, -84.0215)
        val (x, y) = Tile.at(p, 15)
        val tile = Tile(15, x.toInt(), y.toInt())
        assertEquals(8736, tile.x)
        assertEquals(15439, tile.y)
        val nw = tile.corner(0, 0)
        val se = tile.corner(1, 1)
        assert(p.lat in se.lat..nw.lat && p.lon in nw.lon..se.lon)
    }

    @Test
    fun zoomMatchesScale() {
        // At the equator a z=0 tile spans the whole 40,075 km circumference.
        assertEquals(0, Tile.zoomFor(40_075_016.686 / 256, 0.0, 256.0))
        assertEquals(10, Tile.zoomFor(40_075_016.686 / 256 / 1024, 0.0, 256.0))
        assertEquals(Tile.MAX_ZOOM, Tile.zoomFor(0.001, 0.0, 256.0))
    }

    @Test
    fun sourceUrlsAndCacheFiles() {
        val t = Tile(15, 8736, 15439)
        assertEquals("https://tile.openstreetmap.org/15/8736/15439.png", BasemapSource.OSM.url(t))
        // EOX's WMTS path puts the row before the column.
        assertEquals(
            "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/15/15439/8736.jpg",
            BasemapSource.SATELLITE.url(t),
        )
        val root = java.io.File("/cache")
        // OSM keeps the pre-0.5 cache location.
        assertEquals("/cache/tiles/15/8736/15439.png", BasemapSource.OSM.cacheFile(root, t).path)
        assertEquals("/cache/tiles-s2cloudless-2025/15/8736/15439.jpg", BasemapSource.SATELLITE.cacheFile(root, t).path)
    }
}
