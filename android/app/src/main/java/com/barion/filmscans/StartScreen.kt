package com.barion.filmscans

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/* The first screen, and the wait while folders are read. */

@Composable
internal fun StartScreen(m: AppModel, pick: () -> Unit, unzip: () -> Unit) {
    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own " +
            "JPEGs with the same details without re-saving them.",
            style = MaterialTheme.typography.bodyLarge)
        Text("Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and " +
            "everything else, then adds your camera, film, push/pull and copyright. JPEGs are saved in the " +
            "same folder as their TIFFs; you choose whether the TIFFs are replaced or kept.", style = MaterialTheme.typography.bodyMedium)
        if (m.settings.author.isBlank()) Text("Add your name in Settings first, so the copyright gets written.",
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
internal fun Busy(title: String, detail: String) {
    Column(Modifier.fillMaxSize().padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center) {
        CircularProgressIndicator()
        Spacer(Modifier.height(16.dp))
        Text(title)
        Text(detail, style = MaterialTheme.typography.bodySmall)
    }
}
