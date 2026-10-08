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

enum class Phase { Start, Scanning, Planning, Plan, Unzipping, Ready, Converting, Done }

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
    /** Starting point for each roll's "delete info files" switch. Off unless chosen. */
    var deleteInfoFiles by mutableStateOf(prefs.getBoolean("deleteInfoFiles", false))
    /** Give the lab's JPEGs a date taken when unzipping, so galleries sort them by scan date. */
    var labJpegDates by mutableStateOf(prefs.getBoolean("labJpegDates", true))
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
            .putBoolean("deleteZips", deleteZips).putBoolean("deleteInfoFiles", deleteInfoFiles)
            .putBoolean("labJpegDates", labJpegDates).apply()
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
    /** What happened when unzipping, kept on the rolls screen afterwards. */
    val notes = mutableStateListOf<String>()
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
        unzipped.clear(); notes.clear()
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
                runCatching { where.flatMap { Rolls.find(ctx, it, zips, deleteInfoFiles) { s -> status = s } } }
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

    /** The zips being planned or unzipped, each with your edits. */
    val plans = mutableStateListOf<ZipPlan>()
    /** Where to go back to from the plan. */
    private var beforePlan = Phase.Start
    @Volatile private var stop = false

    /** Zips picked from Downloads: each will unzip into its own folder inside [destTree]. */
    fun unzipDownloads(destTree: Uri) {
        val ctx = getApplication<Application>()
        val dest = DocumentFile.fromTreeUri(ctx, destTree) ?: return
        val picked = pendingZips.mapNotNull { u -> DocumentFile.fromSingleUri(ctx, u)?.let { Triple(u, it.name ?: "download.zip", it) } }
        pendingZips = emptyList()
        makePlans(picked.map { (u, name, doc) -> Job3(u, name, doc.length(), dest) { doc.delete() } })
    }

    /** Zips found inside the picked folder: each will unzip next to itself. */
    fun unzipFound() {
        val jobs = zipsFound.mapNotNull { z ->
            val parent = z.parentFile ?: return@mapNotNull null
            Job3(z.uri, z.name ?: "archive.zip", z.length(), parent) { z.delete() }
        }
        makePlans(jobs)
    }

    private class Job3(val uri: Uri, val name: String, val size: Long, val dest: DocumentFile, val delete: () -> Boolean)

    /** Reads each zip's contents and dates, and shows the plan. Nothing is written yet. */
    private fun makePlans(jobs: List<Job3>) {
        if (jobs.isEmpty()) return
        val ctx = getApplication<Application>()
        thumbJob?.cancel()
        closePlans()
        beforePlan = if (phase == Phase.Ready) Phase.Ready else Phase.Start
        phase = Phase.Planning
        log.clear()
        viewModelScope.launch {
            for (j in jobs) {
                status = "Reading ${j.name}…"
                withContext(Dispatchers.IO) {
                    runCatching { Unzip.plan(ctx, j.uri, j.name, j.size, j.dest, j.delete) { s -> status = "${j.name}: $s" } }
                }.onSuccess { plans += it }.onFailure { log += "✗ ${it.message ?: j.name}" }
            }
            status = ""
            phase = if (plans.isEmpty()) beforePlan else Phase.Plan
            if (plans.isEmpty()) status = log.joinToString("\n")
        }
    }

    fun leavePlan() {
        closePlans()
        phase = beforePlan
        if (beforePlan == Phase.Ready) loadThumbnails()
    }

    private fun closePlans() { plans.forEach { it.close() }; plans.clear() }

    override fun onCleared() { closePlans(); super.onCleared() }

    /** Problems that stop the plan, across all the zips. */
    fun planProblems(): List<String> {
        val out = mutableListOf<String>()
        val results = plans.map { it to it.build() }
        results.forEach { (p, r) -> r?.conflicts?.forEach { out += "${p.zipName}: $it" } }
        // Two zips unpacking to the same place.
        val seen = HashMap<String, String>()
        for ((p, r) in results) for (t in r?.targets.orEmpty()) {
            val key = p.dest.uri.toString() + "|" + t.path.lowercase()
            val other = seen.put(key, p.zipName)
            if (other != null && other != p.zipName) { out += "${p.zipName} and $other would both save ${t.path}"; break }
        }
        results.forEach { (p, r) -> if (r != null && r.targets.isEmpty() && r.sealed.isEmpty()) out += "${p.zipName}: nothing is left to unzip" }
        return out
    }

    fun stopUnzip() { stop = true }

    fun startUnzip() {
        if (plans.isEmpty()) return
        val ctx = getApplication<Application>()
        saveSettings()
        val todo = plans.toList()
        val datesForJpegs = labJpegDates
        stop = false
        phase = Phase.Unzipping
        log.clear()
        viewModelScope.launch {
            val total = todo.sumOf { (it.build()?.bytes ?: it.zipSize).coerceAtLeast(1) }
            var before = 0L
            val tops = mutableListOf<DocumentFile>()
            for (p in todo) {
                val bytes = p.build()?.bytes ?: p.zipSize
                // Check there's room first (with a little to spare).
                val free = Places.freeBytes(p.dest.uri)
                if (free != null && free < bytes + bytes / 20 + 50_000_000) {
                    log += "✗ ${p.zipName}: not enough free space (needs about ${gb(bytes)}, ${gb(free)} free)"
                    continue
                }
                val out = Unzip.Outcome()
                var lastShown = 0L
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Unzip.run(ctx, p, datesForJpegs, out, { stop }) { n ->
                            if (n - lastShown > 4_000_000) {
                                lastShown = n
                                progress = ((before + n).toFloat() / total).coerceAtMost(1f)
                                status = "Unzipping ${p.zipName}: ${gb(n)} of ${gb(bytes)}"
                            }
                        }
                    }.also { runCatching { ZipDates.putAll(ctx, out.dates) } }
                }
                before += bytes
                tops += out.tops
                val parts = listOfNotNull(
                    "${out.written} file(s) unzipped",
                    out.already.takeIf { it > 0 }?.let { "$it already there" },
                    out.jpegsDated.takeIf { it > 0 }?.let { "$it JPEG(s) given their scan date" },
                )
                ok.onSuccess {
                    unzipped += p.uri.toString()
                    log += "✓ ${p.zipName}: ${parts.joinToString(", ")}"
                    out.problems.forEach { log += "✗ $it" }
                    if (deleteZips && out.problems.isEmpty()) log += if (withContext(Dispatchers.IO) { runCatching { p.delete() }.getOrDefault(false) })
                        "  ${p.zipName} deleted" else "  ${p.zipName} couldn't be deleted; delete it in My Files"
                }.onFailure {
                    if (it is Unzip.Cancelled) log += "Stopped. ${parts.joinToString(", ")} from ${p.zipName}; " +
                        "unzipping it again later carries on where this left off."
                    else log += "✗ ${p.zipName}: ${it.message ?: it.javaClass.simpleName}"
                    out.problems.forEach { log += "✗ $it" }
                }
                if (stop) break
            }
            closePlans()
            notes.clear(); notes.addAll(log)
            val where = if (beforePlan == Phase.Ready) roots else tops.distinctBy { it.uri.toString() }
            if (where.isEmpty()) { status = log.joinToString("\n"); phase = Phase.Start; return@launch }
            scan(where, if (beforePlan == Phase.Ready) rootName else where.joinToString { it.name ?: "" })
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
        thumbJob?.cancel(); rolls.clear(); log.clear(); outputs.clear(); zipsFound.clear(); notes.clear()
        closePlans()
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
