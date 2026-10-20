package com.barion.filmscans.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import java.io.File

/* Unzipping, converting, fixing dates, and what came out. */

@Composable
internal fun UnzipScreen(m: AppModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(m.status.ifEmpty { "Unzipping…" })
        LinearProgressIndicator(progress = { m.progress }, modifier = Modifier.fillMaxWidth())
        Text("Each file only gets its real name once it's complete, so stopping is safe: unzipping the same zip " +
            "again later carries on where it left off.", style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = { m.stopWork() }) { Text("Stop after this file") }
        ScrollList(contentPadding = PaddingValues(0.dp), spacing = 2.dp) {
            items(m.log.toList()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun ProgressScreen(m: AppModel) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Converting ${m.status}… ${m.done} of ${m.total}")
        LinearProgressIndicator(progress = { if (m.total == 0) 0f else m.done.toFloat() / m.total }, modifier = Modifier.fillMaxWidth())
        LinearProgressIndicator(progress = { m.fileProgress }, modifier = Modifier.fillMaxWidth(),
            color = MaterialTheme.colorScheme.secondary)
        OutlinedButton(onClick = { m.stopWork() }) { Text("Stop after the scans in progress") }
        ScrollList(contentPadding = PaddingValues(0.dp), spacing = 2.dp) {
            items(m.log.reversed()) { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
internal fun DoneScreen(m: AppModel) {
    var details by remember { mutableStateOf(false) }
    val failed = m.log.count { it.startsWith("✗") }
    val made = m.outputs.sumOf { it.count }
    ScrollList(contentPadding = PaddingValues(16.dp), spacing = 12.dp) {
        item {
            Text(if (failed == 0) "Done — $made JPEGs saved." else "Done — $made saved, $failed didn't convert.",
                style = MaterialTheme.typography.headlineSmall)
            if (failed > 0) Text("The originals that didn't convert are untouched.", style = MaterialTheme.typography.bodySmall)
            m.log.firstOrNull { it.startsWith("File dates") }?.let { Text(it, style = MaterialTheme.typography.bodySmall) }
        }
        items(m.outputs, key = { it.roll.info.folder.absolutePath }) { o ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(o.dir.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    Text("${o.count} JPEG(s) in ${o.dir.path}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        val first = o.firstJpeg?.let { File(o.dir, it) }
                        Button(onClick = { first?.let { Shell.open(it) } }, enabled = first != null) { Text("View photos") }
                        OutlinedButton(onClick = { Shell.showInFolder(first ?: o.dir) }) { Text("Open folder") }
                    }
                }
            }
        }
        item { Button(onClick = { m.reset() }, modifier = Modifier.fillMaxWidth()) { Text("Back to start") } }
        item { TextButton(onClick = { details = !details }) { Text(if (details) "Hide details" else "Show details (${m.log.size})") } }
        if (details) items(m.log.toList()) { line -> Text(line, style = MaterialTheme.typography.bodySmall) }
    }
}

/** Fix dates: what each photo would get, then what happened. */
@Composable
internal fun FixDatesScreen(m: AppModel) {
    val items = m.fixItems
    val dated = items.filter { it.date != null }
    val undated = items.filter { it.date == null }
    val folder = m.fixFolder
    ScrollList(contentPadding = PaddingValues(16.dp), spacing = 6.dp) {
        item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(folder?.path ?: "", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (m.phase == Phase.FixDone) {
                    m.log.forEach { Text(it, color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        if (folder != null) OutlinedButton(onClick = { Shell.open(folder) }) { Text("Open folder") }
                        Button(onClick = { m.reset() }) { Text("Back to start") }
                    }
                } else {
                    Text("${items.size} photo(s). ${dated.size} will get their scan date as their created and modified date; " +
                        "nothing inside the files changes." + if (undated.isNotEmpty()) " ${undated.size} have no date inside and are left alone." else "",
                        style = MaterialTheme.typography.bodyMedium)
                    val sources = dated.groupingBy { it.source ?: "" }.eachCount().entries.sortedByDescending { it.value }
                    if (sources.isNotEmpty()) Text("Dates from: " + sources.joinToString { "${it.key} (${it.value})" },
                        style = MaterialTheme.typography.bodySmall)
                }
            }
        }
        if (m.phase == Phase.FixReady) {
            if (undated.isNotEmpty()) item {
                Text("No date inside", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(top = 8.dp))
            }
            items(undated) { Text(relative(folder, it.file), style = MaterialTheme.typography.bodySmall) }
            if (dated.isNotEmpty()) item {
                Text("Dates to set", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.padding(top = 8.dp))
            }
            items(dated) { Text("${relative(folder, it.file)} → ${MINUTE.format(it.date!!)}", style = MaterialTheme.typography.bodySmall) }
        }
    }
}

private fun relative(base: File?, f: File): String =
    base?.let { runCatching { it.toPath().relativize(f.toPath()).toString() }.getOrNull() } ?: f.path
