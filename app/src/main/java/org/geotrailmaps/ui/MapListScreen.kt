package org.geotrailmaps.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.calculateEndPadding
import androidx.compose.foundation.layout.calculateStartPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Archive
import androidx.compose.material.icons.automirrored.filled.Sort
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.collectAsState
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.ui.semantics.Role
import org.geotrailmaps.track.TrackRecorder
import org.geotrailmaps.ui.theme.MapStyle
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.MapEntry
import org.geotrailmaps.data.MapSort
import org.geotrailmaps.data.Units
import org.geotrailmaps.geo.LatLon
import java.io.File

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MapListScreen(
    maps: List<MapEntry>,
    thumbnail: (String) -> File,
    myLocation: LatLon?,
    units: Units,
    importing: Boolean,
    snackbar: SnackbarHostState,
    onImport: () -> Unit,
    onOpen: (String) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    /** Exports the map's PDF, in case the original is lost. */
    onSavePdf: (MapEntry) -> Unit,
    onMoveToFolder: (String, String?) -> Unit,
    /** Moves a map to the Archived section at the bottom, or back out of it. */
    onSetArchived: (String, Boolean) -> Unit,
    sort: MapSort,
    onSortChange: (MapSort) -> Unit,
    onSettings: () -> Unit,
    /** Opens the map last viewed, from the "Recording a track" row; null when there isn't one. */
    onRecordingClick: (() -> Unit)? = null,
) {
    var renaming by remember { mutableStateOf<MapEntry?>(null) }
    var deleting by remember { mutableStateOf<MapEntry?>(null) }
    var moving by remember { mutableStateOf<MapEntry?>(null) }
    var sortMenu by remember { mutableStateOf(false) }
    val recordingActive = TrackRecorder.state.collectAsState().value.status != TrackRecorder.Status.IDLE
    var collapsed by rememberSaveable { mutableStateOf(setOf<String>()) }
    // Archived maps start hidden; the section is opened by tapping its header.
    var archiveOpen by rememberSaveable { mutableStateOf(false) }
    val active = maps.filter { !it.archived }
    val folders = active.mapNotNull { it.folder }.distinct().sortedBy { it.lowercase() }
    val sorted = when (sort) {
        MapSort.NAME -> maps.sortedBy { it.name.lowercase() }
        MapSort.RECENT -> maps.sortedByDescending { it.importedAt }
        // Maps you're on come first (distance 0); without a fix, fall back to name order.
        MapSort.DISTANCE -> if (myLocation == null) maps.sortedBy { it.name.lowercase() } else {
            maps.sortedWith(compareBy({ it.geo?.distanceFromMap(myLocation) ?: Double.MAX_VALUE }, { it.name.lowercase() }))
        }
    }

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = { Text("My Maps") },
                    actions = {
                        Box {
                            IconButton(onClick = { sortMenu = true }) { Icon(Icons.AutoMirrored.Filled.Sort, "Sort maps") }
                            DropdownMenu(sortMenu, onDismissRequest = { sortMenu = false }) {
                                for (s in MapSort.entries) {
                                    val needsFix = s == MapSort.DISTANCE && myLocation == null
                                    DropdownMenuItem(
                                        text = {
                                            Column {
                                                Text(s.label)
                                                if (needsFix) Text("Needs a GPS fix", style = MaterialTheme.typography.bodySmall)
                                            }
                                        },
                                        leadingIcon = { if (s == sort) Icon(Icons.Filled.Check, null) },
                                        enabled = !needsFix,
                                        onClick = { sortMenu = false; onSortChange(s) },
                                    )
                                }
                            }
                        }
                        IconButton(onClick = onSettings) { Icon(Icons.Filled.Settings, "Settings") }
                    },
                )
                if (importing) LinearProgressIndicator(Modifier.fillMaxWidth())
            }
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(
                onClick = onImport,
                icon = { Icon(Icons.Filled.Add, null) },
                text = { Text("Import maps") },
            )
        },
        snackbarHost = { SnackbarHost(snackbar) },
    ) { padding ->
        if (maps.isEmpty()) {
            Column(
                Modifier.fillMaxSize().padding(padding).padding(32.dp),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(Icons.Filled.Map, null, Modifier.size(72.dp), tint = MaterialTheme.colorScheme.primary)
                Text("No maps yet", style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(top = 16.dp))
                Text(
                    "Import a geospatial PDF map, or open one from your files, email or browser with this app.",
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        } else {
            // Rows scroll behind the nav bar; extra bottom space clears the import button.
            val layoutDirection = LocalLayoutDirection.current
            LazyColumn(
                contentPadding = PaddingValues(
                    start = padding.calculateStartPadding(layoutDirection),
                    top = padding.calculateTopPadding(),
                    end = padding.calculateEndPadding(layoutDirection),
                    bottom = padding.calculateBottomPadding() + 88.dp,
                ),
            ) {
                // Maps outside folders first, then one collapsible group per folder.
                val row: @Composable (MapEntry) -> Unit = { map ->
                    MapRow(
                        map = map,
                        thumbnail = thumbnail(map.id),
                        status = statusText(map, myLocation, units),
                        onClick = { onOpen(map.id) },
                        onRename = { renaming = map },
                        onMove = { moving = map },
                        onSavePdf = { onSavePdf(map) },
                        onToggleArchived = { onSetArchived(map.id, !map.archived) },
                        onDelete = { deleting = map },
                    )
                    HorizontalDivider()
                }
                if (recordingActive) {
                    item(key = "recording") {
                        ListItem(
                            headlineContent = { Text("Recording a track") },
                            supportingContent = { Text("Open a map to see it, pause or stop.") },
                            leadingContent = { Icon(Icons.Filled.FiberManualRecord, null, tint = MapStyle.recordingTrack) },
                            modifier = if (onRecordingClick != null) Modifier.clickable(onClickLabel = "Open map", onClick = onRecordingClick) else Modifier,
                        )
                        HorizontalDivider()
                    }
                }
                items(sorted.filter { !it.archived && it.folder == null }, key = { it.id }) { row(it) }
                for (folder in folders) {
                    val inFolder = sorted.filter { !it.archived && it.folder == folder }
                    val open = folder !in collapsed
                    item(key = "folder:$folder") {
                        ListItem(
                            headlineContent = { Text(folder, style = MaterialTheme.typography.titleSmall) },
                            supportingContent = { Text(if (inFolder.size == 1) "1 map" else "${inFolder.size} maps") },
                            leadingContent = { Icon(Icons.Filled.Folder, null, tint = MaterialTheme.colorScheme.primary) },
                            trailingContent = { Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
                            modifier = Modifier.clickable(onClickLabel = if (open) "Collapse" else "Expand") {
                                collapsed = if (open) collapsed + folder else collapsed - folder
                            },
                        )
                        HorizontalDivider()
                    }
                    if (open) items(inFolder, key = { it.id }) { Box(Modifier.padding(start = 16.dp)) { Column { row(it) } } }
                }
                // Archived maps go last, in one section (their folders are kept for when they're unarchived).
                val archived = sorted.filter { it.archived }
                if (archived.isNotEmpty()) {
                    item(key = "archived") {
                        ListItem(
                            headlineContent = { Text("Archived", style = MaterialTheme.typography.titleSmall) },
                            supportingContent = { Text(if (archived.size == 1) "1 map" else "${archived.size} maps") },
                            leadingContent = { Icon(Icons.Filled.Archive, null, tint = MaterialTheme.colorScheme.onSurfaceVariant) },
                            trailingContent = { Icon(if (archiveOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null) },
                            modifier = Modifier.clickable(onClickLabel = if (archiveOpen) "Collapse" else "Expand") { archiveOpen = !archiveOpen },
                        )
                        HorizontalDivider()
                    }
                    if (archiveOpen) items(archived, key = { it.id }) { Box(Modifier.padding(start = 16.dp)) { Column { row(it) } } }
                }
            }
        }
    }

    renaming?.let { map ->
        var name by remember(map.id) { mutableStateOf(map.name) }
        AlertDialog(
            onDismissRequest = { renaming = null },
            title = { Text("Rename map") },
            text = { OutlinedTextField(name, { name = it }, singleLine = true) },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onRename(map.id, name.trim()); renaming = null }) { Text("Rename") }
            },
            dismissButton = { TextButton(onClick = { renaming = null }) { Text("Cancel") } },
        )
    }

    moving?.let { map ->
        var name by remember(map.id) { mutableStateOf(map.folder ?: "") }
        AlertDialog(
            onDismissRequest = { moving = null },
            title = { Text("Move to folder") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedTextField(
                        name, { name = it }, singleLine = true, label = { Text("Folder name") },
                        supportingText = { Text("Type a new name to create a folder") },
                    )
                    if (folders.isNotEmpty()) {
                        Text("Existing folders", style = MaterialTheme.typography.labelMedium)
                        for (f in folders) {
                            Row(
                                Modifier.fillMaxWidth().selectable(f == name, role = Role.RadioButton) { name = f },
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                RadioButton(selected = f == name, onClick = null)
                                Text(f, modifier = Modifier.padding(start = 8.dp, top = 12.dp, bottom = 12.dp))
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = name.isNotBlank(), onClick = { onMoveToFolder(map.id, name.trim()); moving = null }) { Text("Move") }
            },
            dismissButton = {
                Row {
                    if (map.folder != null) TextButton(onClick = { onMoveToFolder(map.id, null); moving = null }) { Text("Remove from folder") }
                    TextButton(onClick = { moving = null }) { Text("Cancel") }
                }
            },
        )
    }

    deleting?.let { map ->
        AlertDialog(
            onDismissRequest = { deleting = null },
            title = { Text("Delete map?") },
            text = { Text("\"${map.name}\" and its placemarks will be removed from this device.") },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(map.id); deleting = null },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) { Text("Delete") }
            },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("Cancel") } },
        )
    }
}

