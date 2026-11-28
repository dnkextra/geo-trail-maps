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
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.material3.Text
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
import org.geotrailmaps.track.TrackRecorder
import android.location.Location
import org.geotrailmaps.ui.map.RecordingBar
import org.geotrailmaps.ui.map.ResumeGapDialog
import org.geotrailmaps.ui.map.resumeGap
import org.geotrailmaps.ui.map.StopTrackDialog
import org.geotrailmaps.ui.map.FeatureEditSheet
import org.geotrailmaps.ui.map.SwitchTrackDialog
import org.geotrailmaps.ui.map.FeatureIcon
import org.geotrailmaps.ui.map.isTrack
import org.geotrailmaps.ui.map.canContinue
import org.geotrailmaps.ui.map.trackSheetFor
import org.geotrailmaps.ui.map.summary
import org.geotrailmaps.data.Settings
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.data.Trails
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.style.TextOverflow
import org.geotrailmaps.ui.map.trackMaps
import org.geotrailmaps.data.TrackData
import org.geotrailmaps.data.CoordinateFormat
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.draw.alpha
import org.geotrailmaps.ui.map.countText
import java.text.DateFormat
import java.util.Date

/**
 * The recording, and every global layer (recorded tracks, and layers shown on all maps), whether
 * or not a map shows them: the one place to find a track whose map was deleted. Each track can be
 * shown or hidden, renamed, downloaded or deleted from its row; tapping it opens the same sheet as
 * on a map, with the maps it can be viewed on.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TracksScreen(
    library: MapLibrary,
    settings: Settings,
    maps: List<MapEntry>,
    location: Location?,
    /** Opens map (first) zoomed to track (second). */
    onShowTrackOn: (String, String) -> Unit,
    onBack: () -> Unit,
) {
    BackHandler(onBack = onBack)
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val snackbar = remember { SnackbarHostState() }
    val global by library.global.collectAsState()
    val recording by TrackRecorder.state.collectAsState()
    val units by settings.units.collectAsState()
    val format by settings.coordinateFormat.collectAsState()
    val recentIcons by settings.recentIcons.collectAsState()
    val nameCounts by settings.nameCounts.collectAsState()
    var showStop by remember { mutableStateOf(false) }
    /** Continue pressed while another track is recording or paused: confirmed, as that one stops. */
    var switchingTo by remember { mutableStateOf<Feature?>(null) }
    // The recording notification needs this; recording works without it, just silently.
    val notificationPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    /** Resume pressed this far (m) from where the track was paused: asked first. */
    var resumingFar by remember { mutableStateOf<Double?>(null) }
    // Stopped from the notification meanwhile: there's nothing left to stop.
    LaunchedEffect(recording.status) {
        if (recording.status == TrackRecorder.Status.IDLE) {
            showStop = false
            resumingFar = null
            switchingTo = null
        }
    }
    var saving by remember { mutableStateOf(false) }
    var editingId by remember { mutableStateOf<String?>(null) }
    var renaming by remember { mutableStateOf<Feature?>(null) }
    var exportingTrack by remember { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }

    // The track being recorded with its points so far (the stored copy is brought up to date every so often).
    val features = remember(global, recording.points, recording.trackId) {
        global.features.map { f ->
            if (f.id != recording.trackId) f
            else f.copy(points = recording.points, track = TrackData(recording.times, recording.elevations, null, recording.ascent, recording.segmentStarts))
        }
    }
    val byLayer = remember(features) { features.groupBy { it.layerId }.mapValues { (_, v) -> v.sortedByDescending { it.createdAt } } }

    fun show(text: String) = scope.launch {
        snackbar.currentSnackbarData?.dismiss()
        snackbar.showSnackbar(text)
    }

    fun setVisible(f: Feature, visible: Boolean) = library.updateGlobal { d -> d.withFeature(f.id) { it.copy(visible = visible) } }

    fun pauseOrResume() {
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
    }

    fun hasPreciseLocation() = context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED

    /** Records more onto [f], as from its sheet on a map. Tracks here are all on every map already. */
    fun continueTrack(f: Feature) {
        if (!hasPreciseLocation()) return run { show("Recording a track needs precise location. Allow it in Android's settings for this app.") }
        if (context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (!TrackRecorder.start(context.applicationContext, f)) return run { show("Still saving the last track. Try again in a moment.") }
        show("Continuing \"${f.name}\".")
    }

    fun requestContinue(f: Feature) {
        if (recording.status != TrackRecorder.Status.IDLE) switchingTo = f else continueTrack(f)
    }

    fun stop(name: String?) {
        if (saving) return
        saving = true
        scope.launch {
            try {
                TrackRecorder.awaitPendingFixes()
                val saved = TrackRecorder.stop(context.applicationContext, name)
                show(if (saved == null) "Nothing was recorded, so the track was removed." else "Stopped \"${saved.name}\".")
            } finally {
                saving = false
            }
        }
    }

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
                        onPauseResume = ::pauseOrResume,
                        onStop = { if (!saving) showStop = true },
                        onOpen = { editingId = recording.trackId },
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
                val inLayer = byLayer[layer.id].orEmpty()
                item(key = "layer-${layer.id}") {
                    LayerHeader(layer, inLayer.size) {
                        deleting = "Delete \"${layer.name}\" and its ${countText(inLayer.size)} from every map? This can't be undone. Export anything you want to keep first." to {
                            library.updateGlobal { it.removeLayer(layer.id) }
                        }
                    }
                }
                items(inLayer, key = { it.id }) { f ->
                    val count = remember(f.points, maps) { trackMaps(f, maps, null).size }
                    TrackRow(
                        f = f,
                        isTrack = isTrack(f, layer),
                        units = units,
                        format = format,
                        mapCount = count,
                        recording = if (f.id == recording.trackId) recording.status == TrackRecorder.Status.RECORDING else null,
                        onOpen = { editingId = f.id },
                        onVisible = { setVisible(f, it) },
                        onRename = { renaming = f },
                        onExport = { exportingTrack = f.id },
                        onDelete = {
                            deleting = "Delete \"${f.name}\"? This can't be undone." to {
                                library.updateGlobal { it.removeFeature(f.id) }
                            }
                        },
                    )
                }
                item(key = "divider-${layer.id}") { HorizontalDivider() }
            }
        }
    }

    features.find { it.id == editingId }?.let { f ->
        val layer = global.layers.find { it.id == f.layerId }
        FeatureEditSheet(
            feature = f,
            units = units,
            format = format,
            layerName = layer?.name,
            // Navigating needs a map to navigate on.
            onNavigate = null,
            onContinueTrack = if (canContinue(f, layer, recording)) ({ requestContinue(f) }) else null,
            trailByLayer = Trails.byLayer(f, layer),
            onTrail = if (f.type == FeatureType.LINE) ({ on -> library.updateGlobal { d -> d.withFeature(f.id) { it.copy(trail = on) } } }) else null,
            recentIcons = recentIcons,
            onIconUsed = settings::iconUsed,
            nameCounts = nameCounts,
            onNameUsed = settings::nameUsed,
            // Only what the sheet changes: a track's points are the recorder's while it records.
            onSave = { e ->
                library.updateGlobal { d ->
                    d.withFeature(f.id) {
                        it.copy(name = e.name, description = e.description, color = e.color, opacity = e.opacity, visible = e.visible, icon = e.icon, time = e.time)
                    }
                }
            },
            onDelete = {
                val removed = global.features.find { it.id == f.id } ?: f
                library.updateGlobal { it.removeFeature(f.id) }
                scope.launch {
                    snackbar.currentSnackbarData?.dismiss()
                    if (snackbar.showSnackbar("\"${f.name}\" deleted", "Undo") == SnackbarResult.ActionPerformed) {
                        library.updateGlobal { it.upsert(removed) }
                    }
                }
            },
            onDismiss = { editingId = null },
            track = trackSheetFor(
                f, layer, recording,
                maps = remember(f.points, maps) { trackMaps(f, maps, null) },
                onPauseResume = ::pauseOrResume,
                onStop = { if (!saving) showStop = true },
                onVisible = { setVisible(f, it) },
                onDownload = { exportingTrack = f.id },
                onOpenMap = { m -> onShowTrackOn(m.id, f.id) },
            ),
        )
    }

    switchingTo?.let { to ->
        SwitchTrackDialog(
            current = global.features.find { it.id == recording.trackId }?.name ?: "The current track",
            continuing = to.name,
            onConfirm = {
                switchingTo = null
                if (!saving) {
                    saving = true
                    scope.launch {
                        try {
                            if (TrackRecorder.stopAndWait(context.applicationContext)) {
                                global.features.find { it.id == to.id }?.let(::continueTrack)
                            }
                        } finally {
                            saving = false
                        }
                    }
                }
            },
            onDismiss = { switchingTo = null },
        )
    }

    resumingFar?.let { distance ->
        ResumeGapDialog(
            distance = distance,
            units = units,
            saveLabel = "Stop",
            onResume = { resumingFar = null; TrackRecorder.resume(context.applicationContext) },
            onSave = { resumingFar = null; stop(null) },
            onDismiss = { resumingFar = null },
        )
    }

    if (showStop) {
        StopTrackDialog(
            state = recording,
            units = units,
            name = global.features.find { it.id == recording.trackId }?.name.orEmpty(),
            onStop = { name ->
                showStop = false
                stop(name)
            },
            onDismiss = { showStop = false },
        )
    }

    renaming?.let { f ->
        RenameDialog(
            "Rename track", f.name,
            onRename = { name ->
                library.updateGlobal { d -> d.withFeature(f.id) { it.copy(name = name) } }
                renaming = null
            },
            onDismiss = { renaming = null },
        )
    }

    // Looked up each time, so a track that changes while the dialog is open is exported as it is then.
    exportingTrack?.let { id ->
        val track = features.firstOrNull { it.id == id }
        val layer = global.layers.firstOrNull { it.id == track?.layerId }
        // Deleted meanwhile: nothing to show.
        if (track == null || layer == null) return@let
        ExportDialog(
            defaultName = track.name,
            features = listOf(track),
            layers = listOf(layer),
            // Tracks lists every global layer, so this can be a placemark or area too.
            title = if (isTrack(track, layer)) "Download track" else "Download feature",
            // GPX suits a track; KML keeps a placemark's icon and an area's shape.
            defaultKml = !isTrack(track, layer),
            onDownload = { name, kml, chosen ->
                exportingTrack = null
                scope.launch {
                    val result = exportFeatures(context, name, chosen, listOf(layer), kml)
                    when {
                        result == null -> show("Couldn't export \"${track.name}\". Storage may be full.")
                        result.savedAs == null -> shareExport(context, result.export)
                        else -> {
                            snackbar.currentSnackbarData?.dismiss()
                            if (snackbar.showSnackbar("Saved ${result.savedAs} to Downloads", "Share", duration = SnackbarDuration.Long) == SnackbarResult.ActionPerformed) {
                                shareExport(context, result.export)
                            }
                        }
                    }
                }
            },
            onDismiss = { exportingTrack = null },
        )
    }

    deleting?.let { (question, apply) ->
        ConfirmDeleteDialog(text = question, onDelete = { apply(); deleting = null }, onDismiss = { deleting = null })
    }
}

