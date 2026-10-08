package com.barion.filmscans

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.barion.filmscans.core.Thumbs
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.documentfile.provider.DocumentFile
import com.barion.filmscans.core.ByteSource
import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.DateFound
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.BytesSource
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.RollMeta
import com.barion.filmscans.core.Scan
import com.barion.filmscans.core.TiffReader
import java.io.BufferedOutputStream
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

val TIFF_EXT = setOf("tif", "tiff")
/** Info/sidecar files removed once a roll converts cleanly. */
val SIDECAR_EXT = setOf("thm", "xmp", "info", "nfo", "xml", "txt", "db", "ini", "ds_store", "md5", "sfv", "log", "dat")
val THUMB_DIR = Regex("""(?i)^[._]*(thumbs?|thumbnails?|thm|previews?)$""")

fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
fun stem(name: String) = name.substringBeforeLast('.')
fun cleanName(s: String) = Names.clean(s)

/** Same rule as the desktop script: "Roll 12 TIFF" -> "Roll 12 JPEG", otherwise add " JPEG". */
fun jpegFolderName(name: String): String {
    val r = Regex("""(?i)\b(tiffs?|tifs?)\b""").replace(name, "JPEG")
    return if (r != name) r else "$name JPEG"
}

@Stable
class ScanFile(
    val doc: DocumentFile,
    val name: String,
    val width: Int,
    val height: Int,
    val bits: Int,
    val scanner: String?,
    val date: DateFound,
    val error: String?,
) {
    var newName by mutableStateOf(stem(name))
    @Volatile var done = false
    var thumb by mutableStateOf<ImageBitmap?>(null)
}

@Stable
class Roll(
    val folder: DocumentFile,
    val isRoot: Boolean,
    val name: String,
    val files: List<ScanFile>,
    val sidecars: List<DocumentFile>,
    /** JPEGs already in the folder, by lowercase name. */
    val existing: Map<String, DocumentFile>,
    /** Other folders next to this one, lowercase, so a rename can't land on one of them. */
    private val siblings: Set<String>,
    deleteInfoFiles: Boolean,
) {
    var camera by mutableStateOf("")
    var lens by mutableStateOf("")
    var film by mutableStateOf("")
    var iso by mutableStateOf("")
    var push by mutableIntStateOf(0)
    var tags by mutableStateOf(setOf<String>())
    var notes by mutableStateOf("")
    var lab by mutableStateOf("")
    /** Text added before and after the lab's file name, which itself is kept whole. */
    var before by mutableStateOf("")
    var join by mutableStateOf("_")
    var after by mutableStateOf("")
    var newFolderName by mutableStateOf(jpegFolderName(name))
    var renameFolder by mutableStateOf(true)
    /** .thm, .xmp and other info files: only ever deleted when chosen (Settings sets the starting point). */
    var deleteSidecars by mutableStateOf(deleteInfoFiles)
    var dateOverride by mutableStateOf("")
    /** Use the files' own dates on the phone for scans with no date inside: only when chosen, as they may be download dates. */
    var useFileDates by mutableStateOf(false)
    /** Replace JPEGs already in the folder that have the same names. */
    var overwrite by mutableStateOf(false)
    var include by mutableStateOf(true)
    /** The folder's address after renaming (renaming changes it). */
    var outputUri: Uri? = null

    val undated get() = files.count { !it.date.embedded }
    val scanners get() = files.mapNotNull { it.scanner }.distinct()

    fun meta() = RollMeta(
        camera = camera.trim(), lens = lens.trim(), film = film.trim(), boxIso = iso.trim().toIntOrNull(),
        pushStops = push, tags = tags, notes = notes.trim(), lab = lab.trim(),
    )

    fun parts() = Names.Parts(before, join, after)

    /** Puts the before/after text around every lab name. Names edited one by one are replaced. */
    fun applyNames() {
        val roll = if (renameFolder) newFolderName else name
        files.forEachIndexed { i, f ->
            f.newName = Names.around(stem(f.name), parts(), i + 1, f.date.date, roll, film).ifEmpty { stem(f.name) }
        }
    }

    /** Camera, film and the rest, from another roll. Only when asked: nothing carries over by itself. */
    fun copyDetailsFrom(o: Roll) {
        camera = o.camera; lens = o.lens; film = o.film; iso = o.iso; push = o.push; tags = o.tags
        notes = o.notes; lab = o.lab
        before = o.before; join = o.join; after = o.after
        applyNames()
    }

    fun overrideDate(): LocalDateTime? = Dates.parse(dateOverride.trim())

    /** The scans whose date would be the phone's file date. */
    val undatedFiles get() = files.filter { !it.date.embedded }

    fun problems(keepTiffs: Boolean): List<String> {
        val out = mutableListOf<String>()
        val names = files.map { it.newName.lowercase() }
        names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "Two files would both be named \"$it.jpg\"" }
        if (files.any { it.newName.isBlank() }) out += "A file name is empty"
        if (dateOverride.isNotBlank() && overrideDate() == null) out += "Scan date isn't a date (use 2019-04-12 or 2019-04-12 14:30)"
        if (undated > 0 && dateOverride.isBlank() && !useFileDates)
            out += "$undated scan(s) have no scan date: enter it, or choose to use their file dates"
        val replacing = replacing()
        if (replacing.isNotEmpty() && !overwrite) out += "${replacing.size} JPEG(s) with these names are already in the folder"
        if (!keepTiffs && renameFolder) {
            val n = cleanName(newFolderName)
            if (n.isEmpty()) out += "The new folder name is empty"
            else if (!n.equals(name, true) && n.lowercase() in siblings) out += "There's already a folder called \"$n\" next to this one"
        }
        files.filter { it.error != null }.forEach { out += "${it.name}: ${it.error}" }
        return out
    }

    fun replacing(): List<String> = files.map { it.newName + ".jpg" }.filter { it.lowercase() in existing }

    /** True when a scan's name no longer has the lab's name in it (only possible by editing one by one). */
    fun labNameDropped() = files.any { !it.newName.contains(stem(it.name), ignoreCase = true) }
}

