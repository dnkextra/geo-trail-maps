package org.geotrailmaps.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AddReaction
import androidx.compose.material.icons.filled.Block
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.TextFieldValue
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.PointIcons

private val SLOT = 48.dp

/** Emoji sized in dp so they fit their fixed slots at any font scale. */
@Composable
private fun dpSp(size: Dp) = with(LocalDensity.current) { size.toSp() }

/**
 * A point's icon: none, the icons used most recently (as many as fit on one line, with this
 * point's own first if it isn't among them), and a button to choose another.
 */
@Composable
fun IconRow(icon: String?, recent: List<String>, onSelect: (String?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    // The last couple picked in this sheet, then the point's own icon, go first and stay, so
    // choosing between them never shifts the row under your finger.
    val initial = remember { icon }
    val picked = remember { mutableStateListOf<String>() }
    if (choosing) IconPickerDialog(
        onPick = {
            choosing = false
            picked.remove(it); picked.add(0, it)
            // A couple at most, so the recent icons still fit beside them.
            while (picked.size > 2) picked.removeAt(picked.lastIndex)
            onSelect(it)
        },
        onDismiss = { choosing = false },
    )
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Less the "no icon" and "choose" slots.
        val room = ((maxWidth / SLOT).toInt() - 2).coerceAtLeast(0)
        val shown = (picked + listOfNotNull(initial) + recent).distinct().take(room)
        Row(Modifier.selectableGroup(), verticalAlignment = Alignment.CenterVertically) {
            Slot(selected = icon == null, description = "No icon", onClick = { onSelect(null) }) {
                Icon(Icons.Filled.Block, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (e in shown) {
                Slot(selected = e == icon, description = e, onClick = { onSelect(e) }) { Text(e, fontSize = dpSp(22.dp)) }
            }
            Box(
                Modifier.size(SLOT).semantics { contentDescription = "Choose another icon" }
                    .clickable(role = Role.Button) { choosing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.AddReaction, null, tint = MaterialTheme.colorScheme.primary) }
        }
    }
}

@Composable
private fun Slot(selected: Boolean, description: String, onClick: () -> Unit, content: @Composable () -> Unit) {
    Box(
        Modifier.size(SLOT).selectable(selected, role = Role.RadioButton, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier.size(40.dp).clearAndSetSemantics {}.then(
                if (selected) Modifier.background(MaterialTheme.colorScheme.primaryContainer, CircleShape)
                    .border(2.dp, MaterialTheme.colorScheme.primary, CircleShape)
                else Modifier,
            ),
            contentAlignment = Alignment.Center,
        ) { content() }
    }
}

/** Any emoji from the keyboard (or pasted), or one of the suggestions. Picking closes it. */
@Composable
fun IconPickerDialog(onPick: (String) -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose an icon") },
        text = {
            // Held while the keyboard is still composing (e.g. romaji on its way to kana), so only
            // finished input is picked.
            var draft by remember { mutableStateOf(TextFieldValue("")) }
            Column {
                OutlinedTextField(
                    draft, { v -> draft = v; if (v.composition == null) PointIcons.first(v.text)?.let(onPick) },
                    label = { Text("Type or paste any emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                LazyVerticalGrid(GridCells.Adaptive(SLOT), Modifier.fillMaxWidth().heightIn(max = 260.dp).padding(top = 12.dp)) {
                    items(PointIcons.suggestions) { e ->
                        Box(
                            Modifier.size(SLOT).clickable(onClickLabel = "Use $e", role = Role.Button) { onPick(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = dpSp(24.dp)) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
