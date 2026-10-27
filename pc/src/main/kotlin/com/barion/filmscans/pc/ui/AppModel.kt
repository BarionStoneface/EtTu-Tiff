package com.barion.filmscans.pc

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.toComposeImageBitmap
import com.barion.filmscans.core.Archive
import com.barion.filmscans.core.UnzipPlan
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Which kind of roll to work on, when a folder holds both. */
enum class WorkOn(val label: String) { TIFFS("TIFFs"), JPEGS("Lab JPEGs"), BOTH("Both") }

enum class Phase { Start, Scanning, Planning, Plan, Unzipping, Unzipped, Ready, Converting, Done, FixReading, FixReady, Fixing, FixDone }

/** One zip's result, for the screen shown after unzipping something that isn't photos. */
data class Unzipped(val zipName: String, val dir: File, val written: Int, val already: Int, val problems: Int)

/** Where a finished roll's JPEGs are, for the done screen. */
data class Output(val roll: Roll, val count: Int, val dir: File, val firstJpeg: String?)

/** One zip being looked at before unzipping, with your edits. */
class ZipPlan(val contents: ZipContents, datesForPhotoZips: Boolean) {
    val zip get() = contents.zip
    val photo get() = contents.photo
    /** Give JPEGs with no date taken their scan date. Starts on only for photo zips; any other zip is left exactly as it is. */
    var dateJpegs by mutableStateOf(datesForPhotoZips && contents.photo)
    val needsPassword get() = contents.listing.needsPassword
    var password by mutableStateOf("")
    var passwordWrong by mutableStateOf(false)
    /** Where it unzips: next to the zip unless you choose somewhere else. */
    var dest by mutableStateOf<File>(contents.zip.absoluteFile.parentFile)
    var top by mutableStateOf(cleanName(stem(contents.zip.name)).ifEmpty { "Unzipped" })
    /** New folder names by path; "" drops that level. */
    val names = mutableStateMapOf<String, String>()
    /** Folders left out, by path. */
    val skip = mutableStateMapOf<String, Boolean>()

    fun build(): UnzipPlan.Result = UnzipPlan.build(contents.listing, top, names.toMap(), skip.filterValues { it }.keys)
    /** The folder the zip unpacks into. */
    val topDir: File get() = cleanName(top).let { if (it.isEmpty()) dest else File(dest, it) }
}

/**
 * Everything the app is doing. Work runs in the background; the window shows it and stays usable
 * for Stop.
 */
class AppModel {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    val settings = Settings()

    var phase by mutableStateOf(Phase.Start)
    var status by mutableStateOf("")
    var rootName by mutableStateOf("")
    val rolls = mutableStateListOf<Roll>()
    var workOn by mutableStateOf(WorkOn.TIFFS)
    val mixed get() = rolls.any { it.jpegRoll } && rolls.any { !it.jpegRoll }
    fun shown(r: Roll) = !mixed || workOn == WorkOn.BOTH || (workOn == WorkOn.JPEGS) == r.jpegRoll
    /** The rolls on screen: only the kind chosen. Nothing else is touched. */
    val shownRolls get() = rolls.filter { shown(it) }
    val activeRolls get() = shownRolls.filter { it.include }
    val log = mutableStateListOf<String>()
    /** What happened when unzipping, kept on the rolls screen afterwards. */
    val notes = mutableStateListOf<String>()
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var fileProgress by mutableFloatStateOf(0f)
    var progress by mutableFloatStateOf(0f)
    val outputs = mutableStateListOf<Output>()
    /** What each zip gave, after an unzip that didn't lead to rolls. */
    val unzippedZips = mutableStateListOf<Unzipped>()

    /** Zip files found in the opened folder, offered for unzipping in place. */
    val zipsFound = mutableStateListOf<File>()
    private var roots: List<File> = emptyList()
    private val unzipped = HashSet<String>()

    /** The zips being looked at or unzipped, each with your edits. */
    val plans = mutableStateListOf<ZipPlan>()
    private var beforePlan = Phase.Start
    @Volatile private var stop = false

    /** Fix dates: the folder and what each photo would get. */
    var fixFolder by mutableStateOf<File?>(null)
    val fixItems = mutableStateListOf<FixDates.Item>()

    val busy get() = phase in setOf(Phase.Scanning, Phase.Planning, Phase.Unzipping, Phase.Converting, Phase.FixReading, Phase.Fixing)

    /** For the window title while working. */
    val progressText: String? get() = when (phase) {
        Phase.Converting -> "$done of $total"
        Phase.Unzipping -> "${(progress * 100).toInt()}%"
        else -> null
    }

