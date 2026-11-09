package org.geotrailmaps.render

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.LruCache
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.geotrailmaps.geo.LatLon
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import kotlin.math.PI
import kotlin.math.atan
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.log2
import kotlin.math.sinh
import kotlin.math.tan

/** A Web Mercator (XYZ) tile address. */
data class Tile(val z: Int, val x: Int, val y: Int) {
    /** North-west corner; (x + 1, y + 1) gives the south-east corner. */
    fun corner(dx: Int, dy: Int): LatLon {
        val n = 1 shl z
        val lon = (x + dx).toDouble() / n * 360.0 - 180.0
        val lat = Math.toDegrees(atan(sinh(PI * (1 - 2.0 * (y + dy) / n))))
        return LatLon(lat, lon)
    }

    companion object {
        fun at(p: LatLon, z: Int): Pair<Double, Double> {
            val n = 1 shl z
            val lat = Math.toRadians(p.lat.coerceIn(-85.0511, 85.0511))
            val x = (p.lon + 180.0) / 360.0 * n
            val y = (1 - ln(tan(lat) + 1 / cos(lat)) / PI) / 2 * n
            return x to y
        }

        /** Zoom at which a tile covers about [tileScreenPx] pixels, given ground metres per pixel. */
        fun zoomFor(metersPerPx: Double, lat: Double, tileScreenPx: Double): Int {
            val metersPerTile = metersPerPx * tileScreenPx
            return floor(log2(40_075_016.686 * cos(Math.toRadians(lat)) / metersPerTile)).toInt().coerceIn(0, MAX_ZOOM)
        }

        const val MAX_ZOOM = 19
    }
}

/** Where background map tiles come from, and the credit each one requires. */
enum class BasemapSource(
    val label: String,
    val credit: String,
    /** For the brief credit on the map itself; [credit] is shown in full in the layer list. */
    val shortCredit: String,
    val creditUrl: String,
    /** Deeper zooms are drawn by enlarging tiles from this level. */
    val maxZoom: Int,
    private val cacheDir: String,
    /** Cached tiles older than this are re-checked with the server; null for imagery that never changes. */
    val refreshAfterDays: Int?,
) {
    OSM("OpenStreetMap", "© OpenStreetMap contributors", "© OpenStreetMap contributors", "https://www.openstreetmap.org/copyright", Tile.MAX_ZOOM, "tiles", 90),
    // Sentinel-2 imagery is 10 m per pixel: zoom 15 is already enlarged on the server.
    SATELLITE(
        "Satellite",
        "Sentinel-2 cloudless by EOX IT Services GmbH (contains modified Copernicus Sentinel data 2025)",
        "Sentinel-2 cloudless by EOX IT Services GmbH · Contains modified Copernicus Sentinel data 2025",
        "https://s2maps.eu",
        15,
        "tiles-s2cloudless-2025",
        null,
    );

    fun url(t: Tile) = when (this) {
        OSM -> "https://tile.openstreetmap.org/${t.z}/${t.x}/${t.y}.png"
        SATELLITE -> "https://tiles.maps.eox.at/wmts/1.0.0/s2cloudless-2025_3857/default/g/${t.z}/${t.y}/${t.x}.jpg"
    }

    /** The server tiles are downloaded from, for privacy disclosures. */
    val host: String get() = url(Tile(0, 0, 0)).substringAfter("://").substringBefore('/')

    fun cacheFile(root: File, t: Tile) = File(root, "$cacheDir/${t.z}/${t.x}/${t.y}." + url(t).substringAfterLast('.'))
}

/**
 * Raster tiles for the background map. Lookups never block: drawing uses whatever is in
 * memory and missing tiles are fetched in the background (memory, then disk cache, then
 * network). [version] changes whenever a tile arrives so the map redraws.
 */
class BasemapTiles(context: Context) {
    private data class Key(val source: BasemapSource, val tile: Tile)

    private val cacheRoot = context.cacheDir
    private val memory = object : LruCache<Key, Bitmap>(48 * 1024 * 1024) {
        override fun sizeOf(key: Key, value: Bitmap) = value.allocationByteCount
    }
    private val inFlight = HashSet<Key>()
    /** The latest visible set; queued tiles that have scrolled away are skipped. */
    @Volatile private var wanted: Set<Key> = emptySet()
    private val failedAt = HashMap<Key, Long>()
    /** Old cached tiles being re-checked; see [refresh]. */
    private val refreshing = HashSet<Key>()
    /** Missing tiles queued or downloading; refreshes wait for these. */
    private var downloading = 0

    // The OSM tile policy asks clients to keep to two connections. Cached tiles have their own
    // workers, so they don't wait behind slow downloads.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val disk = Dispatchers.IO.limitedParallelism(2)
    private val network = Dispatchers.IO.limitedParallelism(2)
    /** One refresh at a time, so missing tiles never queue behind more than one. */
    private val refreshNetwork = network.limitedParallelism(1)

