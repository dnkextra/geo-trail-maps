package org.geotrailmaps.ui.map

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.channels.Channel
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.LayerKind
import org.geotrailmaps.data.MapFeatures
import org.geotrailmaps.data.MapEntry
import org.geotrailmaps.data.MapLibrary
import org.geotrailmaps.data.NO_BASEMAP
import org.geotrailmaps.data.Trails
import org.geotrailmaps.data.coverage
import org.geotrailmaps.data.overlaps
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.render.BasemapSource
import org.geotrailmaps.ui.theme.MapStyle
import androidx.compose.ui.graphics.toArgb
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.PagePoint
import org.geotrailmaps.geo.PdfLayerLines
import org.geotrailmaps.geo.TrailNetwork
import org.geotrailmaps.pdf.PdfFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.geotrailmaps.render.dominantColor
import org.geotrailmaps.render.removePaper
import org.geotrailmaps.render.samplePixels
import java.io.Closeable
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

/** Everything that lives while one map is open: renderer, view state and features. */
class MapSession(val entry: MapEntry, val library: MapLibrary) : Closeable {
    private val renderer = library.pdfRenderer(entry.id)
    val viewport = MapViewport(renderer.width.toFloat(), renderer.height.toFloat())

    var base by mutableStateOf<RenderedRegion?>(null)
        private set
    /**
     * A small stored image of the page, shown until [base] is rendered (which can take a
     * moment for a large map); null once it is, or when the map has none yet.
     */
    var preview by mutableStateOf(
        library.loadPreview(entry.id)?.let { RenderedRegion(it, 0f, 0f, it.width / renderer.width.toFloat()) },
    )
        private set
    val tiles = PageTiles(renderer.width.toFloat(), renderer.height.toFloat())
    /** Changes whenever a tile or paper-free image arrives, so the map redraws. */
    var imagesVersion by mutableIntStateOf(0)
        private set

    /** See [MapEntry.seeThrough]; changes live while the slider moves, [saveSeeThrough] when it's let go. */
    var seeThrough by mutableFloatStateOf(entry.seeThrough)
    fun saveSeeThrough() {
        if (!library.setSeeThrough(entry.id, seeThrough)) seeThrough = library.get(entry.id)?.seeThrough ?: entry.seeThrough
    }

    /** This map's background, see [MapEntry.basemap]: null until one is chosen for it. */
    var basemapChoice by mutableStateOf(entry.basemap)
        private set
    /** False (and nothing changes) if it couldn't be saved. */
    fun setBasemap(source: BasemapSource?): Boolean {
        val choice = source?.name ?: NO_BASEMAP
        return library.setBasemap(entry.id, choice).also { if (it) basemapChoice = choice }
    }
    val basemap: BasemapSource? get() = BasemapSource.entries.firstOrNull { it.name == basemapChoice }

    /**
     * The paper colour inside the map frame, and outside it (margins and legend, often a
     * different colour), each removed by [seeThrough]. Found once the base image renders;
     * null for maps without georeferencing, which can't be seen through.
     */
    @Volatile private var paperColors: Pair<Int, Int>? = null

    /** This map's own layers and features. */
    private var local by mutableStateOf(library.loadFeatures(entry.id))
    /** All the global layers; kept current by [followGlobal] while the map is open. */
    private var global by mutableStateOf(library.global.value)
    val coverage = entry.coverage()

    suspend fun followGlobal(): Nothing = library.global.collect { global = it }

    /**
     * Keeps [geometry] to the features on this map, however they leave it, plus the shapes in
     * [keep] (not features). Runs while the map is open.
     */
    suspend fun pruneGeometry(keep: Set<String>) {
        val g = geometry ?: return
        snapshotFlow { features }.collect { list -> g.retain(list.mapTo(HashSet(keep)) { it.id }) }
    }

    /** The global layers and features that overlap this map. */
    private val globalHere by derivedStateOf {
        val shown = global.features.filter { overlaps(it, coverage) }
        val ids = shown.mapNotNull { it.layerId }.toSet()
        MapFeatures(global.layers.filter { it.id in ids }, shown)
    }