    /** Folders or zips dropped on the window, picked, or given when the app was started. */
    fun openPaths(files: List<File>) {
        if (busy) return
        val zips = files.filter { it.isFile && ext(it.name) == "zip" }
        val dirs = files.filter { it.isDirectory }
        when {
            zips.isNotEmpty() -> { reset(); makePlans(zips) }
            dirs.isNotEmpty() -> open(dirs)
            else -> status = "That isn't a folder or a zip."
        }
    }

    fun open(dirs: List<File>) {
        reset()
        settings.lastFolder = dirs.first().absolutePath
        scan(dirs, dirs.joinToString { it.name.ifEmpty { it.path } })
    }

    /** [afterUnzip]: nothing to convert there isn't a problem, it just wasn't photos. */
    private fun scan(where: List<File>, label: String, afterUnzip: Boolean = false) {
        roots = where
        rootName = label
        phase = Phase.Scanning
        thumbJob?.cancel()
        rolls.clear(); log.clear(); zipsFound.clear()
        workOn = WorkOn.TIFFS
        scope.launch {
            val zips = mutableListOf<File>()
            val found = withContext(Dispatchers.IO) {
                runCatching { where.flatMap { Rolls.find(it, zips) { s -> status = s } } }
            }
            found.onSuccess { list -> rolls.addAll(list.map { Roll(it, settings.deleteInfoFiles) }) }
                .onFailure { status = "Couldn't read the folder: ${it.message}" }
            zipsFound.addAll(zips.filter { it.absolutePath !in unzipped })
            loadThumbnails()
            val any = rolls.isNotEmpty() || zipsFound.isNotEmpty()
            phase = if (any) Phase.Ready else if (afterUnzip) Phase.Unzipped else Phase.Start
            if (!any && found.isSuccess && !afterUnzip) status = "No TIFFs, JPEGs or zips in $label."
            else status = ""
        }
    }

    /** Zips found in the opened folder: each unzips next to itself. */
    fun unzipFound() = makePlans(zipsFound.toList())

