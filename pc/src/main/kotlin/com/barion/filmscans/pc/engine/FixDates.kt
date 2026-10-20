package com.barion.filmscans.pc

import com.barion.filmscans.core.Dates
import java.io.File
import java.time.LocalDateTime

/**
 * Gives every photo in a folder (subfolders included) its scan date as its created and modified
 * date, from the date inside it, or failing that its date in the zip it came from. Photos with
 * neither are left alone and listed. Useful for photos copied from the phone, which can't set them.
 */
object FixDates {
    class Item(val file: File, val date: LocalDateTime?, val source: String?)

    /** Every photo and the date it would get. Changes nothing. */
    fun look(folder: File, onProgress: (String) -> Unit = {}): List<Item> {
        val out = mutableListOf<Item>()
        fun walk(dir: File) {
            for (f in listed(dir)) {
                if (f.isDirectory) { walk(f); continue }
                if (!isImage(f.name) || isAppleDouble(f.name)) continue
                onProgress(f.name)
                val found = runCatching { FileSource(f).use { Dates.fromImage(it) } }.getOrNull().orEmpty().firstOrNull()
                out += when {
                    found != null -> Item(f, found.date, plainSource(found.source))
                    else -> ZipDates.get(f)?.let { Item(f, it, "date in the zip") } ?: Item(f, null, null)
                }
            }
        }
        walk(folder)
        return out
    }

    /** Sets the dates found by [look]; returns how many were set and how many couldn't be. */
    fun apply(items: List<Item>, cancelled: () -> Boolean = { false }): Pair<Int, Int> {
        var set = 0; var failed = 0
        for (i in items) {
            if (cancelled()) break
            val d = i.date ?: continue
            if (FileTimes.set(i.file, d)) set++ else failed++
        }
        return set to failed
    }
}
