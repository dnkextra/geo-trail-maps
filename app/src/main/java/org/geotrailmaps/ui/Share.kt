package org.geotrailmaps.ui

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureExport
import org.geotrailmaps.data.FeatureLayer
import java.io.File
import java.io.IOException

/** Writes [features] as KML or GPX and opens the share sheet; false if the file couldn't be written. */
suspend fun shareFeatures(context: Context, mapName: String, features: List<Feature>, layers: List<FeatureLayer>, kml: Boolean): Boolean {
    val file = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "exports").apply { mkdirs() }
        val safeName = mapName.replace(Regex("[^A-Za-z0-9._ -]"), "_").ifBlank { "features" }
        val file = File(dir, "$safeName.${if (kml) "kml" else "gpx"}")
        try {
            file.writeText(if (kml) FeatureExport.kml(mapName, features, layers) else FeatureExport.gpx(mapName, features))
            file
        } catch (e: IOException) {
            file.delete()
            null
        }
    } ?: return false
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
    val send = Intent(Intent.ACTION_SEND)
        .setType(if (kml) "application/vnd.google-earth.kml+xml" else "application/gpx+xml")
        .putExtra(Intent.EXTRA_STREAM, uri)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    context.startActivity(Intent.createChooser(send, "Export features"))
    return true
}

