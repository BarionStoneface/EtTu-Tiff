package com.barion.filmscans.core

import java.io.ByteArrayOutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/** Who owns the photos. Set once in Settings; applies to every roll. */
data class Credits(
    val author: String = "",
    val license: License = License.ALL_RIGHTS,
    val contact: String = "",
)

enum class License(val label: String, val short: String, val url: String?) {
    ALL_RIGHTS("All rights reserved (ask me first)", "All rights reserved", null),
    CC_BY_NC_ND("CC BY-NC-ND 4.0 (share with credit, no edits, no money)", "CC BY-NC-ND 4.0",
        "https://creativecommons.org/licenses/by-nc-nd/4.0/"),
    CC_BY_NC("CC BY-NC 4.0 (share and edit with credit, no money)", "CC BY-NC 4.0",
        "https://creativecommons.org/licenses/by-nc/4.0/"),
    CC_BY("CC BY 4.0 (any use with credit)", "CC BY 4.0",
        "https://creativecommons.org/licenses/by/4.0/"),
}

/** What you pick for one roll. Nothing here carries over to the next roll. */
data class RollMeta(
    val camera: String = "",
    val lens: String = "",
    val film: String = "",
    val boxIso: Int? = null,
    val pushStops: Int = 0,
    val tags: Set<String> = emptySet(), // Experimental, Expired, Redscale...
    val notes: String = "",
    val lab: String = "",
) {
    val exposureIndex: Int? get() = boxIso?.let { iso ->
        if (pushStops >= 0) iso shl pushStops else iso shr -pushStops
    }
    val pushLabel: String get() = when {
        pushStops > 0 -> "Push +$pushStops"
        pushStops < 0 -> "Pull $pushStops"
        else -> ""
    }
}

/** Facts about one scan, read from the TIFF. The scanner fields are kept as-is. */
data class Frame(
    val originalName: String,
    val scanDate: LocalDateTime,
    val width: Int,
    val height: Int,
    val orientation: Int = 1,
    val dpi: Double? = null,
    val scannerMake: String? = null,
    val scannerModel: String? = null,
    val scannerSoftware: String? = null,
    val icc: ByteArray? = null,
    val frameNumber: Int = 0,
    val rollName: String = "",
)

object Metadata {
    private val EXIF_FMT = DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss")
    fun exifDate(d: LocalDateTime): String = EXIF_FMT.format(d)
    private val ISO_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss")

    fun copyrightNotice(c: Credits, year: Int): String =
        if (c.author.isBlank()) "" else when (c.license) {
            License.ALL_RIGHTS -> "Copyright $year ${c.author}. All rights reserved."
            else -> "Copyright $year ${c.author}. Licensed under ${c.license.short}."
        }

    fun usageTerms(c: Credits): String {
        if (c.author.isBlank()) return ""
        val base = when (c.license) {
            License.ALL_RIGHTS -> "All rights reserved. Do not use, copy, edit or publish without written permission from ${c.author}."
            else -> "Licensed under ${c.license.short} (${c.license.url}). Credit: ${c.author}."
        }
        return if (c.contact.isBlank()) base else "$base Contact: ${c.contact}"
    }

    fun scannerName(f: Frame): String? {
        val make = f.scannerMake?.trim().orEmpty()
        val model = f.scannerModel?.trim().orEmpty()
        val s = if (make.isNotEmpty() && model.startsWith(make, ignoreCase = true)) model else "$make $model".trim()
        return s.ifBlank { null }
    }

    fun description(f: Frame, r: RollMeta): String {
        val parts = mutableListOf<String>()
        if (r.film.isNotBlank()) {
            var film = r.film
            val ei = r.exposureIndex
            if (r.pushStops != 0 && ei != null) film += " shot at EI $ei (${r.pushLabel.lowercase()})"
            else if (r.pushStops != 0) film += " (${r.pushLabel.lowercase()})"
            parts += film
        } else if (r.pushStops != 0) parts += r.pushLabel
        listOf(r.camera, r.lens).filter { it.isNotBlank() }.takeIf { it.isNotEmpty() }?.let { parts += it.joinToString(", ") }
        if (r.tags.isNotEmpty()) parts += r.tags.joinToString(", ")
        if (r.notes.isNotBlank()) parts += r.notes.trim()
        val scanner = scannerName(f)
        val scan = listOfNotNull(scanner?.let { "Scanned on $it" }, r.lab.ifBlank { null }?.let { "at $it" })
        if (scan.isNotEmpty()) parts += scan.joinToString(" ")
        return parts.joinToString(". ").let { if (it.isEmpty() || it.endsWith(".")) it else "$it." }
    }

