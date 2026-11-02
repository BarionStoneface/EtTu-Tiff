package com.barion.filmscans

import android.app.Application
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.barion.filmscans.core.Archive
import com.barion.filmscans.core.ArchiveFile
import com.barion.filmscans.core.ZipPeek
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicInteger

/** Which kind of roll to work on, when a folder holds both. */
enum class WorkOn(val label: String) { TIFFS("TIFFs"), JPEGS("Lab JPEGs"), BOTH("Both") }

enum class Phase { Start, Scanning, Planning, Plan, Unzipping, Unzipped, Ready, Converting, Done }

/** One zip's result, for the screen shown after unzipping something that isn't photos. */
data class Unzipped(val zipName: String, val folder: Uri, val written: Int, val already: Int, val problems: Int)

data class Output(val roll: Roll, val count: Int, val firstJpeg: String?, val renamed: Boolean = false,
                  val media: Uri? = null) {
    val folderName get() = if (renamed) roll.newFolderName else roll.name
}

/**
 * Everything the app is doing. One instance for the whole app (see [EtTuTiffApp]), not tied to the
 * screen, so an unzip or a conversion carries on when you switch away or the screen is closed;
 * [WorkService] keeps the app running meanwhile.
 */
class AppModel(private val app: Application) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    /** Your name, licence, choices and past answers, kept between runs. */
    val settings = Settings(app)

    // ---- the current batch
    var phase by mutableStateOf(Phase.Start)
    var status by mutableStateOf("")
    var rootName by mutableStateOf("")
    val rolls = mutableStateListOf<Roll>()
    /** Only matters when the folder holds both TIFF rolls and lab JPEG rolls; starts on TIFFs. */
    var workOn by mutableStateOf(WorkOn.TIFFS)
    val mixed get() = rolls.any { it.jpegRoll } && rolls.any { !it.jpegRoll }
    fun shown(r: Roll) = !mixed || workOn == WorkOn.BOTH || (workOn == WorkOn.JPEGS) == r.jpegRoll
    /** The rolls on screen: only the kind chosen. Nothing else is touched. */
    val shownRolls get() = rolls.filter { shown(it) }
    /** The rolls that will actually be worked on. */
    val activeRolls get() = shownRolls.filter { it.include }
    val log = mutableStateListOf<String>()
    /** What happened when unzipping, kept on the rolls screen afterwards. */
    val notes = mutableStateListOf<String>()
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var fileProgress by mutableFloatStateOf(0f)
    /** Where each finished roll's JPEGs ended up, for the done screen. */
    val outputs = mutableStateListOf<Output>()

    /** Zip files found in the picked folder, offered for unzipping in place. */
    val zipsFound = mutableStateListOf<FoundZip>()
    private var roots: List<DocumentFile> = emptyList()
    private val unzipped = HashSet<String>()
    var progress by mutableFloatStateOf(0f)
    /** What each zip gave, after an unzip that didn't lead to rolls. */
    val unzippedZips = mutableStateListOf<Unzipped>()
    /** Zips picked from Downloads, waiting for a destination folder. */
    var pendingZips: List<Uri> = emptyList()
    /** Set while the keyboard is up, so background previews pause and typing stays quick. */
    @Volatile var typing = false

    fun open(uri: Uri) {
        val root = DocumentFile.fromTreeUri(app, uri) ?: return
        unzipped.clear(); notes.clear()
        scan(listOf(root), root.name ?: "")
    }

    // ---- looking inside a zip before unzipping it

    /** A photo from a zip, shown big; [picture] is null while it's being read. */
    class Peek(val plan: ZipPlan, val file: ArchiveFile, val picture: androidx.compose.ui.graphics.ImageBitmap?)
    var peek by mutableStateOf<Peek?>(null)
    @OptIn(ExperimentalCoroutinesApi::class)
    private val peekWork = Dispatchers.IO.limitedParallelism(2)

    /** Makes the thumbnail of a photo in the zip, once, when its row comes on screen. */
    fun thumb(p: ZipPlan, f: ArchiveFile) {
        if (!isImage(f.name) || (f.item.encrypted && p.password.isEmpty()) || !p.thumbsTried.add(f.path)) return
        scope.launch(peekWork) {
            if (typing) delay(300)
            Thumbnails.fromZip(f, p.password.ifEmpty { null }, 160)?.let { withContext(Dispatchers.Main) { p.thumbs[f.path] = it } }
        }
    }

    /**
     * Opens one file from the zip without unzipping the rest. A photo is shown here, big; anything else
     * (or a photo, from there) is pulled out on its own into the app's cache and opened in the app that
     * handles that kind of file.
     */
    fun openInZip(p: ZipPlan, f: ArchiveFile, elsewhere: Boolean = false) {
        if (f.item.encrypted && p.password.isEmpty()) { toast("Type the zip's password first"); return }
        val password = p.password.ifEmpty { null }
        if (isImage(f.name) && !elsewhere) {
            peek = Peek(p, f, null)
            scope.launch(peekWork) {
                val big = Thumbnails.fromZip(f, password, 1600)
                withContext(Dispatchers.Main) {
                    if (peek?.file !== f) return@withContext
                    if (big == null) { peek = null; openInZip(p, f, elsewhere = true) } else peek = Peek(p, f, big)
                }
            }
            return
        }
        toast("Opening ${f.name}…")
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                runCatching {
                    val dir = java.io.File(app.cacheDir, "zip-peek")
                    // Only the latest few are kept: these are for looking at, not keeping.
                    dir.listFiles()?.sortedBy { it.lastModified() }?.dropLast(4)?.forEach { it.delete() }
                    ZipPeek.extract(f, password, dir)
                }
            }
            file.onFailure { toast(if (it is com.barion.filmscans.core.WrongPassword) "That password doesn't open it" else "Couldn't open ${f.name}: ${it.message}") }
            file.onSuccess { out ->
                val uri = androidx.core.content.FileProvider.getUriForFile(app, app.packageName + ".peek", out)
                val mime = android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(f.name)) ?: "application/octet-stream"
                val view = android.content.Intent(android.content.Intent.ACTION_VIEW).setDataAndType(uri, mime)
                    .addFlags(android.content.Intent.FLAG_GRANT_READ_URI_PERMISSION or android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
                try { app.startActivity(view) } catch (_: android.content.ActivityNotFoundException) {
                    toast("No app on this phone opens .${ext(f.name)} files")
                }
            }
        }
    }

    private fun toast(msg: String) = android.widget.Toast.makeText(app, msg, android.widget.Toast.LENGTH_SHORT).show()

    /** Convert and tag photo zips straight after unzipping them, with the details typed in the preview. */
    var convertAfterUnzip by mutableStateOf(true)

    /**
     * [afterUnzip]: nothing to convert there isn't a problem, it just wasn't photos. [carry]: details
     * typed in the unzip preview, by where each roll landed (its document address, lowercase).
     */
    private fun scan(where: List<DocumentFile>, label: String, afterUnzip: Boolean = false,
                     carry: Map<String, PlanRoll> = emptyMap()) {
        roots = where
        rootName = label
        phase = Phase.Scanning
        thumbJob?.cancel()
        rolls.clear(); log.clear(); zipsFound.clear()
        workOn = WorkOn.TIFFS
        scope.launch {
            val zips = mutableListOf<FoundZip>()
            val found = withContext(Dispatchers.IO) {
                runCatching { where.flatMap { Rolls.find(app, it, zips, settings.deleteInfoFiles) { s -> status = s } } }
            }
            found.onSuccess { rolls.addAll(it) }.onFailure { status = "Couldn't read the folder: ${it.message}" }
            zipsFound.addAll(zips.filter { it.doc.uri.toString() !in unzipped })
            loadThumbnails()
            val any = rolls.isNotEmpty() || zipsFound.isNotEmpty()
            phase = if (any) Phase.Ready else if (afterUnzip) Phase.Unzipped else Phase.Start
            if (!any && found.isSuccess && !afterUnzip) status = "No TIFFs, JPEGs or zips in that folder."
            else status = ""
            if (carry.isNotEmpty() && rolls.isNotEmpty()) carryOn(carry)
        }
    }

    /**
     * Puts the details typed in the unzip preview onto the rolls that came out, then converts and tags
     * them straight away: one step, as LabUnzip does it. When a roll still needs something (a scan date,
     * say) or wasn't in the preview, it stops on the rolls screen instead, with what's missing in red.
     */
    private fun carryOn(carry: Map<String, PlanRoll>) {
        var unmatched = 0
        for (r in rolls) {
            val id = runCatching { android.provider.DocumentsContract.getDocumentId(r.folder.uri) }.getOrNull()?.lowercase()
            val p = id?.let { carry[it] }
            if (p == null) { unmatched++; continue }
            r.fillFrom(p)
            r.applyNames()
        }
        workOn = WorkOn.BOTH
        if (!convertAfterUnzip) return
        val blocked = activeRolls.filter { it.problems(settings.keepTiffs).isNotEmpty() }
        when {
            unmatched > 0 -> notes += "Not converted yet: $unmatched roll(s) weren't in the preview (they were in a compressed " +
                "zip inside). Fill them in below, then convert."
            blocked.isNotEmpty() -> notes += "Not converted yet: ${blocked.joinToString { it.name }} need(s) what's marked in red below."
            else -> convert()
        }
    }

    /** The zips being planned or unzipped, each with your edits. */
    val plans = mutableStateListOf<ZipPlan>()
    /** Where to go back to from the plan. */
    private var beforePlan = Phase.Start
    @Volatile private var stop = false

    /** Zips picked from Downloads: each will unzip into its own folder inside [destTree]. */
    fun unzipDownloads(destTree: Uri) {
        val dest = DocumentFile.fromTreeUri(app, destTree) ?: return
        val picked = pendingZips.mapNotNull { u -> DocumentFile.fromSingleUri(app, u)?.let { Triple(u, it.name ?: "download.zip", it) } }
        pendingZips = emptyList()
        makePlans(picked.map { (u, name, doc) -> ZipJob(u, name, doc.length(), dest) { doc.delete() } })
    }

    /** Zips found inside the picked folder: each will unzip next to itself. */
    fun unzipFound() {
        val jobs = zipsFound.map { z -> ZipJob(z.doc.uri, z.doc.name, z.doc.size, z.parent) { z.doc.file.delete() } }
        makePlans(jobs)
    }

    private class ZipJob(val uri: Uri, val name: String, val size: Long, val dest: DocumentFile, val delete: () -> Boolean)

    /** Reads each zip's contents and dates, and shows the plan. Nothing is written yet. */
    private fun makePlans(jobs: List<ZipJob>) {
        if (jobs.isEmpty()) return
        thumbJob?.cancel()
        closePlans()
        beforePlan = if (phase == Phase.Ready) Phase.Ready else Phase.Start
        phase = Phase.Planning
        log.clear()
        scope.launch {
            for (j in jobs) {
                status = "Reading ${j.name}…"
                withContext(Dispatchers.IO) {
                    runCatching { Unzip.plan(app, j.uri, j.name, j.size, j.dest, j.delete, settings.labJpegDates) { s -> status = "${j.name}: $s" } }
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

    private fun closePlans() { peek = null; plans.forEach { it.close() }; plans.clear() }


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
        plans.filter { it.needsPassword && it.password.isEmpty() }.forEach { out += "${it.zipName}: type its password" }
        plans.filter { it.passwordWrong }.forEach { out += "${it.zipName}: that password doesn't open it" }
        return out
    }

    /** Stops an unzip after the current file, or a conversion after the files already started. */
    fun stopWork() { stop = true }

    /** Set while passwords are being tried, so Unzip isn't pressed twice. */
    var checkingPasswords by mutableStateOf(false)

    fun startUnzip() {
        if (plans.isEmpty() || checkingPasswords) return
        settings.save()
        val todo = plans.toList()
        scope.launch {
            // Each password is tried on the zip first: a wrong one is said on the preview, before anything is written.
            checkingPasswords = true
            val wrong = withContext(Dispatchers.IO) {
                todo.filter { p -> p.needsPassword && !runCatching { Archive.passwordFits(p.listing!!, p.password) }.getOrDefault(false) }
            }
            checkingPasswords = false
            wrong.forEach { it.passwordWrong = true }
            if (wrong.isEmpty() && phase == Phase.Plan) unzip(todo)
        }
    }

    private fun unzip(todo: List<ZipPlan>) {
        // Where each roll will land, so what was typed for it in the preview can be put on it afterwards.
        val carry = todo.flatMap { p ->
            val base = runCatching { android.provider.DocumentsContract.getDocumentId(p.dest.uri) }.getOrNull() ?: return@flatMap emptyList()
            p.rolls.values.mapNotNull { r -> p.outputPath(r.path)?.let { rel -> (if (rel.isEmpty()) base else "$base/$rel").lowercase() to r } }
        }.toMap()
        stop = false
        phase = Phase.Unzipping
        log.clear()
        unzippedZips.clear()
        WorkService.start(app, "Unzipping")
        scope.launch {
            val total = todo.sumOf { (it.build()?.bytes ?: it.zipSize).coerceAtLeast(1) }
            var before = 0L
            val tops = mutableListOf<DocumentFile>()
            for (p in todo) {
                val bytes = p.build()?.bytes ?: p.zipSize
                // Check there's room first (with a little to spare).
                val free = Places.freeBytes(p.dest.uri)
                if (free != null && free < bytes + bytes / 20 + 50_000_000) {
                    log += "✗ ${p.zipName}: not enough free space (needs about ${sizeText(bytes)}, ${sizeText(free)} free)"
                    continue
                }
                val out = Unzip.Outcome()
                var lastShown = 0L
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Unzip.run(app, p, out, { stop }) { n ->
                            if (n - lastShown > 4_000_000) {
                                lastShown = n
                                progress = ((before + n).toFloat() / total).coerceAtMost(1f)
                                status = "Unzipping ${p.zipName}: ${sizeText(n)} of ${sizeText(bytes)}"
                                WorkService.update(app, "Unzipping ${p.zipName}", "${sizeText(n)} of ${sizeText(bytes)}", progress)
                            }
                        }
                    }.also { runCatching { ZipDates.putAll(app, out.dates) } }
                }
                before += bytes
                tops += out.tops
                out.tops.firstOrNull()?.let { unzippedZips += Unzipped(p.zipName, it.uri, out.written, out.already, out.problems.size) }
                val parts = listOfNotNull(
                    "${out.written} file(s) unzipped",
                    out.already.takeIf { it > 0 }?.let { "$it already there" },
                    out.jpegsDated.takeIf { it > 0 }?.let { "$it JPEG(s) given their scan date" },
                )
                ok.onSuccess {
                    unzipped += p.uri.toString()
                    log += "✓ ${p.zipName}: ${parts.joinToString(", ")}"
                    out.problems.forEach { log += "✗ $it" }
                    FileDates.summary(out.fileDated, out.fileDateRefused)?.let { log += "  $it" }
                    if (settings.deleteZips && out.problems.isEmpty()) log += if (withContext(Dispatchers.IO) { runCatching { p.delete() }.getOrDefault(false) })
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
            WorkService.finish(app, if (stop) "Unzipping stopped" else "Unzipped",
                log.count { it.startsWith("✓") }.let { "$it zip(s) unzipped" } +
                    log.count { it.startsWith("✗") }.let { if (it > 0) ", $it problem(s)" else "" })
            notes.clear(); notes.addAll(log)
            val where = if (beforePlan == Phase.Ready) roots else tops.distinctBy { it.uri.toString() }
            if (where.isEmpty()) { status = log.joinToString("\n"); phase = Phase.Start; return@launch }
            // Photo zips go on to their rolls. Any other zip ends here, with where its files went.
            when {
                beforePlan == Phase.Ready -> scan(where, rootName, carry = carry)
                todo.any { it.photo } -> scan(where, where.joinToString { it.name ?: "" }, afterUnzip = true, carry = carry)
                else -> phase = Phase.Unzipped
            }
        }
    }


    private fun depth(r: Roll) = runCatching {
        android.provider.DocumentsContract.getDocumentId(r.folder.uri).count { it == '/' }
    }.getOrDefault(0)

    private var thumbJob: Job? = null

    /**
     * Previews load in the background, first roll first, one at a time at low priority,
     * and pause while the keyboard is up so they never compete with typing.
     */
    private fun loadThumbnails() {
        thumbJob?.cancel()
        thumbJob = scope.launch(Dispatchers.IO) {
            android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_BACKGROUND)
            try {
                for (roll in rolls.toList()) for (f in roll.files) {
                    if (f.error != null || f.thumb != null) continue
                    while (typing) delay(250)
                    f.thumb = Thumbnails.of(app, f)
                }
            } finally {
                android.os.Process.setThreadPriority(android.os.Process.THREAD_PRIORITY_DEFAULT)
            }
        }
    }

    fun reset() {
        thumbJob?.cancel(); rolls.clear(); log.clear(); outputs.clear(); zipsFound.clear(); notes.clear(); unzippedZips.clear()
        closePlans()
        phase = Phase.Start; status = ""
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun convert() {
        val todo = activeRolls
        todo.forEach { settings.rememberAnswers(it) }
        settings.save()
        thumbJob?.cancel()
        val credits = settings.credits()
        val keep = settings.keepTiffs
        val quality = settings.quality
        total = todo.sumOf { it.files.size }
        done = 0
        log.clear()
        outputs.clear()
        stop = false
        phase = Phase.Converting
        WorkService.start(app, "Converting")
        // A few files at once: memory stays small because rows are streamed.
        val workers = Dispatchers.Default.limitedParallelism(minOf(3, Runtime.getRuntime().availableProcessors()))
        val datesSet = AtomicInteger(0)
        val datesRefused = AtomicInteger(0)
        scope.launch {
            val renames = mutableListOf<Roll>()
            for (roll in todo) {
                status = roll.name
                val meta = roll.meta()
                val failed = AtomicInteger(0)
                roll.files.mapIndexed { i, f ->
                    async(workers) {
                        // Stopped: files not yet started are left exactly as they are.
                        if (stop) { failed.incrementAndGet(); withContext(Dispatchers.Main) { done++ }; return@async }
                        try {
                            val dated = Convert.one(app, roll, f, i, meta, credits, quality, keep) { p -> if (i % 3 == 0) fileProgress = p }
                            (if (dated) datesSet else datesRefused).incrementAndGet()
                            f.done = true
                            withContext(Dispatchers.Main) { log += "✓ ${roll.name}/${f.name} → ${f.newName}.jpg" }
                        } catch (t: Throwable) {
                            failed.incrementAndGet()
                            withContext(Dispatchers.Main) { log += "✗ ${roll.name}/${f.name}: ${t.message ?: t.javaClass.simpleName}" + if (keep) "" else " (TIFF kept)" }
                        } finally {
                            withContext(Dispatchers.Main) {
                                done++
                                WorkService.update(app, "Converting ${roll.name}", "$done of $total", done.toFloat() / total)
                            }
                        }
                    }
                }.awaitAll()
                val made = roll.files.count { it.done }
                if (made > 0) outputs += Output(roll, made, roll.files.firstOrNull { it.done }?.newName?.plus(".jpg"))
                // Keeping the TIFFs means nothing in a TIFF roll's folder is deleted or renamed.
                if (roll.keeps(keep)) continue
                if (failed.get() > 0) {
                    log += "${roll.name}: ${failed.get()} not converted, so its info files and folder name were left alone."
                    continue
                }
                if (roll.deleteSidecars && roll.sidecars.isNotEmpty()) {
                    val n = withContext(Dispatchers.IO) { Convert.deleteSidecars(roll) }
                    log += "${roll.name}: deleted $n info file(s)"
                }
                if (roll.renameFolder) renames += roll
            }
            // Deepest folders first, so renaming a parent doesn't break its children's links.
            for (roll in renames.sortedByDescending { depth(it) }) {
                val err = withContext(Dispatchers.IO) { Convert.renameFolder(app, roll) }
                log += if (err == null) "${roll.name} → ${roll.newFolderName}" else "${roll.name}: $err"
                if (err == null) for (i in outputs.indices) if (outputs[i].roll === roll) outputs[i] = outputs[i].copy(renamed = true)
            }
            // Tell the phone's media index about the new JPEGs, so the gallery shows them
            // right away and "View photos" can open them there.
            for (i in outputs.indices) {
                val o = outputs[i]
                val names = o.roll.files.filter { it.done }.map { it.newName + ".jpg" }
                val media = withContext(Dispatchers.IO) { Places.scan(app, o.roll.outputUri ?: o.roll.folder.uri, names) }
                outputs[i] = o.copy(media = media)
            }
            FileDates.summary(datesSet.get(), datesRefused.get())?.let { log += it }
            if (stop) log += "Stopped. Scans not converted yet were left untouched."
            status = ""
            phase = Phase.Done
            WorkService.finish(app, if (stop) "Converting stopped" else "Converted",
                "${outputs.sumOf { it.count }} JPEG(s) saved" + log.count { it.startsWith("✗") }.let { if (it > 0) ", $it didn't convert" else "" })
        }
    }
}
