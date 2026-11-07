package org.geotrailmaps.data

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import android.provider.DocumentsContract
import android.provider.OpenableColumns
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.withContext
import org.json.JSONArray
import org.json.JSONObject
import org.geotrailmaps.geo.GeoPdfParser
import org.geotrailmaps.geo.GeoReference
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.geo.PagePoint
import org.geotrailmaps.geo.TrailNetwork
import org.geotrailmaps.render.PdfPageRenderer
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** An imported map. Page size is in PDF points as PdfRenderer reports it. */
data class MapEntry(
    val id: String,
    val name: String,
    val importedAt: Long,
    val fileSize: Long,
    val pageWidth: Int,
    val pageHeight: Int,
    val geo: GeoReference?,
    /** Folder in My Maps; null = not in a folder. */
    val folder: String? = null,
    /**
     * How much the background map shows through the PDF's paper colour, 0..1: 0 is the
     * plain PDF, 1 makes the paper fully transparent. Lines and labels stay opaque.
     */
    val seeThrough: Float = 0f,
    /**
     * The background map chosen for this map: a [org.geotrailmaps.render.BasemapSource] name, or
     * [NO_BASEMAP]; null until one is chosen (the last one chosen on any map is used).
     */
    val basemap: String? = null,
    /** Moved to the Archived section at the bottom of My Maps, out of the way. */
    val archived: Boolean = false,
)

const val NO_BASEMAP = "NONE"

class ImportException(message: String) : Exception(message)

/** Larger than any real map, and PDFs over 2 GB can't be memory-mapped for reading anyway. */
internal const val MAX_PDF_BYTES = 1L shl 30

/**
 * Copies a PDF from [input] to [out], checking it looks like a PDF before copying the rest and
 * stopping after [maxBytes] (a provider can report any size, or stream forever).
 */
internal fun copyPdf(input: InputStream, out: OutputStream, displayName: String, maxBytes: Long = MAX_PDF_BYTES) {
    val head = input.readNBytes(1024)
    if (!String(head, Charsets.ISO_8859_1).contains("%PDF")) {
        throw ImportException("\"$displayName\" isn't a PDF. Only geospatial PDF maps are supported.")
    }
    out.write(head)
    var copied = head.size.toLong()
    val buffer = ByteArray(64 * 1024)
    while (true) {
        val n = input.read(buffer)
        if (n < 0) return
        copied += n
        if (copied > maxBytes) throw ImportException("\"$displayName\" is too large to import.")
        out.write(buffer, 0, n)
    }
}

/**
 * Stores imported maps in app storage, one folder per map:
 * maps/<id>/map.pdf, meta.json, thumb.png, features.json
 */
class MapLibrary(private val context: Context) {
    private val root = File(context.filesDir, "maps").apply { mkdirs() }

    private val _maps = MutableStateFlow(loadAll())
    val maps: StateFlow<List<MapEntry>> = _maps

    fun get(id: String): MapEntry? = _maps.value.find { it.id == id }
    fun pdfFile(id: String) = File(File(root, id), "map.pdf")
    fun thumbnailFile(id: String) = File(File(root, id), "thumb.png")
    private fun metaFile(id: String) = File(File(root, id), "meta.json")
    private fun featuresFile(id: String) = File(File(root, id), "features.json")

