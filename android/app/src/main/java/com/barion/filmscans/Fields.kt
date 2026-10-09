package com.barion.filmscans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.dp

/* Text fields, pickers and switches used across screens. */

@Composable
internal fun DeleteZipsSwitch(m: AppModel) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text("Delete the zip after unzipping")
            if (m.deleteZips) Text("The zip is permanently deleted once everything in it is out.",
                color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
        }
        Switch(m.deleteZips, { m.deleteZips = it; m.saveSettings() })
    }
}

/**
 * Text field with suggestions shown as chips under it while it's focused, plus a ▾ button for
 * the full list. No popup opens while typing, which keeps typing quick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun SuggestField(label: String, value: String, onValue: (String) -> Unit, options: List<String>) {
    var focused by remember { mutableStateOf(false) }
    var showAll by remember { mutableStateOf(false) }
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Box {
            OutlinedTextField(
                value = value, onValueChange = onValue, label = { Text(label) }, singleLine = true,
                trailingIcon = if (options.isEmpty()) null else {
                    { IconButton(onClick = { showAll = true }) { Text("▾", style = MaterialTheme.typography.titleLarge) } }
                },
                modifier = Modifier.fillMaxWidth().onFocusChanged { focused = it.isFocused },
            )
            DropdownMenu(expanded = showAll, onDismissRequest = { showAll = false }, modifier = Modifier.heightIn(max = 380.dp)) {
                options.distinct().forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onValue(o); showAll = false }) }
            }
        }
        if (focused && options.isNotEmpty()) {
            val q = value.trim()
            val matches = (if (q.isEmpty()) options else options.filter { it.contains(q, ignoreCase = true) && !it.equals(q, true) })
                .distinct().take(6)
            if (matches.isNotEmpty()) FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                matches.forEach { o -> SuggestionChip(onClick = { onValue(o) }, label = { Text(o) }) }
            }
        }
    }
}

@Composable
internal fun SettingSwitch(title: String, on: Boolean, set: (Boolean) -> Unit, detail: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f).padding(end = 8.dp)) {
            Text(title)
            Text(detail, style = MaterialTheme.typography.bodySmall)
        }
        Switch(on, set)
    }
}

/** Fixed choice list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun PickField(label: String, value: String, options: List<String>, onPick: (String) -> Unit, modifier: Modifier) {
    var open by remember { mutableStateOf(false) }
    ExposedDropdownMenuBox(expanded = open, onExpandedChange = { open = it }, modifier = modifier) {
        OutlinedTextField(
            value = value, onValueChange = {}, readOnly = true, singleLine = true,
            label = { Text(label) },
            trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = open) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryNotEditable),
        )
        ExposedDropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            options.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onPick(o); open = false }) }
        }
    }
}
