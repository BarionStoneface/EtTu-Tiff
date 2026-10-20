package com.barion.filmscans.pc

import com.barion.filmscans.core.ByteSource
import com.barion.filmscans.core.Names
import java.io.Closeable
import java.io.File
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.time.format.DateTimeFormatter

/* File names, sizes and reading, the same everywhere in the app (as on the phone). */

val TIFF_EXT = setOf("tif", "tiff")
val JPEG_EXT = setOf("jpg", "jpeg")

/** Info/sidecar files that can go once a roll converts cleanly. */
val SIDECAR_EXT = setOf("thm", "xmp", "info", "nfo", "xml", "txt", "db", "ini", "ds_store", "md5", "sfv", "log", "dat")
val THUMB_DIR = Regex("""(?i)^[._]*(thumbs?|thumbnails?|thm|previews?)$""")

fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
fun stem(name: String) = name.substringBeforeLast('.')
fun cleanName(s: String) = Names.clean(s)
fun isJpeg(name: String) = ext(name) in JPEG_EXT
fun isImage(name: String) = ext(name).let { it in JPEG_EXT || it in TIFF_EXT }

/** The ._name files a Mac leaves next to everything. */
fun isAppleDouble(name: String) = name.startsWith("._")

/** "1.4 GB", "350 MB". */
fun sizeText(b: Long): String = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%d MB".format((b + 999_999) / 1_000_000)

/** Dates as shown on screen. */
val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** "XMP xmp:MetadataDate" → "XMP MetadataDate". */
fun plainSource(s: String) = s.replace(Regex("""XMP (xmp|exif|photoshop):"""), "XMP ")

/** Positional reads from a file on disk. */
class FileSource(file: File) : ByteSource, Closeable {
    private val raf = RandomAccessFile(file, "r")
    private val ch = raf.channel
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
    override fun close() = raf.close()
}

/** A folder's contents, sorted by name. */
fun listed(dir: File): List<File> = (dir.listFiles() ?: emptyArray()).sortedBy { it.name.lowercase() }
