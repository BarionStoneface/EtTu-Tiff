package com.barion.filmscans

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.ArchiveFile
import com.barion.filmscans.core.PlanFolder

/**
 * What's in the picked zip(s), before anything is written: every folder it will make, which
 * can be renamed, dropped (an empty name moves its contents up a level) or left out.
 */
@Composable
fun PlanScreen(m: AppModel) {
    val problems by remember { derivedStateOf { m.planProblems() } }
    m.peek?.let { PeekDialog(m, it) }
    LazyColumn(
        Modifier.fillMaxSize(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item {
            Text("Nothing is written until you press Unzip. Rename any folder, clear a name to drop that level " +
                "(what's in it moves up), or untick a folder to leave it out. Tap a file to look at it.",
                style = MaterialTheme.typography.bodySmall)
        }
        for (p in m.plans) {
            item(key = "head:" + p.uri) { PlanHeader(p) }
            items(p.folders, key = { "f:" + p.uri + "/" + it.path }) { f -> FolderRow(m, p, f) }
            item(key = "foot:" + p.uri) { PlanFooter(m, p) }
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
            if (p.needsPassword || l == null) PasswordField(p)
            if (p.hasJpegs()) SettingSwitch("Date the lab's JPEGs", p.dateJpegs, { p.dateJpegs = it },
                if (p.photo) "JPEGs with no date taken get their scan date, so galleries sort them by when they were scanned."
                else "Off for zips that aren't mostly photos, so their files come out exactly as they went in.")
            l?.skipped?.forEach { Text("Can't unzip $it", color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
            if (!l?.sealed.isNullOrEmpty()) Text("${l!!.sealed.size} zip(s) inside are compressed, so their contents are " +
                "only seen while unzipping. They go into the folders marked below.", style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FolderRow(m: AppModel, p: ZipPlan, f: PlanFolder) {
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
            if (!left && f.files > 0) p.listing?.let { l -> FilePeek(m, p, remember(l, f.path) { l.files.filter { it.folder == f.path } }) }
            if (!left) p.dateSummary[f.path]?.let { (sources, undated) ->
                if (sources.isNotEmpty()) Text("Scan dates from: " + sources.entries.sortedByDescending { it.value }
                    .joinToString { "${plainSource(it.key)} (${it.value})" }, style = MaterialTheme.typography.bodySmall)
                if (undated > 0) Text("$undated image(s) with no scan date anywhere: type it in this roll's details below.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
            if (!left && p.photo) p.rolls[f.path]?.let { r -> PlanRollDetails(m, p, f, r) }
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
private fun PlanFooter(m: AppModel, p: ZipPlan) {
    val r by remember(p) { derivedStateOf { p.build() } }
    val res = r ?: return
    val looseFiles = p.listing?.files?.count { it.folder.isEmpty() } ?: 0
    Column(Modifier.padding(start = 12.dp, bottom = 8.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        if (looseFiles > 0) {
            Text("$looseFiles file(s) at the top of the zip go straight into the folder to make:",
                style = MaterialTheme.typography.bodySmall)
            p.listing?.let { l -> FilePeek(m, p, remember(l) { l.files.filter { it.folder.isEmpty() } }) }
        }
        val example = res.targets.firstOrNull { isImage(it.name) } ?: res.targets.firstOrNull()
        if (example != null) Text("e.g. ${example.path}", style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.primary)
    }
}

/** The zip's password: needed when files in it are locked, or offered when the zip can't be looked into first. */
@Composable
private fun PasswordField(p: ZipPlan) {
    var show by remember { mutableStateOf(false) }
    val locked = p.listing?.let { l -> l.files.count { it.item.encrypted } + l.sealed.count { it.item.encrypted } }
    OutlinedTextField(
        value = p.password,
        onValueChange = { p.password = it; p.passwordWrong = false },
        singleLine = true,
        label = { Text(if (locked == null) "Password, if it has one" else "Password") },
        isError = p.passwordWrong,
        visualTransformation = if (show) VisualTransformation.None else PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
        trailingIcon = { TextButton(onClick = { show = !show }) { Text(if (show) "Hide" else "Show") } },
        supportingText = {
            Text(when {
                p.passwordWrong -> "That password doesn't open it."
                locked == null -> "This zip can't be looked into first, so a password is only checked while unzipping."
                else -> "$locked locked file(s) in it. The password is checked before anything is unzipped."
            })
        },
        modifier = Modifier.fillMaxWidth(),
    )
}

/**
 * The files in one folder of the zip: the first few, then how many more, with a way to see every one.
 * Each shows a thumbnail when it's a photo, its size and its date in the zip, and opens when tapped:
 * a photo here, anything else in the app for it. Nothing else in the zip is unzipped to do it.
 */
@Composable
private fun FilePeek(m: AppModel, p: ZipPlan, files: List<ArchiveFile>) {
    var all by remember { mutableStateOf(false) }
    val shown = if (all) files else files.take(6)
    Column(Modifier.padding(top = 2.dp, bottom = 2.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        for (f in shown) Row(
            Modifier.fillMaxWidth().clickable(onClickLabel = "Open ${f.name}") { m.openInZip(p, f) }.padding(vertical = 2.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            LaunchedEffect(f.path, p.password) { m.thumb(p, f) }
            Box(Modifier.size(44.dp).clip(RoundedCornerShape(4.dp)).background(MaterialTheme.colorScheme.surfaceVariant),
                contentAlignment = Alignment.Center) {
                val t = p.thumbs[f.path]
                if (t != null) Image(t, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
                else Text(if (f.item.encrypted) "🔒" else ext(f.name).take(4).uppercase(), style = MaterialTheme.typography.labelSmall)
            }
            Column(Modifier.weight(1f).padding(start = 8.dp)) {
                Text(f.name, style = MaterialTheme.typography.bodySmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(fileSize(f.size) + (f.item.time?.let { " · " + MINUTE.format(it) } ?: ""),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (files.size > 6) TextButton(onClick = { all = !all }) {
            Text(if (all) "Show fewer" else "and ${files.size - 6} more: show all")
        }
    }
}

/** A photo from the zip, big, with a way to open it in another app. */
@Composable
internal fun PeekDialog(m: AppModel, peek: AppModel.Peek) {
    AlertDialog(
        onDismissRequest = { m.peek = null },
        title = { Text(peek.file.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        text = {
            Box(Modifier.fillMaxWidth().heightIn(min = 160.dp), contentAlignment = Alignment.Center) {
                val pic = peek.picture
                if (pic == null) CircularProgressIndicator()
                else Image(pic, contentDescription = peek.file.name, contentScale = ContentScale.Fit, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = { TextButton(onClick = { m.peek = null }) { Text("Close") } },
        dismissButton = { TextButton(onClick = { m.peek = null; m.openInZip(peek.plan, peek.file, elsewhere = true) }) { Text("Open in another app") } },
    )
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
            if ((p.dateSummary[f.path]?.second ?: 0) > 0)
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
