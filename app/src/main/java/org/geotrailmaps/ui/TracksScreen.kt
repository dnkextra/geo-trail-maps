package org.geotrailmaps.ui

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.MapEntry
import org.geotrailmaps.data.MapLibrary
import org.geotrailmaps.data.Units
import org.geotrailmaps.data.coverage
import org.geotrailmaps.data.overlaps
import org.geotrailmaps.track.TrackRecorder
import android.location.Location
import org.geotrailmaps.ui.map.RecordingBar
import org.geotrailmaps.ui.map.ResumeGapDialog
import org.geotrailmaps.ui.map.resumeGap
import org.geotrailmaps.ui.map.StopTrackDialog
import java.text.DateFormat
import java.util.Date

/**
 * The recording, and every global layer (recorded tracks, and layers shown on all maps), whether
 * or not a map shows them: the one place to find a track whose map was deleted.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(library: MapLibrary, maps: List<MapEntry>, units: Units, location: Location?, onOpenMap: (String) -> Unit, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val global by library.global.collectAsState()
    val recording by TrackRecorder.state.collectAsState()
    var showStop by remember { mutableStateOf(false) }
    /** Resume pressed this far (m) from where the track was paused: asked first. */
    var resumingFar by remember { mutableStateOf<Double?>(null) }
    // Stopped from the notification meanwhile: there's nothing left to stop.
    LaunchedEffect(recording.status) {
        if (recording.status == TrackRecorder.Status.IDLE) {
            showStop = false
            resumingFar = null
        }
    }
    var saving by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf<Feature?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    val coverages = remember(maps) { maps.filter { it.geo != null }.mapNotNull { m -> m.coverage()?.let { m to it } } }

    fun show(text: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(text)
    }

    /** Opens a map the feature is on, preferring maps not archived; null if none is. */
    fun mapFor(f: Feature): MapEntry? =
        coverages.filter { (_, area) -> overlaps(f, area) }.map { it.first }.minByOrNull { it.archived }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Tracks") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding)) {
            if (recording.status != TrackRecorder.Status.IDLE) {
                item(key = "recording") {
                    RecordingBar(
                        state = recording,
                        units = units,
                        onPauseResume = {
                            if (recording.status == TrackRecorder.Status.RECORDING) {
                                scope.launch {
                                    TrackRecorder.awaitPendingFixes()
                                    TrackRecorder.pause(context.applicationContext)
                                }
                            } else if (context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED) {
                                show("Recording a track needs precise location. Allow it in Android's settings for this app.")
                            } else {
                                resumeGap(recording, location)?.let { resumingFar = it } ?: TrackRecorder.resume(context.applicationContext)
                            }
                        },
                        onStop = { if (!saving) showStop = true },
                    )
                    HorizontalDivider()
                }
            }
            if (global.layers.isEmpty()) {
                item(key = "empty") {
                    Text(
                        "No tracks yet. Tracks you record are kept here, and shown on every map they cross.",
                        textAlign = TextAlign.Center,
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(32.dp),
                    )
                }
            }
            for (layer in global.layers) {
                val inLayer = global.features.filter { it.layerId == layer.id }.sortedByDescending { it.createdAt }
                item(key = "layer-${layer.id}") {
                    LayerHeader(layer, inLayer.size) {
                        val what = if (inLayer.size == 1) "its 1 item" else "its ${inLayer.size} items"
                        deleting = "Delete \"${layer.name}\" and $what from every map? This can't be undone. Export anything you want to keep first." to {
                            library.updateGlobal { d -> d.copy(layers = d.layers.filter { it.id != layer.id }, features = d.features.filter { it.layerId != layer.id }) }
                        }
                    }
                }
                items(inLayer, key = { it.id }) { f ->
                    val map = remember(f.points, coverages) { mapFor(f) }
                    TrackRow(
                        f = f,
                        units = units,
                        onMap = map?.name,
                        onOpen = map?.let { m -> { onOpenMap(m.id) } },
                        onRename = { renaming = f },
                        onExport = { kml ->
                            scope.launch {
                                if (!shareFeatures(context, f.name, listOf(f), listOf(layer), kml)) show("Couldn't export the track. Storage may be full.")
                            }
                        },
                        onDelete = {
                            deleting = "Delete \"${f.name}\"? This can't be undone." to {
                                library.updateGlobal { d -> d.copy(features = d.features.filter { it.id != f.id }) }
                            }
                        },
                    )
                }
                item(key = "divider-${layer.id}") { HorizontalDivider() }
            }
        }
    }

    fun save() {
        if (saving) return
        saving = true
        scope.launch {
            try {
                TrackRecorder.awaitPendingFixes()
                val saved = TrackRecorder.stop(context.applicationContext)
                show(if (saved == null) "The track was too short to save." else "Saved \"${saved.name}\".")
            } finally {
                saving = false
            }
        }
    }

    resumingFar?.let { distance ->
        ResumeGapDialog(
            distance = distance,
            units = units,
            saveLabel = "Save",
            onResume = { resumingFar = null; TrackRecorder.resume(context.applicationContext) },
            onSave = { resumingFar = null; save() },
            onDismiss = { resumingFar = null },
        )
    }

    if (showStop) {
        StopTrackDialog(
            state = recording,
            units = units,
            onSave = {
                showStop = false
                save()
            },
            onDiscard = {
                showStop = false
                TrackRecorder.finish(context.applicationContext)
                TrackRecorder.clearLog()
                show("Track discarded.")
            },
            onDismiss = { showStop = false },
        )
    }

    renaming?.let { f ->
        var name by remember(f.id) { mutableStateOf(f.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename track") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = {
                    library.updateGlobal { d -> d.copy(features = d.features.map { if (it.id == f.id) it.copy(name = name.trim()) else it }) }
                    renaming = null
                }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    deleting?.let { (question, apply) ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            text = { Text(question) },
            confirmButton = {
                TextButton(
                    onClick = { apply(); deleting = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

@Composable
private fun LayerHeader(layer: FeatureLayer, count: Int, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(layer.name, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(if (count == 1) "1 item" else "$count items") },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete ${layer.name}") } },
    )
}

@Composable
private fun TrackRow(
    f: Feature,
    units: Units,
    /** The name of a map it's on; null if it's on none. */
    onMap: String?,
    onOpen: (() -> Unit)?,
    onRename: () -> Unit,
    onExport: (kml: Boolean) -> Unit,
    onDelete: () -> Unit,
) {
    var menu by remember { mutableStateOf(false) }
    val started = f.track?.times?.firstOrNull { it != null } ?: f.createdAt
    ListItem(
        modifier = Modifier.padding(start = 16.dp).then(if (onOpen != null) Modifier.clickable(onClickLabel = "Open map", onClick = onOpen) else Modifier),
        headlineContent = { Text(f.name) },
        supportingContent = {
            Column {
                Text(DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(started)) + " · " + Formats.distance(f.length, units))
                Text(
                    if (onMap != null) "On \"$onMap\"" else "Not on any map",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options for ${f.name}") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    DropdownMenuItem(text = { Text("Export GPX") }, onClick = { menu = false; onExport(false) })
                    DropdownMenuItem(text = { Text("Export KML") }, onClick = { menu = false; onExport(true) })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}
