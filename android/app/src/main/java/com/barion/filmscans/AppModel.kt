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
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

enum class Phase { Start, Scanning, Unzipping, Ready, Converting, Done }

data class Output(val roll: Roll, val count: Int, val firstJpeg: String?, val renamed: Boolean = false,
                  val media: android.net.Uri? = null) {
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
    var keepTiffs by mutableStateOf(prefs.getBoolean("keepTiffs", true))
    /** Delete a zip once everything in it is unzipped. Off unless chosen. */
    var deleteZips by mutableStateOf(prefs.getBoolean("deleteZips", false))
    var theme by mutableStateOf(runCatching { AppTheme.valueOf(prefs.getString("theme", "")!!) }.getOrDefault(AppTheme.STUDIO))

    /** Past answers, offered in the dropdowns. Never filled in automatically. */
    val cameras = mutableStateListOf<String>().apply { addAll(history("cameras")) }
    val lenses = mutableStateListOf<String>().apply { addAll(history("lenses")) }
    val labs = mutableStateListOf<String>().apply { addAll(history("labs")) }
    val customFilms = mutableStateListOf<String>().apply { addAll(history("films")) }

    fun saveSettings() {
        prefs.edit().putString("author", author.trim()).putString("license", license.name)
            .putString("contact", contact.trim()).putInt("quality", quality)
            .putBoolean("keepTiffs", keepTiffs).putString("theme", theme.name)
            .putBoolean("deleteZips", deleteZips).apply()
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

    /** Zip files found in the picked folder, offered for unzipping in place. */
    val zipsFound = mutableStateListOf<DocumentFile>()
    private var roots: List<DocumentFile> = emptyList()
    private val unzipped = HashSet<String>()
    var progress by mutableFloatStateOf(0f)
    /** Zips picked from Downloads, waiting for a destination folder. */
    var pendingZips: List<Uri> = emptyList()
    /** Set while the keyboard is up, so background previews pause and typing stays quick. */
    @Volatile var typing = false

    fun open(uri: Uri) {
        val root = DocumentFile.fromTreeUri(getApplication(), uri) ?: return
        unzipped.clear()
        scan(listOf(root), root.name ?: "")
    }

    private fun scan(where: List<DocumentFile>, label: String) {
        val ctx = getApplication<Application>()
        roots = where
        rootName = label
        phase = Phase.Scanning
        thumbJob?.cancel()
        rolls.clear(); log.clear(); zipsFound.clear()
        viewModelScope.launch {
            val zips = mutableListOf<DocumentFile>()
            val found = withContext(Dispatchers.IO) {
                runCatching { where.flatMap { Rolls.find(ctx, it, zips) { s -> status = s } } }
            }
            found.onSuccess { rolls.addAll(it) }.onFailure { status = "Couldn't read the folder: ${it.message}" }
            zipsFound.addAll(zips.filter { it.uri.toString() !in unzipped })
            loadThumbnails()
            val any = rolls.isNotEmpty() || zipsFound.isNotEmpty()
            phase = if (any) Phase.Ready else Phase.Start
            if (!any && found.isSuccess) status = "No TIFF or zip files in that folder."
            else if (any) status = ""
        }
    }

    /** Zips picked from Downloads: unzip each into its own folder inside [destTree], then open those. */
    fun unzipDownloads(destTree: Uri) {
        val ctx = getApplication<Application>()
        val dest = DocumentFile.fromTreeUri(ctx, destTree) ?: return
        val picked = pendingZips.mapNotNull { u -> DocumentFile.fromSingleUri(ctx, u)?.let { Triple(u, it.name ?: "download.zip", it) } }
        pendingZips = emptyList()
        unzipAll(picked.map { (u, name, doc) -> Job3(u, name, doc.length(), dest) { doc.delete() } }, fallbackRoots = null)
    }

    /** Zips found inside the picked folder: unzip each next to itself, then rescan. */
    fun unzipFound() {
        val jobs = zipsFound.mapNotNull { z ->
            val parent = z.parentFile ?: return@mapNotNull null
            Job3(z.uri, z.name ?: "archive.zip", z.length(), parent) { z.delete() }
        }
        unzipAll(jobs, fallbackRoots = roots)
    }

    private class Job3(val uri: Uri, val name: String, val size: Long, val dest: DocumentFile, val delete: () -> Boolean)

    private fun unzipAll(jobs: List<Job3>, fallbackRoots: List<DocumentFile>?) {
        if (jobs.isEmpty()) return
        val ctx = getApplication<Application>()
        saveSettings()
        thumbJob?.cancel()
        phase = Phase.Unzipping
        log.clear()
        viewModelScope.launch {
            val result = Unzip.Result()
            val total = jobs.sumOf { it.size.coerceAtLeast(1) }
            var before = 0L
            for (j in jobs) {
                // Check there's room first: the TIFFs take about as much space as the zip, or more.
                val free = Places.freeBytes(j.dest.uri)
                if (free != null && free < j.size * 13 / 10) {
                    log += "✗ ${j.name}: not enough free space (needs about ${gb(j.size * 13 / 10)}, ${gb(free)} free)"
                    continue
                }
                var lastShown = 0L
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Unzip.unzip(ctx, j.uri, j.name, j.dest, result) { n ->
                            if (n - lastShown > 4_000_000) {
                                lastShown = n
                                progress = (before + n).toFloat() / total
                                status = "Unzipping ${j.name}: ${gb(n)} of ${gb(j.size)}"
                            }
                        }
                    }
                }
                before += j.size
                ok.onSuccess {
                    unzipped += j.uri.toString()
                    log += "✓ ${j.name} unzipped"
                    if (deleteZips) log += if (withContext(Dispatchers.IO) { runCatching { j.delete() }.getOrDefault(false) })
                        "  ${j.name} deleted" else "  ${j.name} couldn't be deleted; delete it in My Files"
                }.onFailure { log += "✗ ${j.name}: ${it.message ?: it.javaClass.simpleName}" }
            }
            Rolls.zipDates = Rolls.zipDates + result.dates
            val failed = log.filter { it.startsWith("✗") }
            val where = fallbackRoots ?: result.tops
            if (where.isEmpty()) { status = failed.joinToString("\n"); phase = Phase.Start; return@launch }
            scan(where, if (fallbackRoots != null) rootName else result.tops.joinToString { it.name ?: "" })
            if (failed.isNotEmpty()) status = failed.joinToString("\n")
        }
    }

    private fun gb(b: Long) = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%d MB".format(b / 1_000_000)

    private fun depth(r: Roll) = runCatching {
        android.provider.DocumentsContract.getDocumentId(r.folder.uri).count { it == '/' }
    }.getOrDefault(0)

    private var thumbJob: Job? = null

    /**
     * Previews load in the background, first roll first, one at a time at low priority,
     * and pause while the keyboard is up so they never compete with typing.
     */
    private fun loadThumbnails() {
        val ctx = getApplication<Application>()
        thumbJob?.cancel()
        thumbJob = viewModelScope.launch(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                for (roll in rolls.toList()) for (f in roll.files) {
                    if (f.error != null || f.thumb != null) continue
                    while (typing) delay(250)
                    f.thumb = Rolls.thumbnail(ctx, f)
                }
            } finally {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }

    fun reset() {
        thumbJob?.cancel(); rolls.clear(); log.clear(); outputs.clear(); zipsFound.clear()
        phase = Phase.Start; status = ""
    }

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
        thumbJob?.cancel()
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
            // Tell the phone's media index about the new JPEGs, so the gallery shows them
            // right away and "View photos" can open them there.
            for (i in outputs.indices) {
                val o = outputs[i]
                val names = o.roll.files.filter { it.done }.map { it.newName + ".jpg" }
                val media = withContext(Dispatchers.IO) { Places.scan(ctx, o.roll.outputUri ?: o.roll.folder.uri, names) }
                outputs[i] = o.copy(media = media)
            }
            status = ""
            phase = Phase.Done
        }
    }
}
