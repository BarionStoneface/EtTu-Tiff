package com.barion.filmscans

import com.barion.filmscans.core.Names
import java.time.format.DateTimeFormatter

/** File names and sizes, the same everywhere in the app. */

val TIFF_EXT = setOf("tif", "tiff")
val JPEG_EXT = setOf("jpg", "jpeg")

/** Info/sidecar files that can go once a roll converts cleanly. */
val SIDECAR_EXT = setOf("thm", "xmp", "info", "nfo", "xml", "txt", "db", "ini", "ds_store", "md5", "sfv", "log", "dat")
val THUMB_DIR = Regex("""(?i)^[._]*(thumbs?|thumbnails?|thm|previews?)$""")

fun ext(name: String) = name.substringAfterLast('.', "").lowercase()
fun stem(name: String) = name.substringBeforeLast('.')
fun cleanName(s: String) = Names.clean(s)
fun isJpeg(name: String) = ext(name) in JPEG_EXT
fun isImage(name: String) = ext(name).let { it in JPEG_EXT || it in TIFF_EXT }

/** "1.4 GB", "350 MB". */
fun sizeText(b: Long): String = if (b >= 1_000_000_000) "%.1f GB".format(b / 1e9) else "%d MB".format((b + 999_999) / 1_000_000)

/** Dates as shown on screen. */
internal val DAY: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd")
internal val MINUTE: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")

/** "XMP xmp:MetadataDate" → "XMP MetadataDate". */
internal fun plainSource(s: String) = s.replace(Regex("""XMP (xmp|exif|photoshop):"""), "XMP ")
