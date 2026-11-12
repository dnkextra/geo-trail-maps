package org.geotrailmaps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.error
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import org.geotrailmaps.data.CoordinateFormat
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.Settings
import org.geotrailmaps.data.Units
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.render.BasemapSource
import org.geotrailmaps.render.CustomBasemap
import org.geotrailmaps.render.Tile
import java.util.UUID

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    var showLicenses by rememberSaveable { mutableStateOf(false) }
    val uriHandler = LocalUriHandler.current
    if (showLicenses) {
        LicensesScreen(onBack = { showLicenses = false })
        return
    }
    BackHandler(onBack = onBack)
    val format by settings.coordinateFormat.collectAsState()
    val units by settings.units.collectAsState()
    val defaultBasemap by settings.defaultBasemap.collectAsState()
    val basemaps by settings.basemaps.collectAsState()
    val customBasemaps by settings.customBasemaps.collectAsState()
    var adding by rememberSaveable { mutableStateOf(false) }
    // The id, so the dialog survives rotation.
    var editingId by rememberSaveable { mutableStateOf<String?>(null) }
    val editing = customBasemaps.firstOrNull { it.id == editingId }
    val example = LatLon(49.2827, -123.1207)

    if (adding || editing != null) {
        val close = { adding = false; editingId = null }
        CustomBasemapDialog(
            initial = editing,
            onSave = { settings.saveCustomBasemap(it); close() },
            onDelete = { settings.deleteCustomBasemap(it.id); close() },
            onDismiss = close,
        )
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = { IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "Back") } },
            )
        },
    ) { padding ->
        Column(Modifier.padding(padding).verticalScroll(rememberScrollState())) {
            SectionTitle("Coordinate format")
            Column(Modifier.selectableGroup()) {
                for (f in CoordinateFormat.entries) {
                    Choice(f.label, Formats.coordinate(example, f), selected = f == format) { settings.setCoordinateFormat(f) }
                }
            }
            SectionTitle("Units")
            Column(Modifier.selectableGroup()) {
                for (u in Units.entries) {
                    Choice(u.label, Formats.distance(1234.0, u), selected = u == units) { settings.setUnits(u) }
                }
            }
            SectionTitle("Background map for new maps")
            Column(Modifier.selectableGroup()) {
                Choice("None", "New maps show no background", selected = defaultBasemap == null) { settings.setDefaultBasemap(null) }
                for (b in basemaps) {
                    val custom = customBasemaps.firstOrNull { it.id == b.id }
                    Choice(
                        b.label,
                        "Downloads tiles from ${b.host}",
                        selected = b == defaultBasemap,
                        trailing = custom?.let { { IconButton(onClick = { editingId = it.id }) { Icon(Icons.Filled.Edit, "Edit ${b.label}") } } },
                    ) { settings.setDefaultBasemap(b) }
                }
            }
            Text(
                "Used the first time you open a map. Maps you've already opened keep their own; change those under Layers.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Add a map source") },
                supportingContent = { Text("Use tiles from another provider as a background map") },
                leadingContent = { Icon(Icons.Filled.Add, null) },
                modifier = Modifier.clickable { adding = true },
            )
            SectionTitle("About")
            Text(
                "Opens geospatial PDF maps (GeoPDFs), the format Avenza Maps imports and exports. Files in Avenza's own " +
                    ".avenzamap format are encrypted and can only be opened in Avenza Maps.\n\n" +
                    "The background map (default above, or under Layers on any map) uses OpenStreetMap tiles, ${BasemapSource.OSM.credit}, " +
                    "satellite imagery: ${BasemapSource.SATELLITE.credit}, licensed CC BY-NC-SA 4.0 for noncommercial use, or a source you add. " +
                    "Tiles are downloaded when online and cached; requests reveal the area you're viewing to the tile provider " +
                    "(openstreetmap.org, eox.at, or the source you added).",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            ListItem(
                headlineContent = { Text("Open-source licenses") },
                supportingContent = { Text("License and notices for this app and its components") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight, null) },
                modifier = Modifier.clickable { showLicenses = true },
            )
            ListItem(
                headlineContent = { Text("Privacy policy") },
                supportingContent = { Text("Opens on GitHub") },
                trailingContent = { Icon(Icons.AutoMirrored.Filled.OpenInNew, null) },
                modifier = Modifier.clickable { uriHandler.openUri(PRIVACY_POLICY_URL) },
            )
            SectionTitle("Routes")
            Text(
                "$ROUTE_NOTICE_TITLE\n\n$ROUTE_NOTICE_TEXT",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            SectionTitle("Your data")
            Text(
                "Maps, tracks and features are stored on this phone. Android's backup may include them, but large maps " +
                    "can exceed its size limit, so don't rely on it. Before uninstalling or changing phones, use each " +
                    "map's More menu to export its features and its PDF.",
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
        }
    }
}