private fun statusText(map: MapEntry, me: LatLon?, units: Units): String {
    val geo = map.geo ?: return "No location information"
    if (me == null) return geo.crsName ?: "Georeferenced"
    val d = geo.distanceFromMap(me)
    return if (d == 0.0) "You are on this map" else "${Formats.distance(d, units)} away"
}

@Composable
private fun MapRow(
    map: MapEntry,
    thumbnail: File,
    status: String,
    onClick: () -> Unit,
    onRename: () -> Unit,
    onMove: () -> Unit,
    onSavePdf: () -> Unit,
    onToggleArchived: () -> Unit,
    onDelete: () -> Unit,
) {
    val image by produceState<ImageBitmap?>(null, thumbnail) {
        value = withContext(Dispatchers.IO) { BitmapFactory.decodeFile(thumbnail.path)?.asImageBitmap() }
    }
    var menu by remember { mutableStateOf(false) }
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = {
            Box(
                Modifier.size(56.dp).clip(MaterialTheme.shapes.small).background(MaterialTheme.colorScheme.surfaceContainer),
                contentAlignment = Alignment.Center,
            ) {
                image?.let { Image(it, null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize()) }
                    ?: Icon(Icons.Filled.Map, null)
            }
        },
        headlineContent = { Text(map.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
        supportingContent = { Text(status) },
        trailingContent = {
            Box {
                IconButton(onClick = { menu = true }) { Icon(Icons.Filled.MoreVert, "Options") }
                DropdownMenu(menu, onDismissRequest = { menu = false }) {
                    DropdownMenuItem(text = { Text("Rename") }, onClick = { menu = false; onRename() })
                    if (!map.archived) DropdownMenuItem(text = { Text("Move to folder…") }, onClick = { menu = false; onMove() })
                    DropdownMenuItem(text = { Text("Export PDF…") }, onClick = { menu = false; onSavePdf() })
                    DropdownMenuItem(
                        text = { Text(if (map.archived) "Unarchive" else "Archive") },
                        onClick = { menu = false; onToggleArchived() },
                    )
                    DropdownMenuItem(text = { Text("Delete") }, onClick = { menu = false; onDelete() })
                }
            }
        },
    )
}
