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
    /** Junk that zips made on a Mac or Windows carry along. */
    private val SKIP = Regex("""(^|/)(__MACOSX/|\._)|(^|/)(\.DS_Store|Thumbs\.db|desktop\.ini)$""", RegexOption.IGNORE_CASE)
    private const val MAX_DEPTH = 4

    /** Lets an inner zip be read without closing the outer one. */
    private class NoClose(input: InputStream) : FilterInputStream(input) { override fun close() {} }

    /**
     * Calls [visit] with (path, data, time) for each file. [data] must be read before returning.
     * [time] is the file's date in the zip, in epoch millis (or -1).
     */
    fun walk(input: InputStream, visit: (String, InputStream, Long) -> Unit) = walk(input, visit, "", 0)

    private fun walk(input: InputStream, visit: (String, InputStream, Long) -> Unit, prefix: String, depth: Int) {
        val zis = ZipInputStream(input)
        while (true) {
            val e = zis.nextEntry ?: break
            val path = e.name.replace('\\', '/').trimStart('/')
            if (e.isDirectory || path.isEmpty() || SKIP.containsMatchIn(path)) continue
            val parts = path.split('/')
            if (parts.any { it == ".." || it == "." }) continue
            val name = parts.last()
            if (name.lowercase().endsWith(".zip") && depth < MAX_DEPTH) {
                walk(NoClose(zis), visit, prefix + path.dropLast(4) + "/", depth + 1)
            } else {
                visit(prefix + path, zis, e.time)
            }
        }
    }
}
