package com.barion.filmscans.core

import java.io.FilterInputStream
import java.io.InputStream
import java.util.zip.ZipInputStream

/**
 * Walks every file in a zip, including files inside zips inside it. An inner zip is read
 * straight out of the outer stream and its files are reported under a folder named after
 * it ("Order.zip" holding "Roll 1.zip" gives "Roll 1/scan001.tif"), so nothing is unzipped twice.
 */
object ZipWalk {
    private const val MAX_DEPTH = Archive.MAX_DEPTH
    // Names not marked as UTF-8 are old DOS names; the default (UTF-8) would stop the whole walk on them.
    private val NAMES = runCatching { java.nio.charset.Charset.forName("IBM437") }.getOrDefault(Charsets.ISO_8859_1)

    /** Lets an inner zip be read without closing the outer one. */
    private class NoClose(input: InputStream) : FilterInputStream(input) { override fun close() {} }

    /**
     * Calls [visit] with (path, data, time) for each file. [data] must be read before returning.
     * [time] is the file's date in the zip, in epoch millis (or -1).
     */
    fun walk(input: InputStream, visit: (String, InputStream, Long) -> Unit) = walk(input, visit, "", 0)

    private fun walk(input: InputStream, visit: (String, InputStream, Long) -> Unit, prefix: String, depth: Int) {
        val zis = ZipInputStream(input, NAMES)
        while (true) {
            val e = zis.nextEntry ?: break
            if (e.isDirectory) continue
            val path = Archive.safePath(e.name) ?: continue
            if (Archive.isZip(path) && depth < MAX_DEPTH) {
                walk(NoClose(zis), visit, prefix + path.dropLast(4) + "/", depth + 1)
            } else {
                visit(prefix + path, zis, e.time)
            }
        }
    }
}
