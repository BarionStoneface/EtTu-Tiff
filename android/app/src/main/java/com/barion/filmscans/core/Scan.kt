package com.barion.filmscans.core

import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** A dated guess at when a file was scanned, with where it came from. */
data class DateFound(val date: LocalDateTime, val source: String, val embedded: Boolean = true)

object Dates {
    private val FORMATS = listOf(
        19 to "yyyy:MM:dd HH:mm:ss", 19 to "yyyy-MM-dd'T'HH:mm:ss", 19 to "yyyy-MM-dd HH:mm:ss",
        16 to "yyyy-MM-dd'T'HH:mm", 16 to "yyyy-MM-dd HH:mm",
    ).map { it.first to DateTimeFormatter.ofPattern(it.second) }

    fun parse(text: String?): LocalDateTime? {
        val t = text?.trim()?.trim('\u0000') ?: return null
        for ((n, f) in FORMATS) {
            if (t.length < n) continue
            runCatching { return LocalDateTime.parse(t.substring(0, n), f) }
        }
        // Date only
        Regex("""^(\d{4})[-:](\d{2})[-:](\d{2})""").find(t)?.let { m ->
            return runCatching {
                LocalDateTime.of(m.groupValues[1].toInt(), m.groupValues[2].toInt(), m.groupValues[3].toInt(), 0, 0)
            }.getOrNull()
        }
        return null
    }

    /** Creation-type dates in an XMP packet, best first. */
    fun fromXmp(xmp: String): List<DateFound> {
        val out = mutableListOf<DateFound>()
        for (key in listOf("exif:DateTimeDigitized", "xmp:CreateDate", "photoshop:DateCreated", "exif:DateTimeOriginal")) {
            val v = Regex("""$key\s*=\s*"([^"]+)"""").find(xmp)?.groupValues?.get(1)
                ?: Regex("""<$key>([^<]+)</$key>""").find(xmp)?.groupValues?.get(1)
            parse(v)?.let { out += DateFound(it, "XMP $key") }
        }
        return out
    }

    /** Dates stored inside the TIFF, best first. */
    fun fromTiff(t: TiffReader): List<DateFound> {
        val out = mutableListOf<DateFound>()
        parse(t.exifText(0x9004))?.let { out += DateFound(it, "EXIF date digitized") }
        t.bytes(700)?.let { out += fromXmp(String(it, Charsets.UTF_8)) }
        parse(t.exifText(0x9003))?.let { out += DateFound(it, "EXIF date original") }
        parse(t.text(306))?.let { out += DateFound(it, "TIFF date") }
        return out
    }
}

object Scan {
    /** Pull out everything we keep from the TIFF (pixels aside). */
    fun frame(t: TiffReader, name: String, date: LocalDateTime, frameNumber: Int, rollName: String) = Frame(
        originalName = name,
        scanDate = date,
        width = t.width,
        height = t.height,
        orientation = t.orientation,
        dpi = t.dpi(),
        scannerMake = t.text(271),
        scannerModel = t.text(272),
        scannerSoftware = t.text(305),
        icc = t.bytes(34675),
        frameNumber = frameNumber,
        rollName = rollName,
    )

    /** TIFF pixels -> JPEG with only the metadata we choose. */
    fun convert(t: TiffReader, f: Frame, r: RollMeta, c: Credits, out: OutputStream, quality: Int = 100,
                progress: (Float) -> Unit = {}) {
        t.checkSupported()
        val enc = JpegEncoder(out, t.width, t.height, t.outChannels, quality)
        enc.writeHeader(Metadata.segments(f, r, c))
        t.readRows { y, rows, px ->
            enc.writeRows(px, rows)
            progress((y + rows).toFloat() / t.height)
        }
        enc.finish()
    }
}
