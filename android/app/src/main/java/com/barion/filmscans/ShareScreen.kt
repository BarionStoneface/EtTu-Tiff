package com.barion.filmscans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp

/* Shrinking a folder's photos and zipping them to send. */

private val EDGES = listOf(1568 to "1568 px", 2048 to "2048 px", 3000 to "3000 px")
private val CAPS = listOf(25 to "25 MB", 50 to "50 MB", 100 to "100 MB", 500 to "500 MB", 0 to "No limit")

/** Preset chips, then a number field for anything else. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Presets(value: Int, presets: List<Pair<Int, String>>, unit: String, min: Int, max: Int, set: (Int) -> Unit) {
    var custom by remember { mutableStateOf(presets.none { it.first == value }) }
    var text by remember { mutableStateOf(value.toString()) }
    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        presets.forEach { (v, label) ->
            FilterChip(selected = !custom && value == v, onClick = { custom = false; set(v) }, label = { Text(label) })
        }
        FilterChip(selected = custom, onClick = { custom = true; text = value.toString() }, label = { Text("Other") })
    }
    if (custom) OutlinedTextField(text, { t ->
        text = t.filter { it.isDigit() }.take(5)
        text.toIntOrNull()?.let { if (it in min..max) set(it) }
    }, label = { Text("$unit ($min to $max)") }, singleLine = true,
        isError = text.toIntOrNull()?.let { it !in min..max } ?: true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number), modifier = Modifier.fillMaxWidth())
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
internal fun ShareChooseScreen(m: AppModel) {
    val s = m.settings
    val share = m.share
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(share.rootName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text("Smaller copies are saved as JPEGs with their details (dates, camera, film, copyright) but no " +
                "location. The zips go in this folder; the originals aren't touched.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("Long side")
            Presets(s.shareEdge, EDGES, "Pixels", 256, 8000) { s.shareEdge = it }
            Text("Claude generally shrinks a photo to about 1568 px on the long side when it reads it, so more " +
                "than that mostly adds size. Smaller photos are never enlarged.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("JPEG quality: ${s.shareQuality}")
            Slider(value = s.shareQuality.toFloat(), onValueChange = { s.shareQuality = it.toInt() }, valueRange = 60f..95f, steps = 34)
        }
        item {
            Text("Largest zip")
            Presets(s.shareCapMb, CAPS, "MB", 1, 100_000) { s.shareCapMb = it }
            Text("Past this, the photos go into several zips (\"part 1 of 3\"). A folder stays in one zip when it fits; " +
                "each zip opens on its own.", style = MaterialTheme.typography.bodySmall)
        }
        if (share.anyMixed) item {
            Text("Folders with both TIFFs and JPEGs: use")
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                ShareUse.entries.forEach { u ->
                    FilterChip(selected = s.shareUse == u, onClick = { s.shareUse = u }, label = { Text(u.label) })
                }
            }
        }
        item { Text("Folders", style = MaterialTheme.typography.titleSmall) }
        items(share.folders, key = { it.path }) { f ->
            Card(Modifier.fillMaxWidth().clickable { f.include = !f.include }) {
                Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(f.include, { f.include = it })
                    Column(Modifier.weight(1f)) {
                        Text(f.path.substringAfter('/', f.path).ifEmpty { f.path }, fontWeight = FontWeight.Medium)
                        val kinds = listOfNotNull(
                            f.tiffs.size.takeIf { it > 0 }?.let { "$it TIFF(s)" },
                            f.jpegs.size.takeIf { it > 0 }?.let { "$it JPEG(s)" },
                            f.others.size.takeIf { it > 0 }?.let { "$it other" },
                        )
                        Text("${kinds.joinToString(" · ")} · ${sizeText(f.bytes)} now", style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

@Composable
internal fun ShareProgressScreen(s: ShareModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Shrinking ${s.done} of ${s.total}")
        LinearProgressIndicator(progress = { s.progress }, modifier = Modifier.fillMaxWidth())
        Text(s.status, style = MaterialTheme.typography.bodySmall)
        Text("You can switch to other apps; progress shows in the notification.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { s.stop = true }) { Text("Stop") }
        s.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ShareDoneScreen(s: ShareModel) {
    val ctx = LocalContext.current
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (s.zips.isEmpty()) "Nothing zipped" else "Ready to send: ${s.zips.size} zip(s), ${sizeText(s.zips.sumOf { it.size })}",
                style = MaterialTheme.typography.headlineSmall)
            if (s.zips.isNotEmpty()) Text("Saved in ${s.folderUri?.let { Places.label(it) } ?: s.rootName}.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(s.zips.toList(), key = { it.uri.toString() }) { z ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(z.name, fontWeight = FontWeight.SemiBold)
                    Text("${z.photos} photo(s) · ${sizeText(z.size)}", style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (s.zips.isNotEmpty()) item {
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ShareModel.send(ctx, s.zips.toList()) }) { Text("Send…") }
                OutlinedButton(onClick = { s.folderUri?.let { Places.openFolder(ctx, it) } }) { Text("Open folder") }
            }
        }
        items(s.log.toList()) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = { s.close() }) { Text("Back to home") } }
    }
}
