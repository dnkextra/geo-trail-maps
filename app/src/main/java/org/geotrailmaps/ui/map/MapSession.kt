package org.geotrailmaps.ui.map

import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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
import org.geotrailmaps.render.PdfPageRenderer
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
    private val renderer = PdfPageRenderer(library.pdfFile(entry.id))
    val viewport = MapViewport(renderer.width.toFloat(), renderer.height.toFloat())

    var base by mutableStateOf<RenderedRegion?>(null)
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

    val layers = mutableStateListOf<FeatureLayer>()
    val features = mutableStateListOf<Feature>()
    init {
        val saved = library.loadFeatures(entry.id)
        layers.addAll(saved.layers)
        features.addAll(saved.features)
    }

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
        val geo = entry.geo ?: return
        paperColors = withContext(Dispatchers.Default) {
            val (inner, outer) = samplePixels(bitmap) { x, y -> geo.contains(PagePoint(x / scale.toDouble(), y / scale.toDouble())) }
            val paper = dominantColor(inner)
            paper to if (outer.isEmpty()) paper else dominantColor(outer)
        }
        requestTiles()
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
        return removePaper(region.bitmap) { x, y ->
            if (paper == margin) paper
            else if (geo.contains(PagePoint(region.left + (x + 0.5) / region.scale, region.top + (y + 0.5) / region.scale))) paper
            else margin
        }
    }

    /** Adds or replaces [feature]; the result completes with true once it's written to disk. */
    fun upsert(feature: Feature, notify: Boolean = true): Deferred<Boolean> {
        val i = features.indexOfFirst { it.id == feature.id }
        if (i >= 0) features[i] = feature else features.add(feature)
        return save(notify)
    }

    fun delete(id: String) {
        features.removeAll { it.id == id }
        geometry?.forget(listOf(id))
        save()
    }

    /** Adds an imported layer and its features. */
    fun addLayer(imported: MapFeatures) {
        layers.addAll(imported.layers)
        features.addAll(imported.features)
        save()
    }

    fun renameLayer(id: String, name: String) {
        val i = layers.indexOfFirst { it.id == id }
        if (i >= 0) layers[i] = layers[i].copy(name = name)
        save()
    }

    fun setLayerVisible(id: String, visible: Boolean) {
        val i = layers.indexOfFirst { it.id == id }
        if (i >= 0 && layers[i].visible != visible) {
            layers[i] = layers[i].copy(visible = visible)
            save()
        }
    }

    fun setFeatureVisible(id: String, visible: Boolean) {
        val i = features.indexOfFirst { it.id == id }
        if (i >= 0 && features[i].visible != visible) {
            features[i] = features[i].copy(visible = visible)
            save()
        }
    }

    /** Uses the layer's lines (only those in [folders], if given) for trail distance, or stops. */
    fun setLayerTrails(id: String, trails: Boolean, folders: Set<String>?) {
        val i = layers.indexOfFirst { it.id == id }
        if (i >= 0) {
            layers[i] = layers[i].copy(trails = trails, trailFolders = folders.takeIf { trails })
            save()
        }
    }

    /** Uses line [id] for trail distance by itself, or stops. */
    fun setFeatureTrail(id: String, trail: Boolean) {
        val i = features.indexOfFirst { it.id == id }
        if (i >= 0 && features[i].trail != trail) {
            features[i] = features[i].copy(trail = trail)
            save()
        }
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
    private fun trailLayer(kind: LayerKind, folders: Set<String>): FeatureLayer {
        val i = layers.indexOfFirst { it.kind == kind }
        if (i >= 0) {
            val l = layers[i]
            return l.copy(trails = true, trailFolders = l.trailFolders?.plus(folders)).also { if (it != l) layers[i] = it }
        }
        val layer = when (kind) {
            LayerKind.DRAWN_TRAILS -> FeatureLayer(name = "My trails", color = MapStyle.trailLine.toArgb(), kind = kind, visible = false, trails = true)
            else -> FeatureLayer(name = "From the map", color = MapStyle.trailLine.toArgb(), kind = kind, visible = false, trails = true)
        }
        layers.add(0, layer)
        return layer
    }

    val drawnTrailCount: Int get() = layers.find { it.kind == LayerKind.DRAWN_TRAILS }?.let { l -> features.count { it.layerId == l.id } } ?: 0

    /** Saves a trail drawn in the app; returns it. */
    fun addTrail(points: List<LatLon>): Feature {
        val layer = trailLayer(LayerKind.DRAWN_TRAILS, setOf("")) // not in a folder
        val f = Feature(type = FeatureType.LINE, name = "Trail ${drawnTrailCount + 1}", points = points, color = layer.color, layerId = layer.id)
        features.add(f)
        save()
        return f
    }

    /** Adds lines picked from the PDF as trails, each in a folder naming how it's drawn. */
    fun addMapLines(picked: List<MapLines.Line>) {
        val geo = entry.geo ?: return
        if (picked.isEmpty()) return
        val layer = trailLayer(LayerKind.MAP_LINES, picked.map { MapLines.describe(it.style) }.toSet())
        var n = features.count { it.layerId == layer.id }
        features.addAll(
            picked.map { line ->
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
        save()
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
    val shownFeatures: List<Feature>
        get() {
            val hidden = layers.filter { !it.visible }.map { it.id }.toSet()
            return if (hidden.isEmpty() && features.all { it.visible }) features
            else features.filter { it.visible && it.layerId !in hidden }
        }

    /** The layer recorded tracks go into, created the first time a track is saved. */
    fun tracksLayer(color: Int): FeatureLayer =
        layers.firstOrNull { it.kind == LayerKind.TRACKS }
            ?: FeatureLayer(name = "Tracks", color = color, kind = LayerKind.TRACKS).also {
                layers.add(0, it)
                save()
            }

    /** Removes a layer together with everything imported in it. */
    fun deleteLayer(id: String) {
        layers.removeAll { it.id == id }
        geometry?.forget(features.filter { it.layerId == id }.map { it.id })
        features.removeAll { it.layerId == id }
        save()
    }

    private fun save(notify: Boolean = true) = library.saveFeatures(entry.id, MapFeatures(layers.toList(), features.toList()), notify)

    override fun close() {
        renderer.close()
        tiles.clear()
    }
}
