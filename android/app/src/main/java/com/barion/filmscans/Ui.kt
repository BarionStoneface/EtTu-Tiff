package com.barion.filmscans

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.Image
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.IconButton
import androidx.compose.material3.SuggestionChip
import androidx.compose.runtime.derivedStateOf
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.Dp
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
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
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
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.FILM_STOCKS
import com.barion.filmscans.core.License
import com.barion.filmscans.core.Metadata
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.PROCESS_TAGS
import com.barion.filmscans.core.isoFor
import java.time.LocalDate
import java.time.format.DateTimeFormatter

private val PUSH_OPTIONS = listOf(-2 to "Pull −2", -1 to "Pull −1", 0 to "Box speed", 1 to "Push +1", 2 to "Push +2", 3 to "Push +3")
private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd")
private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun App(m: AppModel) {
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) m.open(uri)
    }
    // Unzipping a download: pick the zip(s), then the folder the rolls should go in.
    val destPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        if (uri != null) m.unzipDownloads(uri) else m.pendingZips = emptyList()
    }
    val zipPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
        if (uris.isNotEmpty()) { m.pendingZips = uris; destPicker.launch(Places.pictures) }
    }
    // Background previews pause while the keyboard is up.
    val ime = WindowInsets.isImeVisible
    LaunchedEffect(ime) { m.typing = ime }

    val goBack: () -> Unit = {
        when {
            settings -> { m.saveSettings(); settings = false }
            m.phase == Phase.Ready -> leave = true
            m.phase == Phase.Plan -> m.leavePlan()
            m.phase == Phase.Done -> m.reset()
        }
    }
    val canGoBack = settings || m.phase == Phase.Ready || m.phase == Phase.Plan || m.phase == Phase.Done
    BackHandler(enabled = canGoBack, onBack = goBack)

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    if (settings) Text("Settings")
                    else Text(buildAnnotatedString {
                        append("Et Tu, Tiff")
                        withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append("?") }
                    })
                },
                navigationIcon = {
                    if (canGoBack)
                        IconButton(onClick = goBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                },
                actions = {
                    if (settings) TextButton(onClick = { m.saveSettings(); settings = false }) { Text("Done") }
                    else if (m.phase != Phase.Converting && m.phase != Phase.Unzipping && m.phase != Phase.Planning)
                        TextButton(onClick = { settings = true }) { Text("Settings") }
                },
            )
        },
        bottomBar = {
            // Hidden while typing, so the keyboard doesn't push it up over the fields.
            if (!settings && m.phase == Phase.Plan && !WindowInsets.isImeVisible) {
                val problems by remember { derivedStateOf { m.planProblems() } }
                val files by remember { derivedStateOf { m.plans.sumOf { p -> p.build()?.targets?.size ?: 0 } } }
                val bytes by remember { derivedStateOf { m.plans.sumOf { p -> p.build()?.bytes ?: p.zipSize } } }
                val streamed = m.plans.any { it.listing == null || it.listing.sealed.isNotEmpty() }
                Button(
                    onClick = { m.startUnzip() },
                    enabled = problems.isEmpty(),
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp),
                ) {
                    Text(when {
                        problems.isNotEmpty() -> "Fix the issues marked in red"
                        streamed && files == 0 -> "Unzip (${sizeText(bytes)})"
                        else -> "Unzip $files file(s) · ${sizeText(bytes)}"
                    })
                }
            }
            if (!settings && m.phase == Phase.Ready && !WindowInsets.isImeVisible) {
                val n by remember { derivedStateOf { m.rolls.filter { it.include }.sumOf { it.files.size } } }
                val blocked by remember { derivedStateOf { m.rolls.any { it.include && it.problems(m.keepTiffs).isNotEmpty() } } }
                val replace = !m.keepTiffs
                Button(
                    onClick = { confirm = true },
                    enabled = n > 0 && !blocked,
                    colors = if (replace) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                    // Above the phone's navigation bar / gesture area, not under it.
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp),
                ) {
                    Text(if (blocked) "Fix the issues marked in red" else actionLabel(m.rolls.filter { it.include }, replace))
                }
            }
        },
    ) { pad ->
        // The keyboard inset is applied once, here, after what the bars already cover.
        Column(Modifier.padding(pad).consumeWindowInsets(pad).imePadding().fillMaxSize()) {
            when {
                settings -> SettingsScreen(m)
                m.phase == Phase.Start -> StartScreen(m, pick = { picker.launch(null) }, unzip = {
                    zipPicker.launch(arrayOf("application/zip", "application/x-zip-compressed", "application/x-zip",
                        "multipart/x-zip", "application/octet-stream"))
                })
                m.phase == Phase.Scanning -> Busy("Reading scans…", m.status)
                m.phase == Phase.Planning -> Busy("Reading the zip…", m.status)
                m.phase == Phase.Plan -> PlanScreen(m)
                m.phase == Phase.Unzipping -> UnzipScreen(m)
                m.phase == Phase.Ready -> RollList(m)
                m.phase == Phase.Converting -> ProgressScreen(m)
                else -> DoneScreen(m)
            }
        }
    }

    if (confirm) ConfirmDialog(m, onDismiss = { confirm = false }) { confirm = false; m.convert() }
    if (leave) AlertDialog(
        onDismissRequest = { leave = false },
        title = { Text("Leave these rolls?") },
        text = { Text("Nothing has been converted yet. The details you typed for these rolls will be cleared.") },
        confirmButton = { TextButton(onClick = { leave = false; m.reset() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("Stay") } },
    )
}

