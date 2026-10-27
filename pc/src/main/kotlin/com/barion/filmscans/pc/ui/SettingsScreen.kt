package com.barion.filmscans.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.barion.filmscans.core.License
import com.barion.filmscans.core.Metadata
import java.time.LocalDate

/* Settings, kept between runs. */

@Composable
internal fun SettingsScreen(m: AppModel) {
    var menuAdded by remember { mutableStateOf(Shell.menuAdded()) }
    var menuNote by remember { mutableStateOf("") }
    ScrollList(contentPadding = PaddingValues(16.dp), spacing = 12.dp) {
        item {
            Text("Written into every photo: EXIF Artist and Copyright, XMP creator, rights and usage terms, " +
                "IPTC by-line and copyright, and a JPEG comment.", style = MaterialTheme.typography.bodySmall)
        }
        item {
            OutlinedTextField(m.settings.author, { m.settings.author = it }, label = { Text("Your name (author / copyright holder)") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            PickField("Usage", m.settings.license.label, License.entries.map { it.label },
                { l -> m.settings.license = License.entries.first { it.label == l } }, Modifier.fillMaxWidth())
        }
        item {
            OutlinedTextField(m.settings.contact, { m.settings.contact = it }, label = { Text("Contact for permission (optional)") },
                supportingText = { Text("Goes into every photo, so only put something you're fine being public.") },
                singleLine = true, modifier = Modifier.fillMaxWidth())
        }
        item {
            val c = m.settings.credits()
            if (c.author.isNotBlank()) Text("${Metadata.copyrightNotice(c, LocalDate.now().year)}\n${Metadata.usageTerms(c)}",
                style = MaterialTheme.typography.bodySmall)
        }
        item {
            PickField("Colours", m.settings.theme.label, AppTheme.entries.map { it.label },
                { l -> m.settings.theme = AppTheme.entries.first { it.label == l } }, Modifier.fillMaxWidth())
        }
        item {
            SettingSwitch("Delete info files when replacing TIFFs", m.settings.deleteInfoFiles, { m.settings.deleteInfoFiles = it },
                "The starting choice for each roll: .thm, .xmp and similar files next to the TIFFs. Only ever " +
                    "deleted when you replace the TIFFs, and each roll can still change it. Off: they're kept.")
        }
        item {
            SettingSwitch("Date the lab's JPEGs when unzipping photo zips", m.settings.labJpegDates, { m.settings.labJpegDates = it },
                "A JPEG with no date taken gets its scan date (from its own XMP data, or failing that its date " +
                    "in the zip), so galleries sort it by when it was scanned, not when it was unzipped. Nothing else " +
                    "in the file is changed. Zips that aren't mostly photos are left exactly as they are; either way " +
                    "it can be switched for each zip before unzipping.")
        }
        if (Shell.isWindows) item {
            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                SettingSwitch("Right-click menu in Explorer", menuAdded, { on ->
                    menuNote = (if (on) Shell.addMenu() else Shell.removeMenu()) ?: ""
                    menuAdded = Shell.menuAdded()
                }, "Adds \"Open in Et Tu, Tiff?\" to folders and \"Unzip with Et Tu, Tiff?\" to zips, for your Windows " +
                    "account only. On Windows 11 they're under \"Show more options\".")
                if (menuNote.isNotEmpty()) Text(menuNote, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
            }
        }
        item {
            Text("JPEG quality: ${m.settings.quality}${if (m.settings.quality == 100) " (best)" else ""}")
            Slider(value = m.settings.quality.toFloat(), onValueChange = { m.settings.quality = it.toInt() }, valueRange = 85f..100f, steps = 14)
            Text("Colour is always stored at full resolution (4:4:4).", style = MaterialTheme.typography.bodySmall)
        }
    }
}