    /** What's drawn on this map: the global layers overlapping it first, then its own. */
    val layers: List<FeatureLayer> by derivedStateOf {
        val g = globalHere.layers.map { it.copy(global = true, visible = it.id !in local.hiddenGlobal) }
        // A layer half-way through moving between the two (the app was killed) is shown once.
        val ids = global.layers.map { it.id }.toSet()
        g + local.layers.filter { it.id !in ids }
    }
    val features: List<Feature> by derivedStateOf {
        val ids = global.features.map { it.id }.toSet()
        globalHere.features + local.features.filter { it.id !in ids }
    }

    // The library's copy, not [global]: that catches up a frame later (e.g. a track just saved).
    private fun isGlobal(layerId: String?) = layerId != null && library.global.value.layers.any { it.id == layerId }
    private fun isGlobalFeature(id: String) = library.global.value.features.any { it.id == id }

    private fun changeLocal(notify: Boolean = true, change: (MapFeatures) -> MapFeatures): Deferred<Boolean> {
        local = change(local)
        return library.saveFeatures(entry.id, local, notify)
    }

    private fun changeGlobal(notify: Boolean = true, change: (MapFeatures) -> MapFeatures): Deferred<Boolean> =
        library.updateGlobal(notify, change).also { global = library.global.value }

    /** Changes the layer or feature list holding [global] items, or this map's. */
    private fun change(global: Boolean, notify: Boolean = true, change: (MapFeatures) -> MapFeatures) =
        if (global) changeGlobal(notify, change) else changeLocal(notify, change)

    /** Page positions of features, for fast drawing; null for maps without georeferencing. */
    val geometry = entry.geo?.let { g ->
        // Projected here, while the map opens in the background, rather than on the first frame.
        FeatureGeometry(g).also { cache -> features.forEach { cache.shape(it) } }
    }

    /** Whole page at a resolution that fits comfortably in memory (about 24 MB). */
    suspend fun renderBase() {
        val w = renderer.width.toFloat()
        val h = renderer.height.toFloat()
        val scale = min(3000f / max(w, h), sqrt(6_000_000f / (w * h)))
        val bitmap = renderer.render(0f, 0f, scale, (w * scale).roundToInt(), (h * scale).roundToInt()) ?: return
        base = RenderedRegion(bitmap, 0f, 0f, scale)
        preview = null
        val geo = entry.geo
        if (geo != null) {
            paperColors = withContext(Dispatchers.Default) {
                val (inner, outer) = samplePixels(bitmap) { x, y -> geo.contains(PagePoint(x / scale.toDouble(), y / scale.toDouble())) }
                val paper = dominantColor(inner)
                paper to if (outer.isEmpty()) paper else dominantColor(outer)
            }
            requestTiles()
        }
        // For next time this map is opened; after the tiles are queued, so it doesn't hold them up.
        library.savePreview(entry.id, bitmap)
    }

    // Main thread only, like the tile cache.
    private var wanted: List<PageTile> = emptyList()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    /** Queues the tiles for the current view. Cheap: call whenever the view moves. */
    fun requestTiles() {
        val b = base
        val list = if (b == null) emptyList() else tiles.wanted(viewport, b.scale)
        wanted = list
        wantedSet = list.toHashSet()
        tiles.touch(list)
        wake.trySend(Unit)
    }

    private var wantedSet: Set<PageTile> = emptySet()