@Composable
private fun UnzipScreen(m: AppModel) {
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
private fun StartScreen(m: AppModel, pick: () -> Unit, unzip: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own " +
            "JPEGs with the same details without re-saving them.",
            style = MaterialTheme.typography.bodyLarge)
        Text("Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and " +
            "everything else, then adds your camera, film, push/pull and copyright. JPEGs are saved in the " +
            "same folder as their TIFFs; you choose whether the TIFFs are replaced or kept.", style = MaterialTheme.typography.bodyMedium)
        if (m.author.isBlank()) Text("Add your name in Settings first, so the copyright gets written.",
            color = MaterialTheme.colorScheme.primary)
        Button(onClick = pick, modifier = Modifier.fillMaxWidth()) { Text("Choose scans folder") }
        Text("Pick a folder holding one roll, or a folder of roll folders. Zips in it can be unzipped from there.",
            style = MaterialTheme.typography.bodySmall)
        OutlinedButton(onClick = unzip, modifier = Modifier.fillMaxWidth()) { Text("Unzip a download (.zip)") }
        Text("Pick the zip (zips inside it are handled too), then the folder the rolls should go in, e.g. " +
            "Pictures. Android doesn't let apps save into the Download folder itself. You'll see everything " +
            "that's in it, and can rename or leave out folders, before anything is written.",
            style = MaterialTheme.typography.bodySmall)
        DeleteZipsSwitch(m)
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
    var preview by remember { mutableStateOf<ScanFile?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val zipItems = (if (m.zipsFound.isNotEmpty()) 1 else 0) + (if (m.notes.isNotEmpty()) 1 else 0)
    val firstRoll = zipItems + 2 // notes and zip cards, mode card, count line
    Column(Modifier.fillMaxSize()) {
        if (m.rolls.size > 1) RollJumpRow(m.rolls) { i -> scope.launch { list.animateScrollToItem(firstRoll + i) } }
        LazyColumn(
            Modifier.fillMaxWidth().weight(1f),
            state = list,
            contentPadding = PaddingValues(12.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            if (m.notes.isNotEmpty()) item { NotesCard(m) }
            if (m.zipsFound.isNotEmpty()) item { ZipCard(m) }
            item { ModeCard(m) }
            item {
                Text(if (m.rolls.isEmpty()) "No TIFFs here yet. Unzip above to get to the rolls inside."
                    else "${m.rolls.size} roll(s) in ${m.rootName}. Each roll starts blank — nothing carries over.",
                    style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(m.rolls, key = { _, r -> r.folder.uri.toString() }) { i, r ->
                val next = if (i < m.rolls.lastIndex) ({ scope.launch { list.animateScrollToItem(firstRoll + i + 1) }; Unit }) else null
                RollCard(m, r, next) { f -> preview = f }
            }
        }
    }
    preview?.let { PreviewDialog(it) { preview = null } }
}

/** Jump straight to any roll. */
@Composable
private fun RollJumpRow(rolls: List<Roll>, onJump: (Int) -> Unit) {
    LazyRow(
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        itemsIndexed(rolls, key = { _, r -> r.folder.uri.toString() }) { i, r ->
            AssistChip(onClick = { onJump(i) }, label = { Text(r.name, maxLines = 1) })
        }
    }
}

/** What happened while unzipping, until you move on. */
@Composable
private fun NotesCard(m: AppModel) {
    var open by remember { mutableStateOf(m.notes.any { it.startsWith("✗") || it.startsWith("Stopped") }) }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            val bad = m.notes.count { it.startsWith("✗") }
            Text(if (bad == 0) "Unzipped" else "Unzipped, with $bad problem(s)", style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.Bold, color = if (bad == 0) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.error)
            val shown = if (open) m.notes else m.notes.take(2)
            shown.forEach { Text(it, style = MaterialTheme.typography.bodySmall,
                color = if (it.startsWith("✗")) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface) }
            if (m.notes.size > 2) TextButton(onClick = { open = !open }) { Text(if (open) "Show less" else "Show all ${m.notes.size}") }
        }
    }
}

@Composable
private fun ZipCard(m: AppModel) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Zip files here", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            m.zipsFound.forEach { z ->
                Text("${z.name} · ${"%.1f".format(z.length() / 1e9)} GB", style = MaterialTheme.typography.bodySmall)
            }
            Text("Each is unzipped into a folder next to it; zips inside it are unpacked too. You'll see what's inside first.",
                style = MaterialTheme.typography.bodySmall)
            DeleteZipsSwitch(m)
            Button(onClick = { m.unzipFound() }, modifier = Modifier.fillMaxWidth()) {
                Text(if (m.zipsFound.size == 1) "Look inside" else "Look inside all ${m.zipsFound.size}")
            }
        }
    }
}

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

