package com.barion.filmscans

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.FILM_STOCKS
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.PROCESS_TAGS
import com.barion.filmscans.core.isoFor

/* One roll: its scans, details, names and folder. Each section reads only its own fields, so typing in one doesn't redraw the rest. */

private val PUSH_OPTIONS = listOf(-2 to "Pull −2", -1 to "Pull −1", 0 to "Box speed", 1 to "Push +1", 2 to "Push +2", 3 to "Push +3")

@Composable
internal fun RollCard(m: AppModel, r: Roll, onNext: (() -> Unit)?, onPreview: (ScanFile) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RollHeader(r)
            if (!r.include) return@Column
            ThumbStrip(r, onPreview)
            RollFacts(r)
            if (m.shownRolls.size > 1) CopyDetails(r, m.shownRolls)
            MetaFields(r, m.cameras, m.lenses, m.labs, m.customFilms)
            TagChips(r)
            DateOverride(r)
            HorizontalDivider()
            NamesSection(r, onPreview)
            HorizontalDivider()
            FolderSection(r, m.keepTiffs)
            RollProblems(r, m.keepTiffs)
            if (onNext != null) TextButton(onClick = onNext, modifier = Modifier.align(Alignment.End)) { Text("Next roll ↓") }
        }
    }
}

// Each section below reads only its own fields, so typing in one doesn't redraw the rest.

@Composable
private fun RollHeader(r: Roll) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Checkbox(checked = r.include, onCheckedChange = { r.include = it })
        Column {
            Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            val span = remember(r) {
                val dates = r.files.map { it.date.date.toLocalDate() }.distinct().sorted()
                when (dates.size) {
                    0 -> ""; 1 -> DAY.format(dates[0]); else -> "${DAY.format(dates.first())} to ${DAY.format(dates.last())}"
                }
            }
            Text(if (r.jpegRoll) "${r.files.size} lab JPEGs to tag, pictures untouched · scanned $span"
                else "${r.files.size} scans · scanned $span", style = MaterialTheme.typography.bodySmall)
            if (r.alreadyTagged) Text("Already tagged by this app, so left unticked. Tick to tag again.",
                style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
        }
    }
}

@Composable
private fun ThumbStrip(r: Roll, onPreview: (ScanFile) -> Unit) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        items(r.files, key = { it.name }) { f -> Thumb(f, 76.dp) { onPreview(f) } }
    }
}

@Composable
private fun Thumb(f: ScanFile, size: Dp, onClick: () -> Unit) {
    Box(
        Modifier.size(size).clip(RoundedCornerShape(6.dp)).background(MaterialTheme.colorScheme.surfaceVariant)
            .clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        val b = f.thumb
        when {
            b != null -> Image(b, contentDescription = f.name, contentScale = ContentScale.Crop, modifier = Modifier.matchParentSize())
            f.error != null -> Text("!", color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.Bold)
            else -> CircularProgressIndicator(Modifier.size(size / 4), strokeWidth = 2.dp)
        }
    }
}

@Composable
private fun RollFacts(r: Roll) {
    val scanners = remember(r) { r.scanners }
    if (scanners.isNotEmpty()) Text("Scanner (kept): ${scanners.joinToString()}", style = MaterialTheme.typography.bodySmall)
    val bits = remember(r) { r.files.map { it.bits }.distinct().filter { it > 8 } }
    if (bits.isNotEmpty()) Text("${bits.joinToString("/")}-bit scans: JPEG holds 8 bits per channel, so they're rounded to 8.",
        style = MaterialTheme.typography.bodySmall)
    val sources = remember(r) {
        r.files.filter { it.date.embedded }.groupingBy { plainSource(it.date.source) }.eachCount().entries
            .sortedByDescending { it.value }.joinToString { "${it.key} (${it.value})" }
    }
    if (sources.isNotEmpty()) Text("Scan dates from: $sources", style = MaterialTheme.typography.bodySmall)
}

/** Fills this roll in from another one, only when asked. */
@Composable
private fun CopyDetails(r: Roll, rolls: List<Roll>) {
    var open by remember { mutableStateOf(false) }
    Box {
        TextButton(onClick = { open = true }) { Text("Copy details from another roll…") }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }) {
            rolls.filter { it !== r }.forEach { o ->
                val what = listOf(o.camera, o.film).filter { it.isNotBlank() }.joinToString(", ").ifEmpty { "nothing filled in yet" }
                DropdownMenuItem(text = { Text("${o.name} — $what") }, onClick = { r.copyDetailsFrom(o); open = false })
            }
        }
    }
}

