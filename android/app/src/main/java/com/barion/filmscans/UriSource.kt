package com.barion.filmscans

import android.content.Context
import android.net.Uri
import com.barion.filmscans.core.ByteSource
import java.io.Closeable
import java.io.File
import java.io.FileInputStream
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.channels.FileChannel

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
