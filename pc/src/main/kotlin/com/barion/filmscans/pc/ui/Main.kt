package com.barion.filmscans.pc

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import java.awt.FileDialog
import java.awt.Frame
import java.awt.datatransfer.DataFlavor
import java.awt.dnd.DnDConstants
import java.awt.dnd.DropTarget
import java.awt.dnd.DropTargetDropEvent
import java.io.File
import javax.imageio.ImageIO
import javax.swing.JFileChooser
import javax.swing.UIManager
import kotlin.system.exitProcess
import kotlinx.coroutines.delay

fun main(args: Array<String>) {
    // "--self-check <folder or zip>…": opens each, lets the screen draw, and exits; any error fails it.
    // The Windows build runs this on the app it just built.
    val selfCheck = args.firstOrNull() == "--self-check"
    val paths = args.filter { it != "--self-check" }.map(::File)
    if (selfCheck) Thread.setDefaultUncaughtExceptionHandler { _, e -> e.printStackTrace(); exitProcess(1) }
    ImageIoJpeg.install()
    // The folder chooser looks like the rest of Windows.
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }
    val m = AppModel()
    // Started from Explorer's right-click menu, or with a folder or zip dropped on the shortcut.
    if (paths.isNotEmpty() && !selfCheck) m.openPaths(paths)
    val icon = runCatching {
        BitmapPainter(ImageIO.read(AppModel::class.java.getResourceAsStream("/icon.png")).toComposeImageBitmap())
    }.getOrNull()
    if (selfCheck && icon == null) { System.err.println("self-check: no icon"); exitProcess(3) }

    application {
        val state = rememberWindowState(width = 1040.dp, height = 880.dp)
        var askClose by remember { mutableStateOf(false) }
        val title = "Et Tu, Tiff?" + (m.progressText?.let { " — $it" } ?: "")
        Window(onCloseRequest = { if (m.busy) askClose = true else { m.settings.save(); exitApplication() } },
            state = state, title = title, icon = icon) {
            // Folders and zips dragged onto the window open the same way as choosing them.
            DisposableEffect(Unit) {
                window.contentPane.dropTarget = object : DropTarget() {
                    @Synchronized override fun drop(e: DropTargetDropEvent) {
                        e.acceptDrop(DnDConstants.ACTION_COPY)
                        @Suppress("UNCHECKED_CAST")
                        val files = runCatching { e.transferable.getTransferData(DataFlavor.javaFileListFlavor) as List<File> }.getOrNull()
                        e.dropComplete(files != null)
                        if (!files.isNullOrEmpty()) m.openPaths(files)
                    }
                }
                onDispose { window.contentPane.dropTarget = null }
            }
            if (selfCheck) LaunchedEffect(Unit) {
                for (p in paths) {
                    m.openPaths(listOf(p))
                    while (m.busy) delay(100)
                    delay(2000)
                    println("self-check ${p.name}: ${m.phase}, ${m.rolls.size} roll(s), ${m.plans.size} zip(s) ${m.status}")
                    if (m.phase != Phase.Ready && m.phase != Phase.Plan) exitProcess(2)
                }
                exitProcess(0)
            }
            val pick = Pickers(window, m.settings)
            MaterialTheme(colorScheme = m.settings.theme.colors) {
                Surface { App(m, pick) }
                if (askClose) AlertDialog(
                    onDismissRequest = { askClose = false },
                    title = { Text("Stop and close?") },
                    text = { Text("Work is still going. Files already finished are kept; the one being written is " +
                        "left as a .part file and done again next time.") },
                    confirmButton = { TextButton(onClick = { m.stopWork(); m.settings.save(); exitApplication() }) { Text("Stop and close") } },
                    dismissButton = { TextButton(onClick = { askClose = false }) { Text("Keep going") } },
                )
            }
        }
    }
}

/** Windows' own choosers for folders and zips, opening where you last were. */
class Pickers(private val owner: Frame, private val settings: Settings) {
    private val start get() = settings.lastFolder.takeIf { it.isNotEmpty() && File(it).isDirectory }
        ?: System.getProperty("user.home")

    fun folder(title: String): File? {
        val c = JFileChooser(start).apply {
            dialogTitle = title
            fileSelectionMode = JFileChooser.DIRECTORIES_ONLY
            isAcceptAllFileFilterUsed = false
        }
        return if (c.showDialog(owner, "Choose") == JFileChooser.APPROVE_OPTION) c.selectedFile else null
    }

    fun zips(): List<File> {
        val d = FileDialog(owner, "Choose the lab's zip", FileDialog.LOAD).apply {
            directory = start
            file = "*.zip"
            isMultipleMode = true
            setFilenameFilter { _, name -> name.lowercase().endsWith(".zip") }
            isVisible = true
        }
        return d.files.orEmpty().filter { it.isFile }
    }
}
