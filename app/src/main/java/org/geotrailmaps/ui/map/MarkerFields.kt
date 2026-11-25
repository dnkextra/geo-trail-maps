package org.geotrailmaps.ui.map

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ArrowDropDown
import androidx.compose.material.icons.filled.Remove
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.FieldKind
import org.geotrailmaps.data.MarkerField
import org.geotrailmaps.data.MarkerType
import org.geotrailmaps.ui.theme.MapStyle

/**
 * The point's marker type, with a menu to change it. [typeId] may name a type that's been
 * deleted: it's shown as such and can be changed but not chosen again.
 */
@Composable
fun MarkerTypePicker(typeId: String?, types: Map<String, MarkerType>, onChange: (MarkerType?) -> Unit) {
    var open by remember { mutableStateOf(false) }
    val type = typeId?.let(types::get)
    Box {
        Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Change type", role = Role.DropdownList) { open = true }.padding(vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            MarkerBadge(type, size = 28.dp, plainColor = MapStyle.placemarkColor)
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text("Type", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Text(
                    when {
                        type != null -> type.name
                        typeId != null -> "${typeId.substringAfter('/')} (type removed)"
                        else -> "Placemark"
                    },
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Icon(Icons.Filled.ArrowDropDown, null)
        }
        DropdownMenu(open, onDismissRequest = { open = false }) {
            DropdownMenuItem(
                text = { Text("Placemark") },
                leadingIcon = { MarkerBadge(null, size = 24.dp, plainColor = MapStyle.placemarkColor) },
                onClick = { open = false; onChange(null) },
            )
            // Set names only matter when there's more than one set.
            val multipleSets = types.values.map { it.setName }.distinct().size > 1
            for (t in types.values) {
                DropdownMenuItem(
                    text = { Text(if (multipleSets) "${t.name} · ${t.setName}" else t.name) },
                    leadingIcon = { MarkerBadge(t, size = 24.dp) },
                    onClick = { open = false; onChange(t) },
                )
            }
        }
    }
}

/**
 * The type's fields, then any values it no longer has a field for (so nothing imported or
 * recorded under an older version of the type is lost or hidden).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun MarkerFieldsEditor(fields: List<MarkerField>, values: Map<String, String>, onChange: (String, String) -> Unit) {
    val all = fields + values.keys.filter { k -> fields.none { it.key == k } }.sorted().map { MarkerField(it, it) }
    for (field in all) {
        val value = values[field.key].orEmpty()
        when (field.kind) {
            FieldKind.TEXT -> OutlinedTextField(
                value, { onChange(field.key, it) },
                label = { Text(field.label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            // Counts are the usual number: steppers save opening the keyboard with gloves on.
            FieldKind.NUMBER -> OutlinedTextField(
                value, { v -> onChange(field.key, v.filter { it.isDigit() || it == '.' || it == '-' }) },
                label = { Text(field.label) }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                trailingIcon = {
                    val n = value.toIntOrNull()
                    if (n != null || value.isEmpty()) {
                        Row {
                            IconButton(onClick = { onChange(field.key, ((n ?: 0) - 1).coerceAtLeast(0).toString()) }, enabled = (n ?: 0) > 0) {
                                Icon(Icons.Filled.Remove, "Fewer")
                            }
                            IconButton(onClick = { onChange(field.key, ((n ?: 0) + 1).toString()) }) { Icon(Icons.Filled.Add, "More") }
                        }
                    }
                },
            )
            FieldKind.CHOICE -> Column {
                Text(field.label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    // A value from outside the options (typed in another app, or an option since removed) stays shown.
                    val options = if (value.isNotEmpty() && value !in field.options) field.options + value else field.options
                    for (o in options) {
                        // Tapping the chosen one again clears it.
                        FilterChip(selected = o == value, onClick = { onChange(field.key, if (o == value) "" else o) }, label = { Text(o) })
                    }
                }
            }
        }
    }
}

/**
 * Changing a marker's type [from] one [to] another: its name if it was the default ("Placemark 3"
 * → "Snake 3"), and its values. The new type's defaults fill what's empty. The old type's values
 * the new one has no field for are kept, unless they're just the old defaults left untouched.
 */
fun retype(name: String, from: MarkerType?, values: Map<String, String>, to: MarkerType?): Pair<String, Map<String, String>> {
    val m = Regex("^${Regex.escape(from?.name ?: "Placemark")} (\\d+)$").find(name)
    val newName = if (m != null) "${to?.name ?: "Placemark"} ${m.groupValues[1]}" else name
    val newKeys = to?.fields?.map { it.key }.orEmpty().toSet()
    val untouched = from?.fields.orEmpty().filter { it.key !in newKeys && it.default.isNotEmpty() && values[it.key] == it.default }.map { it.key }
    val kept = values.filter { (k, v) -> k !in untouched && v.isNotBlank() }
    val defaults = to?.fields.orEmpty().filter { it.default.isNotEmpty() && kept[it.key].isNullOrEmpty() }.associate { it.key to it.default }
    return newName to kept + defaults
}