    suspend fun import(uri: Uri): MapEntry = withContext(Dispatchers.IO) {
        val displayName = context.displayName(uri) ?: "Map"
        val lower = displayName.lowercase()
        if (lower.endsWith(".avenzamap")) {
            throw ImportException(
                "\"$displayName\" is in Avenza's proprietary format, which other apps can't read. " +
                    "Ask the sender for a geospatial PDF of the map.",
            )
        }
        val id = UUID.randomUUID().toString()
        val dir = File(root, id).apply { mkdirs() }
        try {
            val pdf = pdfFile(id)
            val input = context.contentResolver.openInputStream(uri) ?: throw ImportException("Couldn't open the file")
            input.use { inp -> pdf.outputStream().use { copyPdf(inp, it, displayName) } }
            val geo = runCatching { GeoPdfParser.parse(pdf).geo }.getOrNull()
            val (w, h) = try {
                PdfPageRenderer(pdf).use { r ->
                    thumbnailFile(id).outputStream().use { r.renderThumbnail(256).compress(Bitmap.CompressFormat.PNG, 90, it) }
                    r.width to r.height
                }
            } catch (e: Exception) {
                throw ImportException("Couldn't open \"$displayName\". It may be damaged or password protected.")
            }
            val entry = MapEntry(
                id = id,
                name = displayName.removeSuffix(".pdf").removeSuffix(".PDF"),
                importedAt = System.currentTimeMillis(),
                fileSize = pdf.length(),
                pageWidth = w,
                pageHeight = h,
                geo = geo,
            )
            writeMeta(entry)
            _maps.update { (it + entry).sortedBy { m -> m.name.lowercase() } }
            entry
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** Writes the map's PDF, exactly as it was imported, to [uri]; false if that failed. */
    suspend fun exportPdf(id: String, uri: Uri): Boolean = withContext(Dispatchers.IO) {
        val ok = try {
            context.contentResolver.openOutputStream(uri, "wt")?.use { o -> pdfFile(id).inputStream().use { it.copyTo(o) } } != null
        } catch (e: Exception) {
            // Providers fail in many ways (IO, permissions, bad state); all mean it wasn't saved.
            false
        }
        // The save dialog already made the file: don't leave it empty or cut short.
        if (!ok) runCatching { DocumentsContract.deleteDocument(context.contentResolver, uri) }
        ok
    }

    fun setFolder(id: String, folder: String?) {
        val entry = get(id)?.copy(folder = folder?.trim()?.ifEmpty { null }) ?: return
        writeMeta(entry)
        _maps.update { list -> list.map { if (it.id == id) entry else it } }
    }

    fun setArchived(id: String, archived: Boolean) {
        val entry = get(id)?.copy(archived = archived) ?: return
        writeMeta(entry)
        _maps.update { list -> list.map { if (it.id == id) entry else it } }
    }

    fun setBasemap(id: String, value: String) {
        val entry = get(id)?.copy(basemap = value) ?: return
        writeMeta(entry)
        _maps.update { list -> list.map { if (it.id == id) entry else it } }
    }

    fun setSeeThrough(id: String, value: Float) {
        val entry = get(id)?.copy(seeThrough = value) ?: return
        writeMeta(entry)
        _maps.update { list -> list.map { if (it.id == id) entry else it } }
    }

    fun rename(id: String, name: String) {
        val entry = get(id)?.copy(name = name) ?: return
        writeMeta(entry)
        _maps.update { list -> list.map { if (it.id == id) entry else it }.sortedBy { it.name.lowercase() } }
    }

    fun delete(id: String) {
        File(root, id).deleteRecursively()
        features.remove(id)
        trailCaches.remove(id)
        _maps.update { list -> list.filter { it.id != id } }
    }

    /** What trail distance needs from a map, slow to work out, kept while the app runs (see [trailCache]). */
    class TrailCache {
        /** Held while [pdfTrails] or [network] is read or built, so it's only built once. */
        val lock = Mutex()
        /** Lines in the PDF's own trail, path or road layers: they never change. */
        var pdfTrails: List<List<LatLon>>? = null
        /** The network, and the trails besides [pdfTrails] it was built from. */
        var network: Pair<List<List<LatLon>>, TrailNetwork>? = null
    }

    private val trailCaches = ConcurrentHashMap<String, TrailCache>()

    /** Map [id]'s trail cache, so reopening a map doesn't read and join its trails again. */
    fun trailCache(id: String): TrailCache = trailCaches.getOrPut(id) { TrailCache() }

    // Features are kept in memory once loaded, so a reopened map never sees a stale file
    // while a write is still queued. Writes run in order on one thread.
    private val features = ConcurrentHashMap<String, MapFeatures>()
    private val writeScope = CoroutineScope(SupervisorJob() + Dispatchers.IO.limitedParallelism(1))

    fun loadFeatures(id: String): MapFeatures = features.getOrPut(id) {
        val file = featuresFile(id)
        if (!file.exists()) return@getOrPut MapFeatures()
        val text = file.readText()
        // 0.1/0.2 stored a bare array of features.
        if (text.trimStart().startsWith("[")) {
            val arr = JSONArray(text)
            return@getOrPut MapFeatures(features = List(arr.length()) { featureFromJson(arr.getJSONObject(it)) })
        }
        val o = JSONObject(text)
        val layers = o.getJSONArray("layers")
        val list = o.getJSONArray("features")
        MapFeatures(
            layers = List(layers.length()) { layerFromJson(layers.getJSONObject(it)) },
            features = List(list.length()) { featureFromJson(list.getJSONObject(it)) },
        )
    }

    /** Updates the cache now and writes in the background; completes with true once written. */
    fun saveFeatures(id: String, data: MapFeatures): Deferred<Boolean> {
        features[id] = data
        return writeScope.async {
            if (!File(root, id).exists()) return@async false // map was deleted
            try {
                val json = JSONObject()
                    .put("layers", JSONArray().apply { data.layers.forEach { put(layerToJson(it)) } })
                    .put("features", JSONArray().apply { data.features.forEach { put(featureToJson(it)) } })
                val tmp = File(featuresFile(id).path + ".tmp")
                tmp.writeText(json.toString())
                tmp.renameTo(featuresFile(id))
            } catch (e: IOException) {
                false // e.g. storage full; the in-memory copy is kept
            }
        }
    }

    // ---------------------------------------------------------------- persistence

    private fun loadAll(): List<MapEntry> {
        // A folder without meta.json is an import the app was killed in the middle of. The
        // library is made once per process, so no import can be running yet.
        root.listFiles().orEmpty().filter { it.isDirectory && !metaFile(it.name).exists() }.forEach { it.deleteRecursively() }
        return root.listFiles().orEmpty()
            .mapNotNull { dir -> runCatching { entryFromJson(JSONObject(metaFile(dir.name).readText())) }.getOrNull() }
            .sortedBy { it.name.lowercase() }
    }

    private fun writeMeta(e: MapEntry) {
        val json = JSONObject()
            .put("id", e.id)
            .put("name", e.name)
            .put("importedAt", e.importedAt)
            .put("fileSize", e.fileSize)
            .put("pageWidth", e.pageWidth)
            .put("pageHeight", e.pageHeight)
            .put("folder", e.folder ?: JSONObject.NULL)
            .put("seeThrough", e.seeThrough.toDouble())
            .put("basemap", e.basemap ?: JSONObject.NULL)
            .put("archived", e.archived)
        e.geo?.let { g ->
            json.put(
                "geo",
                JSONObject()
                    .put("crs", g.crsName)
                    .put("controlPoints", JSONArray().apply { g.controlPoints.forEach { (ll, p) -> put(JSONArray(listOf(ll.lat, ll.lon, p.x, p.y))) } })
                    .put("neatline", JSONArray().apply { g.neatline.forEach { put(JSONArray(listOf(it.x, it.y))) } }),
            )
        }
        metaFile(e.id).writeText(json.toString())
    }

    private fun entryFromJson(o: JSONObject): MapEntry {
        val geo = o.optJSONObject("geo")?.let { g ->
            val cps = g.getJSONArray("controlPoints").let { a ->
                List(a.length()) { i ->
                    val p = a.getJSONArray(i)
                    LatLon(p.getDouble(0), p.getDouble(1)) to PagePoint(p.getDouble(2), p.getDouble(3))
                }
            }
            val neat = g.getJSONArray("neatline").let { a ->
                List(a.length()) { i -> a.getJSONArray(i).let { PagePoint(it.getDouble(0), it.getDouble(1)) } }
            }
            GeoReference(cps, neat, if (g.isNull("crs")) null else g.getString("crs"))
        }
        return MapEntry(
            id = o.getString("id"),
            name = o.getString("name"),
            importedAt = o.getLong("importedAt"),
            fileSize = o.getLong("fileSize"),
            pageWidth = o.getInt("pageWidth"),
            pageHeight = o.getInt("pageHeight"),
            folder = if (o.isNull("folder")) null else o.optString("folder").ifEmpty { null },
            seeThrough = o.optDouble("seeThrough", 0.0).toFloat().coerceIn(0f, 1f),
            basemap = if (o.isNull("basemap")) null else o.optString("basemap").ifEmpty { null },
            archived = o.optBoolean("archived", false),
            geo = geo,
        )
    }

    private fun featureToJson(f: Feature) = JSONObject()
        .put("id", f.id)
        .put("type", f.type.name)
        .put("name", f.name)
        .put("description", f.description)
        .put("color", f.color)
        .put("createdAt", f.createdAt)
        .put("layerId", f.layerId ?: JSONObject.NULL)
        .apply { f.track?.let { put("track", trackToJson(it)) } }
        .put("visible", f.visible)
        .put("folder", f.folder ?: JSONObject.NULL)
        .put("trail", f.trail)
        .put("points", JSONArray().apply { f.points.forEach { put(JSONArray(listOf(it.lat, it.lon))) } })

    private fun layerToJson(l: FeatureLayer) = JSONObject()
        .put("id", l.id)
        .put("name", l.name)
        .put("color", l.color)
        .put("createdAt", l.createdAt)
        .put("visible", l.visible)
        .put("kind", l.kind.name)
        .put("trails", l.trails)
        .put("trailFolders", l.trailFolders?.let { JSONArray(it.toList()) } ?: JSONObject.NULL)

    private fun layerFromJson(o: JSONObject) = FeatureLayer(
        id = o.getString("id"),
        name = o.getString("name"),
        color = o.getInt("color"),
        createdAt = o.getLong("createdAt"),
        visible = o.optBoolean("visible", true),
        kind = LayerKind.entries.firstOrNull { it.name == o.optString("kind") } ?: LayerKind.IMPORTED,
        trails = o.optBoolean("trails", false),
        trailFolders = o.optJSONArray("trailFolders")?.let { a -> List(a.length()) { a.getString(it) }.toSet() },
    )

    private fun trackToJson(t: TrackData) = JSONObject()
        .put("times", JSONArray().apply { t.times.forEach { put(it ?: JSONObject.NULL) } })
        .put("elevations", JSONArray().apply { t.elevations.forEach { put(it ?: JSONObject.NULL) } })
        .put("durationMillis", t.durationMillis ?: JSONObject.NULL)
        .put("ascent", t.ascent ?: JSONObject.NULL)

    private fun trackFromJson(o: JSONObject): TrackData {
        val times = o.getJSONArray("times")
        val elevations = o.getJSONArray("elevations")
        return TrackData(
            times = List(times.length()) { if (times.isNull(it)) null else times.getLong(it) },
            elevations = List(elevations.length()) { if (elevations.isNull(it)) null else elevations.getDouble(it) },
            durationMillis = if (o.isNull("durationMillis")) null else o.getLong("durationMillis"),
            ascent = if (o.isNull("ascent")) null else o.getDouble("ascent"),
        )
    }

    private fun featureFromJson(o: JSONObject): Feature {
        val pts = o.getJSONArray("points")
        return Feature(
            id = o.getString("id"),
            type = FeatureType.valueOf(o.getString("type")),
            name = o.getString("name"),
            description = o.optString("description"),
            points = List(pts.length()) { i -> pts.getJSONArray(i).let { LatLon(it.getDouble(0), it.getDouble(1)) } },
            color = o.getInt("color"),
            createdAt = o.getLong("createdAt"),
            layerId = if (o.isNull("layerId")) null else o.optString("layerId").ifEmpty { null },
            track = o.optJSONObject("track")?.let(::trackFromJson),
            visible = o.optBoolean("visible", true),
            folder = if (o.isNull("folder")) null else o.optString("folder").ifEmpty { null },
            trail = o.optBoolean("trail", false),
        )
    }
}

/** The file name a content URI was shared with. */
fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
