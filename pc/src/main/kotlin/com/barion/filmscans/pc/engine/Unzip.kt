package com.barion.filmscans.pc

import com.barion.filmscans.core.Archive
import com.barion.filmscans.core.BytesSource
import com.barion.filmscans.core.DateFound
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.JpegRetag
import com.barion.filmscans.core.Listing
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.PlanFolder
import com.barion.filmscans.core.UnzipPlan
import com.barion.filmscans.core.ZipIndex
import com.barion.filmscans.core.ZipWalk
import java.io.Closeable
import java.io.File
import java.io.InputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** What's in one zip, read without unpacking anything, and where each folder's scan dates come from. */
class ZipContents(val zip: File, private val source: FileSource, val listing: Listing) : Closeable {
    val folders: List<PlanFolder> = UnzipPlan.folders(listing)
    /** Per folder: where the scan dates come from (label to count), and how many images have none. */
    val dateSummary = HashMap<String, Pair<Map<String, Int>, Int>>()
    val hasJpegs get() = listing.files.any { isJpeg(it.name) }
    override fun close() = source.close()
}

/** Unzips a download, zips inside it included, following a plan you've checked. */
object Unzip {
    /** Enough to reach the EXIF and XMP of these labs' scans when a file has to be inflated to read them. */
    private const val HEAD = 256 * 1024

    class Outcome {
        var written = 0
        var already = 0
        var jpegsDated = 0
        var fileDated = 0
        var fileDateRefused = 0
        val problems = mutableListOf<String>()
        /** Each file's date in the zip, remembered as its scan date for later. */
        val dates = HashMap<File, LocalDateTime>()
    }

    class Cancelled : Exception("stopped")

    /** Reads what's in the zip and where each scan's date will come from. Writes nothing. */
    fun read(zip: File, onProgress: (String) -> Unit = {}): ZipContents {
        val src = FileSource(zip)
        val listing = try {
            Archive.list(src)
        } catch (e: Exception) {
            src.close()
            throw IllegalStateException("${zip.name} isn't a zip this app can read (${e.message})")
        }
        val c = ZipContents(zip, src, listing)
        val byFolder = listing.files.filter { isImage(it.name) }.groupBy { it.folder }
        val all = byFolder.values.sumOf { it.size }
        var n = 0
        for ((folder, files) in byFolder) {
            val counts = HashMap<String, Int>()
            var undated = 0
            for (f in files) {
                if (++n % 10 == 0) onProgress("Reading dates: $n of $all")
                val found: DateFound? = runCatching {
                    val s = ZipIndex.storedSource(f.container, f.item) ?: BytesSource(ZipIndex.head(f.container, f.item, HEAD))
                    Dates.fromImage(s).firstOrNull()
                }.getOrNull()
                val label = found?.source ?: if (Dates.usable(f.item.time) != null) "date in the zip" else null
                if (label == null) undated++ else counts[label] = (counts[label] ?: 0) + 1
            }
            c.dateSummary[folder] = counts to undated
        }
        return c
    }

    /**
     * Carries out [plan] into [dest]. Each file is written as name.part and renamed once complete, so
     * stopping is safe and running it again carries on. A file already there with the same size is
     * left alone. Every file's created and modified dates become its scan date. With [labJpegDates],
     * a JPEG with no EXIF date taken gets one: its own XMP date if it has one, otherwise its date in the zip.
     */
    fun run(plan: UnzipPlan.Result, dest: File, labJpegDates: Boolean, out: Outcome,
            cancelled: () -> Boolean = { false }, onBytes: (Long) -> Unit = {}) {
        var done = 0L

        fun one(dirSegs: List<String>, name: String, size: Long?, time: LocalDateTime?, label: String, open: () -> InputStream) {
            try {
                val dir = dirSegs.fold(dest) { d, s -> File(d, s) }
                if (dir.isFile) error("there's already a file called ${dir.name} where a folder has to go")
                if (!dir.isDirectory && !dir.mkdirs()) error("couldn't create the folder ${dir.name}")
                val target = File(dir, name)
                var scanDate = Dates.usable(time)
                if (target.exists()) {
                    // Size unknown (a zip read start to finish): whatever is there is kept, never overwritten.
                    // A lab JPEG given its date last time differs in size but holds the same picture.
                    if (size != null && size >= 0 && target.length() != size && !samePicture(target, name, open))
                        error("a different $name is already there, so it was left alone")
                    out.already++
                } else {
                    val part = File(dir, "$name.part")
                    try {
                        open().use { data ->
                            part.outputStream().use { o ->
                                val buf = ByteArray(1 shl 20)
                                while (true) {
                                    if (cancelled()) throw Cancelled()
                                    val n = data.read(buf)
                                    if (n < 0) break
                                    o.write(buf, 0, n)
                                    done += n
                                    onBytes(done)
                                }
                            }
                        }
                        if (isJpeg(name)) {
                            val best = runCatching { FileSource(part).use { Dates.fromImage(it) } }.getOrNull().orEmpty().firstOrNull()
                            if (best != null) scanDate = best.date
                            val d = scanDate
                            if (labJpegDates && best?.source != "EXIF date taken" && d != null) {
                                runCatching { ExifDate.write(part, d) }
                                    .onSuccess { out.jpegsDated++ }
                                    .onFailure { out.problems += "$label: unzipped, but the date couldn't be written into it (${it.message})" }
                            }
                        }
                        Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    } catch (e: Throwable) {
                        part.delete()
                        throw e
                    }
                    out.written++
                }
                val d = scanDate
                if (d != null) { if (FileTimes.set(target, d)) out.fileDated++ else out.fileDateRefused++ }
                Dates.usable(time)?.let { out.dates[target] = it }
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

        for (t in plan.targets.sortedBy { it.file.item.localHeaderOffset }) {
            if (cancelled()) throw Cancelled()
            one(t.dir, t.name, t.file.size, t.file.item.time, t.file.path) { ZipIndex.open(t.file.container, t.file.item) }
        }
        for (s in plan.sealed) {
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

    private fun samePicture(existing: File, name: String, open: () -> InputStream): Boolean = isJpeg(name) && runCatching {
        FileSource(existing).use { a -> JpegRetag.sameImage(a, BytesSource(open().use { it.readBytes() })) }
    }.getOrDefault(false)

    private class NoClose(input: InputStream) : java.io.FilterInputStream(input) { override fun close() {} }
}
