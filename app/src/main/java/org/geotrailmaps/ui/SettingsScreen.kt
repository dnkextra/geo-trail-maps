package org.geotrailmaps.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
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
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.CoordinateFormat
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.Settings
import org.geotrailmaps.data.Units
import org.geotrailmaps.geo.LatLon
import org.geotrailmaps.render.BasemapSource

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SettingsScreen(settings: Settings, onBack: () -> Unit) {
    BackHandler(onBack = onBack)
    val format by settings.coordinateFormat.collectAsState()
    val units by settings.units.collectAsState()
    val example = LatLon(49.2827, -123.1207)

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
            for (f in CoordinateFormat.entries) {
                Choice(f.label, Formats.coordinate(example, f), selected = f == format) { settings.setCoordinateFormat(f) }
            }
            SectionTitle("Units")
            for (u in Units.entries) {
                Choice(u.label, Formats.distance(1234.0, u), selected = u == units) { settings.setUnits(u) }
            }
            SectionTitle("About")
            Text(
                "Opens geospatial PDF maps (GeoPDFs), the format Avenza Maps imports and exports. Files in Avenza's own " +
                    ".avenzamap format are encrypted and can only be opened in Avenza Maps.\n\n" +
                    "The background map (chosen under Layers on any map) uses OpenStreetMap tiles, ${BasemapSource.OSM.credit}, " +
                    "or satellite imagery: ${BasemapSource.SATELLITE.credit}, licensed CC BY-NC-SA 4.0 for noncommercial use. " +
                    "Tiles are downloaded when online and cached; requests reveal the area you're viewing to the tile provider " +
                    "(openstreetmap.org or eox.at).",
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
private fun Choice(title: String, example: String, selected: Boolean, onClick: () -> Unit) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = { Text(example) },
        leadingContent = { RadioButton(selected = selected, onClick = null) },
        modifier = Modifier.selectable(selected = selected, role = Role.RadioButton, onClick = onClick),
    )
}