    /** Reads each zip's contents and dates, and shows the plan. Nothing is written yet. */
    fun makePlans(zips: List<File>) {
        if (zips.isEmpty()) return
        thumbJob?.cancel()
        closePlans()
        beforePlan = if (phase == Phase.Ready) Phase.Ready else Phase.Start
        phase = Phase.Planning
        log.clear()
        settings.lastFolder = zips.first().absoluteFile.parent ?: settings.lastFolder
        scope.launch {
            for (z in zips) {
                status = "Reading ${z.name}…"
                withContext(Dispatchers.IO) { runCatching { Unzip.read(z) { s -> status = "${z.name}: $s" } } }
                    .onSuccess { plans += ZipPlan(it, settings.labJpegDates) }.onFailure { log += "✗ ${it.message ?: z.name}" }
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

    private fun closePlans() { plans.forEach { runCatching { it.contents.close() } }; plans.clear() }

    /** Problems that stop the plan, across all the zips. */
    fun planProblems(): List<String> {
        val out = mutableListOf<String>()
        val results = plans.map { it to it.build() }
        results.forEach { (p, r) -> r.conflicts.forEach { out += "${p.zip.name}: $it" } }
        val seen = HashMap<String, String>()
        for ((p, r) in results) for (t in r.targets) {
            val key = File(p.dest, t.path).absolutePath.lowercase()
            val other = seen.put(key, p.zip.name)
            if (other != null && other != p.zip.name) { out += "${p.zip.name} and $other would both save ${t.path}"; break }
        }
        results.forEach { (p, r) -> if (r.targets.isEmpty() && r.sealed.isEmpty()) out += "${p.zip.name}: nothing is left to unzip" }
        plans.filter { !it.dest.isDirectory }.forEach { out += "${it.zip.name}: the folder ${it.dest} isn't there" }
        plans.filter { it.needsPassword && it.password.isEmpty() }.forEach { out += "${it.zip.name}: type its password" }
        plans.filter { it.passwordWrong }.forEach { out += "${it.zip.name}: that password doesn't open it" }
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
                todo.filter { p -> p.needsPassword && !runCatching { Archive.passwordFits(p.contents.listing, p.password) }.getOrDefault(false) }
            }
            checkingPasswords = false
            wrong.forEach { it.passwordWrong = true }
            if (wrong.isEmpty() && phase == Phase.Plan) unzip(todo)
        }
    }

    private fun unzip(todo: List<ZipPlan>) {
        stop = false
        phase = Phase.Unzipping
        progress = 0f
        log.clear()
        unzippedZips.clear()
        scope.launch {
            val results = todo.associateWith { it.build() }
            val total = results.values.sumOf { it.bytes.coerceAtLeast(1) }
            var before = 0L
            val tops = mutableListOf<File>()
            for (p in todo) {
                val plan = results.getValue(p)
                val bytes = plan.bytes
                // Check there's room first (with a little to spare).
                val free = p.dest.usableSpace
                if (free in 1 until bytes + bytes / 20 + 50_000_000) {
                    log += "✗ ${p.zip.name}: not enough free space (needs about ${sizeText(bytes)}, ${sizeText(free)} free)"
                    continue
                }
                val out = Unzip.Outcome()
                var lastShown = 0L
                val ok = withContext(Dispatchers.IO) {
                    runCatching {
                        Unzip.run(plan, p.dest, p.dateJpegs, out, { stop }, p.password.ifEmpty { null }) { n ->
                            if (n - lastShown > 4_000_000) {
                                lastShown = n
                                progress = ((before + n).toFloat() / total).coerceAtMost(1f)
                                status = "Unzipping ${p.zip.name}: ${sizeText(n)} of ${sizeText(bytes)}"
                            }
                        }
                    }.also { runCatching { ZipDates.putAll(out.dates) } }
                }
                before += bytes
                tops += p.topDir
                unzippedZips += Unzipped(p.zip.name, p.topDir, out.written, out.already, out.problems.size)
                val parts = listOfNotNull(
                    "${out.written} file(s) unzipped",
                    out.already.takeIf { it > 0 }?.let { "$it already there" },
                    out.jpegsDated.takeIf { it > 0 }?.let { "$it JPEG(s) given their scan date" },
                )
                ok.onSuccess {
                    unzipped += p.zip.absolutePath
                    log += "✓ ${p.zip.name}: ${parts.joinToString(", ")}"
                    out.problems.forEach { log += "✗ $it" }
                    FileTimes.summary(out.fileDated, out.fileDateRefused)?.let { log += "  $it" }
                    if (settings.deleteZips && out.problems.isEmpty()) log +=
                        if (withContext(Dispatchers.IO) { Trash.delete(p.zip) }) "  ${p.zip.name} deleted" else "  ${p.zip.name} couldn't be deleted"
                }.onFailure {
                    if (it is Unzip.Cancelled) log += "Stopped. ${parts.joinToString(", ")} from ${p.zip.name}; " +
                        "unzipping it again later carries on where this left off."
                    else log += "✗ ${p.zip.name}: ${it.message ?: it.javaClass.simpleName}"
                    out.problems.forEach { log += "✗ $it" }
                }
                if (stop) break
            }
            closePlans()
            notes.clear(); notes.addAll(log)
            val where = if (beforePlan == Phase.Ready) roots else tops.filter { it.isDirectory }.distinctBy { it.absolutePath.lowercase() }
            if (where.isEmpty()) { status = log.joinToString("\n"); phase = Phase.Start; return@launch }
            // Photo zips go on to their rolls. Any other zip ends here, with where its files went.
            when {
                beforePlan == Phase.Ready -> scan(where, rootName)
                todo.any { it.photo } -> scan(where, where.joinToString { it.name }, afterUnzip = true)
                else -> phase = Phase.Unzipped
            }
        }
    }

    private var thumbJob: Job? = null

    /** Previews load in the background, first roll first, a couple at a time. */
    @OptIn(ExperimentalCoroutinesApi::class)
    private fun loadThumbnails() {
        thumbJob?.cancel()
        val io = Dispatchers.IO.limitedParallelism(2)
        thumbJob = scope.launch {
            for (roll in rolls.toList()) {
                roll.files.filter { it.error == null && it.thumb == null }.map { f ->
                    async(io) { f.thumb = Previews.of(f.info.file, 320, f.info.orientation)?.toComposeImageBitmap() }
                }.awaitAll()
            }
        }
    }

    fun reset() {
        thumbJob?.cancel(); rolls.clear(); log.clear(); outputs.clear(); zipsFound.clear(); notes.clear(); unzippedZips.clear()
        fixItems.clear(); fixFolder = null
        unzipped.clear()
        closePlans()
        phase = Phase.Start; status = ""
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    fun convert() {
        val todo = activeRolls
        val keep = settings.keepTiffs
        val plans = todo.associateWith { it.plan(keep) }
        todo.forEach { settings.rememberAnswers(it) }
        settings.save()
        thumbJob?.cancel()
        val credits = settings.credits()
        val quality = settings.quality
        total = todo.sumOf { it.files.size }
        done = 0
        log.clear()
        outputs.clear()
        stop = false
        phase = Phase.Converting
        // A few files at once: memory stays small because rows are streamed.
        val workers = Dispatchers.Default.limitedParallelism(minOf(4, Runtime.getRuntime().availableProcessors()))
        val datesSet = AtomicInteger(0)
        val datesRefused = AtomicInteger(0)
        scope.launch {
            val renames = mutableListOf<Roll>()
            for (roll in todo) {
                status = roll.name
                val plan = plans.getValue(roll)
                val failed = AtomicInteger(0)
                roll.files.mapIndexed { i, f ->
                    async(workers) {
                        // Stopped: files not yet started are left exactly as they are.
                        if (stop) { failed.incrementAndGet(); withContext(Dispatchers.Main) { done++ }; return@async }
                        try {
                            val dated = Convert.one(plan, i, credits, quality) { p -> if (i % 4 == 0) fileProgress = p }
                            (if (dated) datesSet else datesRefused).incrementAndGet()
                            f.done = true
                            withContext(Dispatchers.Main) { log += "✓ ${roll.name}/${f.name} → ${f.newName}.jpg" }
                        } catch (t: Throwable) {
                            failed.incrementAndGet()
                            withContext(Dispatchers.Main) {
                                log += "✗ ${roll.name}/${f.name}: ${t.message ?: t.javaClass.simpleName}" + if (keep) "" else " (original kept)"
                            }
                        } finally {
                            withContext(Dispatchers.Main) { done++ }
                        }
                    }
                }.awaitAll()
                val made = roll.files.count { it.done }
                roll.outputDir = if (roll.jpegRoll && keep) plan.copiesDir else roll.info.folder
                if (made > 0) outputs += Output(roll, made, roll.outputDir!!, roll.files.firstOrNull { it.done }?.newName?.plus(".jpg"))
                // Keeping the originals means nothing in the folder is deleted or renamed.
                if (keep) continue
                if (failed.get() > 0) {
                    log += "${roll.name}: ${failed.get()} not done, so its info files and folder name were left alone."
                    continue
                }
                if (roll.deleteSidecars && roll.sidecars.isNotEmpty()) {
                    val n = withContext(Dispatchers.IO) { Convert.deleteSidecars(roll.info) }
                    log += "${roll.name}: deleted $n info file(s)"
                }
                if (plan.renames) renames += roll
            }
            // Deepest folders first, so renaming a parent doesn't break its children's paths.
            for (roll in renames.sortedByDescending { it.info.folder.absolutePath.count { c -> c == File.separatorChar } }) {
                val from = roll.info.folder.absoluteFile
                withContext(Dispatchers.IO) { Convert.renameFolder(plans.getValue(roll)) }
                    .onSuccess { to ->
                        log += "${roll.name} → ${to.name}"
                        for (i in outputs.indices) outputs[i] = outputs[i].let { o -> o.copy(dir = moved(o.dir, from, to)) }
                    }
                    .onFailure { log += "${roll.name}: ${it.message}" }
            }
            FileTimes.summary(datesSet.get(), datesRefused.get())?.let { log += it }
            if (stop) log += "Stopped. Scans not done yet were left untouched."
            status = ""
            phase = Phase.Done
        }
    }

    /** [f]'s path after the folder [from] was renamed to [to]. */
    private fun moved(f: File, from: File, to: File): File {
        val a = f.absolutePath; val p = from.absolutePath
        return when {
            a.equals(p, true) -> to
            a.startsWith(p + File.separator, ignoreCase = true) -> File(to, a.substring(p.length + 1))
            else -> f
        }
    }

    /** Fix dates: reads every photo's date in [folder]. Changes nothing yet. */
    fun lookForDates(folder: File) {
        reset()
        settings.lastFolder = folder.absolutePath
        fixFolder = folder
        phase = Phase.FixReading
        scope.launch {
            val items = withContext(Dispatchers.IO) { runCatching { FixDates.look(folder) { status = it } } }
            items.onSuccess { fixItems.addAll(it); phase = Phase.FixReady; status = "" }
                .onFailure { status = "Couldn't read ${folder.name}: ${it.message}"; phase = Phase.Start }
        }
    }

    fun fixDates() {
        val items = fixItems.toList()
        stop = false
        phase = Phase.Fixing
        scope.launch {
            val (set, failed) = withContext(Dispatchers.IO) { FixDates.apply(items) { stop } }
            log.clear()
            log += "$set file(s) given their scan date as their created and modified date."
            if (failed > 0) log += "✗ $failed file(s) couldn't be changed (they may be read-only or open in another program)."
            items.count { it.date == null }.takeIf { it > 0 }?.let { log += "$it photo(s) with no date inside were left alone." }
            if (stop) log += "Stopped part way; running it again finishes the rest."
            phase = Phase.FixDone
        }
    }
}
