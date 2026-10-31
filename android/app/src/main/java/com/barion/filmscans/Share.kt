package com.barion.filmscans

import android.app.Application
import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.net.Uri
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import androidx.exifinterface.media.ExifInterface
import com.barion.filmscans.core.BytesSource
import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.JpegRetag
import com.barion.filmscans.core.Metadata
import com.barion.filmscans.core.RollMeta
import com.barion.filmscans.core.Scan
import com.barion.filmscans.core.Shrink
import com.barion.filmscans.core.TiffReader
import com.barion.filmscans.core.ZipParts
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.BufferedOutputStream
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId
import java.util.zip.CRC32
import java.util.zip.CheckedOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** Which files to use from a folder holding both TIFFs and JPEGs (usually the same photos twice). */
enum class ShareUse(val label: String) { JPEGS("The JPEGs"), TIFFS("The TIFFs"), BOTH("Both") }

enum class ShareStep { Reading, Choose, Working, Done }

/** Pictures that can be shrunk: scans, JPEGs, and what phones and the web save. */
val SHARE_EXT = TIFF_EXT + JPEG_EXT + setOf("png", "webp", "heic", "heif")

/** A folder of pictures, with its place under the picked folder ("Album/Roll 12"). */
class ShareFolder(val path: String, val tiffs: List<Doc>, val jpegs: List<Doc>, val others: List<Doc>) {
    var include by mutableStateOf(true)
    val mixed get() = tiffs.isNotEmpty() && jpegs.isNotEmpty()
    val bytes = (tiffs + jpegs + others).sumOf { it.size }
    fun files(use: ShareUse): List<Doc> = (when {
        !mixed || use == ShareUse.BOTH -> tiffs + jpegs
        use == ShareUse.JPEGS -> jpegs
        else -> tiffs
    } + others).sortedBy { it.name.lowercase() }
}

class SharedZip(val name: String, val size: Long, val uri: Uri, val photos: Int)

/**
 * Smaller copies of a folder's photos, zipped to send: each photo shrunk to a set long side and
 * saved as a JPEG with its details (minus location), the folders kept as they are inside the zip,
 * and split into several zips when one would pass a set size. The originals aren't touched; the
 * zips are saved in the picked folder.
 */
class ShareModel(private val app: Application, private val scope: CoroutineScope, private val settings: Settings) {
    var step by mutableStateOf<ShareStep?>(null)
    var status by mutableStateOf("")
    var progress by mutableFloatStateOf(0f)
    var done by mutableIntStateOf(0)
    var total by mutableIntStateOf(0)
    var rootName by mutableStateOf("")
    private var root: DocumentFile? = null
    val folders = mutableStateListOf<ShareFolder>()
    val log = mutableStateListOf<String>()
    val zips = mutableStateListOf<SharedZip>()
    @Volatile var stop = false

    val folderUri: Uri? get() = root?.uri
    val anyMixed get() = folders.any { it.include && it.mixed }
    fun photos() = folders.filter { it.include }.sumOf { it.files(settings.shareUse).size }

    /** A rough size for [n] photos at the chosen size and quality, to help choose the zip size. */
    fun estimate(n: Int): Long {
        val px = settings.shareEdge.toLong() * settings.shareEdge * 2 / 3
        val perPixel = 0.15 + (settings.shareQuality - 60).coerceAtLeast(0) * 0.012
        return (n * px * perPixel).toLong()
    }

    fun open(uri: Uri) {
        val dir = DocumentFile.fromTreeUri(app, uri) ?: return
        root = dir
        rootName = dir.name ?: "Photos"
        folders.clear(); log.clear(); zips.clear(); status = ""
        step = ShareStep.Reading
        scope.launch {
            val found = withContext(Dispatchers.IO) {
                runCatching { mutableListOf<ShareFolder>().also { walk(dir, rootName, 0, it) } }
            }
            found.onSuccess { folders.addAll(it) }.onFailure { status = "Couldn't read the folder: ${it.message}" }
            if (folders.isEmpty() && found.isSuccess) status = "No photos in that folder."
            step = if (folders.isEmpty()) null else ShareStep.Choose
        }
    }

    private fun walk(dir: DocumentFile, path: String, depth: Int, into: MutableList<ShareFolder>) {
        status = path
        val kids = Docs.list(app, dir).sortedBy { it.name.lowercase() }
        val files = kids.filter { it.isFile && !it.isAppleDouble && it.ext in SHARE_EXT }
        if (files.isNotEmpty()) into += ShareFolder(path, files.filter { it.ext in TIFF_EXT }, files.filter { it.ext in JPEG_EXT },
            files.filter { it.ext !in TIFF_EXT && it.ext !in JPEG_EXT })
        if (depth < 6) kids.filter { it.isDir && !THUMB_DIR.matches(it.name) }
            .forEach { walk(it.file, "$path/${it.name}", depth + 1, into) }
    }

    fun close() {
        step = null; root = null
        folders.clear(); log.clear(); zips.clear(); status = ""
    }

    private class Todo(val doc: Doc, val path: String, val folder: String, val number: Int)
    private class Made(val item: ZipParts.Item, val file: File, val crc: Long, val time: Long)

