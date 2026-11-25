package org.geotrailmaps.ui

import android.content.Context
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.FileOpen
import androidx.compose.material.icons.filled.Pets
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.geotrailmaps.data.MarkerSet
import org.geotrailmaps.data.MarkerTypesException
import org.geotrailmaps.data.Settings
import org.geotrailmaps.data.displayName
import org.geotrailmaps.ui.map.MarkerBadge

private const val MAX_FILE_BYTES = 1_000_000
private const val EXAMPLE_ASSET = "marker-types/wildlife.json"

/**
 * Settings → Marker types: import a file of types (offered by + on every map), see what each
 * set holds, and delete a set. Importing a set again (same id) replaces it.
 */
@Composable
fun MarkerTypesSection(settings: Settings) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val sets by settings.markerSets.collectAsState()
    var result by remember { mutableStateOf<Pair<String, String>?>(null) }
    var deleting by remember { mutableStateOf<MarkerSet?>(null) }

    fun import(read: suspend () -> Pair<String, String>) = scope.launch {
        result = try {
            val (text, name) = read()
            val (set, replaced) = settings.importMarkerSet(text, name)
            (if (replaced) "Updated \"${set.name}\"" else "Added \"${set.name}\"") to
                "${set.types.size} ${if (set.types.size == 1) "type" else "types"}: ${set.types.joinToString(", ") { it.name }}.\n\n" +
                "Choose them when you add a point with +, or change a placemark's type in its sheet."
        } catch (e: MarkerTypesException) {
            "Couldn't import marker types" to (e.message ?: "The file couldn't be read.")
        } catch (e: Exception) {
            "Couldn't import marker types" to "The file couldn't be opened."
        }
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) import { readText(context, uri) }
    }

    result?.let { (title, text) ->
        AlertDialog(
            onDismissRequest = { result = null },
            title = { Text(title) },
            text = { Text(text) },
            confirmButton = { TextButton(onClick = { result = null }) { Text("OK") } },
        )
    }
    deleting?.let { set ->
        ConfirmDeleteDialog(
            title = "Delete \"${set.name}\"?",
            text = "Its types are no longer offered. Markers already placed keep their details and colour, and show as plain pins.",
            onDelete = { settings.deleteMarkerSet(set.id); deleting = null },
            onDismiss = { deleting = null },
        )
    }

    Text(
        "Kinds of point to record, such as wildlife sightings, each with its own icon and details to fill in. " +
            "They're offered when you add a point on any map, and you can show just some kinds on the map under Layers.",
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
    )
    for (set in sets) {
        ListItem(
            headlineContent = { Text(set.name) },
            supportingContent = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(set.types.joinToString(", ") { it.name })
                    Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                        for (t in set.types.take(8)) MarkerBadge(t)
                    }
                }
            },
            trailingContent = { IconButton(onClick = { deleting = set }) { Icon(Icons.Filled.Delete, "Delete ${set.name}") } },
        )
    }
    ListItem(
        headlineContent = { Text("Import marker types") },
        supportingContent = { Text("A .json file of types, with their icons and details") },
        leadingContent = { Icon(Icons.Filled.FileOpen, null) },
        // JSON files are often labelled as plain text or not at all, so anything can be picked.
        modifier = Modifier.clickable { launcher.launch(arrayOf("application/json", "text/*", "application/octet-stream")) },
    )
    if (sets.none { it.id == "wildlife" }) {
        ListItem(
            headlineContent = { Text("Add the Wildlife example") },
            supportingContent = { Text("Snakes, bear sign, deer, birds and plants, to use or as a starting point") },
            leadingContent = { Icon(Icons.Filled.Pets, null) },
            modifier = Modifier.clickable {
                import { withContext(Dispatchers.IO) { context.assets.open(EXAMPLE_ASSET).use { it.readBytes().decodeToString() } } to "Wildlife" }
            },
        )
    }
}

private suspend fun readText(context: Context, uri: Uri): Pair<String, String> = withContext(Dispatchers.IO) {
    val bytes = context.contentResolver.openInputStream(uri)?.use { it.readNBytes(MAX_FILE_BYTES + 1) }
        ?: throw MarkerTypesException("The file couldn't be opened.")
    if (bytes.size > MAX_FILE_BYTES) throw MarkerTypesException("The file is too large for marker types (over 1 MB).")
    bytes.decodeToString() to (context.displayName(uri)?.substringBeforeLast('.') ?: "Marker types")
}
