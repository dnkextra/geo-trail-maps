package org.geotrailmaps.ui.map

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.RadioButton
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.CenterFocusStrong
import androidx.compose.material.icons.filled.ContentCopy
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Hiking
import androidx.compose.material.icons.filled.Layers
import androidx.compose.material.icons.filled.Public
import org.geotrailmaps.data.LayerKind
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.DriveFileRenameOutline
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Place
import androidx.compose.material.icons.filled.Timeline
import androidx.compose.material.icons.outlined.Pentagon
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.BottomSheetDefaults
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Switch
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.SnapshotStateMap
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.window.DialogWindowProvider
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.CoordinateFormat
import org.geotrailmaps.render.BasemapSource
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureType
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.Trails
import org.geotrailmaps.data.Units
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.ui.ConfirmDeleteDialog
import org.geotrailmaps.ui.RenameDialog
import org.geotrailmaps.ui.theme.MapStyle
import java.util.Locale
import kotlin.math.roundToInt

fun FeatureType.icon(): ImageVector = when (this) {
    FeatureType.POINT -> Icons.Filled.Place
    FeatureType.LINE -> Icons.Filled.Timeline
    FeatureType.AREA -> Icons.Outlined.Pentagon
}

fun Feature.summary(units: Units, format: CoordinateFormat): String = when (type) {
    FeatureType.POINT -> Formats.coordinate(points.first(), format)
    FeatureType.LINE -> "Length ${Formats.distance(length, units)}" +
        (track?.durationMillis?.let { " · " + Formats.duration(it) } ?: "") +
        (track?.ascent?.takeIf { it >= 1 }?.let { " · ↑" + Formats.distance(it, units) } ?: "")
    FeatureType.AREA -> "Area ${Formats.area(area, units)} · Perimeter ${Formats.distance(length, units)}"
}

/** A point's emoji if it has one, else the symbol for its kind of feature in its colour. */
@Composable
fun FeatureIcon(type: FeatureType, icon: String?, color: Color, size: androidx.compose.ui.unit.Dp) {
    if (type == FeatureType.POINT && icon != null) {
        // No semantics, like the vector icon, so lists read the same with or without one.
        Box(Modifier.size(size).clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
            Text(icon, fontSize = with(androidx.compose.ui.platform.LocalDensity.current) { (size * 0.8f).toSp() }, maxLines = 1)
        }
    } else {
        Icon(type.icon(), null, Modifier.size(size), tint = color)
    }
}

/** Always decimal "lat, lon" so it pastes into other map apps. Android shows its own "Copied" confirmation. */
fun copyCoordinates(context: Context, p: LatLon) {
    context.getSystemService(ClipboardManager::class.java)
        .setPrimaryClip(ClipData.newPlainText("Coordinates", String.format(Locale.US, "%.6f, %.6f", p.lat, p.lon)))
}

