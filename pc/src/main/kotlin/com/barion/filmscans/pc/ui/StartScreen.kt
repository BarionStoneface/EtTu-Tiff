package com.barion.filmscans.pc

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

/* The first screen, and the wait while folders are read. */

@Composable
internal fun StartScreen(m: AppModel, pick: Pickers) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text("Turns folders of TIFF scans into full-quality JPEGs, one roll per folder, and tags the lab's own " +
            "JPEGs with the same details without re-saving them.", style = MaterialTheme.typography.bodyLarge)
        Text("Keeps the pixels, colour profile, DPI, scanner and original scan date. Drops location and " +
            "everything else, then adds your camera, film, push/pull and copyright. Every file's created and " +
            "modified dates become its scan date.", style = MaterialTheme.typography.bodyMedium)
        if (m.settings.author.isBlank()) Text("Add your name in Settings first, so the copyright gets written.",
            color = MaterialTheme.colorScheme.primary)

        Button(onClick = { pick.folder("Choose the scans folder")?.let { m.open(listOf(it)) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Choose scans folder")
        }
        Text("A folder holding one roll, or a folder of roll folders. Zips in it can be unzipped from there.",
            style = MaterialTheme.typography.bodySmall)

        OutlinedButton(onClick = { pick.zips().takeIf { it.isNotEmpty() }?.let { m.makePlans(it) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Unzip a zip")
        }
        Text("Any zip, not only lab downloads. Zips inside it are unpacked too, and a password-protected one asks for " +
            "its password. It unzips next to the zip unless you choose another folder, and you'll see everything " +
            "that's in it, and can rename or leave out folders, before anything is written.",
            style = MaterialTheme.typography.bodySmall)
        DeleteZipsSwitch(m)

        OutlinedButton(onClick = { pick.folder("Choose the folder of photos")?.let { m.lookForDates(it) } }, modifier = Modifier.fillMaxWidth()) {
            Text("Fix file dates in a folder")
        }
        Text("Gives every photo in a folder (subfolders too) its scan date as its created and modified date, from the " +
            "date inside it. For photos copied from the phone, which can't always set them. Nothing else changes.",
            style = MaterialTheme.typography.bodySmall)

        HorizontalDivider()
        Text("You can also drag a folder or a zip onto this window.", style = MaterialTheme.typography.bodySmall)
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
