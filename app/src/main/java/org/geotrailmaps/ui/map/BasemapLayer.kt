package org.geotrailmaps.ui.map

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Paint
import androidx.compose.ui.geometry.Offset
import androidx.core.graphics.withClip
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.drawIntoCanvas
import androidx.compose.ui.graphics.nativeCanvas
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.render.BasemapSource
import org.geotrailmaps.render.BasemapTiles
import org.geotrailmaps.render.Tile
import kotlin.math.floor
import kotlin.math.hypot

private const val MAX_TILES = 48

/**
 * Tiles covering the screen at a zoom that matches the map scale (at most [maxZoom]),
 * nearest the centre first.
 */
fun visibleTiles(proj: MapProjection, viewport: MapViewport, density: Float, maxZoom: Int = Tile.MAX_ZOOM): List<Tile> {
    if (viewport.size.width <= 0 || viewport.size.height <= 0) return emptyList()
    val bounds = viewport.visibleViewBounds()
    val w = bounds.width
    val h = bounds.height
    val mid = bounds.center
    val center = proj.toLatLon(mid)
    val metersPerPx = Geodesy.distance(center, proj.toLatLon(mid + Offset(100f, 0f))) / 100
    if (!metersPerPx.isFinite() || metersPerPx <= 0) return emptyList()
    val samples = listOf(0f to 0f, w / 2 to 0f, w to 0f, w to h / 2, w to h, w / 2 to h, 0f to h, 0f to h / 2, w / 2 to h / 2)
        .map { (x, y) -> proj.toLatLon(Offset(bounds.left + x, bounds.top + y)) }
        .filter { it.lat.isFinite() && it.lon.isFinite() }

    // Tiles drawn at roughly 1.6x their pixel size on a typical phone: sharp enough, few requests.
    var z = Tile.zoomFor(metersPerPx, center.lat, 256.0 * density / 1.6).coerceAtMost(maxZoom)
    while (true) {
        val xy = samples.map { Tile.at(it, z) }
        val n = 1 shl z
        val x0 = floor(xy.minOf { it.first }).toInt().coerceIn(0, n - 1)
        val x1 = floor(xy.maxOf { it.first }).toInt().coerceIn(0, n - 1)
        val y0 = floor(xy.minOf { it.second }).toInt().coerceIn(0, n - 1)
        val y1 = floor(xy.maxOf { it.second }).toInt().coerceIn(0, n - 1)
        if ((x1 - x0 + 1) * (y1 - y0 + 1) > MAX_TILES && z > 0) {
            z--
            continue
        }
        val (cx, cy) = Tile.at(center, z)
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> Tile(z, x, y) } }
            .sortedBy { hypot(it.x + 0.5 - cx, it.y + 0.5 - cy) }
    }
}

/**
 * Draws the background map. Each tile is warped onto its four projected corners, so it
 * lines up with the PDF whatever the PDF's projection. Missing tiles are filled from a
 * cached lower zoom level until they arrive, or for good where the source has none.
 */
fun DrawScope.drawBasemap(tiles: BasemapTiles, source: BasemapSource, visible: List<Tile>, proj: MapProjection, paint: Paint) {
    @Suppress("UNUSED_EXPRESSION")
    tiles.version // redraw when tiles arrive
    val matrix = Matrix()
    val src = FloatArray(8)
    val dst = FloatArray(8)
    val clip = android.graphics.Path()

    fun draw(tile: Tile, bitmap: Bitmap, ancestorLevels: Int) {
        // For a stand-in, the whole ancestor bitmap is warped, so clip it to this tile's area.
        // Sized from the bitmap: some sources serve tiles larger than 256 px.
        val w = bitmap.width.toFloat() / (1 shl ancestorLevels)
        val h = bitmap.height.toFloat() / (1 shl ancestorLevels)
        val sx = (tile.x - ((tile.x shr ancestorLevels) shl ancestorLevels)) * w
        val sy = (tile.y - ((tile.y shr ancestorLevels) shl ancestorLevels)) * h
        floatArrayOf(sx, sy, sx + w, sy, sx + w, sy + h, sx, sy + h).copyInto(src)
        listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1).forEachIndexed { i, (dx, dy) ->
            val p = proj.toScreen(tile.corner(dx, dy))
            dst[2 * i] = p.x
            dst[2 * i + 1] = p.y
        }
        if (!matrix.setPolyToPoly(src, 0, dst, 0, 4)) return
        drawIntoCanvas {
            val canvas = it.nativeCanvas
            if (ancestorLevels == 0) {
                canvas.drawBitmap(bitmap, matrix, paint)
            } else {
                clip.rewind()
                clip.moveTo(dst[0], dst[1])
                for (i in 1..3) clip.lineTo(dst[2 * i], dst[2 * i + 1])
                clip.close()
                canvas.withClip(clip) { drawBitmap(bitmap, matrix, paint) }
            }
        }
    }

    // Lower-zoom stand-ins first, so exact tiles drawn afterwards cover their overspill. Any
    // zoom out will do: where the source has no detail, an enlarged tile is all there is.
    for (tile in visible) {
        if (tiles.cached(source, tile) != null) continue
        for (d in 1..tile.z) {
            val ancestor = tiles.cached(source, Tile(tile.z - d, tile.x shr d, tile.y shr d)) ?: continue
            draw(tile, ancestor, d)
            break
        }
    }
    for (tile in visible) tiles.cached(source, tile)?.let { draw(tile, it, 0) }
}