/** "Convert 36 and tag 36, keep the originals". */
private fun actionLabel(rolls: List<Roll>, replace: Boolean): String {
    val tiffs = rolls.filter { !it.jpegRoll }.sumOf { it.files.size }
    val jpegs = rolls.filter { it.jpegRoll }.sumOf { it.files.size }
    val what = listOfNotNull(tiffs.takeIf { it > 0 }?.let { "Convert $it" }, jpegs.takeIf { it > 0 }?.let { if (tiffs > 0) "tag $it" else "Tag $it" })
        .joinToString(" and ").ifEmpty { "Convert 0" }
    return when {
        !replace -> "$what, keep the originals"
        tiffs > 0 -> "$what, delete the TIFFs"
        else -> "$what in place"
    }
}

/** The one choice that deletes files, stated plainly before anything happens. */
@Composable
private fun ModeCard(m: AppModel) {
    val replace = !m.keepTiffs
    val hasJpegs = m.rolls.any { it.jpegRoll }
    val hasTiffs = m.rolls.any { !it.jpegRoll }
    Card(
        Modifier.fillMaxWidth(),
        colors = if (replace) CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer) else CardDefaults.cardColors(),
    ) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(if (hasJpegs) "What happens to the originals?" else "What happens to the TIFFs?",
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            Row(Modifier.fillMaxWidth().clickable { m.keepTiffs = true }, verticalAlignment = Alignment.Top) {
                RadioButton(selected = m.keepTiffs, onClick = { m.keepTiffs = true })
                Column(Modifier.padding(top = 12.dp)) {
                    Text("Keep them", fontWeight = FontWeight.SemiBold)
                    Text(listOfNotNull(
                        if (hasTiffs) "The JPEGs are added next to the TIFFs." else null,
                        if (hasJpegs) "The lab's JPEGs are copied, tagged, into a new folder beside each roll." else null,
                        "Nothing is deleted or renamed.",
                    ).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
            Row(Modifier.fillMaxWidth().clickable { m.keepTiffs = false }, verticalAlignment = Alignment.Top) {
                RadioButton(selected = replace, onClick = { m.keepTiffs = false })
                Column(Modifier.padding(top = 12.dp)) {
                    Text(if (hasTiffs) "Replace them: DELETE the TIFFs" else "Replace them: tag the JPEGs in place",
                        fontWeight = FontWeight.SemiBold,
                        color = if (replace) MaterialTheme.colorScheme.onErrorContainer else MaterialTheme.colorScheme.error)
                    Text(listOfNotNull(
                        if (hasTiffs) "Each TIFF is permanently deleted once its JPEG is saved and checked. Deleted TIFFs do " +
                            "not go to the Recycle bin, so keep a backup if you might want them." else null,
                        if (hasJpegs) "The lab's JPEGs get the new details and names in place. Their pictures aren't " +
                            "re-saved, but their old details (including any location) are replaced." else null,
                    ).joinToString(" "), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

@Composable
private fun RollCard(m: AppModel, r: Roll, onNext: (() -> Unit)?, onPreview: (ScanFile) -> Unit) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            RollHeader(r)
            if (!r.include) return@Column
            ThumbStrip(r, onPreview)
            RollFacts(r)
            if (m.rolls.size > 1) CopyDetails(r, m.rolls)
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
private fun PreviewDialog(f: ScanFile, onClose: () -> Unit) {
    AlertDialog(
        onDismissRequest = onClose,
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
        title = { Text(f.name, style = MaterialTheme.typography.titleMedium) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                val b = f.thumb
                if (b != null) Image(b, contentDescription = f.name, contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxWidth().aspectRatio(b.width.toFloat() / b.height))
                else Text(f.error ?: "Preview still loading…")
                Text("→ ${f.newName}.jpg · ${f.width}×${f.height} · ${f.bits}-bit · scanned ${MINUTE.format(f.date.date)}",
                    style = MaterialTheme.typography.bodySmall)
            }
        },
    )
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

/** "XMP xmp:MetadataDate" → "XMP MetadataDate". */
fun plainSource(s: String) = s.replace(Regex("""XMP (xmp|exif|photoshop):"""), "XMP ")

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

@Composable
private fun ConfirmDialog(m: AppModel, onDismiss: () -> Unit, onGo: () -> Unit) {
    val rolls = m.rolls.filter { it.include }
    val n = rolls.sumOf { it.files.size }
    val replace = !m.keepTiffs
    val side = if (replace) rolls.filter { it.deleteSidecars }.sumOf { it.sidecars.size } else 0
    val overwrites = rolls.filter { it.overwrite }.sumOf { it.replacing(m.keepTiffs).size }
    val tiffs = rolls.filter { !it.jpegRoll }.sumOf { it.files.size }
    val jpegs = rolls.filter { it.jpegRoll }.sumOf { it.files.size }
    val renames = if (replace) rolls.filter { it.renameFolder && cleanName(it.newFolderName) != it.name } else emptyList()
    val noFilm = rolls.count { it.film.isBlank() }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(when {
            replace && tiffs > 0 -> "Convert and DELETE $tiffs TIFFs?"
            replace -> "Tag $jpegs JPEGs in place?"
            else -> actionLabel(rolls, false) + "?"
        }) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                if (replace) {
                    if (tiffs > 0) Text("All $tiffs TIFF files will be permanently deleted, each one right after its JPEG is saved " +
                        "and checked. They will not be in the Recycle bin.",
                        color = MaterialTheme.colorScheme.error, fontWeight = FontWeight.SemiBold)
                    if (jpegs > 0) Text("$jpegs lab JPEG(s) get the new details and names in place; their old details are " +
                        "replaced. The pictures themselves aren't re-saved.")
                    if (side > 0) Text("$side info file(s) will also be deleted.")
                    renames.forEach { Text("Folder renamed: ${it.name} → ${cleanName(it.newFolderName)}") }
                } else {
                    if (tiffs > 0) Text("The JPEGs are added next to the TIFFs.")
                    rolls.filter { it.jpegRoll }.forEach { Text("${it.name}: tagged copies go into a new folder, ${cleanName(it.copiesFolder)}") }
                    Text("Nothing is deleted or renamed.")
                }
                if (overwrites > 0) Text("$overwrites JPEG(s) already in the folders will be replaced.")
                if (noFilm > 0) Text("$noFilm roll(s) have no film stock set.", color = MaterialTheme.colorScheme.primary)
                if (m.author.isBlank()) Text("No name set in Settings, so no copyright will be written.",
                    color = MaterialTheme.colorScheme.error)
                else Text(Metadata.copyrightNotice(m.credits(), LocalDate.now().year).replace(LocalDate.now().year.toString(), "<scan year>"),
                    style = MaterialTheme.typography.bodySmall)
            }
        },
        confirmButton = {
            Button(
                onClick = onGo,
                colors = if (replace) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
            ) { Text(when { replace && tiffs > 0 -> "Convert and delete"; replace -> "Tag in place"; tiffs > 0 -> "Convert"; else -> "Tag" }) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Back") } },
    )
}

@Composable
private fun ProgressScreen(m: AppModel) {
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
private fun DoneScreen(m: AppModel) {
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

@Composable
private fun SettingsScreen(m: AppModel) {
    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(16.dp),
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
            PickField("Colours", m.theme.label, AppTheme.entries.map { it.label },
                { l -> m.theme = AppTheme.entries.first { it.label == l } }, Modifier.fillMaxWidth())
        }
        item {
            SettingSwitch("Delete info files when replacing TIFFs", m.deleteInfoFiles, { m.deleteInfoFiles = it },
                "The starting choice for each roll: .thm, .xmp and similar files next to the TIFFs. Only ever " +
                    "deleted when you replace the TIFFs, and each roll can still change it. Off: they're kept.")
        }
        item {
            SettingSwitch("Date the lab's JPEGs when unzipping", m.labJpegDates, { m.labJpegDates = it },
                "A JPEG with no date taken gets its scan date (from its own XMP data, or failing that its date " +
                    "in the zip), so galleries sort it by when it was scanned, not when it was unzipped. Nothing else " +
                    "in the file is changed.")
        }
        item {
            Text("JPEG quality: ${m.quality}${if (m.quality == 100) " (best)" else ""}")
            Slider(value = m.quality.toFloat(), onValueChange = { m.quality = it.toInt() }, valueRange = 85f..100f, steps = 14)
            Text("Colour is always stored at full resolution (4:4:4).", style = MaterialTheme.typography.bodySmall)
        }
    }
}

/**
 * Text field with suggestions shown as chips under it while it's focused, plus a ▾ button for
 * the full list. No popup opens while typing, which keeps typing quick.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SuggestField(label: String, value: String, onValue: (String) -> Unit, options: List<String>) {
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

/** "1.4 GB", "350 MB". */
fun sizeText(b: Long): String = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%d MB".format((b + 999_999) / 1_000_000)

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
