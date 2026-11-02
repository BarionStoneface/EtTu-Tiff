package com.barion.filmscans.core

import java.io.File
import java.io.InputStream

/**
 * Looking at one file inside a zip without unzipping the rest: a thumbnail of a scan, or the file
 * itself pulled out on its own to open in another app.
 */
object ZipPeek {
    /** Compressed files up to this size are read into memory for a thumbnail; bigger ones get none. */
    const val MAX_IN_MEMORY = 96L * 1024 * 1024

    /**
     * Random access to [f] for reading a TIFF: in place when it's stored (no copying, however big),
     * otherwise read into memory when it's small enough. Null when it can't be had cheaply.
     */
    fun source(f: ArchiveFile, password: String?): ByteSource? {
        if (!f.item.encrypted) ZipIndex.storedSource(f.container, f.item)?.let { return it }
        if (f.size > MAX_IN_MEMORY) return null
        return BytesSource(ZipIndex.open(f.container, f.item, password).use { it.readBytes() })
    }

    /** A small picture of a scan in the zip, or null when it isn't a TIFF this app can read. */
    fun tiffThumb(f: ArchiveFile, password: String?, maxSide: Int): Thumb? = runCatching {
        source(f, password)?.let { Thumbs.sample(TiffReader(it), maxSide) }
    }.getOrNull()

    /** [f]'s contents as a stream, for decoding a JPEG. */
    fun open(f: ArchiveFile, password: String?): InputStream = ZipIndex.open(f.container, f.item, password)

    /**
     * Writes [f] alone into [dir] under its own name and returns it. Written as name.part first, so a
     * half-written file is never opened.
     */
    fun extract(f: ArchiveFile, password: String?, dir: File, cancelled: () -> Boolean = { false }): File {
        dir.mkdirs()
        val name = Names.clean(f.name).ifEmpty { "file" }
        val target = File(dir, name)
        if (target.isFile && target.length() == f.size) return target
        val part = File(dir, "$name.part")
        try {
            open(f, password).use { input ->
                part.outputStream().use { out ->
                    val buf = ByteArray(1 shl 16)
                    while (true) {
                        if (cancelled()) throw java.io.InterruptedIOException("stopped")
                        val n = input.read(buf)
                        if (n < 0) break
                        out.write(buf, 0, n)
                    }
                }
            }
            target.delete()
            if (!part.renameTo(target)) error("couldn't save $name")
        } finally {
            part.delete()
        }
        return target
    }
}
