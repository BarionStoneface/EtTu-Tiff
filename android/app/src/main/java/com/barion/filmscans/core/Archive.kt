package com.barion.filmscans.core

/** A file somewhere in a download, with where its bytes are. [path] is where it unpacks to, "/"-separated. */
class ArchiveFile(val path: String, val container: ByteSource, val item: ZipItem) {
    val name get() = path.substringAfterLast('/')
    val folder get() = path.substringBeforeLast('/', "")
    val size get() = item.size
}

/**
 * A zip inside the download that was compressed rather than stored, so it can't be read in
 * place. Its contents are only known while it's being unzipped; they go into [folder].
 */
class SealedZip(val folder: String, val container: ByteSource, val item: ZipItem)

class Listing(val files: List<ArchiveFile>, val sealed: List<SealedZip>, val skipped: List<String>) {
    val totalBytes get() = files.sumOf { it.size } + sealed.sumOf { it.item.size }
}

/**
 * Lists every file in a download, including files in zips inside it, without unpacking anything.
 * An inner zip that was stored (as zip tools do with files that are already compressed) is read in
 * place through a window onto the outer file, so a 13 GB Tiffs.zip lists as fast as a small one.
 * "Order.zip" holding "Tiffs.zip" holding "Roll 1/x.tif" lists as "Tiffs/Roll 1/x.tif".
 */
object Archive {
    /** Junk that zips made on a Mac or Windows carry along. */
    val JUNK = Regex("""(^|/)(__MACOSX/|\._)|(^|/)(\.DS_Store|Thumbs\.db|desktop\.ini)$""", RegexOption.IGNORE_CASE)
    const val MAX_DEPTH = 4

    /**
     * The entry's path cleaned up, or null if it must not be unpacked: folders, junk, and
     * paths that would escape the destination ("../x", "/etc/x", "C:/x").
     */
    fun safePath(name: String): String? {
        val path = name.replace('\\', '/').trimStart('/')
        if (path.isEmpty() || path.endsWith("/") || JUNK.containsMatchIn(path)) return null
        val parts = path.split('/').filter { it.isNotEmpty() }
        if (parts.isEmpty() || parts.any { it == ".." || it == "." } || parts[0].matches(Regex("[A-Za-z]:"))) return null
        return parts.joinToString("/")
    }

    fun isZip(name: String) = name.lowercase().endsWith(".zip")

    fun list(src: ByteSource): Listing {
        val files = mutableListOf<ArchiveFile>()
        val sealed = mutableListOf<SealedZip>()
        val skipped = mutableListOf<String>()
        fun walk(container: ByteSource, items: List<ZipItem>, prefix: String, depth: Int) {
            for (item in items) {
                if (item.isDirectory) continue
                val path = safePath(item.name) ?: continue
                val full = prefix + path
                val why = item.unreadable
                if (why != null) { skipped += "$full: $why"; continue }
                if (!isZip(path) || depth >= MAX_DEPTH) { files += ArchiveFile(full, container, item); continue }
                val inner = runCatching { ZipIndex.storedSource(container, item) }.getOrNull()
                if (inner == null) { sealed += SealedZip(full.dropLast(4), container, item); continue }
                // Not a readable zip after all: unpack it as an ordinary file.
                val innerItems = runCatching { ZipIndex.read(inner) }.getOrNull()
                if (innerItems == null) files += ArchiveFile(full, container, item)
                else walk(inner, innerItems, full.dropLast(4) + "/", depth + 1)
            }
        }
        walk(src, ZipIndex.read(src), "", 0)
        return Listing(files, sealed, skipped)
    }
}
