package com.barion.filmscans

import android.content.Context
import android.net.Uri
import android.webkit.MimeTypeMap
import androidx.documentfile.provider.DocumentFile
import com.barion.filmscans.core.ZipWalk
import java.io.FilterInputStream
import java.io.InputStream
import java.time.Instant
import java.time.LocalDateTime
import java.time.ZoneId

/** Unzips a download (zips inside it included) into a new folder named after it. */
object Unzip {
    class Result {
        var files = 0
        /** Each file's date as stored in the zip: usually close to when the lab scanned it. */
        val dates = HashMap<String, LocalDateTime>()
        val tops = mutableListOf<DocumentFile>()
    }

    /** Counts bytes read from the zip, for the progress bar. */
    private class Counting(input: InputStream, val onRead: (Long) -> Unit) : FilterInputStream(input) {
        var n = 0L
        override fun read(): Int = super.read().also { if (it >= 0) { n++; onRead(n) } }
        override fun read(b: ByteArray, off: Int, len: Int): Int = super.read(b, off, len).also { if (it > 0) { n += it; onRead(n) } }
    }

    fun unzip(ctx: Context, zip: Uri, zipName: String, dest: DocumentFile, result: Result, onBytes: (Long) -> Unit) {
        val top = dest.createDirectory(cleanName(stem(zipName)).ifEmpty { "Unzipped" })
            ?: error("couldn't create a folder for $zipName")
        result.tops += top
        val dirs = HashMap<String, DocumentFile>()
        dirs[""] = top
        fun dirFor(path: String): DocumentFile = dirs.getOrPut(path) {
            val parent = dirFor(path.substringBeforeLast('/', ""))
            val name = cleanName(path.substringAfterLast('/')).ifEmpty { "folder" }
            parent.findFile(name)?.takeIf { it.isDirectory } ?: parent.createDirectory(name)
                ?: error("couldn't create folder $name")
        }
        val input = ctx.contentResolver.openInputStream(zip) ?: error("couldn't open $zipName")
        Counting(input.buffered(1 shl 20), onBytes).use { counted ->
            ZipWalk.walk(counted) { path, data, time ->
                val name = cleanName(path.substringAfterLast('/'))
                if (name.isEmpty()) return@walk
                val parent = dirFor(path.substringBeforeLast('/', ""))
                val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext(name)) ?: "application/octet-stream"
                val out = parent.createFile(mime, name) ?: error("couldn't create $name")
                (ctx.contentResolver.openOutputStream(out.uri) ?: error("couldn't write $name")).use { data.copyTo(it, 1 shl 20) }
                if (time > 0) result.dates[out.uri.toString()] =
                    LocalDateTime.ofInstant(Instant.ofEpochMilli(time), ZoneId.systemDefault()).withNano(0)
                result.files++
            }
        }
    }
}