    @OptIn(ExperimentalCoroutinesApi::class)
    fun start() {
        val dir = root ?: return
        settings.save()
        val edge = settings.shareEdge.coerceIn(256, 8000)
        val quality = settings.shareQuality.coerceIn(40, 100)
        val cap = settings.shareCapMb.takeIf { it > 0 }?.let { it * 1_000_000L }
        val credits = settings.credits()
        val todo = mutableListOf<Todo>()
        for (f in folders.filter { it.include }) {
            val used = HashSet<String>()
            f.files(settings.shareUse).forEachIndexed { i, d ->
                // foo.tif and foo.jpg both become foo.jpg: the second is foo (2).jpg.
                var name = "${stem(d.name)}.jpg"; var k = 2
                while (!used.add(name.lowercase())) name = "${stem(d.name)} (${k++}).jpg"
                todo += Todo(d, "${f.path}/$name", f.path, i + 1)
            }
        }
        if (todo.isEmpty()) return
        stop = false
        log.clear(); zips.clear()
        done = 0; total = todo.size; progress = 0f
        step = ShareStep.Working
        WorkService.start(app, "Shrinking photos")
        val workers = Dispatchers.Default.limitedParallelism(2)
        scope.launch {
            val cache = File(app.cacheDir, "share")
            withContext(Dispatchers.IO) { cache.deleteRecursively(); cache.mkdirs() }
            val made = arrayOfNulls<Made>(todo.size)
            todo.mapIndexed { i, t ->
                async(workers) {
                    if (stop) return@async
                    try {
                        made[i] = shrink(t, File(cache, "$i.jpg"), edge, quality, credits)
                    } catch (e: Throwable) {
                        withContext(Dispatchers.Main) { log += "✗ ${t.path.substringBeforeLast(".")} (${t.doc.name}): ${e.message ?: e.javaClass.simpleName}" }
                    } finally {
                        withContext(Dispatchers.Main) {
                            done++
                            progress = done.toFloat() / total
                            status = t.folder
                            WorkService.update(app, "Shrinking ${t.folder}", "$done of $total", progress)
                        }
                    }
                }
            }.awaitAll()
            if (stop) {
                withContext(Dispatchers.IO) { cache.deleteRecursively() }
                log += "Stopped. Nothing was zipped, and the photos are as they were."
                WorkService.finish(app, "Shrinking stopped", "Nothing was zipped")
                step = ShareStep.Done
                return@launch
            }
            val ready = made.filterNotNull()
            val byPath = ready.associateBy { it.item.path }
            val parts = ZipParts.pack(ready.map { it.item }, cap)
            val base = cleanName("$rootName (small)").ifBlank { "Photos (small)" }
            withContext(Dispatchers.IO) { runCatching { clearOld(dir, base) } }
            for ((k, part) in parts.withIndex()) {
                val name = ZipParts.name(base, k, parts.size)
                status = "Saving $name"
                WorkService.update(app, "Zipping", name, 1f)
                withContext(Dispatchers.IO) { runCatching { writeZip(dir, name, part.map { byPath.getValue(it.path) }) } }
                    .onSuccess { zips += it }
                    .onFailure { log += "✗ $name: ${it.message ?: it.javaClass.simpleName}" }
            }
            withContext(Dispatchers.IO) { cache.deleteRecursively() }
            val big = zips.filter { cap != null && it.size > cap }
            if (big.isNotEmpty()) log += "${big.joinToString { it.name }}: one photo alone is bigger than the zip size chosen."
            status = ""
            step = ShareStep.Done
            WorkService.finish(app, "Ready to send",
                "${ready.size} photo(s) in ${zips.size} zip(s)" + log.count { it.startsWith("✗") }.let { if (it > 0) ", $it problem(s)" else "" })
        }
    }

    /** One photo, shrunk and saved as a JPEG in the cache. */
    private fun shrink(t: Todo, out: File, edge: Int, quality: Int, credits: Credits): Made {
        val doc = t.doc
        val encoded: ByteArray
        val segments: List<ByteArray>
        when (doc.ext) {
            in TIFF_EXT -> UriSource.open(app, doc.uri).use { src ->
                val tiff = TiffReader(src)
                val (dw, dh) = Shrink.fit(tiff.width, tiff.height, edge)
                val px = Shrink.tiff(tiff, dw, dh)
                encoded = jpeg(Bitmap.createBitmap(px, dw, dh, Bitmap.Config.ARGB_8888), quality)
                val date = Dates.fromTiff(tiff).firstOrNull()?.date ?: ZipDates.get(app, doc.uri) ?: fileDate(doc)
                val frame = Scan.frame(tiff, doc.name, date, t.number, t.folder.substringAfterLast('/'))
                    .copy(width = dw, height = dh, dpi = tiff.dpi()?.let { it * dw / tiff.width })
                segments = Metadata.segments(frame, RollMeta(), credits)
            }
            in JPEG_EXT -> {
                segments = UriSource.open(app, doc.uri).use { Shrink.jpegSegments(it) }
                // The picture stays as stored; the orientation in the copied details turns it.
                encoded = jpeg(decode(doc.uri, edge), quality)
            }
            else -> {
                val o = runCatching { app.contentResolver.openInputStream(doc.uri)!!.use { ExifInterface(it).getAttributeInt(
                    ExifInterface.TAG_ORIENTATION, 1) } }.getOrDefault(1)
                var bmp = decode(doc.uri, edge)
                val m = Thumbnails.orientationMatrix(o)
                if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
                encoded = jpeg(bmp, quality)
                segments = emptyList()
            }
        }
        val crc = CRC32()
        CheckedOutputStream(BufferedOutputStream(FileOutputStream(out), 1 shl 16), crc).use { os ->
            if (segments.isEmpty()) os.write(encoded)
            else JpegRetag.rewrite(BytesSource(encoded), segments, os, keepProfile = false)
        }
        return Made(ZipParts.Item(t.path, out.length(), t.folder), out, crc.value, doc.modified.takeIf { it > 0 } ?: System.currentTimeMillis())
    }

