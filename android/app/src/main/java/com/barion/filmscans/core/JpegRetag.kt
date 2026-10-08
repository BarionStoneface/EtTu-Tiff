package com.barion.filmscans.core

import java.io.OutputStream

/** What's in a JPEG's header that tagging needs: its size, how it's turned, and the scanner it came from. */
class JpegInfo(
    val width: Int,
    val height: Int,
    val orientation: Int,
    val dpi: Double?,
    val scannerMake: String?,
    val scannerModel: String?,
    val scannerSoftware: String?,
    /** The XMP packet, if any (used to spot JPEGs this app already tagged). */
    val xmp: String?,
) {
    /** Written by this app before: its film fields are in the XMP. */
    val taggedByThisApp get() = xmp?.contains("AnalogExif:") == true && xmp.contains("xmpMM:PreservedFileName")
}

/**
 * Re-tags a lab's JPEG without re-saving it: the image data is copied byte for byte, so nothing
 * about the picture changes. Only the metadata is replaced, by the same rules as a converted
 * scan: EXIF, XMP and IPTC become the roll's details, and location and every other tag go.
 * The colour profile, JFIF and Adobe colour blocks are kept, since the image needs them.
 */
object JpegRetag {
    private const val SOS = 0xDA

    fun info(src: ByteSource): JpegInfo {
        var w = 0; var h = 0
        var exif: TiffReader? = null
        var xmp: String? = null
        var jfifDpi: Double? = null
        JpegHeader.segments(src) { marker, p ->
            when {
                marker in 0xC0..0xCF && marker != 0xC4 && marker != 0xC8 && marker != 0xCC && p.size >= 5 -> {
                    val b = p.bytes()
                    h = (b[1].u() shl 8) or b[2].u(); w = (b[3].u() shl 8) or b[4].u()
                }
                marker == 0xE1 && p.startsWith("Exif\u0000\u0000") && exif == null ->
                    exif = runCatching { TiffReader(SubSource(src, p.start + 6, p.size - 6L), image = false) }.getOrNull()
                marker == 0xE1 && p.startsWith(JpegHeader.XMP_ID) && xmp == null ->
                    xmp = String(p.bytes(JpegHeader.XMP_ID.length), Charsets.UTF_8)
                marker == 0xE0 && p.startsWith("JFIF\u0000") && p.size >= 12 -> {
                    val b = p.bytes()
                    val x = (b[8].u() shl 8) or b[9].u()
                    jfifDpi = when (b[7].u()) { 1 -> x.toDouble(); 2 -> x * 2.54; else -> null }?.takeIf { it > 1 }
                }
            }
        }
        if (w == 0 || h == 0) throw TiffException("not a JPEG this app can read")
        val e = exif
        // Tagged by this app before: EXIF Make/Model now hold the camera, and the scanner is in the XMP.
        fun fromXmp(key: String) = xmp?.let { x ->
            Regex("""AnalogExif:$key="([^"]*)"""").find(x)?.groupValues?.get(1)
                ?.replace("&quot;", "\"")?.replace("&lt;", "<")?.replace("&gt;", ">")?.replace("&amp;", "&")?.ifBlank { null }
        }
        val ownScanner = fromXmp("Scanner")
        return JpegInfo(
            width = w, height = h,
            orientation = e?.tags?.get(274)?.int()?.takeIf { it in 1..8 } ?: 1,
            dpi = e?.dpi() ?: jfifDpi,
            scannerMake = if (ownScanner != null) fromXmp("ScannerMaker") else e?.text(271),
            scannerModel = ownScanner ?: e?.text(272),
            scannerSoftware = if (ownScanner != null) fromXmp("ScannerSoftware") else e?.text(305),
            xmp = xmp,
        )
    }

    /** The facts a tagged JPEG keeps, in the same shape as a converted scan's. */
    fun frame(info: JpegInfo, name: String, date: java.time.LocalDateTime, frameNumber: Int, rollName: String) = Frame(
        originalName = name, scanDate = date, width = info.width, height = info.height,
        orientation = info.orientation, dpi = info.dpi,
        scannerMake = info.scannerMake, scannerModel = info.scannerModel, scannerSoftware = info.scannerSoftware,
        icc = null, // the original colour profile segments are copied as they are
        frameNumber = frameNumber, rollName = rollName,
    )

    /**
     * Writes [src] to [out] with its metadata replaced by [segments] (complete APPn/COM segments,
     * e.g. from [Metadata.segments] with no ICC). Everything from the start of the image data to the
     * end of the file is copied unchanged.
     */
    fun rewrite(src: ByteSource, segments: List<ByteArray>, out: OutputStream) {
        val two = ByteArray(2)
        src.read(0, two)
        if (two[0] != 0xFF.toByte() || two[1] != 0xD8.toByte()) throw TiffException("not a JPEG")
        val jfif = mutableListOf<ByteArray>()
        val keepApp = mutableListOf<ByteArray>()   // ICC profile, Adobe colour
        val tables = mutableListOf<ByteArray>()    // DQT, DHT, SOF, DRI...
        var p = 2L
        var sos = -1L
        while (p + 4 <= src.size) {
            src.read(p, two)
            if (two[0] != 0xFF.toByte()) throw TiffException("damaged JPEG header")
            val marker = two[1].u()
            if (marker == 0xFF) { p++; continue }
            if (marker == SOS) { sos = p; break }
            if (marker == 0xD9) throw TiffException("JPEG has no image data")
            if (marker in 0xD0..0xD7 || marker == 0x01) { p += 2; continue }
            src.read(p + 2, two)
            val len = (two[0].u() shl 8) or two[1].u()
            if (len < 2 || p + 2 + len > src.size) throw TiffException("damaged JPEG header")
            val seg = ByteArray(2 + len).also { src.read(p, it) }
            fun starts(id: String) = len - 2 >= id.length &&
                seg.copyOfRange(4, 4 + id.length).contentEquals(id.toByteArray(Charsets.ISO_8859_1))
            when {
                marker == 0xE0 && starts("JFIF\u0000") -> if (jfif.isEmpty()) jfif += seg
                marker == 0xE2 && starts("ICC_PROFILE\u0000") -> keepApp += seg
                marker == 0xEE && starts("Adobe") -> keepApp += seg
                marker in 0xE0..0xEF || marker == 0xFE -> {} // old metadata (EXIF, XMP, IPTC, MPF, comments...): replaced
                else -> tables += seg
            }
            p += 2 + len
        }
        if (sos < 0) throw TiffException("JPEG has no image data")
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        jfif.forEach { out.write(it) }
        segments.forEach { out.write(it) }
        keepApp.forEach { out.write(it) }
        tables.forEach { out.write(it) }
        // The image itself, untouched.
        val buf = ByteArray(1 shl 16)
        var q = sos
        while (q < src.size) {
            val n = minOf(buf.size.toLong(), src.size - q).toInt()
            src.read(q, buf, 0, n)
            out.write(buf, 0, n)
            q += n
        }
    }
}