@Composable
private fun MetaFields(r: Roll, cameras: List<String>, lenses: List<String>, labs: List<String>, customFilms: List<String>) {
    SuggestField("Camera body", r.camera, { r.camera = it }, cameras)
    SuggestField("Lens (optional)", r.lens, { r.lens = it }, lenses)
    val films = remember(customFilms.size) { customFilms + FILM_STOCKS.map { it.name } }
    SuggestField("Film stock", r.film, {
        r.film = it; isoFor(it)?.let { iso -> r.iso = iso.toString() }
        if ("{film}" in r.before + r.after) r.applyNames()
    }, films)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(r.iso, { v -> r.iso = v.filter { it.isDigit() }.take(5) }, label = { Text("Box ISO") },
            singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            modifier = Modifier.width(110.dp))
        PickField("Push / pull", PUSH_OPTIONS.first { it.first == r.push }.second, PUSH_OPTIONS.map { it.second },
            { label -> r.push = PUSH_OPTIONS.first { it.second == label }.first }, Modifier.weight(1f))
    }
    val iso = r.iso.toIntOrNull()
    if (iso != null && r.push != 0) {
        val ei = if (r.push > 0) iso shl r.push else iso shr -r.push
        Text("Shot at EI $ei", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
    }
    OutlinedTextField(r.notes, { r.notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
    SuggestField("Lab / scanned by (optional)", r.lab, { r.lab = it }, labs)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TagChips(r: Roll) {
    FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        PROCESS_TAGS.forEach { tag ->
            FilterChip(selected = tag in r.tags, onClick = { r.tags = if (tag in r.tags) r.tags - tag else r.tags + tag },
                label = { Text(tag) })
        }
    }
}

/**
 * Scans with no date inside them and none from a zip. Their file dates on the phone are often just
 * the download date, so they're never used without asking.
 */
@Composable
private fun DateOverride(r: Roll) {
    if (r.undated == 0) return
    val span = remember(r) {
        val d = r.undatedFiles.map { it.date.date.toLocalDate() }.distinct().sorted()
        if (d.size <= 1) d.firstOrNull()?.let { DAY.format(it) } ?: "" else "${DAY.format(d.first())} to ${DAY.format(d.last())}"
    }
    Text("${r.undated} scan(s) have no scan date stored in them. Their file dates on the phone say $span, " +
        "which may just be when they were downloaded.",
        color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
    OutlinedTextField(r.dateOverride, { r.dateOverride = it }, singleLine = true,
        label = { Text("Scan date, e.g. 2019-04-12 or 2019-04-12 14:30") }, modifier = Modifier.fillMaxWidth())
    if (r.dateOverride.isBlank()) Row(Modifier.fillMaxWidth().clickable { r.useFileDates = !r.useFileDates },
        verticalAlignment = Alignment.CenterVertically) {
        Checkbox(r.useFileDates, { r.useFileDates = it })
        Text("Use their file dates ($span) instead", style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun NamesSection(r: Roll, onPreview: (ScanFile) -> Unit) {
    var showNames by remember { mutableStateOf(false) }
    Text("File names", fontWeight = FontWeight.SemiBold)
    Text("The lab's file name is kept whole. Add text before or after it if you like.", style = MaterialTheme.typography.bodySmall)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        OutlinedTextField(r.before, { r.before = it; r.applyNames() }, singleLine = true, label = { Text("Before") },
            modifier = Modifier.weight(1f))
        OutlinedTextField(r.join, { r.join = it.take(3); r.applyNames() }, singleLine = true, label = { Text("Join") },
            modifier = Modifier.width(76.dp))
        OutlinedTextField(r.after, { r.after = it; r.applyNames() }, singleLine = true, label = { Text("After") },
            modifier = Modifier.weight(1f))
    }
    Text("You can use ${Names.TOKENS_HELP}. First file: ${r.files.first().newName}.jpg",
        style = MaterialTheme.typography.bodySmall)
    if (r.labNameDropped()) Text("Some names no longer have the lab's name in them. It's still stored inside each JPEG.",
        color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
    TextButton(onClick = { showNames = !showNames }) {
        Text(if (showNames) "Hide names" else "Edit names one by one (${r.files.first().newName}.jpg, …)")
    }
    if (showNames) r.files.forEach { f -> NameRow(f, onPreview) }
}

@Composable
private fun NameRow(f: ScanFile, onPreview: (ScanFile) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        Thumb(f, 52.dp) { onPreview(f) }
        OutlinedTextField(f.newName, { f.newName = it }, singleLine = true, label = { Text(f.name) },
            suffix = { Text(".jpg") }, modifier = Modifier.weight(1f))
    }
}

@Composable
private fun FolderSection(r: Roll, keep: Boolean) {
    if (keep && r.jpegRoll) {
        OutlinedTextField(r.copiesFolder, { r.copiesFolder = it }, singleLine = true,
            label = { Text("New folder for the tagged copies") }, modifier = Modifier.fillMaxWidth())
        Text("It's made next to ${r.name}; the originals stay as they are.", style = MaterialTheme.typography.bodySmall)
        return
    }
    if (keep) {
        Text("Folder name and info files stay as they are (keeping the originals).", style = MaterialTheme.typography.bodySmall)
        return
    }
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text("Rename folder", Modifier.weight(1f))
        Switch(r.renameFolder, { r.renameFolder = it })
    }
    if (r.renameFolder) OutlinedTextField(r.newFolderName, {
        r.newFolderName = it
        if ("{roll}" in r.before + r.after) r.applyNames()
    }, singleLine = true,
        label = { Text("New folder name") }, modifier = Modifier.fillMaxWidth())
    if (r.sidecars.isNotEmpty()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("Also delete ${r.sidecars.size} info file(s)", Modifier.weight(1f))
            Switch(r.deleteSidecars, { r.deleteSidecars = it })
        }
        Text(r.sidecars.joinToString { (it.name ?: "?") + if (it.isDirectory) "/" else "" },
            style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RollProblems(r: Roll, keepTiffs: Boolean) {
    val replacing = r.replacing(keepTiffs)
    if (replacing.isNotEmpty()) Row(Modifier.fillMaxWidth().clickable { r.overwrite = !r.overwrite }, verticalAlignment = Alignment.Top) {
        Checkbox(r.overwrite, { r.overwrite = it })
        Text("Replace the JPEG(s) already there with these names: ${replacing.take(6).joinToString()}" +
            if (replacing.size > 6) " and ${replacing.size - 6} more" else "",
            style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
    r.problems(keepTiffs).forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
}
