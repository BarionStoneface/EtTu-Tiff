package com.barion.filmscans

import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.barion.filmscans.core.BytesSource
import com.barion.filmscans.core.DateFound
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.JpegRetag
import com.barion.filmscans.core.TiffReader
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

object Rolls {
    /**
     * Every folder (the picked one and up to 3 levels down) that holds TIFFs is a roll to convert.
     * A folder holding only JPEGs (a lab's) is a roll to tag.
     */
    fun find(ctx: Context, root: DocumentFile, zips: MutableList<FoundZip>, deleteInfoFiles: Boolean,
             onProgress: (String) -> Unit): List<Roll> {
        val out = mutableListOf<Roll>()
        // Each scan's header is a few small reads; read a few files at once rather than one by one.
        val pool = java.util.concurrent.Executors.newFixedThreadPool(4)
        fun walk(dir: DocumentFile, name: String, siblings: Set<String>, isRoot: Boolean, depth: Int) {
            val kids = Docs.list(ctx, dir).sortedBy { it.name.lowercase() }
            val files = kids.filter { it.isFile && !it.isAppleDouble }
            files.filter { it.ext == "zip" }.forEach { zips += FoundZip(it, dir) }
            val tiffs = files.filter { it.ext in TIFF_EXT }
            val images = tiffs.ifEmpty { files.filter { it.ext in JPEG_EXT } }
            if (images.isNotEmpty())
                out += readRoll(ctx, pool, dir, name, isRoot, kids, images, siblings, deleteInfoFiles, onProgress, jpegRoll = tiffs.isEmpty())
            if (depth < 3) {
                val folders = kids.filter { it.isDir && !THUMB_DIR.matches(it.name) }
                val names = kids.filter { it.isDir }.map { it.name.lowercase() }.toSet()
                folders.forEach { walk(it.file, it.name, names, false, depth + 1) }
            }
        }
        try {
            walk(root, root.name ?: "Scans", emptySet(), true, 0)
        } finally {
            pool.shutdown()
        }
        return out
    }

    private fun readRoll(ctx: Context, pool: java.util.concurrent.ExecutorService, dir: DocumentFile, dirName: String,
                         isRoot: Boolean, kids: List<Doc>, images: List<Doc>, siblings: Set<String>,
                         deleteInfoFiles: Boolean, onProgress: (String) -> Unit, jpegRoll: Boolean): Roll {
        val byStem = kids.filter { it.isFile }.groupBy { stem(it.name).lowercase() }
        val read = images.map { doc ->
            pool.submit<Pair<ScanFile, Boolean>> {
                onProgress("$dirName/${doc.name}")
                val sidecars = byStem[stem(doc.name).lowercase()].orEmpty().filter { it.uri != doc.uri }
                if (jpegRoll) readJpeg(ctx, doc, sidecars) else readTiff(ctx, doc, sidecars) to false
            }
        }.map { it.get() }
        val files = read.map { it.first }
        val sidecars = kids.filter { k ->
            if (k.isDir) THUMB_DIR.matches(k.name)
            else k.ext in SIDECAR_EXT || k.isAppleDouble || k.name.equals(".DS_Store", true)
        }.map { it.file }
        val jpegs = kids.filter { it.isFile && it.ext in JPEG_EXT }.associate { it.name.lowercase() to it.file }
        return Roll(dir, isRoot, dirName, files, sidecars, jpegs, if (isRoot) emptySet() else siblings, deleteInfoFiles,
            jpegRoll = jpegRoll, alreadyTagged = jpegRoll && read.all { it.second })
    }

    /** A lab JPEG's facts, and whether this app already tagged it. */
    private fun readJpeg(ctx: Context, doc: Doc, sidecars: List<Doc>): Pair<ScanFile, Boolean> = try {
        UriSource.open(ctx, doc.uri).use { src ->
            val info = JpegRetag.info(src)
            val found = Dates.fromJpeg(src).firstOrNull() ?: sidecarDate(ctx, sidecars) ?: fileDate(ctx, doc)
            val scanner = listOfNotNull(info.scannerMake, info.scannerModel).joinToString(" ").ifBlank { null }
            ScanFile(doc.file, doc.name, info.width, info.height, 8, scanner, found, null, info.orientation) to info.taggedByThisApp
        }
    } catch (e: Exception) {
        ScanFile(doc.file, doc.name, 0, 0, 0, null, fileDate(ctx, doc), e.message ?: "can't read this JPEG") to false
    }

    private fun readTiff(ctx: Context, doc: Doc, sidecars: List<Doc>): ScanFile = try {
        UriSource.open(ctx, doc.uri).use { src ->
            val t = TiffReader(src)
            val err = runCatching { t.checkSupported() }.exceptionOrNull()?.message
            val found = Dates.fromTiff(t).firstOrNull() ?: sidecarDate(ctx, sidecars) ?: fileDate(ctx, doc)
            val scanner = listOfNotNull(t.text(271), t.text(272)).joinToString(" ").ifBlank { null }
            ScanFile(doc.file, doc.name, t.width, t.height, t.bits, scanner, found, err)
        }
    } catch (e: Exception) {
        ScanFile(doc.file, doc.name, 0, 0, 0, null, fileDate(ctx, doc), e.message ?: "can't read this TIFF")
    }

    /** Camera/scanner .thm (a small JPEG) and .xmp sidecars written at scan time can carry the date. */
    private fun sidecarDate(ctx: Context, same: List<Doc>): DateFound? {
        for (s in same) {
            val e = s.ext
            if (e != "thm" && e != "xmp") continue
            if (s.size > 4_000_000) continue
            runCatching {
                ctx.contentResolver.openInputStream(s.uri)?.use { ins ->
                    val b = ins.readBytes()
                    val found = if (e == "thm") Dates.fromImage(BytesSource(b)) else Dates.fromXmp(b.toString(Charsets.UTF_8))
                    found.firstOrNull()?.let { return it.copy(source = ".$e file: ${it.source}") }
                }
            }
        }
        return null
    }

    /**
     * The date the file had inside the zip it was unzipped from (kept by this app), or, failing that,
     * the file's modified date on the phone. That last one is often the download date, so it's flagged
     * and only used if you choose to.
     */
    private fun fileDate(ctx: Context, doc: Doc): DateFound {
        ZipDates.get(ctx, doc.uri)?.let { return DateFound(it, "date in the zip") }
        val t = doc.modified.takeIf { it > 0 } ?: System.currentTimeMillis()
        return DateFound(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).withNano(0),
            "file date on the phone, not stored in the scan", embedded = false)
    }
}
