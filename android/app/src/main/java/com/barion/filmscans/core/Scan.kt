package com.barion.filmscans.core

import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * A dated guess at when a file was scanned, with where it came from. [embedded] is false
 * only for a file's own modified date on the phone, which may just be when it was downloaded.
 */
data class DateFound(val date: LocalDateTime, val source: String, val embedded: Boolean = true)

/**
 * Where a scan's date comes from, best first. The download or unzip date is never on this list:
 * a file's modified date on the phone is only ever offered, never used without asking.
 *
 *  1. EXIF DateTimeOriginal, then DateTimeDigitized (CreateDate)
 *  2. The same dates written as XMP, plus XMP CreateDate and Photoshop DateCreated
 *  3. EXIF/TIFF ModifyDate: changes when a program re-saves the file, so it ranks below the creation dates
 *  4. XMP MetadataDate: the only date some labs write; it equals the scan time on an untouched scan
 *  5. (outside this object) a .thm or .xmp file beside the scan, then the date stored in the zip
 */
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

    /**
     * A date that could really be a scan date. Blank fields are often filled with zero dates:
     * 1970-01-01 (Unix), 1980-01-01 00:00 (zip/DOS), 1904 (old Macs). Those, and future dates, aren't.
     */
    fun usable(d: LocalDateTime?): LocalDateTime? = d?.takeIf {
        it.year in 1900..(LocalDateTime.now().year + 1) &&
            it.toLocalDate() != java.time.LocalDate.of(1970, 1, 1) &&
            it != LocalDateTime.of(1980, 1, 1, 0, 0)
    }

    private val XMP_KEYS = listOf(
        "exif:DateTimeOriginal" to 20, "exif:DateTimeDigitized" to 21, "xmp:CreateDate" to 22,
        "photoshop:DateCreated" to 23, "xmp:MetadataDate" to 40,
    )

    private class Ranked(val rank: Int, val found: DateFound)

    private fun xmpRanked(xmp: String, prefix: String): List<Ranked> = XMP_KEYS.mapNotNull { (key, rank) ->
        val v = Regex("""$key\s*=\s*["']([^"']+)["']""").find(xmp)?.groupValues?.get(1)
            ?: Regex("""<$key>([^<]+)</$key>""").find(xmp)?.groupValues?.get(1)
        usable(parse(v))?.let { Ranked(rank, DateFound(it, "$prefix$key")) }
    }

    /** Dates in an XMP packet, best first. */
    fun fromXmp(xmp: String): List<DateFound> = xmpRanked(xmp, "XMP ").sortedBy { it.rank }.map { it.found }

    /** Dates stored inside a TIFF (or a JPEG's EXIF block), best first. */
    fun fromTiff(t: TiffReader): List<DateFound> {
        val out = mutableListOf<Ranked>()
        usable(parse(t.exifText(0x9003)))?.let { out += Ranked(10, DateFound(it, "EXIF date taken")) }
        usable(parse(t.exifText(0x9004)))?.let { out += Ranked(11, DateFound(it, "EXIF date digitized")) }
        usable(parse(t.text(306)))?.let { out += Ranked(30, DateFound(it, "EXIF modify date")) }
        t.bytes(700)?.let { out += xmpRanked(String(it, Charsets.UTF_8), "XMP ") }
        return out.sortedBy { it.rank }.map { it.found }
    }

    /** Dates in a JPEG's EXIF and XMP blocks, best first. Reads only the header segments. */
    fun fromJpeg(src: ByteSource): List<DateFound> {
        val out = mutableListOf<Ranked>()
        var exif: List<DateFound> = emptyList()
        JpegHeader.segments(src) { marker, payload ->
            if (marker != 0xE1) return@segments
            when {
                payload.startsWith("Exif\u0000\u0000") -> exif = runCatching {
                    fromTiff(TiffReader(SubSource(src, payload.start + 6, payload.size - 6L), image = false))
                }.getOrDefault(emptyList())
                payload.startsWith(JpegHeader.XMP_ID) -> {
                    val n = JpegHeader.XMP_ID.length
                    out += xmpRanked(String(payload.bytes(n), Charsets.UTF_8), "XMP ")
                }
            }
        }
        // EXIF dates keep their own ranks; XMP fills in what EXIF doesn't have.
        val ranks = mapOf("EXIF date taken" to 10, "EXIF date digitized" to 11, "EXIF modify date" to 30)
        exif.forEach { d -> out += Ranked(ranks[d.source] ?: xmpRank(d.source), d) }
        return out.sortedBy { it.rank }.map { it.found }
    }

    private fun xmpRank(source: String) = XMP_KEYS.firstOrNull { source.endsWith(it.first) }?.second ?: 50

    /** Dates in a TIFF or JPEG, told apart by their first bytes. Empty if neither, or unreadable. */
    fun fromImage(src: ByteSource): List<DateFound> = runCatching {
        if (src.size < 4) return emptyList()
        val h = ByteArray(2).also { src.read(0, it) }
        when {
            h[0] == 0xFF.toByte() && h[1] == 0xD8.toByte() -> fromJpeg(src)
            (h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte()) || (h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte()) ->
                fromTiff(TiffReader(src, image = false))
            else -> emptyList()
        }
    }.getOrDefault(emptyList())
}

/** Walks a JPEG's header segments (APPn, COM) up to the image data. */
object JpegHeader {
    const val XMP_ID = "http://ns.adobe.com/xap/1.0/\u0000"

    class Payload(private val src: ByteSource, val start: Long, val size: Int) {
        fun bytes(from: Int = 0): ByteArray = ByteArray(size - from).also { src.read(start + from, it) }
        fun startsWith(s: String): Boolean {
            val id = s.toByteArray(Charsets.ISO_8859_1)
            if (size < id.size) return false
            val b = ByteArray(id.size).also { src.read(start, it) }
            return b.contentEquals(id)
        }
    }

    fun segments(src: ByteSource, visit: (Int, Payload) -> Unit) {
        val two = ByteArray(2)
        src.read(0, two)
        if (two[0] != 0xFF.toByte() || two[1] != 0xD8.toByte()) return
        var p = 2L
        while (p + 4 <= src.size) {
            src.read(p, two)
            if (two[0] != 0xFF.toByte()) return
            val marker = two[1].u()
            if (marker == 0xFF) { p++; continue } // fill byte
            if (marker == 0xDA || marker == 0xD9) return // image data starts / end
            if (marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            src.read(p + 2, two)
            val len = (two[0].u() shl 8) or two[1].u()
            if (len < 2 || p + 2 + len > src.size) return
            visit(marker, Payload(src, p + 4, len - 2))
            p += 2 + len
        }
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
