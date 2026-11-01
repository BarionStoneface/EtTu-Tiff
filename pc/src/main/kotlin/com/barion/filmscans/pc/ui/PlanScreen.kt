package com.barion.filmscans.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.ArchiveFile
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
            items(p.contents.folders, key = { "f:$key/${it.path}" }) { f -> FolderRow(m, p, f) }
            item(key = "foot:$key") { PlanFooter(p) }
        }
        if (problems.isNotEmpty()) item {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                problems.take(20).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
                if (problems.size > 20) Text("…and ${problems.size - 20} more", color = MaterialTheme.colorScheme.error,
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        if (m.convertAfterUnzip && m.plans.any { p -> p.photo && p.rolls.values.any { !it.jpegRoll } }) item {
            ModeCard(m, hasTiffs = true)
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (m.plans.any { it.photo && it.rolls.isNotEmpty() }) SettingSwitch("Convert and tag straight after unzipping",
                        m.convertAfterUnzip, { m.convertAfterUnzip = it },
                        "With the details typed above, in one go. A roll that still needs something stops on the rolls screen.")
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
            if (p.needsPassword) PasswordField(p)
            if (p.contents.hasJpegs) SettingSwitch("Date the lab's JPEGs", p.dateJpegs, { p.dateJpegs = it },
                if (p.photo) "JPEGs with no date taken get their scan date, so photo apps sort them by when they were scanned."
                else "Off for zips that aren't mostly photos, so their files come out exactly as they went in.")
            l.skipped.forEach { Text("Can't unzip $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (l.sealed.isNotEmpty()) Text("${l.sealed.size} zip(s) inside are compressed, so their contents are " +
                "only seen while unzipping. They go into the folders marked below.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FolderRow(m: AppModel, p: ZipPlan, f: PlanFolder) {
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
            if (!left && f.files > 0) p.contents.listing.let { l -> FilePeek(remember(l, f.path) { l.files.filter { it.folder == f.path } }) }
            if (!left) p.contents.dateSummary[f.path]?.let { (sources, undated) ->
                if (sources.isNotEmpty()) Text("Scan dates from: " + sources.entries.sortedByDescending { it.value }
                    .joinToString { "${plainSource(it.key)} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                if (undated > 0) Text("$undated image(s) with no scan date anywhere: type it in this roll's details below.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!left && p.photo) p.rolls[f.path]?.let { r -> PlanRollDetails(m, p, f, r) }
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
        if (looseFiles > 0) {
            Text("$looseFiles file(s) at the top of the zip go straight into the folder to make:",
                style = MaterialTheme.typography.bodySmall)
            p.contents.listing.let { l -> FilePeek(remember(l) { l.files.filter { it.folder.isEmpty() } }) }
        }
        val example = res.targets.firstOrNull { isImage(it.name) } ?: res.targets.firstOrNull()
        if (example != null) Text("e.g. ${java.io.File(p.dest, example.path).path}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** The zip's password, needed when files in it are locked. */
@Composable
private fun PasswordField(p: ZipPlan) {
    var show by remember { mutableStateOf(false) }
    val l = p.contents.listing
    val locked = l.files.count { it.item.encrypted } + l.sealed.count { it.item.encrypted }
    OutlinedTextField(
        value = p.password,
        onValueChange = { p.password = it; p.passwordWrong = false },
        singleLine = true,
        label = { Text("Password") },
        isError = p.passwordWrong,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
        supportingText = {
            Text(if (p.passwordWrong) "That password doesn't open it."
                else "$locked locked file(s) in it. The password is checked before anything is unzipped.")
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The files in one folder of the zip, as LabUnzip shows them: the first few, then how many more,
 * with a way to see every one. Name, size and its date in the zip, read from the zip's directory.
 */
@Composable
private fun FilePeek(files: List<ArchiveFile>) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all) files else files.take(6)
    Column(Modifier.padding(top = 2.dp, bottom = 2.dp)) {
        for (f in shown) Row(Modifier.fillMaxWidth()) {
            Text((if (f.item.encrypted) "🔒 " else "") + f.name, style = MaterialTheme.typography.bodySmall,
                maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            Text(" " + fileSize(f.size) + (f.item.time?.let { " · " + MINUTE.format(it) } ?: ""),
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (files.size > 6) TextButton(onClick = { all = !all }) {
            Text(if (all) "Show fewer" else "and ${files.size - 6} more: show all")
        }
    }
}

/** "740 KB", "35 MB": small files aren't rounded up to a megabyte. */
private fun fileSize(b: Long) = when {
    b < 1_000 -> "$b B"
    b < 1_000_000 -> "${(b + 999) / 1000} KB"
    else -> sizeText(b)
}

/**
 * A roll's details, typed before it's unzipped, as in LabUnzip: carried onto the roll once it's out,
 * so it's converted and tagged in the same go. Left blank, the roll is filled in afterwards as before.
 */
@Composable
private fun PlanRollDetails(m: AppModel, p: ZipPlan, f: PlanFolder, r: PlanRoll) {
    var open by remember { mutableStateOf(true) }
    Card(Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 4.dp)) {
        Column(Modifier.padding(10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(if (r.jpegRoll) "Roll details: ${r.files.size} lab JPEGs, tagged where they are"
                    else "Roll details: ${r.files.size} scans, converted to JPEG",
                    fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = { open = !open }) { Text(if (open) "Hide" else "Show") }
            }
            if (!open) return@Column
            MetaFields(r, m.settings.cameras, m.settings.lenses, m.settings.labs, m.settings.customFilms)
            TagChips(r)
            if ((p.contents.dateSummary[f.path]?.second ?: 0) > 0)
                OutlinedTextField(r.dateOverride, { r.dateOverride = it }, singleLine = true,
                    label = { Text("Scan date, e.g. 2019-04-12 or 2019-04-12 14:30") }, modifier = Modifier.fillMaxWidth())
            Text("File names: the lab's name is kept whole; add text before or after it.", style = MaterialTheme.typography.bodySmall)
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.before, { r.before = it }, singleLine = true, label = { Text("Before") }, modifier = Modifier.weight(1f))
                OutlinedTextField(r.join, { r.join = it.take(3) }, singleLine = true, label = { Text("Join") }, modifier = Modifier.width(76.dp))
                OutlinedTextField(r.after, { r.after = it }, singleLine = true, label = { Text("After") }, modifier = Modifier.weight(1f))
            }
            val rollName = (p.names[f.path] ?: f.name).ifBlank { f.name }
            Text("${r.files.first().name} → ${r.example(rollName)}", style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.primary)
            // The same roll usually sits under both Jpegs and Tiffs: one set of details for both. Only offered
            // once something's typed, so a blank roll can't wipe what was typed for its twin.
            val twins = p.rolls.values.filter { it !== r && it.name == r.name }
            if (twins.isNotEmpty() && r.filled) TextButton(onClick = { twins.forEach { it.fillFrom(r) } }) {
                Text("Copy these details to the other ${twins.size} \"${r.name}\" folder(s)")
            }
        }
    }
}
