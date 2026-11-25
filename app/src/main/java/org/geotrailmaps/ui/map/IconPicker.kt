package org.geotrailmaps.ui.map

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.selection.selectable
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import org.geotrailmaps.data.PointIcons

private val SLOT = 48.dp

/**
 * A point's icon: none, the icons used most recently (as many as fit on one line, with this
 * point's own first if it isn't among them), and a button to choose another.
 */
@Composable
fun IconRow(icon: String?, recent: List<String>, onSelect: (String?) -> Unit) {
    var choosing by remember { mutableStateOf(false) }
    if (choosing) IconPickerDialog(onPick = { choosing = false; onSelect(it) }, onDismiss = { choosing = false })
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Less the "no icon" and "choose" slots.
        val room = ((maxWidth / SLOT).toInt() - 2).coerceAtLeast(0)
        val shown = (if (icon != null && icon !in recent.take(room)) listOf(icon) + recent.filter { it != icon } else recent).take(room)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Slot(selected = icon == null, description = "No icon", onClick = { onSelect(null) }) {
                Icon(Icons.Filled.Block, null, Modifier.size(22.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            for (e in shown) {
                Slot(selected = e == icon, description = e, onClick = { onSelect(e) }) { Text(e, fontSize = 22.sp) }
            }
            Box(
                Modifier.size(SLOT).clickable(onClickLabel = "Choose another icon", role = Role.Button) { choosing = true },
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.AddReaction, "Choose another icon", tint = MaterialTheme.colorScheme.primary) }
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
            Modifier.size(40.dp).then(
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
            Column {
                OutlinedTextField(
                    "", { v -> PointIcons.first(v)?.let(onPick) },
                    label = { Text("Type or paste any emoji") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                LazyVerticalGrid(GridCells.Adaptive(SLOT), Modifier.fillMaxWidth().height(260.dp).padding(top = 12.dp)) {
                    items(PointIcons.suggestions) { e ->
                        Box(
                            Modifier.size(SLOT).clickable(onClickLabel = "Use $e") { onPick(e) },
                            contentAlignment = Alignment.Center,
                        ) { Text(e, fontSize = 24.sp) }
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
