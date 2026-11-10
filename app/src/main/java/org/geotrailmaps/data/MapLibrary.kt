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
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
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
     * [NO_BASEMAP]; null until one is chosen (the default from Settings is used).
     */
    val basemap: String? = null,
    /** Moved to the Archived section at the bottom of My Maps, out of the way. */
    val archived: Boolean = false,
    /** Pages in the PDF; only the first is shown. */
    val pageCount: Int = 1,
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
 * and the global layers, shown on every map they overlap, in global-features.json.
 */
class MapLibrary(private val context: Context) {
    private val root = File(context.filesDir, "maps").apply { mkdirs() }

    private val _maps = MutableStateFlow(loadAll())
    val maps: StateFlow<List<MapEntry>> = _maps

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 4)
    /** Messages for the user: something couldn't be saved or read, or an import note. */
    val messages: SharedFlow<String> = _messages

    fun get(id: String): MapEntry? = _maps.value.find { it.id == id }
    fun pdfFile(id: String) = File(File(root, id), "map.pdf")
    /** Opens the map's PDF for rendering; blocks, so not on the main thread. */
    fun pdfRenderer(id: String) = PdfPageRenderer(context, pdfFile(id))
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
            val info = runCatching { GeoPdfParser.parse(pdf) }.getOrNull()
            val geo = info?.geo
            val (w, h) = try {
                pdfRenderer(id).use { r ->
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
                pageCount = info?.pageCount?.coerceAtLeast(1) ?: 1,
            )
            writeMeta(entry)
            _maps.update { (it + entry).sortedBy { m -> m.name.lowercase() } }
            importNotes(entry)?.let { _messages.tryEmit(it) }
            entry
        } catch (e: Exception) {
            dir.deleteRecursively()
            throw e
        }
    }

    /** What a successful import doesn't show: pages left out, or a datum whose shift isn't applied. */
    private fun importNotes(e: MapEntry): String? = listOfNotNull(
        if (e.pageCount > 1) "\"${e.name}\" has ${e.pageCount} pages; only the first is shown." else null,
        if (e.geo?.oldDatum == true) "\"${e.name}\" uses an older datum (${e.geo.crsName}). Positions on it may be off by tens to hundreds of metres." else null,
    ).joinToString(" ").ifEmpty { null }

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

    fun setFolder(id: String, folder: String?) = update(id) { it.copy(folder = folder?.trim()?.ifEmpty { null }) }

    fun setArchived(id: String, archived: Boolean) = update(id) { it.copy(archived = archived) }

    fun setBasemap(id: String, value: String) = update(id) { it.copy(basemap = value) }

    fun setSeeThrough(id: String, value: Float) = update(id) { it.copy(seeThrough = value) }

    fun rename(id: String, name: String) = update(id) { it.copy(name = name) }

    /** Saves a change to a map's details; if that fails, nothing changes, the user is told, and it returns false. */
    private fun update(id: String, change: (MapEntry) -> MapEntry): Boolean {
        val entry = get(id)?.let(change) ?: return false
        try {
            writeMeta(entry)
        } catch (e: IOException) {
            _messages.tryEmit("Couldn't save the change. Storage may be full.")
            return false
        }
        _maps.update { list -> list.map { if (it.id == id) entry else it }.sortedBy { it.name.lowercase() } }
        return true
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
        readFeatures(featuresFile(id), "This map's points, lines and tracks couldn't be read, so it opened without them.")
    }

    private fun readFeatures(file: File, damagedMessage: String, report: (String) -> Unit = { _messages.tryEmit(it) }): MapFeatures {
        if (!file.exists()) return MapFeatures()
        return try {
            parseFeatures(file.readText())
        } catch (e: Exception) { // unreadable, JSONException, or a bad enum name
            // Damaged (e.g. by a storage fault): keep it aside, so a later save can't overwrite it,
            // and carry on without it rather than not at all.
            val kept = file.renameTo(File(file.path + ".damaged-" + System.currentTimeMillis()))
            report(damagedMessage + if (kept) " The damaged data was kept in the app's storage." else "")
            MapFeatures()
        }
    }

    /**
     * Set if the global layers couldn't be read at startup: [messages] has no one listening
     * yet then, so the app shows this once it's up (see [takeStartupNotice]).
     */
    private var startupNotice: String? = null

    fun takeStartupNotice(): String? = startupNotice.also { startupNotice = null }

    private val globalFile = File(context.filesDir, "global-features.json")
    private val _global = MutableStateFlow(
        readFeatures(globalFile, "Your recorded tracks and layers shown on all maps couldn't be read.") { startupNotice = it },
    )

    /**
     * Layers kept apart from any one map: recorded tracks, and layers chosen to show on all
     * maps. Each map shows the features that overlap it.
     */
    val global: StateFlow<MapFeatures> = _global

    /**
     * Changes the global layers now and writes them in the background; completes with true
     * once written. Main thread only.
     */
    fun updateGlobal(notify: Boolean = true, change: (MapFeatures) -> MapFeatures): Deferred<Boolean> {
        val data = change(_global.value)
        _global.value = data
        return writeScope.async {
            writeFeatures(globalFile, data).also {
                if (!it && notify) _messages.tryEmit("Couldn't save your tracks. Storage may be full.")
            }
        }
    }

    /** How many global features no other georeferenced map shows, which deleting map [id] would leave on no map. */
    fun onlyOnMap(id: String): Int {
        val here = get(id)?.coverage() ?: return 0
        val others = _maps.value.filter { it.id != id }.mapNotNull { it.coverage() }
        return _global.value.features.count { f -> overlaps(f, here) && others.none { overlaps(f, it) } }
    }

    private fun parseFeatures(text: String): MapFeatures {
        // 0.1/0.2 stored a bare array of features.
        if (text.trimStart().startsWith("[")) {
            val arr = JSONArray(text)
            return MapFeatures(features = List(arr.length()) { featureFromJson(arr.getJSONObject(it)) })
        }
        val o = JSONObject(text)
        val layers = o.getJSONArray("layers")
        val list = o.getJSONArray("features")
        val hidden = o.optJSONArray("hiddenGlobal")
        return MapFeatures(
            layers = List(layers.length()) { layerFromJson(layers.getJSONObject(it)) },
            features = List(list.length()) { featureFromJson(list.getJSONObject(it)) },
            hiddenGlobal = hidden?.let { a -> List(a.length()) { a.getString(it) }.toSet() }.orEmpty(),
        )
    }

    /** Updates the cache now and writes in the background; completes with true once written. */
    /** [notify]: tell the user if the write fails (off when the caller explains it itself). */
    fun saveFeatures(id: String, data: MapFeatures, notify: Boolean = true): Deferred<Boolean> {
        features[id] = data
        return writeScope.async {
            if (!File(root, id).exists()) return@async false // map was deleted
            writeFeatures(featuresFile(id), data).also {
                if (!it && notify) _messages.tryEmit("Couldn't save your changes to this map. Storage may be full.")
            }
        }
    }

    /** False if it couldn't be written (e.g. storage full); the in-memory copy is kept, and the next save writes it all. */
    private fun writeFeatures(file: File, data: MapFeatures): Boolean = try {
        val json = JSONObject()
            .put("layers", JSONArray().apply { data.layers.forEach { put(layerToJson(it)) } })
            .put("features", JSONArray().apply { data.features.forEach { put(featureToJson(it)) } })
            .apply { if (data.hiddenGlobal.isNotEmpty()) put("hiddenGlobal", JSONArray(data.hiddenGlobal.toList())) }
        file.writeTextAtomically(json.toString())
        true
    } catch (e: IOException) {
        false
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
            .put("pageCount", e.pageCount)
        e.geo?.let { g ->
            json.put(
                "geo",
                JSONObject()
                    .put("crs", g.crsName)
                    .put("controlPoints", JSONArray().apply { g.controlPoints.forEach { (ll, p) -> put(JSONArray(listOf(ll.lat, ll.lon, p.x, p.y))) } })
                    .put("neatline", JSONArray().apply { g.neatline.forEach { put(JSONArray(listOf(it.x, it.y))) } }),
            )
        }
        // A map without readable details wouldn't be listed.
        metaFile(e.id).writeTextAtomically(json.toString())
    }

    /** Written aside and renamed over the old file, so a failed write can't leave it damaged. */
    private fun File.writeTextAtomically(text: String) {
        val tmp = File("$path.tmp")
        tmp.writeText(text)
        if (!tmp.renameTo(this)) throw IOException("rename failed")
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
            folder = o.optStringOrNull("folder"),
            seeThrough = o.optDouble("seeThrough", 0.0).toFloat().coerceIn(0f, 1f),
            basemap = o.optStringOrNull("basemap"),
            archived = o.optBoolean("archived", false),
            pageCount = o.optInt("pageCount", 1),
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
        .apply { if (t.segmentStarts.isNotEmpty()) put("segmentStarts", JSONArray(t.segmentStarts)) }

    private fun trackFromJson(o: JSONObject): TrackData {
        val times = o.getJSONArray("times")
        val elevations = o.getJSONArray("elevations")
        val starts = o.optJSONArray("segmentStarts")
        return TrackData(
            times = List(times.length()) { if (times.isNull(it)) null else times.getLong(it) },
            elevations = List(elevations.length()) { if (elevations.isNull(it)) null else elevations.getDouble(it) },
            durationMillis = if (o.isNull("durationMillis")) null else o.getLong("durationMillis"),
            ascent = if (o.isNull("ascent")) null else o.getDouble("ascent"),
            segmentStarts = starts?.let { a -> List(a.length()) { a.getInt(it) } }.orEmpty(),
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
            layerId = o.optStringOrNull("layerId"),
            track = o.optJSONObject("track")?.let(::trackFromJson),
            visible = o.optBoolean("visible", true),
            folder = o.optStringOrNull("folder"),
            trail = o.optBoolean("trail", false),
        )
    }
}

/** The string at [key], or null if it's missing, null or empty. */
private fun JSONObject.optStringOrNull(key: String): String? = if (isNull(key)) null else optString(key).ifEmpty { null }

/** The file name a content URI was shared with. */
fun Context.displayName(uri: Uri): String? =
    contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
        if (c.moveToFirst()) c.getString(0) else null
    } ?: uri.lastPathSegment?.substringAfterLast('/')