    /** Decoded at about twice the size wanted or less, then scaled to it. */
    private fun decode(uri: Uri, edge: Int): Bitmap {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) error("can't read this picture")
        val long = maxOf(bounds.outWidth, bounds.outHeight)
        var sample = 1
        while (long / (sample * 2) >= edge) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample; inPreferredConfig = Bitmap.Config.ARGB_8888 }
        val bmp = app.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
            ?: error("can't read this picture")
        val (dw, dh) = Shrink.fit(bmp.width, bmp.height, edge)
        if (dw == bmp.width && dh == bmp.height) return bmp
        return Bitmap.createScaledBitmap(bmp, dw, dh, true).also { if (it !== bmp) bmp.recycle() }
    }

    /** Transparent parts (a PNG's) go white rather than black. */
    private fun jpeg(bmp: Bitmap, quality: Int): ByteArray {
        var b = bmp
        if (b.hasAlpha()) {
            val flat = Bitmap.createBitmap(b.width, b.height, Bitmap.Config.ARGB_8888)
            Canvas(flat).apply { drawColor(Color.WHITE); drawBitmap(b, 0f, 0f, null) }
            b.recycle(); b = flat
        }
        val out = ByteArrayOutputStream()
        if (!b.compress(Bitmap.CompressFormat.JPEG, quality, out)) error("couldn't save the smaller copy")
        b.recycle()
        return out.toByteArray()
    }

    private fun fileDate(doc: Doc): LocalDateTime =
        LocalDateTime.ofInstant(Instant.ofEpochMilli(doc.modified.takeIf { it > 0 } ?: System.currentTimeMillis()), ZoneId.systemDefault()).withNano(0)

    /** Zips this made before from the same folder, so a smaller run doesn't leave old parts behind. */
    private fun clearOld(dir: DocumentFile, base: String) {
        val mine = Regex("""^${Regex.escape(base)}( part \d+ of \d+)?\.zip$""", RegexOption.IGNORE_CASE)
        Docs.list(app, dir).filter { it.isFile && mine.matches(it.name) }.forEach { it.file.delete() }
    }

    /** Stored, not compressed: JPEGs don't shrink in a zip, and the zip's size is then known ahead. */
    private fun writeZip(dir: DocumentFile, name: String, part: List<Made>): SharedZip {
        val doc = dir.createFile("application/zip", name.removeSuffix(".zip")) ?: error("couldn't create $name")
        try {
            val os = app.contentResolver.openOutputStream(doc.uri, "w") ?: error("couldn't write $name")
            ZipOutputStream(BufferedOutputStream(os, 1 shl 16)).use { z ->
                for (m in part) {
                    val size = m.file.length()
                    z.putNextEntry(ZipEntry(m.item.path).apply {
                        method = ZipEntry.STORED; this.size = size; compressedSize = size; crc = m.crc; time = m.time
                    })
                    m.file.inputStream().use { it.copyTo(z, 1 shl 16) }
                    z.closeEntry()
                }
            }
        } catch (e: Throwable) {
            runCatching { doc.delete() }
            throw e
        }
        part.forEach { it.file.delete() }
        return SharedZip(doc.name ?: name, doc.length(), doc.uri, part.size)
    }

    companion object {
        /** The share sheet, with every zip: send them to another app, or to a chat. */
        fun send(ctx: Context, zips: List<SharedZip>) {
            if (zips.isEmpty()) return
            val uris = ArrayList(zips.map { it.uri })
            val i = if (uris.size == 1) Intent(Intent.ACTION_SEND).putExtra(Intent.EXTRA_STREAM, uris[0])
                else Intent(Intent.ACTION_SEND_MULTIPLE).putParcelableArrayListExtra(Intent.EXTRA_STREAM, uris)
            i.type = "application/zip"
            i.clipData = ClipData.newUri(ctx.contentResolver, zips[0].name, uris[0]).apply {
                uris.drop(1).forEach { addItem(ClipData.Item(it)) }
            }
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            ctx.startActivity(Intent.createChooser(i, "Send the zip${if (uris.size > 1) "s" else ""}").addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
    }
}
