package org.geotrailmaps.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddLocationAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.MyLocation
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import android.content.res.Configuration
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.CoordinateFormat
import org.geotrailmaps.data.CoordinateParser
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureLayer
import org.geotrailmaps.data.Formats
import org.geotrailmaps.data.MarkerType
import org.geotrailmaps.data.Units
import org.geotrailmaps.geo.Geodesy
import org.geotrailmaps.geo.LatLon

/** Features whose name, notes, marker type or details contain [query] (at least 2 characters). */
fun searchMatches(query: String, features: List<Feature>, markerTypes: Map<String, MarkerType> = emptyMap()): List<Feature> {
    val q = query.trim()
    if (q.length < 2) return emptyList()
    return features.filter { f ->
        f.name.contains(q, ignoreCase = true) || f.description.contains(q, ignoreCase = true) ||
            f.markerType?.let { markerTypes[it]?.name }?.contains(q, ignoreCase = true) == true ||
            f.attributes.values.any { it.contains(q, ignoreCase = true) }
    }.take(50)
}

/**
 * The search box, shown in place of the toolbar in the bottom panel (same height), so opening
 * search doesn't rearrange the screen; the keyboard then pushes the panel up.
 */
@Composable
fun SearchPanel(query: String, onQueryChange: (String) -> Unit, onGo: () -> Unit, onClose: () -> Unit) {
    val focus = remember { FocusRequester() }
    LaunchedEffect(Unit) { focus.requestFocus() }
    Column(Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp)) {
        OutlinedTextField(
            value = query,
            onValueChange = onQueryChange,
            singleLine = true,
            leadingIcon = { Icon(Icons.Filled.Search, null) },
            trailingIcon = { IconButton(onClick = onClose) { Icon(Icons.Filled.Close, "Close search") } },
            placeholder = { Text("Coordinates or a name") },
            // Enter goes to the coordinate, or to the only match.
            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
            keyboardActions = KeyboardActions(onGo = { onGo() }),
            modifier = Modifier.fillMaxWidth().focusRequester(focus)
                .semantics { contentDescription = "Search coordinates or placemark names" },
        )
        Text(
            "e.g. 10.3241, -84.0215 · 10°19'27\"N 84°01'17\"W · 16P 830000 1142500",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, top = 6.dp),
        )
    }
}

/** Results card floating above the search panel; nothing until something is typed. */
@Composable
fun SearchResults(
    query: String,
    features: List<Feature>,
    layers: List<FeatureLayer>,
    markerTypes: Map<String, MarkerType>,
    myLocation: LatLon?,
    format: CoordinateFormat,
    units: Units,
    onGoTo: (LatLon) -> Unit,
    onAddPlacemark: (LatLon) -> Unit,
    onShow: (Feature) -> Unit,
    modifier: Modifier = Modifier,
) {
    if (query.isBlank()) return
    val coordinate = remember(query) { CoordinateParser.parse(query) }
    val matches = remember(query, features, markerTypes) { searchMatches(query, features, markerTypes) }
    val layerNames = layers.associate { it.id to it.name }
    val transparent = ListItemDefaults.colors(containerColor = Color.Transparent)
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.medium,
        color = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.onSurface,
        shadowElevation = 4.dp,
    ) {
        // Lower in landscape, where the keyboard leaves little room.
        val landscape = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
        LazyColumn(Modifier.heightIn(max = if (landscape) 120.dp else 240.dp)) {
            if (coordinate != null) {
                item(key = "coordinate") {
                    ListItem(
                        headlineContent = { Text(Formats.coordinate(coordinate, format), maxLines = 1, overflow = TextOverflow.Ellipsis) },
                        supportingContent = {
                            Text("Go to this coordinate" + (myLocation?.let { " · " + Formats.distance(Geodesy.distance(it, coordinate), units) + " away" } ?: ""))
                        },
                        leadingContent = { Icon(Icons.Filled.MyLocation, null, tint = MaterialTheme.colorScheme.primary) },
                        trailingContent = {
                            IconButton(onClick = { onAddPlacemark(coordinate) }) { Icon(Icons.Filled.AddLocationAlt, "Add a placemark here") }
                        },
                        colors = transparent,
                        modifier = Modifier.clickable(onClickLabel = "Go to") { onGoTo(coordinate) },
                    )
                }
            }
            items(matches, key = { it.id }) { f ->
                ListItem(
                    headlineContent = { Text(f.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                    supportingContent = {
                        Text(f.layerId?.let { layerNames[it] } ?: f.summary(units, format), maxLines = 1, overflow = TextOverflow.Ellipsis)
                    },
                    leadingContent = { Icon(f.type.icon(), null, tint = Color(f.color)) },
                    colors = transparent,
                    modifier = Modifier.clickable(onClickLabel = "Show on map") { onShow(f) },
                )
            }
            if (coordinate == null && matches.isEmpty()) {
                item {
                    Text(
                        if (query.trim().length < 2) "Keep typing…" else "No coordinate or placemark matches \"${query.trim()}\".",
                        style = MaterialTheme.typography.bodyMedium,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 12.dp),
                    )
                }
            }
        }
    }
}
