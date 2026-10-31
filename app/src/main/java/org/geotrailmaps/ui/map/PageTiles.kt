package org.geotrailmaps.ui.map

import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min

/** A square of the page, [PageTile.SIZE] pixels across, rendered at 2^[level] pixels per point. */
data class PageTile(val level: Int, val x: Int, val y: Int) {
    val scale: Float get() = Math.scalb(1f, level)
    val left: Float get() = x * SIZE / scale
    val top: Float get() = y * SIZE / scale

    companion object {
        const val SIZE = 512

        /**
         * The level for a view at [viewScale] pixels per point: its tiles are shown at
         * 0.8–1.6 times their own size, sharp enough without rendering far more than the
         * screen holds.
         */
        fun levelFor(viewScale: Float): Int = ceil(log2(viewScale) - 0.3f).toInt()
    }
}

/**
 * Sharp images of the page once zoomed in past the low-resolution base image, as tiles
 * kept in memory: panning back over a part already seen shows it straight away, and new
 * parts arrive a tile at a time, nearest the centre first.
 */
class PageTiles(private val pageWidth: Float, private val pageHeight: Float) {
    // Oldest first. Only the tiles in demand are moved to the end (see [touch]); drawing
    // older ones as stand-ins mustn't keep them from being evicted.
    private val cache = LinkedHashMap<PageTile, RenderedRegion>()
    private var bytes = 0L

    /** The tile's images, if rendered. Doesn't count as a use. Main thread only, like [put]. */
    operator fun get(tile: PageTile): RenderedRegion? = cache[tile]

    /**
     * Stores [region] as the newest entry, then evicts the oldest entries until the cache
     * fits, sparing the tiles in [keep] (the current request) while there are others.
     */
    fun put(tile: PageTile, region: RenderedRegion, keep: Set<PageTile>) {
        cache.remove(tile)?.let { bytes -= it.byteCount }
        cache[tile] = region
        bytes += region.byteCount
        val it = cache.entries.iterator()
        while (bytes > MAX_BYTES && it.hasNext()) {
            val e = it.next()
            if (e.key !in keep) {
                bytes -= e.value.byteCount
                it.remove()
            }
        }
    }

    /** Marks [tiles] as in demand, so other tiles are evicted before them. */
    fun touch(tiles: List<PageTile>) {
        for (t in tiles) cache.remove(t)?.let { cache[t] = it }
    }

    fun clear() {
        cache.clear()
        bytes = 0
    }

    /** Pixel size of [tile], smaller than [PageTile.SIZE] along the page's right and bottom edges. */
    fun pixelSize(tile: PageTile): Pair<Int, Int> = Pair(
        min(PageTile.SIZE, ceil((pageWidth - tile.left) * tile.scale).toInt()),
        min(PageTile.SIZE, ceil((pageHeight - tile.top) * tile.scale).toInt()),
    )

    /**
     * The level to fetch and show, or null when the base image (at [baseScale]) is as sharp.
     * Coarser than the view's own level when the screen would need more than [MAX_REQUEST]
     * tiles (a large or rotated screen).
     */
    fun level(viewport: MapViewport, baseScale: Float): Int? {
        fun useful(level: Int) = Math.scalb(1f, level) > baseScale * 1.1f
        var level = PageTile.levelFor(viewport.scale)
        while (useful(level) && covering(viewport, level).size > MAX_REQUEST) level--
        return level.takeIf { useful(it) }
    }

    /**
     * Tiles at [level] covering the screen, plus a ring of [margin] around it so short pans
     * find them ready, nearest the centre first.
     */
    fun covering(viewport: MapViewport, level: Int, margin: Int = 0): List<PageTile> {
        val view = viewport.visibleViewBounds() // larger than the screen while the map is rotated
        val s = Math.scalb(1f, level)
        val n = PageTile.SIZE / s // tile size in points
        fun toPageX(v: Float) = (v - viewport.offset.x) / viewport.scale
        fun toPageY(v: Float) = (v - viewport.offset.y) / viewport.scale
        val x0 = max(0, floor(toPageX(view.left) / n).toInt() - margin)
        val y0 = max(0, floor(toPageY(view.top) / n).toInt() - margin)
        val x1 = min(ceil(pageWidth / n).toInt() - 1, floor(toPageX(view.right) / n).toInt() + margin)
        val y1 = min(ceil(pageHeight / n).toInt() - 1, floor(toPageY(view.bottom) / n).toInt() + margin)
        if (x1 < x0 || y1 < y0) return emptyList()
        val cx = toPageX(view.center.x) / n
        val cy = toPageY(view.center.y) / n
        return (x0..x1).flatMap { x -> (y0..y1).map { y -> PageTile(level, x, y) } }
            .sortedBy { hypot(it.x + 0.5f - cx, it.y + 0.5f - cy) }
    }

    /**
     * Rendered tiles to draw over the base image, in drawing order: coarser and finer levels
     * left from zooming first, so the current level's tiles end up on top.
     */
    fun drawable(viewport: MapViewport, baseScale: Float): List<RenderedRegion> {
        val level = level(viewport, baseScale) ?: return emptyList()
        return listOf(level - 1, level + 1, level).flatMap { l ->
            if (Math.scalb(1f, l) <= baseScale * 1.1f) emptyList()
            else covering(viewport, l).mapNotNull { cache[it] }
        }
    }

    /**
     * What to fetch for the current view: the tiles on screen, nearest the centre first,
     * then the ring around them, at most [MAX_REQUEST].
     */
    fun wanted(viewport: MapViewport, baseScale: Float): List<PageTile> {
        val level = level(viewport, baseScale) ?: return emptyList()
        val visible = covering(viewport, level)
        val shown = visible.toHashSet()
        return (visible + covering(viewport, level, margin = 1).filter { it !in shown }).take(MAX_REQUEST)
    }

    private companion object {
        const val MAX_BYTES = 128L * 1024 * 1024
        /** Tiles fetched at once: with their paper-free copies they fit in [MAX_BYTES]. */
        const val MAX_REQUEST = 48
    }
}

private val RenderedRegion.byteCount: Long
    get() = bitmap.allocationByteCount.toLong() + (keyed?.allocationByteCount ?: 0)
