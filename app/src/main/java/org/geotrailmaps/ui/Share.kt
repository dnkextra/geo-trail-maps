package org.geotrailmaps.ui

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.provider.MediaStore
import android.webkit.MimeTypeMap
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureExport
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.MarkerType
import java.io.File
import java.io.IOException

/** A KML or GPX file written to the app's cache, ready to save or share. */
class ExportedFile(val file: File, val mimeType: String)

/** What became of an export: saved to Downloads under [savedAs], or null if only sharing is possible. */
class ExportResult(val export: ExportedFile, val savedAs: String?)

/**
 * Writes [features] as KML or GPX and saves a copy in Downloads.
 * Null if the file couldn't be written at all; [ExportResult.savedAs] is null if only Downloads failed.
 */
suspend fun exportFeatures(
    context: Context,
    name: String,
    features: List<Feature>,
    layers: List<FeatureLayer>,
    kml: Boolean,
    markerTypes: Map<String, MarkerType> = emptyMap(),
): ExportResult? =
    withContext(Dispatchers.IO) {
        // Two exports with one name share the cache file: one at a time, so neither copies the other's half-written file.
        exportLock.withLock {
            val dir = File(context.cacheDir, "exports").apply { mkdirs() }
            val file = File(dir, "${fileNameFor(name)}.${if (kml) "kml" else "gpx"}")
            // Pasted characters XML doesn't allow would make the file invalid.
            val title = name.filterNot { it.isISOControl() || it == '\uFFFE' || it == '\uFFFF' }
            try {
                file.writeText(if (kml) FeatureExport.kml(title, features, layers, markerTypes) else FeatureExport.gpx(title, features, markerTypes))
            } catch (e: Exception) {
                file.delete()
                return@withContext null
            }
            val export = ExportedFile(file, if (kml) "application/vnd.google-earth.kml+xml" else "application/gpx+xml")
            ExportResult(export, saveToDownloads(context, export))
        }
    }

private val exportLock = Mutex()

/**
 * [name] as a file name: letters in any script are kept; only what file systems refuse is
 * replaced, leading and trailing dots and spaces go, and it's cut to 150 UTF-8 bytes (names
 * are limited to 255 bytes, with room left for the extension and Android's " (1)").
 */
internal fun fileNameFor(name: String): String {
    val cleaned = name.replace(Regex("[\\\\/:*?\"<>|\\p{Cntrl}]"), "_").trim('.', ' ')
    var end = 0
    var bytes = 0
    while (end < cleaned.length) {
        val next = cleaned.offsetByCodePoints(end, 1)
        bytes += cleaned.substring(end, next).toByteArray(Charsets.UTF_8).size
        if (bytes > 150) break
        end = next
    }
    return cleaned.substring(0, end).trimEnd('.', ' ').ifBlank { "features" }
}

/** Copies [export] into Downloads; the name it was saved under (Android adds " (1)" etc. on a clash), or null. */
private fun saveToDownloads(context: Context, export: ExportedFile): String? {
    val resolver = context.contentResolver
    val values = ContentValues().apply {
        put(MediaStore.Downloads.DISPLAY_NAME, export.file.name)
        // A type Android doesn't know would make it add its own extension ("Trail.gpx (1)" on a clash).
        put(MediaStore.Downloads.MIME_TYPE, MimeTypeMap.getSingleton().getMimeTypeFromExtension(export.file.extension) ?: "application/octet-stream")
        put(MediaStore.Downloads.IS_PENDING, 1)
    }
    val uri = try {
        resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
    } catch (e: Exception) {
        null
    } ?: return null
    try {
        resolver.openOutputStream(uri)?.use { out -> export.file.inputStream().use { it.copyTo(out) } }
            ?: throw IOException("No output stream")
        if (resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null) != 1) {
            throw IOException("Not published")
        }
    } catch (e: Exception) {
        runCatching { resolver.delete(uri, null, null) }
        return null
    }
    // Saved by now: if the name can't be read back, report the one asked for rather than undo the save.
    return runCatching {
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }
    }.getOrNull() ?: export.file.name
}

/** Opens the share sheet for [export]. */
fun shareExport(context: Context, export: ExportedFile) {
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", export.file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(export.mimeType)
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Share features"))
}
