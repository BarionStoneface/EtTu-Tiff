package com.barion.filmscans

import android.app.Application
import android.content.Context
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.License
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class Phase { Start, Scanning, Ready, Converting, Done }

data class Output(val roll: Roll, val count: Int, val firstJpeg: String?, val renamed: Boolean = false) {
    val folderName get() = if (renamed) roll.newFolderName else roll.name
}

class AppModel(app: Application) : AndroidViewModel(app) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    // ---- settings, kept between runs
    var author by mutableStateOf(prefs.getString("author", "") ?: "")
    var license by mutableStateOf(runCatching { License.valueOf(prefs.getString("license", "")!!) }.getOrDefault(License.ALL_RIGHTS))
    var contact by mutableStateOf(prefs.getString("contact", "") ?: "")
    var quality by mutableIntStateOf(prefs.getInt("quality", 100))
    /** Keep the TIFFs and add JPEGs beside them, instead of replacing them. */
    var keepTiffs by mutableStateOf(prefs.getBoolean("keepTiffs", false))
    var theme by mutableStateOf(runCatching { AppTheme.valueOf(prefs.getString("theme", "")!!) }.getOrDefault(AppTheme.STUDIO))

    /** Past answers, offered in the dropdowns. Never filled in automatically. */
    val cameras = mutableStateListOf<String>().apply { addAll(history("cameras")) }
    val lenses = mutableStateListOf<String>().apply { addAll(history("lenses")) }
    val labs = mutableStateListOf<String>().apply { addAll(history("labs")) }
    val customFilms = mutableStateListOf<String>().apply { addAll(history("films")) }

    fun saveSettings() {
        prefs.edit().putString("author", author.trim()).putString("license", license.name)
            .putString("contact", contact.trim()).putInt("quality", quality)
            .putBoolean("keepTiffs", keepTiffs).putString("theme", theme.name).apply()
    }

    private fun history(key: String): List<String> =
        prefs.getString(key, "")!!.split('\n').filter { it.isNotBlank() }

    private fun remember(key: String, list: MutableList<String>, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        list.remove(v); list.add(0, v)
        while (list.size > 30) list.removeAt(list.lastIndex)
        prefs.edit().putString(key, list.joinToString("\n")).apply()
    }

    fun credits() = Credits(author.trim(), license, contact.trim())

    // ---- the current batch
    var phase by mutableStateOf(Phase.Start)
    var status by mutableStateOf("")
    var rootName by mutableStateOf("")
    val rolls = mutableStateListOf<Roll>()
    val log = mutableStateListOf<String>()
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var fileProgress by mutableFloatStateOf(0f)
    /** Where each finished roll's JPEGs ended up, for the done screen. */
    val outputs = mutableStateListOf<Output>()

    fun open(uri: Uri) {
        val ctx = getApplication<Application>()
        val root = DocumentFile.fromTreeUri(ctx, uri) ?: return
        rootName = root.name ?: ""
        phase = Phase.Scanning
        rolls.clear(); log.clear()
        viewModelScope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { Rolls.find(ctx, root) { status = it } }
            }
            found.onSuccess { rolls.addAll(it) }.onFailure { status = "Couldn't read the folder: ${it.message}" }
            phase = if (rolls.isEmpty()) Phase.Start else Phase.Ready
            if (rolls.isEmpty() && found.isSuccess) status = "No TIFF files in that folder."
        }
    }

    private fun depth(r: Roll) = runCatching {
        android.provider.DocumentsContract.getDocumentId(r.folder.uri).count { it == '/' }
    }.getOrDefault(0)

    fun reset() { rolls.clear(); log.clear(); outputs.clear(); phase = Phase.Start; status = "" }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun convert() {
        val ctx = getApplication<Application>()
        val todo = rolls.filter { it.include }
        todo.forEach { r ->
            remember("cameras", cameras, r.camera); remember("lenses", lenses, r.lens); remember("labs", labs, r.lab)
            if (r.film.isNotBlank() && com.barion.filmscans.core.FILM_STOCKS.none { it.name.equals(r.film.trim(), true) })
                remember("films", customFilms, r.film)
        }
        saveSettings()
        val credits = credits()
        val keep = keepTiffs
        total = todo.sumOf { it.files.size }
        done = 0
        log.clear()
        outputs.clear()
        phase = Phase.Converting
        // A few files at once: memory stays small because rows are streamed.
        val workers = Dispatchers.Default.limitedParallelism(minOf(3, Runtime.getRuntime().availableProcessors()))
        viewModelScope.launch {
            val renames = mutableListOf<Roll>()
            for (roll in todo) {
                status = roll.name
                val meta = roll.meta()
                val failed = AtomicInteger(0)
                roll.files.mapIndexed { i, f ->
                    async(workers) {
                        try {
                            Rolls.convertOne(ctx, roll, f, i, meta, credits, quality, keep) { p -> if (i % 3 == 0) fileProgress = p }
                            f.done = true
                            withContext(Dispatchers.Main) { log += "✓ ${roll.name}/${f.name} → ${f.newName}.jpg" }
                        } catch (t: Throwable) {
                            failed.incrementAndGet()
                            withContext(Dispatchers.Main) { log += "✗ ${roll.name}/${f.name}: ${t.message ?: t.javaClass.simpleName}" + if (keep) "" else " (TIFF kept)" }
                        } finally {
                            withContext(Dispatchers.Main) { done++ }
                        }
                    }
                }.awaitAll()
                val made = roll.files.count { it.done }
                if (made > 0) outputs += Output(roll, made, roll.files.firstOrNull { it.done }?.newName?.plus(".jpg"))
                // Keeping the TIFFs means nothing in the folder is deleted or renamed.
                if (keep) continue
                if (failed.get() > 0) {
                    log += "${roll.name}: ${failed.get()} failed, so its info files and folder name were left alone."
                    continue
                }
                if (roll.deleteSidecars && roll.sidecars.isNotEmpty()) {
                    val n = withContext(Dispatchers.IO) { Rolls.deleteSidecars(roll) }
                    log += "${roll.name}: deleted $n info file(s)"
                }
                if (roll.renameFolder) renames += roll
            }
            // Deepest folders first, so renaming a parent doesn't break its children's links.
            for (roll in renames.sortedByDescending { depth(it) }) {
                val err = withContext(Dispatchers.IO) { Rolls.renameFolder(ctx, roll) }
                log += if (err == null) "${roll.name} → ${roll.newFolderName}" else "${roll.name}: $err"
                if (err == null) for (i in outputs.indices) if (outputs[i].roll === roll) outputs[i] = outputs[i].copy(renamed = true)
            }
            status = ""
            phase = Phase.Done
        }
    }
}
