package com.barion.filmscans

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.consumeWindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.isImeVisible
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import kotlinx.coroutines.launch

/* The app's frame: top bar, bottom button, and which screen shows. */

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun App(m: AppModel) {
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var dryRun by remember { mutableStateOf(false) }
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
                    else if (m.phase != Phase.Converting && m.phase != Phase.Unzipping && m.phase != Phase.Planning) {
                        if (m.phase == Phase.Ready && m.activeRolls.isNotEmpty()) TextButton(onClick = { dryRun = true }) { Text("Dry run") }
                        TextButton(onClick = { settings = true }) { Text("Settings") }
                    }
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
                val n by remember { derivedStateOf { m.activeRolls.sumOf { it.files.size } } }
                val blocked by remember { derivedStateOf { m.activeRolls.any { it.problems(m.keepTiffs).isNotEmpty() } } }
                val replace = !m.keepTiffs
                Button(
                    onClick = { confirm = true },
                    enabled = n > 0 && !blocked,
                    colors = if (replace) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                        contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                    // Above the phone's navigation bar / gesture area, not under it.
                    modifier = Modifier.navigationBarsPadding().fillMaxWidth().padding(16.dp),
                ) {
                    Text(if (blocked) "Fix the issues marked in red" else actionLabel(m.activeRolls, replace))
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
    if (dryRun) DryRunDialog(m) { dryRun = false }
    if (leave) AlertDialog(
        onDismissRequest = { leave = false },
        title = { Text("Leave these rolls?") },
        text = { Text("Nothing has been converted yet. The details you typed for these rolls will be cleared.") },
        confirmButton = { TextButton(onClick = { leave = false; m.reset() }) { Text("Leave") } },
        dismissButton = { TextButton(onClick = { leave = false }) { Text("Stay") } },
    )
}
