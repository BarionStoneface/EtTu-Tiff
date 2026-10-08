package com.barion.filmscans

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.barion.filmscans.core.Archive
import com.barion.filmscans.core.BytesSource
import com.barion.filmscans.core.DateFound
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.Listing
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.PlanFolder
import com.barion.filmscans.core.UnzipPlan
import com.barion.filmscans.core.ZipIndex
import com.barion.filmscans.core.ZipWalk
import java.io.File
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.time.format.DateTimeFormatter

/**
 * Each unzipped file's date as stored in the zip, kept on the phone by document, so it's still
 * known after the app is closed and the folder is opened again. Unzipping on a phone gives every
 * file today's date, so without this the zip's date would be lost the moment the app closes.
 */
object ZipDates {
    private const val FILE = "zip-dates.tsv"
    private const val MAX = 50_000
    private var map: LinkedHashMap<String, LocalDateTime>? = null

    private fun key(uri: Uri) = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    @Synchronized fun get(ctx: Context, uri: Uri): LocalDateTime? = key(uri)?.let { load(ctx)[it] }

    @Synchronized fun putAll(ctx: Context, dates: Map<Uri, LocalDateTime>) {
        if (dates.isEmpty()) return
        val m = load(ctx)
        for ((uri, d) in dates) { val k = key(uri) ?: continue; m.remove(k); m[k] = d }
        val it = m.keys.iterator()
        while (m.size > MAX && it.hasNext()) { it.next(); it.remove() }
        val f = File(ctx.filesDir, FILE)
        val tmp = File(ctx.filesDir, "$FILE.tmp")
        tmp.bufferedWriter().use { w -> m.forEach { (k, v) -> w.write(k); w.write('\t'.code); w.write(v.toString()); w.newLine() } }
        tmp.renameTo(f)
    }

    private fun load(ctx: Context): LinkedHashMap<String, LocalDateTime> = map ?: LinkedHashMap<String, LocalDateTime>().also { m ->
        runCatching {
            File(ctx.filesDir, FILE).takeIf { it.exists() }?.forEachLine { line ->
                val k = line.substringBeforeLast('\t', "")
                val v = runCatching { LocalDateTime.parse(line.substringAfterLast('\t')) }.getOrNull()
                if (k.isNotEmpty() && v != null) m[k] = v
            }
        }
        map = m
    }
}

/** What's in one picked zip and what you've changed, before anything is written. */
class ZipPlan(
    val uri: Uri,
    val zipName: String,
    val zipSize: Long,
    val dest: DocumentFile,
    /** Random access to the zip, or null when the phone only lets it be read start to finish. */
    val source: UriSource?,
    val listing: Listing?,
    val delete: () -> Boolean,
) {
    val folders: List<PlanFolder> = listing?.let { UnzipPlan.folders(it) } ?: emptyList()
    /** Per folder: where the scan dates come from (label to count), and how many images have none. */
    val dateSummary = HashMap<String, Pair<Map<String, Int>, Int>>()

    var top by mutableStateOf(Names.clean(stem(zipName)).ifEmpty { "Unzipped" })
    /** New folder names by path; "" drops that level. */
    val names = mutableStateMapOf<String, String>()
    /** Folders left out, by path. */
    val skip = mutableStateMapOf<String, Boolean>()

    fun build(): UnzipPlan.Result? = listing?.let { UnzipPlan.build(it, top, names.toMap(), skip.filterValues { v -> v }.keys) }
    fun hasJpegs() = listing?.files?.any { isJpeg(it.name) } ?: true
    fun close() = runCatching { source?.close() }
}

fun isJpeg(name: String) = ext(name) in setOf("jpg", "jpeg")
private fun isImage(name: String) = ext(name) in setOf("jpg", "jpeg", "tif", "tiff")

/** Unzips a download, zips inside it included, following a plan you've checked. */
object Unzip {
    /** Enough to reach the EXIF and XMP of these labs' scans when a file has to be inflated to read them. */
    private const val HEAD = 256 * 1024

    class Outcome {
        var written = 0
        var already = 0
        var jpegsDated = 0
        val problems = mutableListOf<String>()
        val tops = mutableListOf<DocumentFile>()
        val dates = HashMap<Uri, LocalDateTime>()
    }

    class Cancelled : Exception("stopped")