/** Positional reads from a file descriptor; falls back to a cached copy for non-seekable sources. */
class UriSource private constructor(private val ch: FileChannel, private val closer: Closeable, private val temp: File?) : ByteSource, Closeable {
    override val size: Long = ch.size()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        val bb = ByteBuffer.wrap(buf, off, len)
        var p = pos
        while (bb.hasRemaining()) {
            val n = ch.read(bb, p)
            if (n < 0) throw java.io.EOFException("unexpected end of file")
            p += n
        }
    }
    override fun close() { closer.close(); temp?.delete() }

    companion object {
        /** Random access to the file, or null when the phone can only stream it (e.g. some cloud files). */
        fun openSeekable(ctx: Context, uri: Uri): UriSource? {
            val pfd = runCatching { ctx.contentResolver.openFileDescriptor(uri, "r") }.getOrNull() ?: return null
            val stream = FileInputStream(pfd.fileDescriptor)
            val ok = runCatching { stream.channel.size() > 0 && stream.channel.position(0) != null }.getOrDefault(false)
            if (ok) return UriSource(stream.channel, Closeable { stream.close(); pfd.close() }, null)
            stream.close(); pfd.close()
            return null
        }

        fun open(ctx: Context, uri: Uri): UriSource {
            val pfd = ctx.contentResolver.openFileDescriptor(uri, "r") ?: error("can't open file")
            val stream = FileInputStream(pfd.fileDescriptor)
            val ok = runCatching { stream.channel.size(); stream.channel.position(0) }.isSuccess
            if (ok) return UriSource(stream.channel, Closeable { stream.close(); pfd.close() }, null)
            stream.close(); pfd.close()
            val tmp = File.createTempFile("scan", ".tif", ctx.cacheDir)
            ctx.contentResolver.openInputStream(uri)!!.use { i -> tmp.outputStream().use { i.copyTo(it, 1 shl 20) } }
            val raf = RandomAccessFile(tmp, "r")
            return UriSource(raf.channel, raf, tmp)
        }
    }
}