    var version by mutableIntStateOf(0)
        private set

    /** True after a download failed for lack of a connection, until one succeeds. */
    var offline by mutableStateOf(false)
        private set

    fun cached(source: BasemapSource, tile: Tile): Bitmap? = memory.get(Key(source, tile))

    /** Makes [tiles] the visible set and queues the ones not loaded yet. Call from the main thread. */
    fun request(source: BasemapSource, tiles: List<Tile>) {
        val keys = tiles.map { Key(source, it) }
        wanted = keys.toHashSet()
        keys.forEach(::enqueue)
    }

    /** Drops everything queued, e.g. when the map is closed or the background map turned off. */
    fun cancelAll() {
        wanted = emptySet()
    }

    private fun enqueue(key: Key) {
        if (memory.get(key) != null || key in inFlight) return
        if (failedAt[key]?.let { SystemClock.elapsedRealtime() - it < RETRY_MS } == true) return
        inFlight += key
        scope.launch {
            var skipped = false
            var stale = false
            var bitmap = withContext(disk) {
                if (key !in wanted) { skipped = true; null } else runCatching { readCached(key) }.getOrNull()
                    ?.also { stale = isStale(key) }
            }
            var downloaded: Result<Bitmap?>? = null
            if (bitmap == null && !skipped) {
                downloading++
                bitmap = withContext(network) {
                    if (key !in wanted) { skipped = true; null } else runCatching { download(key) }.also { downloaded = it }.getOrNull()
                }
                downloading--
            }
            downloaded?.let { r -> if (r.exceptionOrNull() is IOException) offline = true else if (r.getOrNull() != null) offline = false }
            inFlight -= key
            // Wanted again while it was being skipped: that request found it in flight.
            if (skipped) { if (key in wanted) enqueue(key); return@launch }
            if (bitmap != null) {
                memory.put(key, bitmap)
                failedAt.remove(key)
                version++
                if (stale) refresh(key)
            } else {
                // Offline or server error: try again later if it's still on screen.
                failedAt[key] = SystemClock.elapsedRealtime()
                delay(RETRY_MS)
                if (key in wanted) enqueue(key)
            }
        }
    }

    /**
     * Re-downloads a tile that was cached long ago, after any missing tiles, asking the server
     * to send it only if it changed. The cached tile stays on screen and on disk if not.
     */
    private fun refresh(key: Key) {
        if (!refreshing.add(key)) return
        scope.launch {
            while (downloading > 0) delay(REFRESH_WAIT_MS)
            val result = withContext(refreshNetwork) {
                if (key !in wanted) null else runCatching { download(key, revalidate = true) }
            }
            refreshing -= key
            if (result == null) return@launch
            offline = result.exceptionOrNull() is IOException
            result.getOrNull()?.let {
                memory.put(key, it)
                version++
            }
        }
    }

    private fun isStale(key: Key): Boolean {
        val days = key.source.refreshAfterDays ?: return false
        val age = System.currentTimeMillis() - key.source.cacheFile(cacheRoot, key.tile).lastModified()
        return age > days * DAY_MS
    }

    private fun readCached(key: Key): Bitmap? {
        val file = key.source.cacheFile(cacheRoot, key.tile)
        return if (file.isFile) BitmapFactory.decodeFile(file.path) else null
    }

    /** With [revalidate], returns null if the cached copy is still current, and marks it as checked. */
    private fun download(key: Key, revalidate: Boolean = false): Bitmap? {
        val file = key.source.cacheFile(cacheRoot, key.tile)
        val conn = URL(key.source.url(key.tile)).openConnection() as HttpURLConnection
        conn.setRequestProperty("User-Agent", USER_AGENT)
        conn.connectTimeout = 10_000
        conn.readTimeout = 15_000
        if (revalidate) conn.ifModifiedSince = file.lastModified()
        try {
            if (conn.responseCode == HttpURLConnection.HTTP_NOT_MODIFIED) {
                file.setLastModified(System.currentTimeMillis())
                return null
            }
            if (conn.responseCode != 200) return null
            val bytes = conn.inputStream.use { it.readBytes() }
            val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            file.parentFile?.mkdirs()
            runCatching { file.writeBytes(bytes) }
            return bitmap
        } finally {
            conn.disconnect()
        }
    }

    private companion object {
        const val RETRY_MS = 30_000L
        const val REFRESH_WAIT_MS = 500L
        const val DAY_MS = 24 * 60 * 60 * 1000L
        const val USER_AGENT = "GeoTrailMaps (Android geospatial PDF viewer)"
    }
}
