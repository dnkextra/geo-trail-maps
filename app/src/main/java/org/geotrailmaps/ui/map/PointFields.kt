package org.geotrailmaps.ui.map

import android.text.format.DateFormat
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Schedule
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.AssistChip
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuAnchorType
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TimePicker
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.material3.rememberTimePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import org.geotrailmaps.data.PointNames
import java.time.Instant
import java.time.ZoneId

/**
 * The name, with names used more than once offered below it as you type (most used first).
 * A default name ([placeholder], e.g. "Placemark 3") isn't in the field but shown greyed in
 * it, so one tap and typing gives your own; left empty, the default is kept.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun NameField(name: String, placeholder: String?, counts: Map<String, Int>, onChange: (String) -> Unit) {
    val focusManager = LocalFocusManager.current
    var focused by remember { mutableStateOf(false) }
    // Closed by picking one; opens again on typing.
    var dismissed by remember { mutableStateOf(false) }
    val suggestions = remember(counts, name) { PointNames.suggestions(counts, name) }
    val expanded = focused && !dismissed && suggestions.isNotEmpty()
    ExposedDropdownMenuBox(expanded = expanded, onExpandedChange = { dismissed = !it }) {
        OutlinedTextField(
            name,
            { onChange(it); dismissed = false },
            // Before it's tapped the label sits in the field, so it names the default too.
            label = { Text(if (placeholder != null && name.isEmpty() && !focused) "Name: $placeholder" else "Name") },
            placeholder = placeholder?.let { { Text(it) } },
            singleLine = true,
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.Sentences, imeAction = ImeAction.Done),
            keyboardActions = KeyboardActions(onDone = { dismissed = true; focusManager.clearFocus() }),
            modifier = Modifier
                .fillMaxWidth()
                .menuAnchor(ExposedDropdownMenuAnchorType.PrimaryEditable)
                .onFocusChanged { focused = it.isFocused },
        )
        ExposedDropdownMenu(expanded = expanded, onDismissRequest = { dismissed = true }) {
            for (s in suggestions) {
                DropdownMenuItem(text = { Text(s) }, onClick = { onChange(s); dismissed = true })
            }
        }
    }
}

/** The point's time, as a date and a time that each open a picker. Starts as when it was placed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TimeRow(time: Long, onChange: (Long) -> Unit) {
    val context = LocalContext.current
    val zone = ZoneId.systemDefault()
    val local = Instant.ofEpochMilli(time).atZone(zone)
    var picking by remember { mutableStateOf<String?>(null) }
    // The pickers work in whole minutes: confirming the same minute isn't a change.
    fun set(t: Long) { if (t != time / 60_000 * 60_000) onChange(t) }

    // Wraps the chips onto a second line on narrow screens or with large text.
    FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Filled.Schedule, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            Text("Time", modifier = Modifier.padding(start = 4.dp))
        }
        // The device's own formats: "25 Nov 2026" / "Nov 25, 2026", "14:32" / "2:32 PM".
        AssistChip(onClick = { picking = "date" }, label = { Text(DateFormat.getMediumDateFormat(context).format(time)) })
        AssistChip(onClick = { picking = "time" }, label = { Text(DateFormat.getTimeFormat(context).format(time)) })
    }

    when (picking) {
        "date" -> {
            val state = rememberDatePickerState(initialSelectedDateMillis = PointNames.pickerDate(time, zone))
            DatePickerDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        state.selectedDateMillis?.let { set(PointNames.combine(it, local.hour, local.minute, zone)) }
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
            ) {
                // Scrolls so every week stays reachable on short landscape screens.
                DatePicker(state, Modifier.verticalScroll(rememberScrollState()))
            }
        }
        "time" -> {
            val state = rememberTimePickerState(local.hour, local.minute, DateFormat.is24HourFormat(context))
            AlertDialog(
                onDismissRequest = { picking = null },
                confirmButton = {
                    TextButton(onClick = {
                        set(PointNames.combine(PointNames.pickerDate(time, zone), state.hour, state.minute, zone))
                        picking = null
                    }) { Text("OK") }
                },
                dismissButton = { TextButton(onClick = { picking = null }) { Text("Cancel") } },
                // Scrolls when landscape or large text makes it taller than the dialog.
                text = { Column(Modifier.verticalScroll(rememberScrollState())) { TimePicker(state) } },
            )
        }
    }
}
