package com.barion.filmscans.core

/** A folder the download unpacks into. [path] is its place in the download ("Tiffs/Roll 1"). */
class PlanFolder(
    val path: String,
    val name: String,
    val depth: Int,
    /** Files directly in this folder. */
    val files: Int,
    /** Bytes in this folder and everything under it. */
    val bytes: Long,
    /** A compressed zip unpacks here, so some of what's in it is only known while unzipping. */
    val sealed: Boolean,
)

/**
 * Turns a listing plus your edits into exactly what will be written, before anything is.
 * The same function feeds the plan screen and the unzip itself, so what you see is what happens.
 */
object UnzipPlan {
    /** Every folder in the download, each parent before its children. */
    fun folders(listing: Listing): List<PlanFolder> {
        val direct = HashMap<String, Int>()
        val bytes = HashMap<String, Long>()
        val sealedAt = HashSet<String>()
        val all = sortedSetOf(Comparator<String> { a, b -> naturalCompare(a, b) })
        fun addAncestors(path: String, size: Long) {
            var p = path
            while (p.isNotEmpty()) {
                all += p; bytes[p] = (bytes[p] ?: 0) + size
                p = p.substringBeforeLast('/', "")
            }
        }
        for (f in listing.files) {
            if (f.folder.isNotEmpty()) direct[f.folder] = (direct[f.folder] ?: 0) + 1
            addAncestors(f.folder, f.size)
        }
        for (s in listing.sealed) { sealedAt += s.folder; addAncestors(s.folder, s.item.size) }
        return all.map { p ->
            PlanFolder(p, p.substringAfterLast('/'), p.count { it == '/' }, direct[p] ?: 0, bytes[p] ?: 0, p in sealedAt)
        }
    }

    class Target(val file: ArchiveFile, val dir: List<String>, val name: String) {
        val path get() = (dir + name).joinToString("/")
    }
    class SealedTarget(val zip: SealedZip, val dir: List<String>)

    class Result(val targets: List<Target>, val sealed: List<SealedTarget>, val conflicts: List<String>) {
        val bytes get() = targets.sumOf { it.file.size } + sealed.sumOf { it.zip.item.size }
        val folders get() = (targets.map { it.dir } + sealed.map { it.dir }).distinct()
    }

    /**
     * [top] is the folder made in the destination ("" unpacks straight into it). [names] maps a
     * folder's path to its new name; an empty name drops that level and moves its contents up.
     * Folders in [skip] are left out, with everything under them.
     */
    fun build(listing: Listing, top: String, names: Map<String, String>, skip: Set<String>): Result {
        fun skipped(folder: String): Boolean {
            var p = folder
            while (p.isNotEmpty()) { if (p in skip) return true; p = p.substringBeforeLast('/', "") }
            return false
        }
        fun outDir(folder: String): List<String> {
            val segs = mutableListOf<String>()
            Names.clean(top).takeIf { it.isNotEmpty() }?.let { segs += it }
            if (folder.isEmpty()) return segs
            val parts = folder.split('/')
            for (i in parts.indices) {
                val path = parts.subList(0, i + 1).joinToString("/")
                val n = Names.clean(names[path] ?: parts[i])
                if (n.isNotEmpty()) segs += n
            }
            return segs
        }
        val targets = listing.files.filter { !skipped(it.folder) }.map { f ->
            Target(f, outDir(f.folder), Names.clean(f.name).ifEmpty { "file" })
        }
        val sealed = listing.sealed.filter { !skipped(it.folder) }.map { SealedTarget(it, outDir(it.folder)) }

        // Two files landing on the same name, or a file where a folder has to go, stops the plan.
        val conflicts = mutableListOf<String>()
        val taken = HashMap<String, String>()
        for (t in targets) {
            val key = t.path.lowercase()
            val other = taken.put(key, t.file.path)
            if (other != null) conflicts += "${t.path}: both ${other} and ${t.file.path} would be saved here"
        }
        val dirs = HashSet<String>()
        for (d in targets.map { it.dir } + sealed.map { it.dir }) for (i in 1..d.size) dirs += d.subList(0, i).joinToString("/").lowercase()
        for (t in targets) if (t.path.lowercase() in dirs) conflicts += "${t.path}: a file and a folder would have the same name"
        return Result(targets, sealed, conflicts)
    }

    /** "Roll 2" before "Roll 10". */
    fun naturalCompare(a: String, b: String): Int {
        val ra = Regex("""\d+|\D+""").findAll(a.lowercase()).map { it.value }.toList()
        val rb = Regex("""\d+|\D+""").findAll(b.lowercase()).map { it.value }.toList()
        for (i in 0 until minOf(ra.size, rb.size)) {
            val x = ra[i]; val y = rb[i]
            val c = if (x[0].isDigit() && y[0].isDigit()) x.trimStart('0').length.compareTo(y.trimStart('0').length).takeIf { it != 0 }
                ?: x.trimStart('0').compareTo(y.trimStart('0')) else x.compareTo(y)
            if (c != 0) return c
        }
        return ra.size.compareTo(rb.size).takeIf { it != 0 } ?: a.compareTo(b)
    }
}