    /**
     * Renders the queued tiles one at a time, in queue order (on screen first, nearest the
     * centre first). While see-through is on, the base gets its paper removed first, and
     * then each tile right after it's rendered, so what's on screen is finished first.
     * Runs on the main thread while the map is open.
     */
    suspend fun renderTiles() {
        for (signal in wake) {
            while (true) {
                val colors = paperColors?.takeIf { seeThrough > 0f }
                val b = base
                if (colors != null && b != null && b.keyed == null) {
                    base = b.withKeyed(withContext(Dispatchers.Default) { paperFree(b, colors) })
                    continue
                }
                val tile = wanted.firstOrNull { t -> tiles[t].let { it == null || (colors != null && it.keyed == null) } } ?: break
                val region = tiles[tile] ?: run {
                    val (w, h) = tiles.pixelSize(tile)
                    val bitmap = renderer.render(tile.left, tile.top, tile.scale, w, h) ?: return // closed
                    RenderedRegion(bitmap, tile.left, tile.top, tile.scale)
                }
                val done = if (colors != null && region.keyed == null) {
                    region.withKeyed(withContext(Dispatchers.Default) { paperFree(region, colors) })
                } else {
                    region
                }
                tiles.put(tile, done, wantedSet)
                imagesVersion++
            }
        }
    }

    private fun paperFree(region: RenderedRegion, colors: Pair<Int, Int>): android.graphics.Bitmap {
        val geo = entry.geo!!
        val (paper, margin) = colors
        return removePaper(region.bitmap) { y, row ->
            if (paper == margin) return@removePaper row.fill(paper)
            // Paper where an odd number of frame crossings lie to the right, margin elsewhere:
            // one walk along the row rather than a point-in-polygon test per pixel.
            val crossings = geo.neatlineCrossings(region.top + (y + 0.5) / region.scale)
            var passed = 0
            for (x in row.indices) {
                val px = region.left + (x + 0.5) / region.scale
                while (passed < crossings.size && crossings[passed] <= px) passed++
                row[x] = if ((crossings.size - passed) % 2 == 1) paper else margin
            }
        }
    }

    /** Adds or replaces [feature]; the result completes with true once it's written to disk. */
    fun upsert(feature: Feature, notify: Boolean = true): Deferred<Boolean> =
        change(isGlobal(feature.layerId), notify) { it.upsert(feature) }

    fun delete(id: String) {
        change(isGlobalFeature(id)) { it.removeFeature(id) }
    }

    /** Adds an imported layer and its features, to this map. */
    fun addLayer(imported: MapFeatures) {
        changeLocal { d -> d.copy(layers = d.layers + imported.layers, features = d.features + imported.features) }
    }

    fun renameLayer(id: String, name: String) {
        change(isGlobal(id)) { d -> d.withLayer(id) { it.copy(name = name) } }
    }

    /** A global layer is shown or hidden on this map only. */
    fun setLayerVisible(id: String, visible: Boolean) {
        if (layers.find { it.id == id }?.visible == visible) return
        if (isGlobal(id)) changeLocal { d -> d.copy(hiddenGlobal = if (visible) d.hiddenGlobal - id else d.hiddenGlobal + id) }
        else changeLocal { d -> d.withLayer(id) { it.copy(visible = visible) } }
    }

    fun setFeatureVisible(id: String, visible: Boolean) {
        if (features.find { it.id == id }?.visible == visible) return
        change(isGlobalFeature(id)) { d -> d.withFeature(id) { it.copy(visible = visible) } }
    }

    /** Uses the layer's lines (only those in [folders], if given) for trail distance, or stops. */
    fun setLayerTrails(id: String, trails: Boolean, folders: Set<String>?) {
        change(isGlobal(id)) { d -> d.withLayer(id) { it.copy(trails = trails, trailFolders = folders.takeIf { trails }) } }
    }

    /** Uses line [id] for trail distance by itself, or stops. */
    fun setFeatureTrail(id: String, trail: Boolean) {
        if (features.find { it.id == id }?.trail == trail) return
        change(isGlobalFeature(id)) { d -> d.withFeature(id) { it.copy(trail = trail) } }
    }