    /** Reads what's in the zip and where each scan's date will come from. Writes nothing. */
    fun plan(ctx: Context, uri: Uri, name: String, size: Long, dest: DocumentFile, delete: () -> Boolean,
             onProgress: (String) -> Unit): ZipPlan {
        val src = UriSource.openSeekable(ctx, uri) ?: return ZipPlan(uri, name, size, dest, null, null, delete)
        val listing = try {
            Archive.list(src)
        } catch (e: Exception) {
            src.close()
            throw IllegalStateException("$name isn't a zip this app can read (${e.message})")
        }
        val plan = ZipPlan(uri, name, size, dest, src, listing, delete)
        val byFolder = listing.files.filter { isImage(it.name) }.groupBy { it.folder }
        var n = 0
        for ((folder, files) in byFolder) {
            val counts = HashMap<String, Int>()
            var undated = 0
            for (f in files) {
                if (++n % 10 == 0) onProgress("Reading dates: $n of ${byFolder.values.sumOf { it.size }}")
                val found: DateFound? = runCatching {
                    val s = ZipIndex.storedSource(f.container, f.item) ?: BytesSource(ZipIndex.head(f.container, f.item, HEAD))
                    Dates.fromImage(s).firstOrNull()
                }.getOrNull()
                val label = found?.source ?: if (Dates.usable(f.item.time) != null) "date in the zip" else null
                if (label == null) undated++ else counts[label] = (counts[label] ?: 0) + 1
            }
            plan.dateSummary[folder] = counts to undated
        }
        return plan
    }

    /** Checks the folder can rename files, so each one can be written under a temporary name first. */
    private fun canRename(ctx: Context, dir: DocumentFile): Boolean = runCatching {
        val probe = dir.createFile("application/octet-stream", ".ettutiff-check.part") ?: return false
        val renamed = runCatching { DocumentsContract.renameDocument(ctx.contentResolver, probe.uri, ".ettutiff-check") }.getOrNull()
        val gone = (renamed?.let { DocumentFile.fromSingleUri(ctx, it) } ?: probe).delete()
        renamed != null && gone
    }.getOrDefault(false)

    /** Folders made as needed, each listed once so checking for existing files stays quick. */
    private class Writer(val ctx: Context, val base: DocumentFile, val safeWrites: Boolean, val cancelled: () -> Boolean) {
        class Dir(val doc: DocumentFile) {
            val kids: HashMap<String, DocumentFile> by lazy {
                HashMap<String, DocumentFile>().also { m -> doc.listFiles().forEach { k -> k.name?.let { m[it.lowercase()] = k } } }
            }
        }
        private val dirs = HashMap<String, Dir>()

        fun dir(segs: List<String>): Dir {
            val key = segs.joinToString("/") { it.lowercase() }
            dirs[key]?.let { return it }
            val d = if (segs.isEmpty()) Dir(base) else {
                val parent = dir(segs.dropLast(1))
                val name = segs.last()
                val found = parent.kids[name.lowercase()]
                val doc = when {
                    found == null -> parent.doc.createDirectory(name)?.also { parent.kids[name.lowercase()] = it }
                        ?: error("couldn't create the folder $name")
                    found.isDirectory -> found
                    else -> error("there's already a file called $name where a folder has to go")
                }
                Dir(doc)
            }
            dirs[key] = d
            return d
        }

        /**
         * Writes [data] as [name]: first as name.part, renamed once complete, so a file only ever has its
         * real name when it's whole. Returns null when a file of that name and [size] is already there.
         */
        fun write(dir: Dir, name: String, size: Long?, data: InputStream, onBytes: (Int) -> Unit, beforeDone: (Uri) -> Unit): DocumentFile? {
            val key = name.lowercase()
            dir.kids[key]?.let { old ->
                // Size unknown (a zip read start to finish): whatever is there is kept, never overwritten.
                if (size == null || size < 0 || old.length() == size) return null
                error("a different $name is already there, so it was left alone")
            }
            dir.kids.remove("$key.part")?.delete()
            val tempName = if (safeWrites) "$name.part" else name
            val mime = if (safeWrites) "application/octet-stream" else mimeFor(name)
            val doc = dir.doc.createFile(mime, tempName) ?: error("couldn't create $name")
            try {
                (ctx.contentResolver.openOutputStream(doc.uri) ?: error("couldn't write $name")).use { out ->
                    val buf = ByteArray(1 shl 20)
                    while (true) {
                        if (cancelled()) throw Cancelled()
                        val n = data.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                        onBytes(n)
                    }
                }
                beforeDone(doc.uri)
                val final = if (!safeWrites) doc else {
                    val u = DocumentsContract.renameDocument(ctx.contentResolver, doc.uri, name) ?: error("couldn't rename $tempName")
                    DocumentFile.fromSingleUri(ctx, u) ?: error("couldn't rename $tempName")
                }
                dir.kids[key] = final
                return final
            } catch (e: Throwable) {
                runCatching { doc.delete() }
                throw e
            }
        }

