package com.barion.filmscans.pc

import com.barion.filmscans.core.BytesSource
import com.barion.filmscans.core.DateFound
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.JpegRetag
import com.barion.filmscans.core.TiffReader
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/** One scan as found: its size, scanner, date and where the date came from. */
class ScanInfo(
    val file: File,
    val width: Int,
    val height: Int,
    val bits: Int,
    val scanner: String?,
    val date: DateFound,
    val error: String?,
    val orientation: Int = 1,
) {
    val name: String get() = file.name
    /** A lab's JPEG, tagged without re-saving, rather than a TIFF to convert. */
    val isJpeg get() = isJpeg(name)
}

/** A folder of scans, as found. What you choose for it is a [RollPlan]. */
class RollInfo(
    val folder: File,
    val isRoot: Boolean,
    val files: List<ScanInfo>,
    /** Info files and thumbnail folders next to the scans. */
    val sidecars: List<File>,
    /** JPEGs already in the folder, by lowercase name. */
    val existing: Map<String, File>,
    /** Other folders next to this one, lowercase, so a rename can't land on one of them. */
    val siblings: Set<String>,
    /** A folder of the lab's JPEGs: they're tagged, not converted. */
    val jpegRoll: Boolean,
    /** Every JPEG here was already tagged by this app. */
    val alreadyTagged: Boolean,
) {
    val name: String get() = folder.name.ifEmpty { folder.path }
}

/** Same rule as on the phone: "Roll 12 TIFF" -> "Roll 12 JPEG", otherwise add " JPEG". */
fun jpegFolderName(name: String): String {
    val r = Regex("""(?i)\b(tiffs?|tifs?)\b""").replace(name, "JPEG")
    return if (r != name) r else "$name JPEG"
}

object Rolls {
    /**
     * Every folder (the picked one and up to 3 levels down) that holds TIFFs is a roll to convert.
     * A folder holding only JPEGs (a lab's) is a roll to tag. Zips found on the way go in [zips].
     */
    fun find(root: File, zips: MutableList<File>, onProgress: (String) -> Unit = {}): List<RollInfo> {
        val out = mutableListOf<RollInfo>()
        // Each scan's header is a few small reads; read a few files at once.
        val pool = Executors.newFixedThreadPool(4)
        fun walk(dir: File, siblings: Set<String>, isRoot: Boolean, depth: Int) {
            val kids = listed(dir)
            val files = kids.filter { it.isFile && !isAppleDouble(it.name) }
            files.filter { ext(it.name) == "zip" }.forEach { zips += it }
            val tiffs = files.filter { ext(it.name) in TIFF_EXT }
            val images = tiffs.ifEmpty { files.filter { ext(it.name) in JPEG_EXT } }
            if (images.isNotEmpty())
                out += readRoll(pool, dir, isRoot, kids, images, if (isRoot) emptySet() else siblings, onProgress, jpegRoll = tiffs.isEmpty())
            if (depth < 3) {
                val names = kids.filter { it.isDirectory }.map { it.name.lowercase() }.toSet()
                kids.filter { it.isDirectory && !THUMB_DIR.matches(it.name) }.forEach { walk(it, names, false, depth + 1) }
            }
        }
        try {
            walk(root, emptySet(), true, 0)
        } finally {
            pool.shutdown()
        }
        return out
    }

    private fun readRoll(pool: ExecutorService, dir: File, isRoot: Boolean, kids: List<File>, images: List<File>,
                         siblings: Set<String>, onProgress: (String) -> Unit, jpegRoll: Boolean): RollInfo {
        val byStem = kids.filter { it.isFile }.groupBy { stem(it.name).lowercase() }
        val read = images.map { f ->
            pool.submit<Pair<ScanInfo, Boolean>> {
                onProgress("${dir.name}/${f.name}")
                val sidecars = byStem[stem(f.name).lowercase()].orEmpty().filter { it != f }
                if (jpegRoll) readJpeg(f, sidecars) else readTiff(f, sidecars) to false
            }
        }.map { it.get() }
        val sidecars = kids.filter { k ->
            if (k.isDirectory) THUMB_DIR.matches(k.name)
            else ext(k.name) in SIDECAR_EXT || isAppleDouble(k.name) || k.name.equals(".DS_Store", true)
        }
        val jpegs = kids.filter { it.isFile && ext(it.name) in JPEG_EXT }.associateBy { it.name.lowercase() }
        return RollInfo(dir, isRoot, read.map { it.first }, sidecars, jpegs, siblings,
            jpegRoll = jpegRoll, alreadyTagged = jpegRoll && read.all { it.second })
    }

    /** A lab JPEG's facts, and whether this app already tagged it. */
    fun readJpeg(f: File, sidecars: List<File> = emptyList()): Pair<ScanInfo, Boolean> = try {
        FileSource(f).use { src ->
            val info = JpegRetag.info(src)
            val found = Dates.fromJpeg(src).firstOrNull() ?: sidecarDate(sidecars) ?: fileDate(f)
            val scanner = listOfNotNull(info.scannerMake, info.scannerModel).joinToString(" ").ifBlank { null }
            ScanInfo(f, info.width, info.height, 8, scanner, found, null, info.orientation) to info.taggedByThisApp
        }
    } catch (e: Exception) {
        ScanInfo(f, 0, 0, 0, null, fileDate(f), e.message ?: "can't read this JPEG") to false
    }

    fun readTiff(f: File, sidecars: List<File> = emptyList()): ScanInfo = try {
        FileSource(f).use { src ->
            val t = TiffReader(src)
            val err = runCatching { t.checkSupported() }.exceptionOrNull()?.message
            val found = Dates.fromTiff(t).firstOrNull() ?: sidecarDate(sidecars) ?: fileDate(f)
            val scanner = listOfNotNull(t.text(271), t.text(272)).joinToString(" ").ifBlank { null }
            ScanInfo(f, t.width, t.height, t.bits, scanner, found, err)
        }
    } catch (e: Exception) {
        ScanInfo(f, 0, 0, 0, null, fileDate(f), e.message ?: "can't read this TIFF")
    }

    /** Camera/scanner .thm (a small JPEG) and .xmp sidecars written at scan time can carry the date. */
    private fun sidecarDate(same: List<File>): DateFound? {
        for (s in same) {
            val e = ext(s.name)
            if (e != "thm" && e != "xmp") continue
            if (s.length() > 4_000_000) continue
            runCatching {
                val b = s.readBytes()
                val found = if (e == "thm") Dates.fromImage(BytesSource(b)) else Dates.fromXmp(b.toString(Charsets.UTF_8))
                found.firstOrNull()?.let { return it.copy(source = ".$e file: ${it.source}") }
            }
        }
        return null
    }

    /**
     * The date the file had inside the zip it was unzipped from (kept by this app), or, failing that,
     * its modified date on this computer. That last one is often the download date, so it's flagged
     * and only used if you choose to.
     */
    fun fileDate(f: File): DateFound {
        ZipDates.get(f)?.let { return DateFound(it, "date in the zip") }
        return DateFound(FileTimes.modified(f), "file date on this computer, not stored in the scan", embedded = false)
    }
}
