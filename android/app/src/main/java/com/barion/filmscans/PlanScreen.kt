package com.barion.filmscans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.MaterialTheme
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
 * What's in the picked zip(s), before anything is written: every folder it will make, which
 * can be renamed, dropped (an empty name moves its contents up a level) or left out.
 */
@Composable
fun PlanScreen(m: AppModel) {
    val problems by remember { derivedStateOf { m.planProblems() } }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Nothing is written until you press Unzip. Rename any folder, clear a name to drop that level " +
                "(what's in it moves up), or untick a folder to leave it out.", style = MaterialTheme.typography.bodySmall)
        }
        for (p in m.plans) {
            item(key = "head:" + p.uri) { PlanHeader(p) }
            items(p.folders, key = { "f:" + p.uri + "/" + it.path }) { f -> FolderRow(p, f) }
            item(key = "foot:" + p.uri) { PlanFooter(p) }
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
                    if (m.plans.any { it.hasJpegs() }) SettingSwitch("Date the lab's JPEGs", m.settings.labJpegDates,
                        { m.settings.labJpegDates = it; m.settings.save() },
                        "JPEGs with no date taken get their scan date, so galleries sort them by when they were scanned.")
                    DeleteZipsSwitch(m)
                }
            }
        }
    }
}

@Composable
private fun PlanHeader(p: ZipPlan) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(p.zipName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val l = p.listing
            Text(if (l == null) "${sizeText(p.zipSize)} · into ${Places.label(p.dest.uri)}"
                else "${l.files.size} file(s), ${sizeText(l.totalBytes)} · into ${Places.label(p.dest.uri)}",
                style = MaterialTheme.typography.bodySmall)
            if (l == null) Text("This zip can't be read directly (it may be stored in the cloud), so there's no " +
                "preview: everything in it is unzipped, in order, into the folder below.", style = MaterialTheme.typography.bodySmall)
            OutlinedTextField(p.top, { p.top = it }, singleLine = true, label = { Text("Folder to make") },
                supportingText = { if (p.top.isBlank()) Text("Empty: unzips straight into ${Places.label(p.dest.uri)}") },
                modifier = Modifier.fillMaxWidth())
            l?.skipped?.forEach { Text("Can't unzip $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!l?.sealed.isNullOrEmpty()) Text("${l!!.sealed.size} zip(s) inside are compressed, so their contents are " +
                "only seen while unzipping. They go into the folders marked below.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FolderRow(p: ZipPlan, f: PlanFolder) {
    val left = generateSequence(f.path) { it.substringBeforeLast('/', "").ifEmpty { null } }.any { p.skip[it] == true }
    val ownSkip = p.skip[f.path] == true
    Row(Modifier.fillMaxWidth().padding(start = (f.depth * 14).dp), verticalAlignment = Alignment.Top) {
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
            if (!left) p.dateSummary[f.path]?.let { (sources, undated) ->
                if (sources.isNotEmpty()) Text("Scan dates from: " + sources.entries.sortedByDescending { it.value }
                    .joinToString { "${plainSource(it.key)} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                if (undated > 0) Text("$undated image(s) with no scan date anywhere: you'll be asked for one before converting.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            // The same roll usually sits under both Jpegs and Tiffs: offer the new name for its twin(s).
            val renamed = p.names[f.path]
            val twins = p.folders.filter { it !== f && it.name == f.name && it.depth == f.depth }
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
    val r by remember(p) { derivedStateOf { p.build() } }
    val res = r ?: return
    val looseFiles = p.listing?.files?.count { it.folder.isEmpty() } ?: 0
    Column(Modifier.padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (looseFiles > 0) Text("$looseFiles file(s) at the top of the zip go straight into the folder to make.",
            style = MaterialTheme.typography.bodySmall)
        val example = res.targets.firstOrNull { isImage(it.name) } ?: res.targets.firstOrNull()
        if (example != null) Text("e.g. ${example.path}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
}
