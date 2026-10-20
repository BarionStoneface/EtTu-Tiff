package com.barion.filmscans.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.PlanFolder

/**
 * What's in the chosen zip(s), before anything is written: every folder it will make, which
 * can be renamed, dropped (an empty name moves its contents up a level) or left out.
 */
@Composable
fun PlanScreen(m: AppModel, pick: Pickers) {
    val problems by remember { derivedStateOf { m.planProblems() } }
    ScrollList(contentPadding = PaddingValues(16.dp), spacing = 8.dp) {
        item {
            Text("Nothing is written until you press Unzip. Rename any folder, clear a name to drop that level " +
                "(what's in it moves up), or untick a folder to leave it out.", style = MaterialTheme.typography.bodySmall)
        }
        for (p in m.plans) {
            val key = p.zip.absolutePath
            item(key = "head:$key") { PlanHeader(p, pick) }
            items(p.contents.folders, key = { "f:$key/${it.path}" }) { f -> FolderRow(p, f) }
            item(key = "foot:$key") { PlanFooter(p) }
        }
        if (problems.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                problems.take(20).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (problems.size > 20) Text("…and ${problems.size - 20} more", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.plans.any { it.contents.hasJpegs }) SettingSwitch("Date the lab's JPEGs", m.settings.labJpegDates,
                        { m.settings.labJpegDates = it; m.settings.save() },
                        "JPEGs with no date taken get their scan date, so photo apps sort them by when they were scanned.")
                    DeleteZipsSwitch(m)
                }
            }
        }
    }
}

@Composable
private fun PlanHeader(p: ZipPlan, pick: Pickers) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.zip.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val l = p.contents.listing
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("${l.files.size} file(s), ${sizeText(l.totalBytes)} · into ${p.dest.path}",
                    style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { pick.folder("Unzip into")?.let { p.dest = it } }) { Text("Change…") }
            }
            OutlinedTextField(p.top, { p.top = it }, singleLine = true, label = { Text("Folder to make") },
                supportingText = { if (p.top.isBlank()) Text("Empty: unzips straight into ${p.dest.path}") },
                modifier = Modifier.fillMaxWidth())
            l.skipped.forEach { Text("Can't unzip $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (l.sealed.isNotEmpty()) Text("${l.sealed.size} zip(s) inside are compressed, so their contents are " +
                "only seen while unzipping. They go into the folders marked below.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FolderRow(p: ZipPlan, f: PlanFolder) {
    val left = generateSequence(f.path) { it.substringBeforeLast('/', "").ifEmpty { null } }.any { p.skip[it] == true }
    val ownSkip = p.skip[f.path] == true
    Row(Modifier.fillMaxWidth().padding(start = (f.depth * 18).dp), verticalAlignment = Alignment.Top) {
        Checkbox(!ownSkip, { p.skip[f.path] = !it }, enabled = !left || ownSkip)
        Column(Modifier.weight(1f)) {
            OutlinedTextField(
                value = p.names[f.path] ?: f.name,
                onValueChange = { p.names[f.path] = it },
                singleLine = true, enabled = !left,
                label = { Text(if ((p.names[f.path] ?: f.name).isBlank()) "Dropped: contents move up" else f.name) },
                modifier = Modifier.fillMaxWidth(),
            )
            val info = buildList {
                if (f.files > 0) add("${f.files} file(s)")
                add(sizeText(f.bytes))
                if (f.sealed) add("a compressed zip unpacks here")
            }.joinToString(" · ")
            Text(if (left) "Left out" else info, style = MaterialTheme.typography.bodySmall)
            if (!left) p.contents.dateSummary[f.path]?.let { (sources, undated) ->
                if (sources.isNotEmpty()) Text("Scan dates from: " + sources.entries.sortedByDescending { it.value }
                    .joinToString { "${plainSource(it.key)} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                if (undated > 0) Text("$undated image(s) with no scan date anywhere: you'll be asked for one before converting.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // The same roll usually sits under both Jpegs and Tiffs: offer the new name for its twin(s).
            val renamed = p.names[f.path]
            val twins = p.contents.folders.filter { it !== f && it.name == f.name && it.depth == f.depth }
            if (!left && renamed != null && renamed != f.name && twins.any { p.names[it.path] != renamed })
                TextButton(onClick = { twins.forEach { p.names[it.path] = renamed } }) {
                    Text(if (renamed.isBlank()) "Drop the other ${twins.size} \"${f.name}\" folder(s) too"
                        else "Use \"$renamed\" for the other ${twins.size} \"${f.name}\" folder(s)")
                }
        }
    }
}

@Composable
private fun PlanFooter(p: ZipPlan) {
    val res by remember(p) { derivedStateOf { p.build() } }
    val looseFiles = p.contents.listing.files.count { it.folder.isEmpty() }
    Column(Modifier.padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (looseFiles > 0) Text("$looseFiles file(s) at the top of the zip go straight into the folder to make.",
            style = MaterialTheme.typography.bodySmall)
        val example = res.targets.firstOrNull { isImage(it.name) } ?: res.targets.firstOrNull()
        if (example != null) Text("e.g. ${java.io.File(p.dest, example.path).path}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
}