    fun keywords(r: RollMeta): List<String> =
        (listOf("film") + listOf(r.film, r.pushLabel, r.camera).filter { it.isNotBlank() } + r.tags).distinct()

    /** Every APPn/COM segment for the JPEG, in file order. */
    fun segments(f: Frame, r: RollMeta, c: Credits): List<ByteArray> {
        val out = mutableListOf(exif(f, r, c), xmp(f, r, c))
        f.icc?.let { out += iccSegments(it) }
        out += iptc(f, r, c)
        val notice = copyrightNotice(c, f.scanDate.year)
        if (notice.isNotEmpty()) out += segment(0xFE, notice.toByteArray(Charsets.UTF_8))
        return out
    }

    // ------------------------------------------------------------------ EXIF

    fun exif(f: Frame, r: RollMeta, c: Credits): ByteArray {
        val date = EXIF_FMT.format(f.scanDate)
        val desc = description(f, r)
        val notice = copyrightNotice(c, f.scanDate.year)
        val (make, model) = if (r.camera.isNotBlank()) {
            r.camera.trim().substringBefore(' ') to r.camera.trim()
        } else (f.scannerMake.orEmpty() to f.scannerModel.orEmpty())

        val ifd0 = Ifd()
        if (desc.isNotEmpty()) ifd0.ascii(0x010E, desc)
        if (make.isNotBlank()) ifd0.ascii(0x010F, make)
        if (model.isNotBlank()) ifd0.ascii(0x0110, model)
        ifd0.short(0x0112, f.orientation.coerceIn(1, 8))
        f.dpi?.let { d ->
            ifd0.rational(0x011A, d); ifd0.rational(0x011B, d); ifd0.short(0x0128, 2)
        }
        f.scannerSoftware?.let { ifd0.ascii(0x0131, it) }
        ifd0.ascii(0x0132, date)
        if (c.author.isNotBlank()) ifd0.ascii(0x013B, c.author)
        if (notice.isNotEmpty()) ifd0.ascii(0x8298, notice)
        if (desc.isNotEmpty()) ifd0.ucs2(0x9C9C, desc)             // Windows "Comments"
        if (c.author.isNotBlank()) ifd0.ucs2(0x9C9D, c.author)     // Windows "Authors"
        ifd0.ucs2(0x9C9E, keywords(r).joinToString(";"))           // Windows "Tags"

        val sub = Ifd()
        r.exposureIndex?.let { sub.short(0x8827, it.coerceAtMost(65535)) }
        sub.undefined(0x9000, "0232".toByteArray())
        sub.ascii(0x9003, date)
        sub.ascii(0x9004, date)
        sub.long(0xA002, f.width.toLong())
        sub.long(0xA003, f.height.toLong())
        if (c.author.isNotBlank()) sub.ascii(0xA430, c.author)
        if (r.lens.isNotBlank()) sub.ascii(0xA434, r.lens)

        val tiff = TiffWriter.write(ifd0, sub)
        return segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff)
    }

    private class Ifd {
        class E(val tag: Int, val type: Int, val count: Int, val data: ByteArray)
        val entries = sortedMapOf<Int, E>()
        fun ascii(tag: Int, s: String) {
            val b = s.toByteArray(Charsets.UTF_8) + 0
            entries[tag] = E(tag, 2, b.size, b)
        }
        fun short(tag: Int, v: Int) { entries[tag] = E(tag, 3, 1, byteArrayOf((v shr 8).toByte(), v.toByte())) }
        fun long(tag: Int, v: Long) { entries[tag] = E(tag, 4, 1, be32(v)) }
        fun rational(tag: Int, v: Double) {
            val den = 1000L
            entries[tag] = E(tag, 5, 1, be32(Math.round(v * den)) + be32(den))
        }
        fun undefined(tag: Int, b: ByteArray) { entries[tag] = E(tag, 7, b.size, b) }
        fun ucs2(tag: Int, s: String) {
            val b = (s + "\u0000").toByteArray(Charsets.UTF_16LE)
            entries[tag] = E(tag, 1, b.size, b)
        }
    }

    /** Big-endian TIFF block: IFD0 (with a pointer to the Exif IFD), then the Exif IFD. */
    private object TiffWriter {
        fun write(ifd0: Ifd, sub: Ifd): ByteArray {
            ifd0.long(0x8769, 0) // placeholder, fixed below
            val ifd0Size = ifdSize(ifd0)
            val subStart = 8 + ifd0Size
            ifd0.long(0x8769, subStart.toLong())
            val out = ByteArrayOutputStream()
            out.write(byteArrayOf('M'.code.toByte(), 'M'.code.toByte(), 0, 42))
            out.write(be32(8))
            writeIfd(out, ifd0, 8)
            writeIfd(out, sub, subStart)
            return out.toByteArray()
        }

        private fun ifdSize(i: Ifd) = 2 + i.entries.size * 12 + 4 +
            i.entries.values.sumOf { if (it.data.size > 4) (it.data.size + 1) and 1.inv() else 0 }

        private fun writeIfd(out: ByteArrayOutputStream, i: Ifd, start: Int) {
            var dataOff = start + 2 + i.entries.size * 12 + 4
            val data = ByteArrayOutputStream()
            out.write(byteArrayOf((i.entries.size shr 8).toByte(), i.entries.size.toByte()))
            for (e in i.entries.values) {
                out.write(byteArrayOf((e.tag shr 8).toByte(), e.tag.toByte(), 0, e.type.toByte()))
                out.write(be32(e.count.toLong()))
                if (e.data.size <= 4) {
                    out.write(e.data); repeat(4 - e.data.size) { out.write(0) }
                } else {
                    out.write(be32(dataOff.toLong()))
                    data.write(e.data)
                    if (e.data.size % 2 == 1) data.write(0)
                    dataOff += (e.data.size + 1) and 1.inv()
                }
            }
            out.write(be32(0))
            out.write(data.toByteArray())
        }
    }

    // ------------------------------------------------------------------ XMP

    fun xmp(f: Frame, r: RollMeta, c: Credits): ByteArray {
        val iso = ISO_FMT.format(f.scanDate)
        val desc = description(f, r)
        val notice = copyrightNotice(c, f.scanDate.year)
        val a = mutableListOf(
            "xmpMM:PreservedFileName" to f.originalName,
            "xmp:CreateDate" to iso,
            "exif:DateTimeDigitized" to iso,
            "exif:DateTimeOriginal" to iso,
            "photoshop:DateCreated" to iso,
        )
        if (r.camera.isNotBlank()) {
            a += "tiff:Make" to r.camera.trim().substringBefore(' '); a += "tiff:Model" to r.camera.trim()
        }
        if (r.lens.isNotBlank()) a += "exifEX:LensModel" to r.lens
        if (r.film.isNotBlank()) {
            a += "AnalogExif:Film" to r.film
            a += "AnalogExif:FilmMaker" to r.film.substringBefore(' ')
        }
        if (r.pushStops != 0 || r.tags.isNotEmpty())
            a += "AnalogExif:DevelopProcess" to (listOf(r.pushLabel).filter { it.isNotEmpty() } + r.tags).joinToString(", ")
        if (f.frameNumber > 0) a += "AnalogExif:ExposureNumber" to f.frameNumber.toString()
        if (f.rollName.isNotBlank()) a += "AnalogExif:RollId" to f.rollName
        if (r.lab.isNotBlank()) a += "AnalogExif:Lab" to r.lab
        f.scannerMake?.let { a += "AnalogExif:ScannerMaker" to it }
        scannerName(f)?.let { a += "AnalogExif:Scanner" to it }
        f.scannerSoftware?.let { a += "AnalogExif:ScannerSoftware" to it }
        if (c.author.isNotBlank()) {
            a += "xmpRights:Marked" to "True"
            a += "photoshop:Credit" to c.author
            a += "cc:attributionName" to c.author
            c.license.url?.let { a += "xmpRights:WebStatement" to it }
        }

        val body = StringBuilder()
        r.exposureIndex?.let { body.append("<exif:ISOSpeedRatings><rdf:Seq><rdf:li>$it</rdf:li></rdf:Seq></exif:ISOSpeedRatings>") }
        if (desc.isNotEmpty()) body.append(alt("dc:description", desc))
        if (c.author.isNotBlank()) {
            body.append("<dc:creator><rdf:Seq><rdf:li>${esc(c.author)}</rdf:li></rdf:Seq></dc:creator>")
            body.append(alt("dc:rights", notice))
            body.append(alt("xmpRights:UsageTerms", usageTerms(c)))
            body.append("<xmpRights:Owner><rdf:Bag><rdf:li>${esc(c.author)}</rdf:li></rdf:Bag></xmpRights:Owner>")
            c.license.url?.let { body.append("<cc:license rdf:resource=\"${esc(it)}\"/>") }
        }
        body.append("<dc:subject><rdf:Bag>")
        keywords(r).forEach { body.append("<rdf:li>${esc(it)}</rdf:li>") }
        body.append("</rdf:Bag></dc:subject>")

        val xml = buildString {
            append("<?xpacket begin=\"\uFEFF\" id=\"W5M0MpCehiHzreSzNTczkc9d\"?>")
            append("<x:xmpmeta xmlns:x=\"adobe:ns:meta/\"><rdf:RDF xmlns:rdf=\"http://www.w3.org/1999/02/22-rdf-syntax-ns#\">")
            append("<rdf:Description rdf:about=\"\"")
            NS.forEach { (p, u) -> append(" xmlns:$p=\"$u\"") }
            a.forEach { (k, v) -> append(" $k=\"${esc(v)}\"") }
            append(">").append(body).append("</rdf:Description></rdf:RDF></x:xmpmeta>")
            append("<?xpacket end=\"w\"?>")
        }
        return segment(0xE1, "http://ns.adobe.com/xap/1.0/\u0000".toByteArray(Charsets.ISO_8859_1) + xml.toByteArray(Charsets.UTF_8))
    }

    private val NS = listOf(
        "xmp" to "http://ns.adobe.com/xap/1.0/",
        "xmpMM" to "http://ns.adobe.com/xap/1.0/mm/",
        "xmpRights" to "http://ns.adobe.com/xap/1.0/rights/",
        "dc" to "http://purl.org/dc/elements/1.1/",
        "photoshop" to "http://ns.adobe.com/photoshop/1.0/",
        "exif" to "http://ns.adobe.com/exif/1.0/",
        "exifEX" to "http://cipa.jp/exif/1.0/",
        "tiff" to "http://ns.adobe.com/tiff/1.0/",
        "cc" to "http://creativecommons.org/ns#",
        "AnalogExif" to "http://analogexif.sourceforge.net/ns",
    )

    private fun alt(tag: String, v: String) =
        "<$tag><rdf:Alt><rdf:li xml:lang=\"x-default\">${esc(v)}</rdf:li></rdf:Alt></$tag>"

    private fun esc(s: String) = s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;")

    // ------------------------------------------------------------------ ICC

    fun iccSegments(icc: ByteArray): List<ByteArray> {
        val max = 65519 - 14
        val chunks = (icc.size + max - 1) / max
        return (0 until chunks).map { i ->
            val part = icc.copyOfRange(i * max, minOf(icc.size, (i + 1) * max))
            segment(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf((i + 1).toByte(), chunks.toByte()) + part)
        }
    }

    // ------------------------------------------------------------------ IPTC

    fun iptc(f: Frame, r: RollMeta, c: Credits): ByteArray {
        val d = ByteArrayOutputStream()
        fun ds(rec: Int, set: Int, v: ByteArray) {
            d.write(0x1C); d.write(rec); d.write(set); d.write((v.size shr 8) and 0x7F); d.write(v.size and 0xFF); d.write(v)
        }
        fun ds(rec: Int, set: Int, s: String, max: Int) = ds(rec, set, s.toByteArray(Charsets.UTF_8).let { if (it.size > max) it.copyOf(max) else it })
        ds(1, 90, byteArrayOf(0x1B, 0x25, 0x47)) // UTF-8
        ds(2, 0, byteArrayOf(0, 4))
        keywords(r).forEach { ds(2, 25, it, 64) }
        ds(2, 62, DateTimeFormatter.ofPattern("yyyyMMdd").format(f.scanDate), 8)
        ds(2, 63, DateTimeFormatter.ofPattern("HHmmss").format(f.scanDate) + "+0000", 11)
        if (c.author.isNotBlank()) {
            ds(2, 80, c.author, 32)
            ds(2, 110, c.author, 32)
            ds(2, 116, copyrightNotice(c, f.scanDate.year), 128)
        }
        val desc = description(f, r)
        if (desc.isNotEmpty()) ds(2, 120, desc, 2000)
        val iptc = d.toByteArray()

        val res = ByteArrayOutputStream()
        res.write("Photoshop 3.0\u0000".toByteArray(Charsets.ISO_8859_1))
        res.write("8BIM".toByteArray(Charsets.ISO_8859_1))
        res.write(byteArrayOf(0x04, 0x04, 0, 0))
        res.write(be32(iptc.size.toLong()))
        res.write(iptc)
        if (iptc.size % 2 == 1) res.write(0)
        return segment(0xED, res.toByteArray())
    }

    // ------------------------------------------------------------------ util

    fun segment(marker: Int, payload: ByteArray): ByteArray {
        require(payload.size <= 65533) { "segment too large" }
        val len = payload.size + 2
        return byteArrayOf(0xFF.toByte(), marker.toByte(), (len shr 8).toByte(), len.toByte()) + payload
    }

    private fun be32(v: Long) = byteArrayOf((v shr 24).toByte(), (v shr 16).toByte(), (v shr 8).toByte(), v.toByte())
}