    /**
     * Moves a layer and everything in it to the global layers, so it shows on every map it
     * overlaps, or back to this map only (taking its features beyond this map with it).
     * It's removed from where it was only once it's written where it's going, so a failed write
     * (e.g. storage full; the user is told) leaves it where it was, and dying in between leaves
     * it in both (shown once, and tidied by the next move), never lost. False if it didn't move.
     */
    suspend fun setLayerGlobal(id: String, toGlobal: Boolean): Boolean {
        // One move of a layer at a time: a second, the other way, would delete both copies.
        if (!movingLayers.add(id)) return false
        try {
            return moveLayer(id, toGlobal)
        } finally {
            movingLayers.remove(id)
        }
    }

    private val movingLayers = mutableSetOf<String>()

    private suspend fun moveLayer(id: String, toGlobal: Boolean): Boolean {
        if (toGlobal == isGlobal(id)) return true
        val from = if (toGlobal) local else library.global.value
        val layer = from.layers.find { it.id == id } ?: return false
        val moving = from.features.filter { it.layerId == id }
        val ids = moving.map { it.id }.toSet()
        // Replacing any copy left in the destination by an earlier, interrupted move.
        fun MapFeatures.add(l: FeatureLayer) = copy(layers = layers.filter { it.id != id } + l, features = features.filter { it.id !in ids } + moving)
        fun MapFeatures.remove() = copy(layers = layers.filter { it.id != id }, features = features.filter { it.layerId != id && it.id !in ids })
        if (toGlobal) {
            if (!changeGlobal { it.add(layer.copy(visible = true)) }.await()) {
                changeGlobal(notify = false) { it.remove() }
                return false
            }
            changeLocal { d -> d.remove().copy(hiddenGlobal = if (layer.visible) d.hiddenGlobal else d.hiddenGlobal + id) }
        } else {
            val hidden = id in local.hiddenGlobal
            if (!changeLocal { d -> d.add(layer.copy(visible = !hidden)).copy(hiddenGlobal = d.hiddenGlobal - id) }.await()) {
                changeLocal(notify = false) { d -> d.remove().copy(hiddenGlobal = if (hidden) d.hiddenGlobal + id else d.hiddenGlobal) }
                return false
            }
            changeGlobal { it.remove() }
        }
        return true
    }

    /** The lines used for trail distance; a new value only when they change. */
    val trailFeatures by derivedStateOf { Trails.features(layers, features) }
    val trailLines by derivedStateOf { trailFeatures.flatMap { it.segments } }

    /**
     * The layer of trails drawn in the app, or of lines picked from the map: created hidden the
     * first time (they'd clutter the map, and the PDF already shows picked lines), and used
     * for trail distance whenever trails are added to it, including from [folders] if only
     * some of its folders were chosen before.
     */
    private fun MapFeatures.withTrailLayer(kind: LayerKind, folders: Set<String>): Pair<MapFeatures, FeatureLayer> {
        layers.find { it.kind == kind }?.let { l ->
            val updated = l.copy(trails = true, trailFolders = l.trailFolders?.plus(folders))
            return withLayer(l.id) { updated } to updated
        }
        val name = if (kind == LayerKind.DRAWN_TRAILS) "My trails" else "From the map"
        val layer = FeatureLayer(name = name, color = MapStyle.trailLine.toArgb(), kind = kind, visible = false, trails = true)
        return copy(layers = listOf(layer) + layers) to layer
    }

    /** Saves a trail drawn in the app (kept with this map, like all trail layers). */
    fun addTrail(points: List<LatLon>) {
        changeLocal { d ->
            val (data, layer) = d.withTrailLayer(LayerKind.DRAWN_TRAILS, setOf("")) // not in a folder
            val n = data.features.count { it.layerId == layer.id } + 1
            data.copy(features = data.features + Feature(type = FeatureType.LINE, name = "Trail $n", points = points, color = layer.color, layerId = layer.id))
        }
    }

