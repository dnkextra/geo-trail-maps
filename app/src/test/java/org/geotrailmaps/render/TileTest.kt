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

    @Test
    fun customSource() {
        val source = CustomBasemap("custom-1", "Topo", "https://user:key@tiles.example.com:8443/topo/{z}/{y}/{x}?key=a.b/c", "", 17).toSource()
        val t = Tile(15, 8736, 15439)
        assertEquals("https://user:key@tiles.example.com:8443/topo/15/15439/8736?key=a.b/c", source.url(t))
        assertEquals("tiles.example.com", source.host)
        assertEquals(17, source.maxZoom)
        // The address's own extension and query never reach the file name.
        val file = source.cacheFile(java.io.File("/cache"), t).path
        assert(file.startsWith("/cache/tiles-custom-custom-1-") && file.endsWith("/15/8736/15439.tile")) { file }
        // A changed address doesn't reuse the old one's cached tiles.
        val moved = CustomBasemap("custom-1", "Topo", "https://other.example.com/{z}/{x}/{y}.png", "", 17).toSource()
        assert(moved.cacheDir != source.cacheDir)
        assertEquals("© Example", CustomBasemap("c", "n", "https://e.com/{z}/{x}/{y}", "© Example", 19).toSource().credit)
    }

    @Test
    fun tileAddressValidation() {
        assertEquals(null, BasemapSource.templateError("https://tiles.example.com/{z}/{x}/{y}.png"))
        assertEquals(null, BasemapSource.templateError("https://tiles.example.com/wmts/{z}/{y}/{x}.jpg?key=abc"))
        for (source in BasemapSource.builtIn) assertEquals(null, BasemapSource.templateError(source.urlTemplate))
        assertEquals("Must start with https://", BasemapSource.templateError("http://tiles.example.com/{z}/{x}/{y}.png"))
        assertEquals("Must include {z}, {x} and {y}", BasemapSource.templateError("https://tiles.example.com/{z}/{x}.png"))
        assertEquals("Only {z}, {x} and {y} can go in braces", BasemapSource.templateError("https://{s}.example.com/{z}/{x}/{y}.png"))
        assertEquals("Not a valid address", BasemapSource.templateError("https:///{z}/{x}/{y}.png"))
        assertEquals("Not a valid address", BasemapSource.templateError("https://tiles.example.com/a b/{z}/{x}/{y}.png"))
    }
}