@Composable
private fun SectionTitle(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.titleSmall,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(start = 16.dp, top = 20.dp, bottom = 4.dp),
    )
}

@Composable
private fun Choice(title: String, example: String, selected: Boolean, trailing: (@Composable () -> Unit)? = null, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(example) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        trailingContent = trailing,
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}

/** Adds a background map source, or edits [initial]. */
@Composable
private fun CustomBasemapDialog(
    initial: CustomBasemap?,
    onSave: (CustomBasemap) -> Unit,
    onDelete: (CustomBasemap) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(initial?.name.orEmpty()) }
    var url by rememberSaveable { mutableStateOf(initial?.urlTemplate.orEmpty()) }
    var attribution by rememberSaveable { mutableStateOf(initial?.attribution.orEmpty()) }
    var maxZoom by rememberSaveable { mutableStateOf((initial?.maxZoom ?: Tile.MAX_ZOOM).toString()) }
    val urlError = BasemapSource.templateError(url)
    // No complaint while it's empty or "https://" is still being typed.
    val shownUrlError = urlError.takeUnless { "https://".startsWith(url) }
    val zoom = maxZoom.toIntOrNull()?.takeIf { it in 0..Tile.MAX_ZOOM }

    AlertDialog(
        onDismissRequest = onDismiss,
        // A stray tap outside shouldn't throw away a pasted address.
        properties = DialogProperties(dismissOnClickOutside = false),
        title = { Text(if (initial == null) "Add a map source" else "Edit map source") },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Name") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it.filterNot(Char::isWhitespace) },
                    label = { Text("Tile address") },
                    isError = shownUrlError != null,
                    // The example stays up with an error: that's when it's needed.
                    supportingText = { Text("${shownUrlError ?: "With {z}, {x} and {y} where the tile numbers go"}. Example: $EXAMPLE_TILE_ADDRESS") },
                    maxLines = 4,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri, autoCorrectEnabled = false, imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp).semantics { shownUrlError?.let { error(it) } },
                )
                OutlinedTextField(
                    value = attribution,
                    // One paragraph: it's drawn on the map in a small chip.
                    onValueChange = { attribution = it.replace('\n', ' ') },
                    label = { Text("Credit (optional)") },
                    supportingText = { Text("Shown on the map. Most providers require one.") },
                    maxLines = 3,
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Next),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                OutlinedTextField(
                    value = maxZoom,
                    onValueChange = { maxZoom = it.filter(Char::isDigit).take(2) },
                    label = { Text("Deepest zoom level") },
                    isError = zoom == null,
                    supportingText = { Text("0 to ${Tile.MAX_ZOOM}. Lower it if the background goes blank when you zoom in.") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number, imeAction = ImeAction.Done),
                    modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                )
                Text(
                    "Tiles are downloaded straight from this address, which shows its operator the area you're viewing. " +
                        "You're responsible for following the source's terms of use.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }
        },
        confirmButton = {
            TextButton(
                enabled = name.isNotBlank() && urlError == null && zoom != null,
                onClick = {
                    onSave(CustomBasemap(initial?.id ?: "custom-${UUID.randomUUID()}", name.trim(), url, attribution.trim(), zoom ?: Tile.MAX_ZOOM))
                },
            ) { Text("Save") }
        },
        dismissButton = {
            Row {
                if (initial != null) {
                    TextButton(onClick = { onDelete(initial) }) { Text("Delete", color = MaterialTheme.colorScheme.error) }
                }
                TextButton(onClick = onDismiss) { Text("Cancel") }
            }
        },
    )
}

private const val EXAMPLE_TILE_ADDRESS = "https://example.com/{z}/{x}/{y}.png"
private const val PRIVACY_POLICY_URL = "https://github.com/dnkextra/geo-trail-maps/blob/main/PRIVACY.md"
