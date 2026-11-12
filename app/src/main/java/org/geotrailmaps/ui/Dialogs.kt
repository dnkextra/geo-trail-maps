package org.geotrailmaps.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue

/** Asks for a new name, starting from [initial]; [onRename] gets it trimmed, and never blank. */
@Composable
fun RenameDialog(title: String, initial: String, onRename: (String) -> Unit, onDismiss: () -> Unit) {
    var name by remember(initial) { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { OutlinedTextField(name, { name = it }, singleLine = true) },
        confirmButton = {
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(name.trim()) }) { Text("Rename") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** Asks before deleting something, with the Delete button in the error colour. */
@Composable
fun ConfirmDeleteDialog(title: String? = null, text: String? = null, onDelete: () -> Unit, onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = title?.let { { Text(it) } },
        text = text?.let { { Text(it) } },
        confirmButton = {
            TextButton(
                onClick = onDelete,
                colors = ButtonDefaults.textButtonColors(contentColor = MaterialTheme.colorScheme.error),
            ) { Text("Delete") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}

/** What a route can get wrong: shown once when Navigate is first used, and under Settings. */
const val ROUTE_NOTICE_TITLE = "When using a route, please keep in mind:"
const val ROUTE_NOTICE_TEXT =
    "• The app doesn't account for real-world conditions or safety.\n" +
        "• Routes are based on map lines and may not be optimal or traversable.\n" +
        "• Trail maps can be inaccurate, so distance estimates may be off.\n\n" +
        "Please be discerning and stay safe!"

/** The route notice. Only OK closes it, so it isn't dismissed unread by a stray tap. */
@Composable
fun RouteNoticeDialog(onOk: () -> Unit) {
    AlertDialog(
        onDismissRequest = {},
        title = { Text(ROUTE_NOTICE_TITLE, style = MaterialTheme.typography.titleLarge) },
        text = { Text(ROUTE_NOTICE_TEXT) },
        confirmButton = { TextButton(onClick = onOk) { Text("OK") } },
    )
}
