package org.geotrailmaps.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.triStateToggleable
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SegmentedButton
import androidx.compose.material3.SegmentedButtonDefaults
import androidx.compose.material3.SingleChoiceSegmentedButtonRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TriStateCheckbox
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.state.ToggleableState
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.Feature
import org.geotrailmaps.data.FeatureLayer

/**
 * Picks a file name, a format and (when there's more than one) which features to download.
 * [onDownload] gets the name without extension, KML or not, and the chosen features in their original order.
 */
@Composable
fun ExportDialog(
    defaultName: String,
    features: List<Feature>,
    layers: List<FeatureLayer>,
    title: String = "Download features",
    /** GPX suits a single track; KML keeps layers as folders. */
    defaultKml: Boolean = true,
    onDownload: (name: String, kml: Boolean, features: List<Feature>) -> Unit,
    onDismiss: () -> Unit,
) {
    var name by rememberSaveable { mutableStateOf(defaultName) }
    var kml by rememberSaveable { mutableStateOf(defaultKml) }
    // The ones unticked, so a feature added while this is open (a track being recorded) is included.
    val unticked = remember { mutableStateMapOf<String, Boolean>() }
    val expanded = remember { mutableStateMapOf<String, Boolean>() }

    // Features drawn in the app (or in a layer that's gone) first, then each layer.
    val groups = remember(features, layers) {
        val known = layers.map { it.id }.toSet()
        val loose = features.filter { it.layerId == null || it.layerId !in known }
        (listOf("" to "My features") + layers.map { it.id to it.name })
            .map { (id, label) -> Triple(id, label, if (id == "") loose else features.filter { it.layerId == id }) }
            .filter { it.third.isNotEmpty() }
    }
    val picked = features.count { it.id !in unticked }
    fun setAll(items: List<Feature>, on: Boolean) = items.forEach { if (on) unticked.remove(it.id) else unticked[it.id] = true }
    val fileName = name.trim().replace(Regex("\\.(kml|gpx)$", RegexOption.IGNORE_CASE), "").trim()

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            // One scrolling list, header included: in landscape with the keyboard up there's
            // no room to give the features a list of their own.
            LazyColumn {
                item(key = "header") {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        OutlinedTextField(
                            value = name,
                            onValueChange = { name = it },
                            label = { Text("File name") },
                            suffix = { Text(if (kml) ".kml" else ".gpx") },
                            isError = fileName.isEmpty(),
                            supportingText = if (fileName.isEmpty()) ({ Text("Enter a file name") }) else null,
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(imeAction = ImeAction.Done),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        SingleChoiceSegmentedButtonRow(Modifier.fillMaxWidth()) {
                            listOf(true to "KML", false to "GPX").forEachIndexed { i, (isKml, label) ->
                                SegmentedButton(
                                    selected = kml == isKml,
                                    onClick = { kml = isKml },
                                    shape = SegmentedButtonDefaults.itemShape(i, 2),
                                ) { Text(label, maxLines = 1) }
                            }
                        }
                        if (features.size > 1) {
                            HorizontalDivider()
                            CheckRow(toggleState(picked, features.size), onClick = { setAll(features, picked < features.size) }) {
                                Text("$picked of ${features.size} selected", style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                if (features.size > 1) for ((id, label, items) in groups) {
                    val open = expanded[id] ?: (groups.size == 1)
                    item(key = "layer:$id") {
                        val inGroup = items.count { it.id !in unticked }
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CheckRow(
                                toggleState(inGroup, items.size),
                                onClick = { setAll(items, inGroup < items.size) },
                                modifier = Modifier.weight(1f),
                            ) {
                                Text(
                                    "$label ($inGroup/${items.size})",
                                    style = MaterialTheme.typography.titleSmall,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                )
                            }
                            IconButton(onClick = { expanded[id] = !open }) {
                                Icon(if (open) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore, if (open) "Collapse $label" else "Expand $label")
                            }
                        }
                    }
                    if (open) items(items, key = { it.id }) { f ->
                        val on = f.id !in unticked
                        CheckRow(ToggleableState(on), onClick = { setAll(listOf(f), !on) }, modifier = Modifier.padding(start = 24.dp)) {
                            Text(f.name.ifBlank { "Unnamed" }, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                enabled = fileName.isNotEmpty() && picked > 0,
                onClick = { onDownload(fileName, kml, features.filter { it.id !in unticked }) },
            ) { Text("Download") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** A checkbox and its label as one tap target, read by TalkBack as one checkbox. */
@Composable
private fun CheckRow(state: ToggleableState, onClick: () -> Unit, modifier: Modifier = Modifier, label: @Composable () -> Unit) {
    Row(
        modifier.fillMaxWidth().heightIn(min = 48.dp).triStateToggleable(state, role = Role.Checkbox, onClick = onClick),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TriStateCheckbox(state = state, onClick = null, modifier = Modifier.padding(horizontal = 12.dp))
        label()
    }
}

private fun toggleState(picked: Int, total: Int) = when (picked) {
    0 -> ToggleableState.Off
    total -> ToggleableState.On
    else -> ToggleableState.Indeterminate
}