    /** Adds lines picked from the PDF as trails, each in a folder naming how it's drawn. */
    fun addMapLines(picked: List<MapLines.Line>) {
        val geo = entry.geo ?: return
        if (picked.isEmpty()) return
        changeLocal { d ->
            val (data, layer) = d.withTrailLayer(LayerKind.MAP_LINES, picked.map { MapLines.describe(it.style) }.toSet())
            var n = data.features.count { it.layerId == layer.id }
            data.copy(
                features = data.features + picked.map { line ->
                    Feature(
                        type = FeatureType.LINE,
                        name = "Map line ${++n}",
                        points = line.points.map(geo::toLatLon),
                        color = line.style.rgb or 0xFF000000.toInt(),
                        layerId = layer.id,
                        folder = MapLines.describe(line.style),
                    )
                },
            )
        }
    }

    /** The lines already picked from the map: page positions of their ends, and their sizes. */
    val pickedLineEnds by derivedStateOf {
        val geo = entry.geo
        val layer = layers.find { it.kind == LayerKind.MAP_LINES }
        if (geo == null || layer == null) emptyList()
        else features.filter { it.layerId == layer.id && it.points.isNotEmpty() }
            .map { f -> Triple(geo.toPage(f.points.first()), geo.toPage(f.points.last()), f.points.size) }
    }

    private var mapLines: MapLines? = null

    /** The lines in the PDF that can be picked as trails, read the first time they're needed. */
    suspend fun mapLines(): MapLines {
        mapLines?.let { return it }
        val geo = entry.geo ?: return MapLines(emptyList()) { false }
        return withContext(Dispatchers.IO) {
            val all = runCatching { PdfLayerLines.readAll(PdfFile.open(library.pdfFile(entry.id))) }.getOrDefault(emptyList())
            MapLines(all, geo::contains)
        }.also { mapLines = it }
    }

    // Kept by the library, so it outlives this session.
    private val trailCache = library.trailCache(entry.id)

    /**
     * The walkable network: lines in the PDF's own trail, path or road layers, plus
     * [trailLines] and any [extra] (trails drawn but not saved yet). Built in the background,
     * and again only when [trailLines] change (always, with extras).
     */
    suspend fun trailNetwork(extra: List<List<LatLon>> = emptyList()): TrailNetwork {
        val imported = trailLines
        if (extra.isNotEmpty()) {
            val pdf = trailCache.lock.withLock { pdfTrails() }
            return withContext(Dispatchers.Default) { TrailNetwork(pdf + imported + extra) }
        }
        return trailCache.lock.withLock {
            trailCache.network?.takeIf { it.first == imported }?.second
                ?: withContext(Dispatchers.Default) { TrailNetwork(pdfTrails() + imported) }.also { trailCache.network = imported to it }
        }
    }

    /** Only with [trailCache]'s lock held. */
    private suspend fun pdfTrails(): List<List<LatLon>> =
        trailCache.pdfTrails ?: withContext(Dispatchers.IO) { readPdfTrails() }.also { trailCache.pdfTrails = it }

    private fun readPdfTrails(): List<List<LatLon>> {
        val geo = entry.geo ?: return emptyList()
        return runCatching { PdfLayerLines.read(PdfFile.open(library.pdfFile(entry.id)), Trails::looksWalkable) }.getOrDefault(emptyList())
            .filter { line -> line.any(geo::contains) } // not a legend's sample line
            .map { line -> line.map(geo::toLatLon) }
    }

    /** Makes sure [f] can be seen: unhides it and its layer. */
    fun reveal(f: Feature) {
        f.layerId?.let { setLayerVisible(it, true) }
        setFeatureVisible(f.id, true)
    }

    /** Visible features in visible layers: what's drawn and tappable. */
    val shownFeatures: List<Feature> by derivedStateOf {
        val hidden = layers.filter { !it.visible }.map { it.id }.toSet()
        if (hidden.isEmpty() && features.all { it.visible }) features
        else features.filter { it.visible && it.layerId !in hidden }
    }

    /** Removes a layer together with everything imported in it; a global one from every map. */
    fun deleteLayer(id: String) {
        change(isGlobal(id)) { it.removeLayer(id) }
    }

    override fun close() {
        renderer.close()
        tiles.clear()
    }
}
