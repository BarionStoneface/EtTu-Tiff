package com.barion.filmscans.pc

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp

/* The window's frame: top bar, bottom button, and which screen shows. */

/** Content keeps to a readable width in a wide window. */
@Composable
internal fun Centered(fillHeight: Boolean = true, content: @Composable () -> Unit) {
    Box(if (fillHeight) Modifier.fillMaxSize() else Modifier.fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.widthIn(max = 920.dp).let { if (fillHeight) it.fillMaxSize() else it.fillMaxWidth() }) { content() }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun App(m: AppModel, pick: Pickers) {
    var settings by remember { mutableStateOf(false) }
    var confirm by remember { mutableStateOf(false) }
    var leave by remember { mutableStateOf(false) }
    var dryRun by remember { mutableStateOf(false) }

    val goBack: () -> Unit = {
        when {
            settings -> { m.settings.save(); settings = false }
            m.phase == Phase.Ready -> leave = true
            m.phase == Phase.Plan -> m.leavePlan()
            m.phase == Phase.Done || m.phase == Phase.Unzipped || m.phase == Phase.FixReady || m.phase == Phase.FixDone -> m.reset()
        }
    }
    val canGoBack = settings || m.phase in setOf(Phase.Ready, Phase.Plan, Phase.Done, Phase.Unzipped, Phase.FixReady, Phase.FixDone)

    Scaffold(
        topBar = {
            Column {
                TopAppBar(
                    title = {
                        if (settings) Text("Settings")
                        else Text(buildAnnotatedString {
                            append("Et Tu, Tiff")
                            withStyle(SpanStyle(color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.Bold)) { append("?") }
                        })
                    },
                    navigationIcon = {
                        if (canGoBack) IconButton(onClick = goBack) { Text("←", style = MaterialTheme.typography.titleLarge) }
                    },
                    actions = {
                        if (settings) TextButton(onClick = { m.settings.save(); settings = false }) { Text("Done") }
                        else if (!m.busy) {
                            if (m.phase == Phase.Ready && m.activeRolls.isNotEmpty()) TextButton(onClick = { dryRun = true }) { Text("Dry run") }
                            TextButton(onClick = { settings = true }) { Text("Settings") }
                        }
                    },
                )
                HorizontalDivider()
            }
        },
        bottomBar = {
            if (!settings && m.phase == Phase.Plan) {
                val problems by remember { derivedStateOf { m.planProblems() } }
                val files by remember { derivedStateOf { m.plans.sumOf { p -> p.build().targets.size } } }
                val bytes by remember { derivedStateOf { m.plans.sumOf { p -> p.build().bytes } } }
                BottomButton(
                    onClick = { m.startUnzip() }, enabled = problems.isEmpty() && !m.checkingPasswords,
                    text = when {
                        m.checkingPasswords -> "Checking the password…"
                        problems.isNotEmpty() -> "Fix the issues marked in red"
                        files == 0 -> "Unzip (${sizeText(bytes)})"
                        else -> "Unzip $files file(s) · ${sizeText(bytes)}"
                    },
                )
            }
            if (!settings && m.phase == Phase.Ready) {
                val n by remember { derivedStateOf { m.activeRolls.sumOf { it.files.size } } }
                val blocked by remember { derivedStateOf { m.activeRolls.any { it.problems(m.settings.keepTiffs).isNotEmpty() } } }
                BottomButton(
                    onClick = { confirm = true }, enabled = n > 0 && !blocked, danger = !m.settings.keepTiffs,
                    text = if (blocked) "Fix the issues marked in red" else actionLabel(m.activeRolls, !m.settings.keepTiffs),
                )
            }
            if (!settings && m.phase == Phase.FixReady) {
                val n = m.fixItems.count { it.date != null }
                BottomButton(onClick = { m.fixDates() }, enabled = n > 0, text = "Set the dates of $n file(s)")
            }
        },
    ) { pad ->
        Box(Modifier.padding(pad).fillMaxSize()) {
            Centered {
                when {
                    settings -> SettingsScreen(m)
                    m.phase == Phase.Start -> StartScreen(m, pick)
                    m.phase == Phase.Scanning -> Busy("Reading scans…", m.status)
                    m.phase == Phase.Planning -> Busy("Reading the zip…", m.status)
                    m.phase == Phase.Plan -> PlanScreen(m, pick)
                    m.phase == Phase.Unzipping -> UnzipScreen(m)
                    m.phase == Phase.Unzipped -> UnzippedScreen(m)
                    m.phase == Phase.Ready -> RollList(m)
                    m.phase == Phase.Converting -> ProgressScreen(m)
                    m.phase == Phase.Done -> DoneScreen(m)
                    m.phase == Phase.FixReading -> Busy("Reading dates…", m.status)
                    m.phase == Phase.Fixing -> Busy("Setting dates…", "")
                    else -> FixDatesScreen(m)
                }
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

@Composable
private fun BottomButton(onClick: () -> Unit, enabled: Boolean, text: String, danger: Boolean = false) {
    Column {
        HorizontalDivider()
        Centered(fillHeight = false) {
            Button(
                onClick = onClick, enabled = enabled,
                colors = if (danger) ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error,
                    contentColor = MaterialTheme.colorScheme.onError) else ButtonDefaults.buttonColors(),
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
            ) { Text(text) }
        }
    }
}
