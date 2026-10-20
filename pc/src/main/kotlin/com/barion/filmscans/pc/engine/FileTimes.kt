package com.barion.filmscans.pc

import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributeView
import java.nio.file.attribute.FileTime
import java.time.LocalDateTime
import java.time.ZoneId

/** Where the app keeps its settings file and remembered zip dates. */
object AppData {
    val dir: File by lazy {
        val base = System.getProperty("ettutiff.data")?.let(::File)
            ?: System.getenv("APPDATA")?.let { File(it, "Et Tu Tiff") } ?: File(System.getProperty("user.home"), ".ettutiff")
        base.apply { mkdirs() }
    }
}

/**
 * A file's own dates (what Explorer shows), set to the scan date: both created and modified, so
 * the folder sorts by when each picture was scanned rather than when it was downloaded.
 */
object FileTimes {
    fun millis(d: LocalDateTime) = d.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** Sets created and modified to [date]; true when the modified date reads back. */
    fun set(file: File, date: LocalDateTime): Boolean = runCatching {
        val t = FileTime.fromMillis(millis(date))
        Files.getFileAttributeView(file.toPath(), BasicFileAttributeView::class.java).setTimes(t, null, t)
        Math.abs(file.lastModified() - t.toMillis()) < 2000
    }.getOrDefault(false)

    fun modified(file: File): LocalDateTime =
        LocalDateTime.ofInstant(java.time.Instant.ofEpochMilli(file.lastModified()), ZoneId.systemDefault()).withNano(0)

    /** One line for the log. */
    fun summary(set: Int, refused: Int): String? = when {
        set + refused == 0 -> null
        refused == 0 -> "File dates set to the scan dates."
        else -> "File dates set to the scan dates for $set file(s); $refused couldn't be changed."
    }
}

/**
 * Each unzipped file's date as stored in the zip, by path, so it's still known as the scan date
 * when the folder is opened later (a file's own date is easy to lose: copying or editing resets it).
 */
object ZipDates {
    private const val MAX = 50_000
    private val file get() = File(AppData.dir, "zip-dates.tsv")
    private var map: LinkedHashMap<String, LocalDateTime>? = null

    private fun key(f: File) = f.absolutePath.lowercase()

    @Synchronized fun get(f: File): LocalDateTime? = load()[key(f)]

    @Synchronized fun putAll(dates: Map<File, LocalDateTime>) {
        if (dates.isEmpty()) return
        val m = load()
        for ((f, d) in dates) { val k = key(f); m.remove(k); m[k] = d }
        val it = m.keys.iterator()
        while (m.size > MAX && it.hasNext()) { it.next(); it.remove() }
        val tmp = File(file.path + ".tmp")
        tmp.bufferedWriter().use { w -> m.forEach { (k, v) -> w.write(k); w.write('\t'.code); w.write(v.toString()); w.newLine() } }
        Files.move(tmp.toPath(), file.toPath(), java.nio.file.StandardCopyOption.REPLACE_EXISTING)
    }

    private fun load(): LinkedHashMap<String, LocalDateTime> = map ?: LinkedHashMap<String, LocalDateTime>().also { m ->
        runCatching {
            file.takeIf { it.exists() }?.forEachLine { line ->
                val k = line.substringBeforeLast('\t', "")
                val v = runCatching { LocalDateTime.parse(line.substringAfterLast('\t')) }.getOrNull()
                if (k.isNotEmpty() && v != null) m[k] = v
            }
        }
        map = m
    }
}