/** Edit a feature's name, notes and colour. Changes are saved when the sheet closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureEditSheet(
    feature: Feature,
    units: Units,
    format: CoordinateFormat,
    /** Name of the imported layer the feature belongs to, if any. */
    layerName: String?,
    /** Start navigating to this placemark. */
    onNavigate: () -> Unit,
    /** Record more onto this track; null hides the button. */
    onContinueTrack: (() -> Unit)?,
    /** For lines: whether its layer already makes it a trail. */
    trailByLayer: Boolean,
    /** For lines: use it for trail distance by itself, or stop; null hides the switch. */
    onTrail: ((Boolean) -> Unit)?,
    /** For points: icons used lately, most recent first; [onIconUsed] when one is saved. */
    recentIcons: List<String>,
    onIconUsed: (String) -> Unit,
    onSave: (Feature) -> Unit,
    onDelete: () -> Unit,
    onDismiss: () -> Unit,
) {
    var name by remember(feature.id) { mutableStateOf(feature.name) }
    var description by remember(feature.id) { mutableStateOf(feature.description) }
    var color by remember(feature.id) { mutableIntStateOf(feature.color) }
    var icon by remember(feature.id) { mutableStateOf(feature.icon) }

    fun close() {
        val edited = feature.copy(name = name.ifBlank { feature.name }, description = description, color = color, icon = icon)
        if (edited != feature) onSave(edited)
        // Counted only when saved, so the recent row doesn't reorder under your finger.
        icon?.takeIf { it != feature.icon }?.let(onIconUsed)
        onDismiss()
    }

    ModalBottomSheet(onDismissRequest = ::close, sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)) {
        // Scrolls so the buttons stay reachable with long notes or the keyboard open in landscape.
        Column(Modifier.verticalScroll(rememberScrollState()).padding(horizontal = 20.dp).padding(bottom = 24.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                FeatureIcon(feature.type, icon, Color(color), 24.dp)
                Spacer(Modifier.size(8.dp))
                Text(feature.summary(units, format), style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                if (feature.type == FeatureType.POINT) {
                    val context = LocalContext.current
                    IconButton(onClick = { copyCoordinates(context, feature.points.first()) }) { Icon(Icons.Filled.ContentCopy, "Copy coordinates") }
                }
            }
            if (layerName != null) {
                Text("In layer \"$layerName\"", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            OutlinedTextField(name, { name = it }, label = { Text("Name") }, singleLine = true, modifier = Modifier.fillMaxWidth())
            OutlinedTextField(description, { description = it }, label = { Text("Notes") }, minLines = 2, modifier = Modifier.fillMaxWidth())
            // Keyed so a different point gets its own row state, like the fields above.
            if (feature.type == FeatureType.POINT) key(feature.id) { IconRow(icon, recentIcons) { icon = it } }
            Row(Modifier.fillMaxWidth()) {
                for ((colorName, c) in MapStyle.featureColors) {
                    val argb = c.toArgb()
                    val selected = argb == color
                    Box(
                        Modifier
                            .weight(1f)
                            .height(48.dp)
                            .selectable(selected, role = Role.RadioButton) { color = argb }
                            .semantics { contentDescription = colorName },
                        contentAlignment = Alignment.Center,
                    ) {
                        Box(
                            Modifier
                                .size(32.dp)
                                .background(c, CircleShape)
                                .border(1.dp, MaterialTheme.colorScheme.outline, CircleShape),
                        )
                        if (selected) Icon(Icons.Filled.Check, null, tint = contentColorOn(c))
                    }
                }
            }
            if (onTrail != null) {
                val on = trailByLayer || feature.trail
                Row(
                    Modifier.fillMaxWidth().toggleable(on, enabled = !trailByLayer, role = Role.Switch, onValueChange = onTrail),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Filled.Hiking, null, tint = if (on) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant)
                    Spacer(Modifier.size(12.dp))
                    Column(Modifier.weight(1f)) {
                        Text("Use as trail")
                        Text(
                            when {
                                trailByLayer -> "Its layer is used for trail distance."
                                on -> "Counts when you navigate along trails, even while it's hidden."
                                else -> "Count it when you navigate along trails."
                            },
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                    Switch(checked = on, onCheckedChange = null, enabled = !trailByLayer)
                }
            }
            if (onContinueTrack != null) {
                OutlinedButton(onClick = { close(); onContinueTrack() }, modifier = Modifier.fillMaxWidth()) {
                    Icon(Icons.Filled.FiberManualRecord, null, Modifier.size(18.dp), tint = MapStyle.recordingTrack)
                    Spacer(Modifier.size(6.dp))
                    Text("Continue recording this track")
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(
                    onClick = { onDelete(); onDismiss() },
                    colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
                ) {
                    Icon(Icons.Filled.Delete, null)
                    Spacer(Modifier.size(4.dp))
                    Text("Delete")
                }
                Spacer(Modifier.weight(1f))
                if (feature.type == FeatureType.POINT) {
                    OutlinedButton(onClick = { close(); onNavigate() }) {
                        Icon(Icons.Filled.Navigation, null, Modifier.size(18.dp))
                        Spacer(Modifier.size(6.dp))
                        Text("Navigate")
                    }
                    Spacer(Modifier.size(8.dp))
                }
                TextButton(onClick = ::close) { Text("Done") }
            }
        }
    }
}

/** Actions offered by the Layers & features list. */
class FeatureListActions(
    val onImport: () -> Unit,
    val onAddTrails: () -> Unit,
    val onShow: (Feature) -> Unit,
    val onEdit: (Feature) -> Unit,
    val onDelete: (Feature) -> Unit,
    val onShowLayer: (FeatureLayer) -> Unit,
    val onRenameLayer: (FeatureLayer, String) -> Unit,
    val onDeleteLayer: (FeatureLayer) -> Unit,
    val onLayerVisible: (FeatureLayer, Boolean) -> Unit,
    /** Turns trail distance on for a layer (for the lines in the given folders, or all) or off. */
    val onLayerTrails: (FeatureLayer, Boolean, Set<String>?) -> Unit,
    /** Shows a layer on every map it overlaps, or only on this one. */
    val onLayerGlobal: (FeatureLayer, Boolean) -> Unit,
    val onBasemap: (BasemapSource?) -> Unit,
    /** Called while the slider moves; [onSeeThroughDone] when it's let go. */
    val onSeeThrough: (Float) -> Unit,
    val onSeeThroughDone: () -> Unit,
    val onFeatureVisible: (Feature, Boolean) -> Unit,
)

/**
 * The Layers sheet, like QField's layer list: the background map, "My features" (drawn in the app)
 * and one collapsible group per imported file, each with its actions as icons.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun FeatureListSheet(
    features: List<Feature>,
    layers: List<FeatureLayer>,
    basemap: BasemapSource?,
    /** Every background map that can be chosen. */
    basemaps: List<BasemapSource>,
    /** This map's PDF over the background: see [org.geotrailmaps.data.MapEntry.seeThrough]. */
    seeThrough: Float,
    canSeeThrough: Boolean,
    myLocation: LatLon?,
    units: Units,
    format: CoordinateFormat,
    /** Expanded groups by layer id ("mine" for My features), kept by the caller across openings. */
    expanded: SnapshotStateMap<String, Boolean>,
    actions: FeatureListActions,
    onDismiss: () -> Unit,
) {
    var renaming by remember { mutableStateOf<FeatureLayer?>(null) }
    var choosingTrails by remember { mutableStateOf<FeatureLayer?>(null) }
    var deleting by remember { mutableStateOf<Pair<String, () -> Unit>?>(null) }
    var choosingBasemap by remember { mutableStateOf(false) }
    val mine = features.filter { it.layerId == null }.sortedByDescending { it.createdAt }
    val byLayer = features.filter { it.layerId != null }.groupBy { it.layerId }
    val uriHandler = LocalUriHandler.current
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)

    @Composable
    fun FeatureRow(f: Feature, layerVisible: Boolean = true) {
        val distance = if (f.type == FeatureType.POINT && myLocation != null) {
            Formats.distance(Geodesy.distance(myLocation, f.points.first()), units) + " away"
        } else null
        // Hidden features: the text dims, the controls stay at full strength.
        // Dimmed when the feature or its whole layer is hidden.
        val textAlpha = if (f.visible && layerVisible) 1f else 0.6f
        ListItem(
            headlineContent = {
                Row(Modifier.alpha(textAlpha), verticalAlignment = Alignment.CenterVertically) {
                    FeatureIcon(f.type, f.icon, Color(f.color), 16.dp)
                    Spacer(Modifier.size(6.dp))
                    Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            },
            supportingContent = {
                Text(
                    distance ?: f.summary(units, format),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.alpha(textAlpha),
                )
            },
            leadingContent = {
                Checkbox(
                    checked = f.visible,
                    onCheckedChange = { actions.onFeatureVisible(f, it) },
                    colors = Color(f.color).let { c ->
                        CheckboxDefaults.colors(
                            checkedColor = c,
                            uncheckedColor = MaterialTheme.colorScheme.outline,
                            checkmarkColor = contentColorOn(c),
                        )
                    },
                    modifier = Modifier.semantics { contentDescription = "Show ${f.name}" },
                )
            },
            colors = transparent,
            trailingContent = {
                Row {
                    SmallIconButton(Icons.Filled.CenterFocusStrong, "Show ${f.name} on map") { actions.onShow(f) }
                    SmallIconButton(Icons.Filled.Edit, "Edit ${f.name}") { actions.onEdit(f) }
                    SmallIconButton(Icons.Filled.Delete, "Delete ${f.name}") { deleting = "Delete \"${f.name}\"?" to { actions.onDelete(f) } }
                }
            },
            modifier = Modifier
                .clickable(onClickLabel = "Edit") { actions.onEdit(f) }
                .padding(start = 12.dp),
        )
    }

    // No dimming while the background map is being changed, so the see-through can be judged.
    ModalBottomSheet(onDismissRequest = onDismiss, scrimColor = if (choosingBasemap) Color.Transparent else BottomSheetDefaults.ScrimColor) {
        Row(Modifier.fillMaxWidth().padding(start = 20.dp, end = 12.dp), verticalAlignment = Alignment.CenterVertically) {
            Text("Layers", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
            // No icons: both fit beside the title on a narrow phone.
            TextButton(onClick = actions.onAddTrails) { Text("Add trails") }
            TextButton(onClick = actions.onImport) { Text("Import") }
        }
        LazyColumn(Modifier.padding(bottom = 16.dp)) {
            // One line: the choice, credits and see-through are in a dialog, so they don't crowd the layers.
            item(key = "basemap") {
                val percent = (seeThrough * 100).roundToInt()
                ListItem(
                    headlineContent = { Text("Background map", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = {
                        Text(
                            when {
                                basemap == null -> "None"
                                canSeeThrough && percent > 0 -> "${basemap.label} · $percent% through the map"
                                else -> basemap.label
                            },
                        )
                    },
                    leadingContent = { Icon(Icons.Filled.Map, null, tint = MaterialTheme.colorScheme.primary) },
                    trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                    colors = transparent,
                    modifier = Modifier.clickable(onClickLabel = "Change", role = Role.Button) { choosingBasemap = true },
                )
            }
            val mineOpen = expanded["mine"] != false
            item(key = "mine") {
                ListItem(
                    headlineContent = { Text("My features", style = MaterialTheme.typography.titleSmall) },
                    supportingContent = {
                        if (mine.isEmpty()) {
                            Text("Add points, lines and areas with the + button")
                        } else {
                            // The open/closed arrow beside the count, as for the imported layers.
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Text(countText(mine.size))
                                Icon(if (mineOpen) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
                            }
                        }
                    },
                    leadingContent = { Icon(Icons.Filled.Layers, null, tint = MaterialTheme.colorScheme.primary) },
                    colors = transparent,
                    modifier = if (mine.isEmpty()) Modifier else Modifier
                        .clickable(onClickLabel = if (mineOpen) "Collapse" else "Expand") { expanded["mine"] = !mineOpen }
                        .semantics { stateDescription = if (mineOpen) "Expanded" else "Collapsed" },
                )
            }
            if (mineOpen) items(mine, key = { it.id }) { FeatureRow(it) }
            for (layer in layers) {
                val items = byLayer[layer.id].orEmpty()
                val open = expanded[layer.id] == true
                val lineFolders = Trails.folders(layer, items)
                item(key = "layer-${layer.id}") {
                    ListItem(
                        // Only the text dims for a hidden layer; its controls stay at full strength.
                        headlineContent = {
                            Text(
                                layer.name,
                                style = MaterialTheme.typography.titleSmall,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.alpha(if (layer.visible) 1f else 0.6f),
                            )
                        },
                        supportingContent = {
                            Row(Modifier.alpha(if (layer.visible) 1f else 0.6f), verticalAlignment = Alignment.CenterVertically) {
                                Text((if (layer.visible) "" else "Hidden · ") + (if (layer.global) "On all maps · " else "") + (if (layer.trails) "Trails · " else "") + countText(items.size))
                                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, null, Modifier.size(18.dp))
                            }
                        },
                        leadingContent = {
                            Checkbox(
                                checked = layer.visible,
                                onCheckedChange = { actions.onLayerVisible(layer, it) },
                                colors = Color(layer.color).let { c ->
                                    CheckboxDefaults.colors(
                                        checkedColor = c,
                                        uncheckedColor = c,
                                        checkmarkColor = contentColorOn(c),
                                    )
                                },
                                modifier = Modifier.semantics { contentDescription = "Show ${layer.name}" },
                            )
                        },
                        trailingContent = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (lineFolders.isNotEmpty()) {
                                    SmallIconButton(
                                        Icons.Filled.Hiking, "Trail distance with ${layer.name}",
                                        tint = if (layer.trails) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f),
                                        state = if (layer.trails) "On" else "Off",
                                    ) {
                                        // Lines in folders: choose which; otherwise it's all of them.
                                        if (lineFolders.keys == setOf("")) actions.onLayerTrails(layer, !layer.trails, null) else choosingTrails = layer
                                    }
                                }
                                SmallIconButton(Icons.Filled.CenterFocusStrong, "Show ${layer.name} on map") { actions.onShowLayer(layer) }
                                // The rest in a menu, so the name keeps its room on narrow screens.
                                Box {
                                    var menu by remember { mutableStateOf(false) }
                                    SmallIconButton(Icons.Filled.MoreVert, "More for ${layer.name}") { menu = true }
                                    DropdownMenu(menu, onDismissRequest = { menu = false }) {
                                        // Only imported files: recorded tracks always stay on all maps (new recordings
                                        // are saved there), and trail layers stay with their map.
                                        if (layer.kind == LayerKind.IMPORTED) DropdownMenuItem(
                                            text = { Text("Show on all maps") },
                                            leadingIcon = { Icon(Icons.Filled.Public, null) },
                                            trailingIcon = { if (layer.global) Icon(Icons.Filled.Check, null) },
                                            modifier = Modifier.semantics { stateDescription = if (layer.global) "On" else "Off" },
                                            onClick = { menu = false; actions.onLayerGlobal(layer, !layer.global) },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Rename") },
                                            leadingIcon = { Icon(Icons.Filled.DriveFileRenameOutline, null) },
                                            onClick = { menu = false; renaming = layer },
                                        )
                                        DropdownMenuItem(
                                            text = { Text("Delete") },
                                            leadingIcon = { Icon(Icons.Filled.Delete, null) },
                                            onClick = {
                                                menu = false
                                                deleting = (
                                                    if (layer.global) "Delete \"${layer.name}\" and everything in it from every map? This can't be undone."
                                                    else "Delete \"${layer.name}\" and its ${countText(items.size)}? This can't be undone."
                                                    ) to { actions.onDeleteLayer(layer) }
                                            },
                                        )
                                    }
                                }
                            }
                        },
                        colors = transparent,
                        modifier = Modifier
                            .clickable(onClickLabel = if (open) "Collapse" else "Expand") { expanded[layer.id] = !open }
                            .semantics { stateDescription = if (open) "Expanded" else "Collapsed" },
                    )
                }
                if (open) items(items, key = { it.id }) { FeatureRow(it, layerVisible = layer.visible) }
            }
        }
    }

    if (choosingBasemap) {
        AlertDialog(
            onDismissRequest = { choosingBasemap = false },
            title = { Text("Background map") },
            text = {
                (LocalView.current.parent as? DialogWindowProvider)?.window?.let { w -> SideEffect { w.setDimAmount(0f) } }
                Column(Modifier.verticalScroll(rememberScrollState())) {
                    // "OpenStreetMap" doesn't fit a segmented button; the list has room for full names.
                    val segmented = basemaps.size <= 2
                    val choices = listOf(null to "None") + basemaps.map { it to if (segmented && it == BasemapSource.OSM) "OSM" else it.label }
                    if (segmented) {
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            choices.forEachIndexed { i, (source, label) ->
                                SegmentedButton(
                                    selected = basemap == source,
                                    onClick = { actions.onBasemap(source) },
                                    shape = SegmentedButtonDefaults.itemShape(i, choices.size),
                                    // No check mark: the fill shows the choice, and "Satellite" needs the room.
                                    icon = {},
                                    colors = SegmentedButtonDefaults.colors(
                                        activeContainerColor = MaterialTheme.colorScheme.primaryContainer,
                                        activeContentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                                    ),
                                ) { Text(label, maxLines = 1, overflow = TextOverflow.Ellipsis) }
                            }
                        }
                    } else {
                        // With sources added in Settings there are too many for one row of buttons.
                        Column(Modifier.selectableGroup()) {
                            for ((source, label) in choices) {
                                Row(
                                    Modifier.fillMaxWidth()
                                        .selectable(selected = basemap == source, role = Role.RadioButton) { actions.onBasemap(source) }
                                        .heightIn(min = 48.dp)
                                        .padding(vertical = 8.dp),
                                    verticalAlignment = Alignment.CenterVertically,
                                ) {
                                    RadioButton(selected = basemap == source, onClick = null)
                                    Text(label, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(start = 12.dp))
                                }
                            }
                        }
                    }
                    if (basemap != null) {
                        Text(
                            "Online. Areas you've viewed are kept for a while and may show offline; others won't.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                            modifier = Modifier.padding(top = 8.dp),
                        )
                        Row(Modifier.padding(top = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                basemap.credit,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.weight(1f),
                            )
                            basemap.creditUrl?.let { url ->
                                IconButton(onClick = { uriHandler.openUri(url) }) {
                                    Icon(Icons.AutoMirrored.Filled.OpenInNew, "${basemap.label} credits and licence")
                                }
                            }
                        }
                    }
                    if (basemap != null && canSeeThrough) {
                        // How much of the background shows through this PDF's paper colour.
                        val percent = "${(seeThrough * 100).roundToInt()}%"
                        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Text("Show background through map", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                            Text(percent, style = MaterialTheme.typography.bodyMedium)
                        }
                        Slider(
                            value = seeThrough,
                            onValueChange = actions.onSeeThrough,
                            onValueChangeFinished = actions.onSeeThroughDone,
                            // 5% steps, without the 19 tick dots they'd draw.
                            steps = 19,
                            colors = SliderDefaults.colors(activeTickColor = Color.Transparent, inactiveTickColor = Color.Transparent),
                            modifier = Modifier.semantics {
                                contentDescription = "Show background through map"
                                stateDescription = percent
                            },
                        )
                    }
                }
            },
            confirmButton = { TextButton(onClick = { choosingBasemap = false }) { Text("Done") } },
        )
    }

    renaming?.let { layer ->
        RenameDialog("Rename layer", layer.name, onRename = { actions.onRenameLayer(layer, it); renaming = null }, onDismiss = { renaming = null })
    }

    choosingTrails?.let { layer ->
        val folders = Trails.folders(layer, features)
        var ticked by remember(layer.id) {
            mutableStateOf(layer.trailFolders?.intersect(folders.keys) ?: if (layer.trails) folders.keys else Trails.suggested(folders.keys))
        }
        AlertDialog(
            onDismissRequest = { choosingTrails = null },
            title = { Text("Trail distance") },
            text = {
                Column {
                    Text("Lines in the ticked folders count as trails when you navigate along trails.")
                    Spacer(Modifier.height(8.dp))
                    Column(Modifier.heightIn(max = 320.dp).verticalScroll(rememberScrollState())) {
                        for ((folder, count) in folders) {
                            val on = folder in ticked
                            Row(
                                Modifier.fillMaxWidth()
                                    .toggleable(on, role = Role.Checkbox) { ticked = if (it) ticked + folder else ticked - folder }
                                    .padding(vertical = 4.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Checkbox(on, onCheckedChange = null)
                                Spacer(Modifier.width(12.dp))
                                Column {
                                    Text(folder.ifEmpty { "Not in a folder" })
                                    Text(
                                        if (count == 1) "1 line" else "$count lines",
                                        style = MaterialTheme.typography.bodySmall,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = {
                TextButton(enabled = ticked.isNotEmpty(), onClick = {
                    actions.onLayerTrails(layer, true, ticked.takeIf { it != folders.keys })
                    choosingTrails = null
                }) { Text("Use") }
            },
            dismissButton = {
                Row {
                    if (layer.trails) TextButton(onClick = { actions.onLayerTrails(layer, false, null); choosingTrails = null }) { Text("Turn off") }
                    TextButton(onClick = { choosingTrails = null }) { Text("Cancel") }
                }
            },
        )
    }

    deleting?.let { (question, apply) ->
        ConfirmDeleteDialog(title = question, onDelete = { apply(); deleting = null }, onDismiss = { deleting = null })
    }
}

/** Black or white, whichever reads better on [c]. */
private fun contentColorOn(c: Color) = if (c.luminance() > 0.5f) Color.Black else Color.White

internal fun countText(n: Int) = if (n == 1) "1 item" else "$n items"

/** Compact icon button so a row of actions leaves room for the name. */
@Composable
private fun SmallIconButton(
    icon: ImageVector,
    description: String,
    tint: Color = LocalContentColor.current,
    /** For a button that shows a setting: read out after its description. */
    state: String? = null,
    onClick: () -> Unit,
) {
    IconButton(
        onClick = onClick,
        modifier = Modifier.size(40.dp).then(if (state != null) Modifier.semantics { stateDescription = state } else Modifier),
    ) { Icon(icon, description, Modifier.size(20.dp), tint = tint) }
}