object Rolls {
    /** Every folder (the picked one and up to 3 levels down) that holds TIFFs is a roll. */
    fun find(ctx: Context, root: DocumentFile, zips: MutableList<DocumentFile>, deleteInfoFiles: Boolean,
             onProgress: (String) -> Unit): List<Roll> {
        val out = mutableListOf<Roll>()
        fun walk(dir: DocumentFile, depth: Int) {
            val kids = dir.listFiles()
            zips += kids.filter { it.isFile && ext(it.name ?: "") == "zip" && !(it.name ?: "").startsWith("._") }
            val tiffs = kids.filter { it.isFile && ext(it.name ?: "") in TIFF_EXT && !(it.name ?: "").startsWith("._") }
                .sortedBy { it.name?.lowercase() }
            if (tiffs.isNotEmpty()) out += readRoll(ctx, dir, dir == root, kids, tiffs, deleteInfoFiles, onProgress)
            if (depth < 3) kids.filter { it.isDirectory && !THUMB_DIR.matches(it.name ?: "") }
                .sortedBy { it.name?.lowercase() }.forEach { walk(it, depth + 1) }
        }
        walk(root, 0)
        return out
    }

    private fun readRoll(ctx: Context, dir: DocumentFile, isRoot: Boolean, kids: Array<DocumentFile>,
                         tiffs: List<DocumentFile>, deleteInfoFiles: Boolean, onProgress: (String) -> Unit): Roll {
        val byStem = kids.filter { it.isFile }.groupBy { stem(it.name ?: "").lowercase() }
        val files = tiffs.map { doc ->
            val name = doc.name ?: "?"
            onProgress("${dir.name}/$name")
            try {
                UriSource.open(ctx, doc.uri).use { src ->
                    val t = TiffReader(src)
                    val err = runCatching { t.checkSupported() }.exceptionOrNull()?.message
                    val found = Dates.fromTiff(t).firstOrNull()
                        ?: sidecarDate(ctx, byStem[stem(name).lowercase()].orEmpty())
                        ?: fileDate(ctx, doc)
                    val scanner = listOfNotNull(t.text(271), t.text(272)).joinToString(" ").ifBlank { null }
                    ScanFile(doc, name, t.width, t.height, t.bits, scanner, found, err)
                }
            } catch (e: Exception) {
                ScanFile(doc, name, 0, 0, 0, null, fileDate(ctx, doc), e.message ?: "can't read this TIFF")
            }
        }
        val sidecars = mutableListOf<DocumentFile>()
        for (k in kids) {
            val n = k.name ?: continue
            if (k.isDirectory && THUMB_DIR.matches(n)) sidecars += k
            else if (k.isFile && (ext(n) in SIDECAR_EXT || n.startsWith("._") || n.equals(".DS_Store", true))) sidecars += k
        }
        val jpegs = kids.filter { it.isFile && ext(it.name ?: "") in setOf("jpg", "jpeg") }.associateBy { it.name!!.lowercase() }
        val siblings = if (isRoot) emptySet() else
            dir.parentFile?.listFiles()?.filter { it.isDirectory }?.mapNotNull { it.name?.lowercase() }?.toSet().orEmpty()
        return Roll(dir, isRoot, dir.name ?: "Scans", files, sidecars, jpegs, siblings, deleteInfoFiles)
    }

