package com.barion.filmscans.core

/**
 * Splits files into zips of at most a set size. Files are stored, not compressed (a JPEG doesn't
 * get smaller in a zip), so each zip's size is known exactly before it's written. A folder stays
 * in one zip when it fits in one; a folder too big for any one zip is split between zips.
 */
object ZipParts {
    class Item(val path: String, val size: Long, val folder: String)

    /** End of the zip's index, plus room to spare. */
    const val END = 22L + 1024

    /** What [item] adds to a zip: its data, its local header and its entry in the index. */
    fun cost(item: Item): Long {
        val name = item.path.toByteArray(Charsets.UTF_8).size
        // 30 + 46 header bytes, the name twice, and room for an extra field or data descriptor.
        return item.size + 30 + 46 + 2L * name + 64
    }

    /** [items] in order, in parts of at most [cap] bytes each (null: one part). */
    fun pack(items: List<Item>, cap: Long?): List<List<Item>> {
        if (items.isEmpty()) return emptyList()
        if (cap == null) return listOf(items)
        val budget = cap - END
        val parts = mutableListOf<MutableList<Item>>()
        var part = mutableListOf<Item>()
        var used = 0L
        fun next() { if (part.isNotEmpty()) { parts += part; part = mutableListOf(); used = 0L } }
        var i = 0
        while (i < items.size) {
            var j = i
            while (j < items.size && items[j].folder == items[i].folder) j++
            val folder = items.subList(i, j)
            val whole = folder.sumOf { cost(it) }
            // A folder that fits in a zip of its own isn't split just to fill up the one before.
            if (used + whole > budget && whole <= budget) next()
            for (it in folder) {
                val c = cost(it)
                if (used + c > budget) next()
                part += it; used += c
            }
            i = j
        }
        next()
        return parts
    }

    /** "Album.zip", or "Album part 2 of 3.zip". */
    fun name(base: String, index: Int, count: Int) =
        if (count <= 1) "$base.zip" else "$base part ${index + 1} of $count.zip"
}