@Composable
private fun LayerHeader(layer: FeatureLayer, count: Int, onDelete: () -> Unit) {
    ListItem(
        headlineContent = { Text(layer.name, style = MaterialTheme.typography.titleSmall) },
        supportingContent = { Text(countText(count)) },
        trailingContent = { IconButton(onClick = onDelete) { Icon(Icons.Filled.Delete, "Delete ${layer.name}") } },
    )
}

@Composable
private fun TrackRow(
    f: Feature,
    /** A track: dated, with its length; anything else (from a layer shown on all maps) shows its summary. */
    isTrack: Boolean,
    units: Units,
    format: CoordinateFormat,
    /** How many maps it crosses. */
    mapCount: Int,
    /** For the track being recorded: true while recording, false while paused; null otherwise. */
    recording: Boolean?,
    onOpen: () -> Unit,
    onVisible: (Boolean) -> Unit,
    onRename: () -> Unit,
    onExport: () -> Unit,
    onDelete: () -> Unit,
) {
    // A sum over every point: not redone each time the screen recomposes (every GPS fix while recording).
    val length = remember(f.points, f.track) { f.length }
    var menu by remember { mutableStateOf(false) }
    val started = f.track?.times?.firstOrNull { it != null } ?: f.time ?: f.createdAt
    // Hidden tracks: the text dims, the controls stay at full strength.
    val textAlpha = if (f.visible) 1f else 0.6f
    ListItem(
        modifier = Modifier.clickable(onClickLabel = "Open", onClick = onOpen).padding(start = 4.dp),
        leadingContent = {
            Checkbox(
                checked = f.visible,
                onCheckedChange = onVisible,
                colors = Color(f.color).let { c -> CheckboxDefaults.colors(checkedColor = c, uncheckedColor = MaterialTheme.colorScheme.outline) },
                modifier = Modifier.semantics { contentDescription = "Show ${f.name}" },
            )
        },
        headlineContent = {
            Row(Modifier.alpha(textAlpha), verticalAlignment = Alignment.CenterVertically) {
                // Points and areas from layers shown on all maps are listed here too: say which is which.
                if (!isTrack) {
                    FeatureIcon(f.type, f.icon, Color(f.color), 16.dp)
                    Spacer(Modifier.size(6.dp))
                }
                Text(f.name)
            }
        },
        supportingContent = {
            Column(Modifier.alpha(textAlpha)) {
                Text(
                    if (!isTrack) f.summary(units, format)
                    else listOfNotNull(
                        when (recording) { true -> "Recording"; false -> "Paused"; null -> null },
                        DateFormat.getDateInstance(DateFormat.MEDIUM).format(Date(started)),
                        Formats.distance(length, units),
                    ).joinToString(" · "),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    when (mapCount) {
                        0 -> "Not on any map"
                        1 -> "On 1 map"
                        else -> "On $mapCount maps"
                    },
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
                    DropdownMenuItem(text = { Text("Download…") }, onClick = { menu = false; onExport() })
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}