    /** Camera/scanner .thm (a small JPEG) and .xmp sidecars written at scan time can carry the date. */
    private fun sidecarDate(ctx: Context, same: List<DocumentFile>): DateFound? {
        for (s in same) {
            val e = ext(s.name ?: "")
            if (e != "thm" && e != "xmp") continue
            if (s.length() > 4_000_000) continue
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
    private fun fileDate(ctx: Context, doc: DocumentFile): DateFound {
        ZipDates.get(ctx, doc.uri)?.let { return DateFound(it, "date in the zip") }
        val t = doc.lastModified().takeIf { it > 0 } ?: System.currentTimeMillis()
        return DateFound(LocalDateTime.ofInstant(Instant.ofEpochMilli(t), ZoneId.systemDefault()).withNano(0),
            "file date on the phone, not stored in the scan", embedded = false)
    }

    /** Convert one file. The TIFF is deleted only after the JPEG is written and read back. */
    fun convertOne(ctx: Context, roll: Roll, f: ScanFile, index: Int, meta: RollMeta, credits: Credits, quality: Int,
                   keepTiff: Boolean, progress: (Float) -> Unit) {
        val target = f.newName + ".jpg"
        val existing = roll.existing[target.lowercase()]
        if (existing != null && !roll.overwrite) error("$target is already there")
        val outDoc = existing ?: roll.folder.createFile("image/jpeg", f.newName) ?: error("couldn't create $target")
        val date = if (f.date.embedded) f.date.date else roll.overrideDate()
            ?: f.date.date.takeIf { roll.useFileDates } ?: error("no scan date chosen")
        try {
            UriSource.open(ctx, f.doc.uri).use { src ->
                val t = TiffReader(src)
                val frame = Scan.frame(t, f.name, date, index + 1, if (keepTiff) roll.name else roll.newFolderName)
                val os = ctx.contentResolver.openOutputStream(outDoc.uri, if (existing != null) "wt" else "w")
                    ?: error("couldn't write $target")
                BufferedOutputStream(os, 1 shl 16).use { Scan.convert(t, frame, meta, credits, it, quality, progress) }
            }
            verify(ctx, outDoc.uri, f)
        } catch (e: Throwable) {
            if (existing == null) runCatching { outDoc.delete() }
            throw e
        }
        if (!keepTiff && !f.doc.delete()) throw IllegalStateException("JPEG saved, but the TIFF couldn't be deleted")
    }

    private fun verify(ctx: Context, uri: Uri, f: ScanFile) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth != f.width || o.outHeight != f.height)
            error("the JPEG didn't read back correctly (${o.outWidth}x${o.outHeight})")
        // Decode the whole stream at low resolution to make sure it isn't truncated.
        val small = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, small) }
            ?: error("the JPEG didn't decode")
        bmp.recycle()
    }

    /** A small preview decoded straight from the TIFF, turned the way it should display. */
    fun thumbnail(ctx: Context, f: ScanFile): ImageBitmap? = runCatching {
        UriSource.open(ctx, f.doc.uri).use { src ->
            val t = TiffReader(src)
            val th = Thumbs.sample(t, 320)
            var bmp = Bitmap.createBitmap(th.argb, th.width, th.height, Bitmap.Config.ARGB_8888)
            val m = Matrix()
            when (t.orientation) {
                2 -> m.setScale(-1f, 1f); 3 -> m.setRotate(180f); 4 -> m.setScale(1f, -1f)
                5 -> { m.setRotate(90f); m.postScale(-1f, 1f) }; 6 -> m.setRotate(90f)
                7 -> { m.setRotate(270f); m.postScale(-1f, 1f) }; 8 -> m.setRotate(270f)
            }
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.asImageBitmap()
        }
    }.getOrNull()

    fun deleteSidecars(roll: Roll): Int = roll.sidecars.count { runCatching { it.delete() }.getOrDefault(false) }

    /** Returns the error, or null when renamed. */
    fun renameFolder(ctx: Context, roll: Roll): String? {
        val newName = cleanName(roll.newFolderName)
        if (newName.isEmpty() || newName == roll.name) return null
        return try {
            val r = DocumentsContract.renameDocument(ctx.contentResolver, roll.folder.uri, newName)
            roll.outputUri = r
            if (r == null) "couldn't rename the folder" else null
        } catch (e: Exception) {
            if (roll.isRoot) "the folder you picked can't be renamed from inside the app; rename it to \"$newName\" in My Files"
            else e.message ?: "couldn't rename the folder"
        }
    }
}
