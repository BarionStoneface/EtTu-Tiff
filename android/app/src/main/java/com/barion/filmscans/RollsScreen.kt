package com.barion.filmscans

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AssistChip
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/* The rolls found in a folder, before converting. */

@Composable
internal fun RollList(m: AppModel) {
    var preview by remember { mutableStateOf<ScanFile?>(null) }
    val list = rememberLazyListState()
    val scope = rememberCoroutineScope()
    val zipItems = (if (m.zipsFound.isNotEmpty()) 1 else 0) + (if (m.notes.isNotEmpty()) 1 else 0)
    val firstRoll = zipItems + 2 // notes and zip cards, mode card, count line
    Column(Modifier.fillMaxSize()) {
        val shown = m.shownRolls
        if (m.mixed) WorkOnRow(m)
        if (shown.size > 1) RollJumpRow(shown) { i -> scope.launch { list.animateScrollToItem(firstRoll + i) } }
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
                    else "${shown.size} roll(s) in ${m.rootName}. Each roll starts blank — nothing carries over." +
                        if (shown.size < m.rolls.size) " ${m.rolls.size - shown.size} other roll(s) aren't shown and won't be touched." else "",
                    style = MaterialTheme.typography.bodySmall)
            }
            itemsIndexed(shown, key = { _, r -> r.folder.uri.toString() }) { i, r ->
                val next = if (i < shown.lastIndex) ({ scope.launch { list.animateScrollToItem(firstRoll + i + 1) }; Unit }) else null
                RollCard(m, r, next) { f -> preview = f }
            }
        }
    }
    preview?.let { PreviewDialog(it) { preview = null } }
}

/** This folder holds both TIFF rolls and lab JPEG rolls: choose which to work on. Only those are touched. */
@Composable
private fun WorkOnRow(m: AppModel) {
    val tiffs = m.rolls.count { !it.jpegRoll }
    val jpegs = m.rolls.count { it.jpegRoll }
    Column(Modifier.padding(horizontal = 12.dp, vertical = 4.dp)) {
        Text("This folder has $tiffs TIFF roll(s) and $jpegs lab JPEG roll(s). Work on:", style = MaterialTheme.typography.bodySmall)
        Row(horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            WorkOn.entries.forEach { w ->
                FilterChip(selected = m.workOn == w, onClick = { m.workOn = w }, label = { Text(w.label) })
            }
        }
    }
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
                Text("${z.doc.name} · ${sizeText(z.doc.size)}", style = MaterialTheme.typography.bodySmall)
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

/** "Convert 36 and tag 36, keep the originals". */
internal fun actionLabel(rolls: List<Roll>, replace: Boolean): String {
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
    val hasJpegs = m.shownRolls.any { it.jpegRoll }
    val hasTiffs = m.shownRolls.any { !it.jpegRoll }
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
