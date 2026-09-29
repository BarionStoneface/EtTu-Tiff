package com.barion.filmscans

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.MenuAnchorType
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Slider
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.FILM_STOCKS
import com.barion.filmscans.core.License
import com.barion.filmscans.core.Metadata
import com.barion.filmscans.core.PROCESS_TAGS
import com.barion.filmscans.core.isoFor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PUSH_OPTIONS = listOf(-2 to "Pull −2", -1 to "Pull −1", 0 to "Box speed", 1 to "Push +1", 2 to "Push +2", 3 to "Push +3")
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(m: AppModel) {
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) m.open(uri)
    }
    BackHandler(enabled = settings) { m.saveSettings(); settings = false }
    BackHandler(enabled = !settings && (m.phase == Phase.Ready || m.phase == Phase.Done)) { m.reset() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(if (settings) "Settings" else "Et Tu, Tiff?") },
                actions = {
                    if (settings) TextButton(onClick = { m.saveSettings(); settings = false }) { Text("Done") }
                    else if (m.phase != Phase.Converting) TextButton(onClick = { settings = true }) { Text("Settings") }
                },
            )
        },
        bottomBar = {
            if (!settings && m.phase == Phase.Ready) {
                val n = m.rolls.filter { it.include }.sumOf { it.files.size }
                val blocked = m.rolls.any { it.include && it.problems().isNotEmpty() }
                Button(
                    onClick = { confirm = true },
                    enabled = n > 0 && !blocked,
                    modifier = Modifier.fillMaxWidth().padding(16.dp).imePadding(),
                ) { Text(if (blocked) "Fix the issues marked in red" else "Convert $n scans") }
            }
        },
    ) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            when {
                settings -> SettingsScreen(m)
                m.phase == Phase.Start -> StartScreen(m) { picker.launch(null) }
                m.phase == Phase.Scanning -> Busy("Reading scans…", m.status)
                m.phase == Phase.Ready -> RollList(m)
                else -> ProgressScreen(m) { m.reset() }
            }
        }
    }

    if (confirm) ConfirmDialog(m, onDismiss = { confirm = false }) { confirm = false; m.convert() }
}

@Composable
private fun StartScreen(m: AppModel, pick: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Turns folders of TIFF scans into full-quality JPEGs, one roll per folder.",
            style = MaterialTheme.typography.bodyLarge)
        Text("Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and " +
            "everything else, then adds your camera, film, push/pull and copyright. Deletes each TIFF only " +
            "after its JPEG checks out.", style = MaterialTheme.typography.bodyMedium)
        if (m.author.isBlank()) Text("Add your name in Settings first, so the copyright gets written.",
            color = MaterialTheme.colorScheme.primary)
        Button(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("Choose scans folder") }
        Text("Pick a folder holding one roll, or a folder of roll folders.", style = MaterialTheme.typography.bodySmall)
        if (m.status.isNotEmpty()) Text(m.status, color = MaterialTheme.colorScheme.error)
    }
}

@Composable
private fun Busy(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}

