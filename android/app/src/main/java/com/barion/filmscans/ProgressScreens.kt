package com.barion.filmscans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp

/* Unzipping, converting, and what came out. */

@Composable
internal fun UnzipScreen(m: AppModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.status.ifEmpty { "Unzipping…" })
        LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
        Text("You can switch to other apps; progress shows in the notification. Each file only gets its real " +
            "name once it's complete, so stopping is safe: unzipping the same zip again later carries on where it " +
            "left off.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { m.stopWork() }) { Text("Stop after this file") }
        m.log.forEach { Text(it, style = MaterialTheme.typography.bodySmall) }
    }
}

@Composable
internal fun ProgressScreen(m: AppModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Converting ${m.status}… ${m.done} of ${m.total}")
        LinearProgressIndicator(progress = { if (m.total == 0) 0f else m.done.toFloat() / m.total }, modifier = Modifier.fillMaxWidth())
        LinearProgressIndicator(progress = { m.fileProgress }, modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary)
        Text("You can switch to other apps; progress shows in the notification.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { m.stopWork() }) { Text("Stop after the scans in progress") }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(m.log.reversed()) { _, line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun DoneScreen(m: AppModel) {
    val ctx = LocalContext.current
    var details by remember { mutableStateOf(false) }
    val failed = m.log.count { it.startsWith("✗") }
    val made = m.outputs.sumOf { it.count }
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text(if (failed == 0) "Done — $made JPEGs saved." else "Done — $made saved, $failed didn't convert.",
                style = MaterialTheme.typography.headlineSmall)
            if (failed > 0) Text("The TIFFs that didn't convert are untouched.", style = MaterialTheme.typography.bodySmall)
        }
        items(m.outputs, key = { it.roll.folder.uri.toString() }) { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(o.folderName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${o.count} JPEG(s) in ${Places.label(o.roll.outputUri ?: o.roll.folder.uri)}",
                        style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { Places.viewPhotos(ctx, o) }, enabled = o.firstJpeg != null) { Text("View photos") }
                        OutlinedButton(onClick = { Places.openFolder(ctx, o) }) { Text("Open folder") }
                    }
                }
            }
        }
        item {
            Button(onClick = { m.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Back to home") }
        }
        item {
            TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details (${m.log.size})") }
        }
        if (details) items(m.log.toList()) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}
