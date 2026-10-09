package com.barion.filmscans

import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.Metadata
import java.time.LocalDate

/* Questions and reports that pop up over the rolls. */

@Composable
internal fun PreviewDialog(f: ScanFile, onClose: () -> Unit) {
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

/** One line of a dry run; problems are shown in red. */
private class DryLine(val text: String, val problem: Boolean = false, val heading: Boolean = false)

/**
 * Exactly what pressing the button would do, file by file, worked out the same way the real run
 * does it. Nothing is written, deleted or renamed.
 */
private fun dryRunLines(m: AppModel): List<DryLine> {
    val keep = m.settings.keepTiffs
    val out = mutableListOf(DryLine("Nothing has been changed. This is what would happen:"))
    for (r in m.activeRolls) {
        out += DryLine(r.name, heading = true)
        val where = when {
            r.jpegRoll && keep -> "into a new folder, ${cleanName(r.copiesFolder)}"
            !keep && r.renameFolder && cleanName(r.newFolderName) != r.name -> "here; the folder becomes ${cleanName(r.newFolderName)}"
            else -> "here"
        }
        out += DryLine(when {
            r.jpegRoll && keep -> "${r.files.size} lab JPEG(s) copied and tagged $where. The originals stay as they are."
            r.jpegRoll -> "${r.files.size} lab JPEG(s) tagged in place, $where. Pictures not re-saved; old details replaced."
            keep -> "${r.files.size} TIFF(s) converted to JPEG $where. The TIFFs stay."
            else -> "${r.files.size} TIFF(s) converted to JPEG $where. Each TIFF is deleted once its JPEG is saved and checked."
        })
        for (f in r.files) {
            val date = when {
                f.date.embedded -> "${MINUTE.format(f.date.date)} (${plainSource(f.date.source)})"
                r.overrideDate() != null -> "${MINUTE.format(r.overrideDate())} (typed in)"
                r.useFileDates -> "${MINUTE.format(f.date.date)} (file date on the phone)"
                else -> null
            }
            out += DryLine("${f.name} → ${f.newName}.jpg · " + (date?.let { "scanned $it" } ?: "no scan date chosen"),
                problem = date == null || f.error != null)
            f.error?.let { out += DryLine("   can't be read: $it", problem = true) }
        }
        val replacing = r.replacing(keep)
        if (replacing.isNotEmpty()) out += DryLine((if (r.overwrite) "Replaces " else "Would need to replace ") +
            "JPEG(s) already there: ${replacing.joinToString()}", problem = !r.overwrite)
        if (!keep && r.deleteSidecars && r.sidecars.isNotEmpty())
            out += DryLine("Also deletes ${r.sidecars.size} info file(s): " + r.sidecars.joinToString { it.name ?: "?" })
        else if (r.sidecars.isNotEmpty()) out += DryLine("Info files kept: " + r.sidecars.joinToString { it.name ?: "?" })
        r.problems(keep).forEach { out += DryLine("Blocks the run: $it", problem = true) }
    }
    val c = m.settings.credits()
    out += DryLine("Every photo", heading = true)
    out += DryLine(if (c.author.isBlank()) "No name set, so no copyright is written." else
        Metadata.copyrightNotice(c, LocalDate.now().year).replace(LocalDate.now().year.toString(), "<scan year>"),
        problem = c.author.isBlank())
    out += DryLine("Location and every other detail in the originals are dropped; the scanner, the original file name, the colour profile and the resolution are kept.")
    return out
}

@Composable
internal fun DryRunDialog(m: AppModel, onClose: () -> Unit) {
    val lines = remember { dryRunLines(m) }
    val problems = lines.count { it.problem }
    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (problems == 0) "Dry run: ready to go" else "Dry run: $problems thing(s) to fix") },
        text = {
            LazyColumn(Modifier.heightIn(max = 520.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                items(lines) { l ->
                    Text(l.text,
                        style = if (l.heading) MaterialTheme.typography.titleSmall else MaterialTheme.typography.bodySmall,
                        fontWeight = if (l.heading) FontWeight.SemiBold else null,
                        color = if (l.problem) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                        modifier = if (l.heading) Modifier.padding(top = 8.dp) else Modifier)
                }
            }
        },
        confirmButton = { TextButton(onClick = onClose) { Text("Close") } },
    )
}

@Composable
internal fun ConfirmDialog(m: AppModel, onDismiss: () -> Unit, onGo: () -> Unit) {
    val rolls = m.activeRolls
    val n = rolls.sumOf { it.files.size }
    val replace = !m.settings.keepTiffs
    val side = if (replace) rolls.filter { it.deleteSidecars }.sumOf { it.sidecars.size } else 0
    val overwrites = rolls.filter { it.overwrite }.sumOf { it.replacing(m.settings.keepTiffs).size }
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
                if (m.settings.author.isBlank()) Text("No name set in Settings, so no copyright will be written.",
                    color = MaterialTheme.colorScheme.error)
                else Text(Metadata.copyrightNotice(m.settings.credits(), LocalDate.now().year).replace(LocalDate.now().year.toString(), "<scan year>"),
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