@Composable
private fun RollList(m: AppModel) {
    LazyColumn(
        Modifier.fillMaxSize().imePadding(),
        contentPadding = PaddingValues(12.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Text("${m.rolls.size} roll(s) in ${m.rootName}. Each roll starts blank — nothing carries over.",
                style = MaterialTheme.typography.bodySmall)
        }
        items(m.rolls, key = { it.folder.uri.toString() }) { RollCard(m, it) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RollCard(m: AppModel, r: Roll) {
    var showNames by remember { mutableStateOf(false) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Checkbox(checked = r.include, onCheckedChange = { r.include = it })
                Column {
                    Text(r.name, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                    val dates = r.files.map { it.date.date.toLocalDate() }.distinct().sorted()
                    val span = when (dates.size) {
                        0 -> ""; 1 -> DAY.format(dates[0]); else -> "${DAY.format(dates.first())} to ${DAY.format(dates.last())}"
                    }
                    Text("${r.files.size} scans · scanned $span", style = MaterialTheme.typography.bodySmall)
                }
            }
            if (!r.include) return@Column
            if (r.scanners.isNotEmpty()) Text("Scanner (kept): ${r.scanners.joinToString()}",
                style = MaterialTheme.typography.bodySmall)
            val bits = r.files.map { it.bits }.distinct().filter { it > 8 }
            if (bits.isNotEmpty()) Text("${bits.joinToString("/")}-bit scans: JPEG holds 8 bits per channel, so they're rounded to 8.",
                style = MaterialTheme.typography.bodySmall)

            ComboField("Camera body", r.camera, { r.camera = it }, m.cameras)
            ComboField("Lens (optional)", r.lens, { r.lens = it }, m.lenses)
            ComboField("Film stock", r.film, { r.film = it; isoFor(it)?.let { iso -> r.iso = iso.toString() } },
                m.customFilms + FILM_STOCKS.map { it.name })
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.iso, { v -> r.iso = v.filter { it.isDigit() }.take(5) }, label = { Text("Box ISO") },
                    singleLine = true, keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    modifier = Modifier.width(110.dp))
                PickField("Push / pull", PUSH_OPTIONS.first { it.first == r.push }.second, PUSH_OPTIONS.map { it.second },
                    { label -> r.push = PUSH_OPTIONS.first { it.second == label }.first }, Modifier.weight(1f))
            }
            r.meta().exposureIndex?.takeIf { r.push != 0 }?.let {
                Text("Shot at EI $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.primary)
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PROCESS_TAGS.forEach { tag ->
                    FilterChip(selected = tag in r.tags, onClick = { r.tags = if (tag in r.tags) r.tags - tag else r.tags + tag },
                        label = { Text(tag) })
                }
            }
            OutlinedTextField(r.notes, { r.notes = it }, label = { Text("Notes (optional)") }, modifier = Modifier.fillMaxWidth())
            ComboField("Lab / scanned by (optional)", r.lab, { r.lab = it }, m.labs)

            if (r.undated > 0) {
                Text("${r.undated} scan(s) have no date stored inside them; the file date is shown instead and may be " +
                    "the download date. Enter the real scan date to use it for those.",
                    color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                OutlinedTextField(r.dateOverride, { r.dateOverride = it }, singleLine = true,
                    label = { Text("Scan date, e.g. 2019-04-12 or 2019-04-12 14:30") }, modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()
            Text("File names", fontWeight = FontWeight.SemiBold)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(r.pattern, { r.pattern = it; r.applyPattern() }, singleLine = true, label = { Text("Pattern") },
                    modifier = Modifier.weight(1f))
                OutlinedButton(onClick = { r.applyPattern() }) { Text("Re-apply") }
            }
            Text("{name} original · {nn} 01, {nnn} 001 · {date} · {roll} · {film}. The original name is always " +
                "stored inside the JPEG.", style = MaterialTheme.typography.bodySmall)
            TextButton(onClick = { showNames = !showNames }) {
                Text(if (showNames) "Hide names" else "Edit names one by one (${r.files.first().newName}.jpg, …)")
            }
            if (showNames) r.files.forEach { f ->
                OutlinedTextField(f.newName, { f.newName = it }, singleLine = true, label = { Text(f.name) },
                    suffix = { Text(".jpg") }, modifier = Modifier.fillMaxWidth())
            }

            HorizontalDivider()
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("Rename folder", Modifier.weight(1f))
                Switch(r.renameFolder, { r.renameFolder = it })
            }
            if (r.renameFolder) OutlinedTextField(r.newFolderName, { r.newFolderName = it }, singleLine = true,
                label = { Text("New folder name") }, modifier = Modifier.fillMaxWidth())
            if (r.sidecars.isNotEmpty()) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("Delete ${r.sidecars.size} info file(s)", Modifier.weight(1f))
                    Switch(r.deleteSidecars, { r.deleteSidecars = it })
                }
                Text(r.sidecars.joinToString { (it.name ?: "?") + if (it.isDirectory) "/" else "" },
                    style = MaterialTheme.typography.bodySmall)
            }
            val replacing = r.replacing()
            if (replacing.isNotEmpty()) Text("Will replace existing: ${replacing.joinToString()}",
                color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodySmall)
            r.problems().forEach { Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun ConfirmDialog(m: AppModel, onDismiss: () -> Unit, onGo: () -> Unit) {
    val rolls = m.rolls.filter { it.include }
    val n = rolls.sumOf { it.files.size }
    val side = if (m.keepTiffs) 0 else rolls.filter { it.deleteSidecars }.sumOf { it.sidecars.size }
    val noFilm = rolls.count { it.film.isBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Convert $n scans?") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { m.keepTiffs = false }) {
                    RadioButton(selected = !m.keepTiffs, onClick = { m.keepTiffs = false })
                    Text("Replace the TIFFs (each is deleted once its JPEG checks out)")
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { m.keepTiffs = true }) {
                    RadioButton(selected = m.keepTiffs, onClick = { m.keepTiffs = true })
                    Text("Keep the TIFFs and add JPEGs beside them")
                }
                if (m.keepTiffs) Text("Nothing is deleted or renamed.", style = MaterialTheme.typography.bodySmall)
                if (side > 0) Text("$side info file(s) will be deleted.")
                if (noFilm > 0) Text("$noFilm roll(s) have no film stock set.", color = MaterialTheme.colorScheme.primary)
                if (m.author.isBlank()) Text("No name set in Settings, so no copyright will be written.",
                    color = MaterialTheme.colorScheme.error)
                else Text(Metadata.copyrightNotice(m.credits(), LocalDate.now().year).replace(LocalDate.now().year.toString(), "<scan year>"))
            }
        },
        confirmButton = { Button(onClick = onGo) { Text("Convert") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

@Composable
private fun ProgressScreen(m: AppModel, again: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (m.phase == Phase.Converting) {
            Text("Converting ${m.status}… ${m.done} of ${m.total}")
            LinearProgressIndicator(progress = { if (m.total == 0) 0f else m.done.toFloat() / m.total }, modifier = Modifier.fillMaxWidth())
            LinearProgressIndicator(progress = { m.fileProgress }, modifier = Modifier.fillMaxWidth())
            Text("Keep the app open until it's done.", style = MaterialTheme.typography.bodySmall)
        } else {
            val failed = m.log.count { it.startsWith("✗") }
            Text(if (failed == 0) "Done. ${m.total} scans converted." else "Done, with $failed problem(s). Those TIFFs are untouched.",
                style = MaterialTheme.typography.titleMedium)
            Button(onClick = again, modifier = Modifier.fillMaxWidth()) { Text("Convert another folder") }
        }
        LazyColumn(Modifier.fillMaxSize()) {
            itemsIndexed(m.log.reversed()) { _, line -> Text(line, style = MaterialTheme.typography.bodySmall) }
        }
    }
}

@Composable
private fun SettingsScreen(m: AppModel) {
    LazyColumn(Modifier.fillMaxSize().imePadding(), contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)) {
        item {
            Text("Written into every photo: EXIF Artist and Copyright, XMP creator, rights and usage terms, " +
                "IPTC by-line and copyright, and a JPEG comment.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(m.author, { m.author = it }, label = { Text("Your name (author / copyright holder)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            PickField("Usage", m.license.label, License.entries.map { it.label },
                { l -> m.license = License.entries.first { it.label == l } }, Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(m.contact, { m.contact = it }, label = { Text("Contact for permission (optional)") },
                supportingText = { Text("Goes into every photo, so only put something you're fine being public.") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            val c = m.credits()
            if (c.author.isNotBlank()) Text("${Metadata.copyrightNotice(c, LocalDate.now().year)}\n${Metadata.usageTerms(c)}",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            Text("JPEG quality: ${m.quality}${if (m.quality == 100) " (best)" else ""}")
            Slider(value = m.quality.toFloat(), onValueChange = { m.quality = it.toInt() }, valueRange = 85f..100f, steps = 14)
            Text("Colour is always stored at full resolution (4:4:4).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/** Text field with a dropdown of suggestions; anything can be typed. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun ComboField(label: String, value: String, onValue: (String) -> Unit, options: List<String>) {
    var open by remember { mutableStateOf(false) }
    val shown = (if (value.isBlank()) options else options.filter { it.contains(value.trim(), ignoreCase = true) && it != value })
        .distinct().take(40)
    ExposedDropdownMenuBox(expanded = open && shown.isNotEmpty(), onExpandedChange = { open = it }) {
        OutlinedTextField(
            value = value,
            onValueChange = { onValue(it); open = true },
            label = { Text(label) },
            singleLine = true,
            trailingIcon = { if (options.isNotEmpty()) ExposedDropdownMenuDefaults.TrailingIcon(expanded = open && shown.isNotEmpty()) },
            modifier = Modifier.fillMaxWidth().menuAnchor(MenuAnchorType.PrimaryEditable),
        )
        ExposedDropdownMenu(expanded = open && shown.isNotEmpty(), onDismissRequest = { open = false }) {
            shown.forEach { o -> DropdownMenuItem(text = { Text(o) }, onClick = { onValue(o); open = false }) }
        }
    }
}

/** Fixed choice list. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun PickField(label: String, value: String, options: List<String>, onPick: (String) -> Unit, modifier: Modifier) {
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
