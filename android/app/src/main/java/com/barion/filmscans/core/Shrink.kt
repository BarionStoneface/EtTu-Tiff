package com.barion.filmscans.core

/**
 * Smaller copies for sending: the picture shrunk so its long side is a set number of pixels, and
 * the photo's details carried over without its location.
 */
object Shrink {
    /** The size to shrink [w]×[h] to so its long side is [longEdge]. Never enlarged. */
    fun fit(w: Int, h: Int, longEdge: Int): Pair<Int, Int> {
        val long = maxOf(w, h)
        if (long <= longEdge || longEdge <= 0) return w to h
        val s = longEdge.toDouble() / long
        return maxOf(1, Math.round(w * s).toInt()) to maxOf(1, Math.round(h * s).toInt())
    }

    /**
     * A TIFF shrunk to [dw]×[dh], as ARGB pixels. Every pixel of the scan is averaged into the one it
     * lands in, so fine grain becomes tone rather than sparkle. Rows are streamed: only the shrunk
     * picture and one band of the scan are held at a time.
     */
    fun tiff(t: TiffReader, dw: Int, dh: Int, progress: (Float) -> Unit = {}): IntArray {
        t.checkSupported()
        val w = t.width; val h = t.height; val oc = t.outChannels
        val colOf = IntArray(w) { (it.toLong() * dw / w).toInt() }
        val out = IntArray(dw * dh)
        val r = IntArray(dw); val g = IntArray(dw); val b = IntArray(dw); val n = IntArray(dw)
        var cur = 0
        fun flush() {
            if (cur >= dh) return
            val base = cur * dw
            for (x in 0 until dw) {
                val c = n[x]
                out[base + x] = if (c == 0) 0xFF000000.toInt() else {
                    val half = c / 2
                    (0xFF shl 24) or (((r[x] + half) / c) shl 16) or (((g[x] + half) / c) shl 8) or ((b[x] + half) / c)
                }
            }
            r.fill(0); g.fill(0); b.fill(0); n.fill(0)
        }
        t.readRows { y0, rows, px ->
            for (k in 0 until rows) {
                val oy = ((y0 + k).toLong() * dh / h).toInt()
                if (oy != cur) { flush(); cur = oy }
                var i = k * w * oc
                if (oc == 1) for (x in 0 until w) {
                    val ox = colOf[x]; val v = px[i].u()
                    r[ox] += v; g[ox] += v; b[ox] += v; n[ox]++
                    i++
                } else for (x in 0 until w) {
                    val ox = colOf[x]
                    r[ox] += px[i].u(); g[ox] += px[i + 1].u(); b[ox] += px[i + 2].u(); n[ox]++
                    i += 3
                }
            }
            progress((y0 + rows).toFloat() / h)
        }
        flush()
        return out
    }

    /**
     * The details worth keeping from a JPEG, as complete segments in file order: EXIF and XMP (with
     * any location taken out), the colour profile and the comment. IPTC is left behind, since it can
     * hold place names; the same credits are in the EXIF and XMP.
     */
    fun jpegSegments(src: ByteSource): List<ByteArray> {
        val out = mutableListOf<ByteArray>()
        JpegHeader.segments(src) { marker, p ->
            fun whole() = Metadata.segment(marker, p.bytes())
            when {
                marker == 0xE1 && p.startsWith("Exif\u0000\u0000") -> out += noGpsExif(whole())
                marker == 0xE1 && p.startsWith(JpegHeader.XMP_ID) -> {
                    val n = JpegHeader.XMP_ID.length
                    val xmp = noGpsXmp(String(p.bytes(n), Charsets.UTF_8))
                    out += Metadata.segment(0xE1, (JpegHeader.XMP_ID + xmp).toByteArray(Charsets.UTF_8))
                }
                marker == 0xE2 && p.startsWith("ICC_PROFILE\u0000") -> out += whole()
                marker == 0xFE -> out += whole()
            }
        }
        return out
    }

    /**
     * An EXIF segment (marker included) with the pointer to its location block removed from the
     * first directory, so no reader can find the location. Anything that isn't laid out as
     * expected is returned unchanged.
     */
    fun noGpsExif(seg: ByteArray): ByteArray {
        val t = 10 // FF E1, length, "Exif\0\0"
        if (seg.size < t + 8) return seg
        val little = seg[t] == 'I'.code.toByte()
        fun u16(i: Int) = if (little) seg[i].u() or (seg[i + 1].u() shl 8) else (seg[i].u() shl 8) or seg[i + 1].u()
        fun u32(i: Int): Long = if (little) (u16(i).toLong() or (u16(i + 2).toLong() shl 16))
            else ((u16(i).toLong() shl 16) or u16(i + 2).toLong())
        val ifd = u32(t + 4)
        if (ifd < 8 || t + ifd + 2 > seg.size) return seg
        val at = t + ifd.toInt()
        val count = u16(at)
        val end = at + 2 + count * 12
        if (end + 4 > seg.size) return seg
        val gps = (0 until count).firstOrNull { u16(at + 2 + it * 12) == 0x8825 } ?: return seg
        val out = seg.copyOf()
        val e = at + 2 + gps * 12
        // The location block itself is blanked, values and all, so it isn't left in the file unseen.
        val g = t + u32(e + 8)
        if (g >= t + 8 && g + 2 <= seg.size) {
            val gn = u16(g.toInt())
            val gEnd = g + 2 + gn * 12L
            if (gEnd + 4 <= seg.size) {
                for (k in 0 until gn) {
                    val ge = g.toInt() + 2 + k * 12
                    val len = (TYPE_SIZE.getOrElse(u16(ge + 2)) { 1 }).toLong() * u32(ge + 4)
                    val v = t + u32(ge + 8)
                    if (len > 4 && v >= t + 8 && v + len <= seg.size) java.util.Arrays.fill(out, v.toInt(), (v + len).toInt(), 0)
                }
                java.util.Arrays.fill(out, g.toInt(), (gEnd + 4).toInt(), 0)
            }
        }
        // Later entries and the next-directory offset move up one place; the 12 freed bytes are unused.
        System.arraycopy(seg, e + 12, out, e, end + 4 - (e + 12))
        java.util.Arrays.fill(out, end - 8, end + 4, 0)
        val c = count - 1
        if (little) { out[at] = c.toByte(); out[at + 1] = (c shr 8).toByte() }
        else { out[at] = (c shr 8).toByte(); out[at + 1] = c.toByte() }
        return out
    }

    /** Bytes per value of each TIFF field type. */
    private val TYPE_SIZE = intArrayOf(1, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, 4, 8, 8, 8)

    /** XMP with every exif:GPS… property removed, whether written as an attribute or an element. */
    fun noGpsXmp(xmp: String): String = xmp
        .replace(Regex("""\s+exif:GPS\w*\s*=\s*("[^"]*"|'[^']*')"""), "")
        .replace(Regex("""<exif:GPS\w*[^>]*/>"""), "")
        .replace(Regex("""<exif:(GPS\w*)\b[^>]*>.*?</exif:\1>""", RegexOption.DOT_MATCHES_ALL), "")
}