        private fun mimeFor(name: String) =
            android.webkit.MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name)) ?: "application/octet-stream"
    }

    /**
     * Carries out [plan] (or, for a zip that can't be read directly, unzips all of it as it streams).
     * With [labJpegDates], a JPEG with no EXIF date taken gets one: its own XMP date if it has one,
     * otherwise its date in the zip. Phones can't set a file's date, and galleries sort by this.
     */
    fun run(ctx: Context, plan: ZipPlan, labJpegDates: Boolean, out: Outcome, cancelled: () -> Boolean, onBytes: (Long) -> Unit) {
        val top = Names.clean(plan.top)
        val base = if (top.isEmpty()) plan.dest else
            plan.dest.findFile(top)?.takeIf { it.isDirectory } ?: plan.dest.createDirectory(top) ?: error("couldn't create the folder $top")
        out.tops += base
        val writer = Writer(ctx, base, canRename(ctx, base), cancelled)
        var done = 0L

        fun one(dirSegs: List<String>, name: String, size: Long?, time: LocalDateTime?, label: String, open: () -> InputStream) {
            try {
                val doc = open().use { data ->
                    writer.write(writer.dir(dirSegs), name, size, data, { n -> done += n; onBytes(done) }) { partUri ->
                        if (labJpegDates && isJpeg(name)) when (val r = dateJpeg(ctx, partUri, time)) {
                            null -> {}
                            "" -> out.jpegsDated++
                            else -> out.problems += "$label: unzipped, but the date couldn't be written into it ($r)"
                        }
                    }
                }
                if (doc == null) out.already++ else out.written++
                val usable = Dates.usable(time)
                val where = doc ?: writer.dir(dirSegs).kids[name.lowercase()]
                if (usable != null && where != null) out.dates[where.uri] = usable
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                out.problems += "$label: ${e.message ?: e.javaClass.simpleName}"
            }
        }

        fun streamZip(input: InputStream, into: List<String>, label: String) {
            ZipWalk.walk(input) { path, data, millis ->
                if (cancelled()) throw Cancelled()
                val segs = path.split('/').map { Names.clean(it).ifEmpty { "_" } }
                val time = if (millis > 0) LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZoneId.systemDefault()) else null
                one(into + segs.dropLast(1), segs.last(), null, time, "$label/$path") { NoClose(data) }
            }
        }

        val result = plan.build()
        val src = plan.source
        if (result == null || src == null) {
            // Can't be read directly (e.g. a cloud file): unzip everything, in order, with no preview.
            val input = ctx.contentResolver.openInputStream(plan.uri) ?: error("couldn't open ${plan.zipName}")
            input.buffered(1 shl 20).use { streamZip(it, emptyList(), plan.zipName) }
            return
        }
        for (t in result.targets.sortedBy { it.file.item.localHeaderOffset }) {
            if (cancelled()) throw Cancelled()
            one(t.dir, t.name, t.file.size, t.file.item.time, t.file.path) { ZipIndex.open(t.file.container, t.file.item) }
        }
        for (s in result.sealed) {
            if (cancelled()) throw Cancelled()
            try {
                ZipIndex.open(s.zip.container, s.zip.item).buffered(1 shl 20).use { streamZip(it, s.dir, s.zip.folder) }
            } catch (e: Cancelled) {
                throw e
            } catch (e: Exception) {
                out.problems += "${s.zip.folder}.zip: ${e.message ?: e.javaClass.simpleName}"
            }
        }
    }

    private class NoClose(input: InputStream) : java.io.FilterInputStream(input) { override fun close() {} }

    private val EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")

    /**
     * Writes a date taken into a JPEG that has none. Returns null if nothing was needed or nothing is
     * known, "" when written, or why it failed.
     */
    private fun dateJpeg(ctx: Context, uri: Uri, zipTime: LocalDateTime?): String? {
        val head = runCatching {
            ctx.contentResolver.openInputStream(uri)?.use { s ->
                val b = ByteArray(HEAD); var n = 0
                while (n < b.size) { val r = s.read(b, n, b.size - n); if (r < 0) break; n += r }
                b.copyOf(n)
            }
        }.getOrNull() ?: return null
        val found = Dates.fromImage(BytesSource(head))
        if (found.firstOrNull()?.source == "EXIF date taken") return null
        val date = found.firstOrNull()?.date ?: Dates.usable(zipTime) ?: return null
        return runCatching {
            ctx.contentResolver.openFileDescriptor(uri, "rw")?.use { pfd ->
                val x = ExifInterface(pfd.fileDescriptor)
                val s = EXIF_FMT.format(date)
                x.setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, s)
                if (x.getAttribute(ExifInterface.TAG_DATETIME_DIGITIZED) == null) x.setAttribute(ExifInterface.TAG_DATETIME_DIGITIZED, s)
                if (x.getAttribute(ExifInterface.TAG_DATETIME) == null) x.setAttribute(ExifInterface.TAG_DATETIME, s)
                x.saveAttributes()
            } ?: error("couldn't open it for writing")
            ""
        }.getOrElse { it.message ?: it.javaClass.simpleName }
    }
}
