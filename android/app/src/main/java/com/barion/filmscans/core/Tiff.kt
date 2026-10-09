package com.barion.filmscans.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Inflater

/** Random access to a file's bytes (a FileChannel on the phone, a RandomAccessFile in tests). */
interface ByteSource {
    val size: Long
    fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size)
}

/** Bytes already in memory, e.g. the start of a file read out of a zip. */
class BytesSource(private val b: ByteArray) : ByteSource {
    override val size = b.size.toLong()
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        if (pos < 0 || pos + len > b.size) throw java.io.EOFException("read past the end")
        System.arraycopy(b, pos.toInt(), buf, off, len)
    }
}

class TiffException(msg: String) : IOException(msg)

/**
 * Reads the first image of a TIFF: its tags, and its pixels streamed a few rows at a time as
 * 8-bit gray or RGB, so a 100 MP scan never has to fit in memory. Handles:
 *  - classic TIFF and BigTIFF (over 4 GB)
 *  - 1 to 16-bit integer samples (packed ones too, like 2, 4 or 12-bit), 32-bit integer, and
 *    16/32/64-bit floating point (taken as 0 to 1 and clipped, as editors write them)
 *  - gray, RGB, CMYK, palette, and YCbCr when JPEG-compressed
 *  - strips or tiles, chunky or planar
 *  - no compression, LZW, Deflate, PackBits, and JPEG (through [jpegDecoder]), with the
 *    horizontal or floating-point predictor
 */
class TiffReader(private val src: ByteSource, image: Boolean = true) {
    private var little = true
    private var big = false
    val tags = HashMap<Int, Entry>()
    val exifTags = HashMap<Int, Entry>()

    class Entry(val type: Int, val count: Long, val raw: ByteArray, private val little: Boolean) {
        private fun u16(i: Int) = if (little) (raw[i].u() or (raw[i + 1].u() shl 8))
        else ((raw[i].u() shl 8) or raw[i + 1].u())
        private fun u32(i: Int): Long = if (little)
            (raw[i].u().toLong() or (raw[i + 1].u().toLong() shl 8) or (raw[i + 2].u().toLong() shl 16) or (raw[i + 3].u().toLong() shl 24))
        else ((raw[i].u().toLong() shl 24) or (raw[i + 1].u().toLong() shl 16) or (raw[i + 2].u().toLong() shl 8) or raw[i + 3].u().toLong())
        private fun u64(i: Int): Long = if (little) u32(i) or (u32(i + 4) shl 32) else (u32(i) shl 32) or u32(i + 4)

        fun longs(): LongArray = LongArray(count.toInt()) { i ->
            when (type) {
                1, 2, 6, 7 -> raw[i].u().toLong()
                3, 8 -> u16(i * 2).toLong()
                4, 9, 13 -> u32(i * 4)
                16, 17, 18 -> u64(i * 8)
                else -> 0L
            }
        }
        fun long(i: Int = 0) = longs()[i]
        fun int(i: Int = 0) = long(i).toInt()
        fun rational(): Double = if (type == 5 || type == 10) {
            val d = u32(4); if (d == 0L) 0.0 else u32(0).toDouble() / d
        } else long().toDouble()
        fun text(): String = String(raw, Charsets.UTF_8).trimEnd('\u0000', ' ').trim()
    }

    // ---- tags
    var width = 0; private set
    var height = 0; private set
    var bits = 8; private set
    var spp = 1; private set
    var photometric = 1; private set
    var compression = 1; private set
    var predictor = 1; private set
    var planar = 1; private set
    var orientation = 1; private set
    var sampleFormat = 1; private set

    init {
        val h = ByteArray(16)
        src.read(0, h, 0, minOf(16L, src.size).toInt())
        little = when {
            h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
            h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
            else -> throw TiffException("not a TIFF file")
        }
        val first = when (u16(h, 2)) {
            42 -> u32(h, 4)
            43 -> { // BigTIFF: 8-byte offsets
                if (u16(h, 4) != 8) throw TiffException("damaged BigTIFF header")
                big = true
                u64(h, 8)
            }
            else -> throw TiffException("not a TIFF file")
        }
        readIfd(first, tags)
        tags[TAG_EXIF_IFD]?.let { runCatching { readIfd(it.long(), exifTags) } }

        // A JPEG's EXIF block is TIFF-shaped but has no image of its own ([image] = false).
        width = tags[256]?.int() ?: if (image) throw TiffException("no width") else 0
        height = tags[257]?.int() ?: if (image) throw TiffException("no height") else 0
        spp = tags[277]?.int() ?: 1
        bits = tags[258]?.longs()?.maxOrNull()?.toInt() ?: 1
        photometric = tags[262]?.int() ?: 1
        compression = tags[259]?.int() ?: 1
        predictor = tags[317]?.int() ?: 1
        planar = tags[284]?.int() ?: 1
        orientation = tags[274]?.int() ?: 1
        sampleFormat = tags[339]?.int() ?: 1
    }

    fun text(tag: Int): String? = tags[tag]?.takeIf { it.type == 2 }?.text()?.ifBlank { null }
    fun exifText(tag: Int): String? = exifTags[tag]?.takeIf { it.type == 2 }?.text()?.ifBlank { null }
    fun bytes(tag: Int): ByteArray? = tags[tag]?.raw

    /** Pixels per inch, or null when the file doesn't say. */
    fun dpi(): Double? {
        val x = tags[282]?.rational() ?: return null
        if (x <= 0) return null
        return when (tags[296]?.int() ?: 2) { 3 -> x * 2.54; 1 -> null; else -> x }
    }

    private val isFloat get() = sampleFormat == 3
    private val isJpeg get() = compression == 7

    /** After decoding, YCbCr JPEG data is RGB. */
    private val colour get() = if (photometric == 6 && isJpeg) 2 else photometric

    /** 1 channel (gray) or 3 (RGB) in the output rows. */
    val outChannels: Int get() = if (colour <= 1 && spp - extraSamples() <= 1) 1 else 3

    private fun extraSamples() = when (colour) {
        0, 1 -> (spp - 1).coerceAtLeast(0)
        2 -> (spp - 3).coerceAtLeast(0)
        5 -> (spp - 4).coerceAtLeast(0)
        else -> 0
    }

    fun checkSupported() {
        when {
            isFloat -> if (bits !in setOf(16, 32, 64)) throw TiffException("$bits-bit floating-point TIFFs aren't supported")
            sampleFormat == 2 -> if (bits !in setOf(8, 16, 32)) throw TiffException("signed $bits-bit TIFFs aren't supported")
            bits !in 1..16 && bits != 32 -> throw TiffException("$bits-bit TIFFs aren't supported")
        }
        if (compression !in setOf(1, 5, 7, 8, 32946, 32773)) throw TiffException(when (compression) {
            6 -> "old-style JPEG TIFFs aren't supported"
            50000 -> "Zstandard-compressed TIFFs aren't supported"
            50001 -> "WebP-compressed TIFFs aren't supported"
            34887 -> "LERC-compressed TIFFs aren't supported"
            else -> "TIFF compression type $compression isn't supported"
        })
        if (isJpeg) {
            if (jpegDecoder == null) throw TiffException("JPEG-compressed TIFFs can't be read here")
            if (bits != 8 || planar != 1) throw TiffException("this kind of JPEG-compressed TIFF isn't supported")
        }
        if (photometric !in setOf(0, 1, 2, 3, 5) && !(photometric == 6 && isJpeg))
            throw TiffException("TIFF colour type $photometric isn't supported")
        when (predictor) {
            1 -> {}
            2 -> if (isFloat || bits !in setOf(8, 16, 32)) throw TiffException("TIFF predictor 2 with $bits-bit samples isn't supported")
            3 -> if (!isFloat) throw TiffException("TIFF floating-point predictor on whole numbers isn't supported")
            else -> throw TiffException("TIFF predictor $predictor isn't supported")
        }
    }

    /** Byte-aligned sample width in bytes (1, 2, 4, 8), or 0 for packed samples (under 8 bits, or e.g. 12). */
    private val sampleBytes get() = if (bits in setOf(8, 16, 32, 64)) bits / 8 else 0

    /** Samples are kept as whole numbers from 0 to this, before becoming 8-bit. */
    private val sampleMax: Int get() = if (isFloat || bits > 16) 65535 else (1 shl bits) - 1

    /**
     * Streams the image top to bottom. [sink] gets (firstRow, rowCount, pixels) where
     * pixels holds rowCount rows of width*outChannels bytes each. With [wantRow], rows it
     * rejects may be skipped (left undefined), and bands with no wanted rows aren't sent.
     */
    fun readRows(wantRow: ((Int) -> Boolean)? = null, sink: (Int, Int, ByteArray) -> Unit) {
        checkSupported()
        val tiled = tags.containsKey(322)
        val chunkW = if (tiled) tags[322]!!.int() else width
        val chunkH = if (tiled) tags[323]!!.int() else (tags[278]?.long()?.coerceAtMost(height.toLong())?.toInt() ?: height).coerceAtLeast(1)
        val offsets = (if (tiled) tags[324] else tags[273])?.longs() ?: throw TiffException("no image data")
        val counts = (if (tiled) tags[325] else tags[279])?.longs()
        val planes = if (planar == 2) spp else 1
        val sppChunk = if (planar == 2) 1 else spp
        val across = if (tiled) (width + chunkW - 1) / chunkW else 1
        val down = (height + chunkH - 1) / chunkH
        val perPlane = across * down
        val chunkRowBytes = ((chunkW.toLong() * sppChunk * bits + 7) / 8).toInt()

        val oc = outChannels
        // Samples for one band of rows, all planes gathered as chunky whole numbers (0..sampleMax).
        // Uncompressed strips are read a band of rows at a time straight from the file, so a scan stored
        // as one giant strip doesn't have to be loaded whole. A compressed strip has to be decoded whole,
        // but is then worked through 64 rows at a time, so only one copy of it is ever held.
        val direct = !tiled && compression == 1
        val maxBand = if (tiled) chunkH else minOf(64, chunkH, height).coerceAtLeast(1)
        val bandSamples = IntArray(maxBand * width * spp)
        val out = ByteArray(maxBand * width * oc)
        // Palette entries are 16-bit; like libtiff, treat a map with nothing over 255 as 8-bit.
        val colorMap = if (photometric == 3) tags[320]?.longs()?.let { m ->
            if (m.all { it <= 255 }) m else LongArray(m.size) { m[it] shr 8 }
        } else null
        val maxVal = sampleMax
        val sb = sampleBytes
        val signed = sampleFormat == 2
        val fl = isFloat
        // Most scans: plain 8 or 16-bit whole numbers, read directly.
        val plain = !fl && !signed && (sb == 1 || sb == 2)

        // Where each band starts. Bands never cross from one strip or tile row into the next.
        val starts = ArrayList<Int>()
        run {
            var y = 0
            while (y < height) {
                val chunkEnd = if (direct) height else minOf(height, (y / chunkH + 1) * chunkH)
                while (y < chunkEnd) { starts += y; y += maxBand }
                y = chunkEnd
            }
        }
        val rowBuf = if (direct) ByteArray(chunkRowBytes) else ByteArray(0)
        // The last strip or tile decoded for each column of tiles and plane, reused by the next band.
        val cachedIdx = IntArray(planes * across) { -1 }
        val cached = arrayOfNulls<ByteArray>(planes * across)

        fun sampleAt(data: ByteArray, rowOff: Int, si: Int): Int {
            if (plain) {
                val p = rowOff + si * sb
                return if (sb == 1) { if (p < data.size) data[p].u() else 0 }
                else if (p + 1 >= data.size) 0
                else if (little) data[p].u() or (data[p + 1].u() shl 8) else (data[p].u() shl 8) or data[p + 1].u()
            }
            if (sb == 0) { // packed, most significant bit first
                val bit = si.toLong() * bits
                var v = 0
                for (k in 0 until bits) {
                    val b = bit + k
                    val p = rowOff + (b shr 3).toInt()
                    val x = if (p < data.size) (data[p].u() shr (7 - (b and 7).toInt())) and 1 else 0
                    v = (v shl 1) or x
                }
                return v
            }
            val p = rowOff + si * sb
            if (p + sb > data.size) return 0
            fun byteAt(k: Int) = data[p + (if (little) k else sb - 1 - k)].u().toLong()
            var raw = 0L
            for (k in sb - 1 downTo 0) raw = (raw shl 8) or byteAt(k)
            return when {
                fl -> {
                    val f = when (sb) {
                        2 -> halfToFloat(raw.toInt())
                        4 -> java.lang.Float.intBitsToFloat(raw.toInt()).toDouble()
                        else -> java.lang.Double.longBitsToDouble(raw)
                    }
                    if (f.isNaN()) 0 else (f.coerceIn(0.0, 1.0) * 65535 + 0.5).toInt()
                }
                sb == 4 -> ((if (signed) raw xor 0x80000000L else raw) ushr 16).toInt()
                signed -> (raw xor (1L shl (bits - 1))).toInt()
                else -> raw.toInt()
            }
        }

        val separate = planar == 2
        fun unpack(data: ByteArray, rowOff: Int, r: Int, x0: Int, cols: Int, plane: Int) {
            val base = (r * width + x0) * spp
            if (plain && !separate) {
                // The common case, kept tight: chunky 8 or 16-bit samples straight into the band.
                val n = cols * sppChunk
                val avail = ((data.size - rowOff) / sb).coerceIn(0, n)
                if (sb == 1) for (i in 0 until avail) bandSamples[base + i] = data[rowOff + i].u()
                else if (little) for (i in 0 until avail) { val p = rowOff + 2 * i; bandSamples[base + i] = data[p].u() or (data[p + 1].u() shl 8) }
                else for (i in 0 until avail) { val p = rowOff + 2 * i; bandSamples[base + i] = (data[p].u() shl 8) or data[p + 1].u() }
                for (i in avail until n) bandSamples[base + i] = 0
                return
            }
            for (c in 0 until cols) {
                for (s in 0 until sppChunk) {
                    val v = sampleAt(data, rowOff, c * sppChunk + s)
                    val sample = if (separate) plane else s
                    bandSamples[base + c * spp + sample] = v
                }
            }
        }
        // Whole number (0..maxVal) to 8-bit, looked up rather than divided for every sample.
        val lut = IntArray(maxVal + 1) { v -> if (maxVal == 255) v else (v * 255 + maxVal / 2) / maxVal }

        for (bi in starts.indices) {
            val y0 = starts[bi]
            val rows = (if (bi + 1 < starts.size) starts[bi + 1] else height) - y0
            // Thumbnails only need some rows: skip whole bands that hold none of them.
            if (wantRow != null && (y0 until y0 + rows).none(wantRow)) continue
            if (direct) {
                val rps = chunkH
                for (plane in 0 until planes) for (r in 0 until rows) {
                    val y = y0 + r
                    if (wantRow != null && !wantRow(y)) continue
                    val idx = plane * down + y / rps
                    if (idx >= offsets.size) throw TiffException("image data is truncated")
                    val off = offsets[idx] + (y % rps).toLong() * chunkRowBytes
                    java.util.Arrays.fill(rowBuf, 0)
                    val n = minOf(chunkRowBytes.toLong(), src.size - off).toInt()
                    if (n > 0) src.read(off, rowBuf, 0, n)
                    undoPredictor(rowBuf, chunkRowBytes, sppChunk, chunkW)
                    unpack(rowBuf, 0, r, 0, width, plane)
                }
            } else for (plane in 0 until planes) {
                for (ax in 0 until across) {
                    val idx = plane * perPlane + (y0 / chunkH) * across + ax
                    if (idx >= offsets.size) throw TiffException("image data is truncated")
                    val slot = plane * across + ax
                    if (cachedIdx[slot] != idx) {
                        cached[slot] = null // let the last one go before decoding the next
                        cached[slot] = decodeChunk(offsets[idx], counts?.getOrNull(idx), chunkRowBytes, chunkH, sppChunk, chunkW)
                        cachedIdx[slot] = idx
                    }
                    val data = cached[slot]!!
                    val x0 = ax * chunkW
                    val cols = minOf(chunkW, width - x0)
                    val first = y0 % chunkH
                    for (r in 0 until rows) unpack(data, (first + r) * chunkRowBytes, r, x0, cols, plane)
                }
            }
            // Convert the band to 8-bit gray or RGB.
            for (i in 0 until rows * width) {
                val b = i * spp
                val o = i * oc
                when (colour) {
                    0 -> out[o] = (255 - lut[bandSamples[b]]).toByte()
                    1 -> out[o] = lut[bandSamples[b]].toByte()
                    2 -> for (k in 0 until 3) out[o + k] = lut[bandSamples[b + k]].toByte()
                    3 -> {
                        val n = 1 shl bits
                        val idxv = bandSamples[b]
                        for (k in 0 until 3) {
                            val cm = colorMap?.getOrNull(k * n + idxv) ?: 0L
                            out[o + k] = cm.toInt().toByte()
                        }
                    }
                    5 -> {
                        val cc = lut[bandSamples[b]]; val m = lut[bandSamples[b + 1]]
                        val yy = lut[bandSamples[b + 2]]; val k = lut[bandSamples[b + 3]]
                        out[o] = ((255 - cc) * (255 - k) / 255).toByte()
                        out[o + 1] = ((255 - m) * (255 - k) / 255).toByte()
                        out[o + 2] = ((255 - yy) * (255 - k) / 255).toByte()
                    }
                }
            }
            sink(y0, rows, out)
        }
    }


    private fun decodeChunk(offset: Long, count: Long?, rowBytes: Int, rows: Int, sppChunk: Int, chunkW: Int): ByteArray {
        val expected = rowBytes * rows
        val len = (count ?: expected.toLong()).coerceAtMost(src.size - offset).toInt().coerceAtLeast(0)
        val raw = ByteArray(len)
        src.read(offset, raw)
        val data = when (compression) {
            1 -> raw
            5 -> lzwDecode(raw, expected)
            7 -> jpegChunk(raw, rowBytes, rows, sppChunk)
            8, 32946 -> inflate(raw, expected)
            32773 -> unpackBits(raw, expected)
            else -> throw TiffException("unsupported compression")
        }
        var r = 0
        while ((r + 1) * rowBytes <= data.size) { undoPredictor(data, rowBytes, sppChunk, chunkW, r * rowBytes); r++ }
        return data
    }

    /** A JPEG strip or tile, decoded to rows of 8-bit samples. Shared tables (tag 347) go in front. */
    private fun jpegChunk(raw: ByteArray, rowBytes: Int, rows: Int, sppChunk: Int): ByteArray {
        val jpeg = mergeJpegTables(tags[347]?.raw, raw)
        val d = jpegDecoder?.decode(jpeg) ?: throw TiffException("couldn't decode a JPEG-compressed part of the TIFF")
        val out = ByteArray(rowBytes * rows)
        val cols = minOf(d.width, rowBytes / sppChunk)
        for (y in 0 until minOf(d.height, rows)) for (x in 0 until cols) for (s in 0 until sppChunk) {
            val from = (y * d.width + x) * d.channels + minOf(s, d.channels - 1)
            out[y * rowBytes + x * sppChunk + s] = d.pixels[from]
        }
        return out
    }

    /** Undoes the predictor on one row of [rowBytes] starting at [at]. */
    private fun undoPredictor(d: ByteArray, rowBytes: Int, stride: Int, chunkW: Int, at: Int = 0) {
        when (predictor) {
            2 -> {
                val sb = sampleBytes
                if (sb == 1) { // 8-bit: each byte adds the one a pixel back
                    for (i in at + stride until at + rowBytes) d[i] = (d[i] + d[i - stride]).toByte()
                    return
                }
                if (sb == 2) { // 16-bit, the usual scan
                    val step = stride * 2
                    var p = at + step
                    val end = at + rowBytes - 1
                    if (little) while (p < end) {
                        val v = (d[p].u() or (d[p + 1].u() shl 8)) + (d[p - step].u() or (d[p - step + 1].u() shl 8))
                        d[p] = v.toByte(); d[p + 1] = (v shr 8).toByte(); p += 2
                    } else while (p < end) {
                        val v = ((d[p].u() shl 8) or d[p + 1].u()) + ((d[p - step].u() shl 8) or d[p - step + 1].u())
                        d[p] = (v shr 8).toByte(); d[p + 1] = v.toByte(); p += 2
                    }
                    return
                }
                val n = rowBytes / sb
                for (i in stride until n) {
                    val p = at + i * sb; val q = at + (i - stride) * sb
                    var carry = 0
                    // Add sample by sample in the file's byte order, carrying between bytes.
                    for (k in 0 until sb) {
                        val pi = p + (if (little) k else sb - 1 - k)
                        val qi = q + (if (little) k else sb - 1 - k)
                        val v = d[pi].u() + d[qi].u() + carry
                        d[pi] = v.toByte(); carry = v shr 8
                    }
                }
            }
            3 -> {
                // Floating point (Adobe TN3): bytes were split into planes, most significant first,
                // then differenced across the row. Undo both, writing the file's byte order back.
                val sb = sampleBytes
                val samples = chunkW * stride
                if (samples * sb > rowBytes) return
                for (i in stride until samples * sb) d[at + i] = (d[at + i] + d[at + i - stride]).toByte()
                val tmp = d.copyOfRange(at, at + samples * sb)
                for (i in 0 until samples) for (k in 0 until sb) {
                    val fromPlane = tmp[k * samples + i]  // k = 0 is the most significant byte
                    d[at + i * sb + (if (little) sb - 1 - k else k)] = fromPlane
                }
            }
        }
    }

    // ---- IFD parsing
    private fun readIfd(pos: Long, into: MutableMap<Int, Entry>) {
        val entrySize = if (big) 20 else 12
        val inline = if (big) 8 else 4
        val cnt = ByteArray(if (big) 8 else 2); src.read(pos, cnt)
        val n = if (big) u64(cnt, 0) else u16(cnt, 0).toLong()
        if (n <= 0 || n > 4096) throw TiffException("damaged TIFF directory")
        val buf = ByteArray(n.toInt() * entrySize); src.read(pos + cnt.size, buf)
        for (i in 0 until n.toInt()) {
            val e = i * entrySize
            val tag = u16(buf, e)
            val type = u16(buf, e + 2)
            val count = if (big) u64(buf, e + 4) else u32(buf, e + 4)
            val v = e + if (big) 12 else 8
            val size = TYPE_SIZE.getOrElse(type) { 0 } * count
            if (size <= 0 || size > 64L * 1024 * 1024) continue
            val raw = if (size <= inline) buf.copyOfRange(v, v + size.toInt())
            else ByteArray(size.toInt()).also {
                val off = if (big) u64(buf, v) else u32(buf, v)
                if (off + size > src.size) return@also
                src.read(off, it)
            }
            into[tag] = Entry(type, count, raw, little)
        }
    }

    private fun u16(b: ByteArray, i: Int) = if (little) b[i].u() or (b[i + 1].u() shl 8) else (b[i].u() shl 8) or b[i + 1].u()
    private fun u32(b: ByteArray, i: Int): Long = if (little)
        (b[i].u().toLong() or (b[i + 1].u().toLong() shl 8) or (b[i + 2].u().toLong() shl 16) or (b[i + 3].u().toLong() shl 24))
    else ((b[i].u().toLong() shl 24) or (b[i + 1].u().toLong() shl 16) or (b[i + 2].u().toLong() shl 8) or b[i + 3].u().toLong())
    private fun u64(b: ByteArray, i: Int): Long = if (little) u32(b, i) or (u32(b, i + 4) shl 32) else (u32(b, i) shl 32) or u32(b, i + 4)

    /** A decoded JPEG: [channels] (1 or 3) 8-bit samples per pixel, row after row. */
    class Decoded(val width: Int, val height: Int, val channels: Int, val pixels: ByteArray)

    /** Decodes JPEG data, for JPEG-compressed TIFFs. Set by the app (the phone's own decoder). */
    fun interface JpegDecoder { fun decode(jpeg: ByteArray): Decoded? }

    companion object {
        const val TAG_EXIF_IFD = 34665
        private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4, 0, 0, 8, 8, 8)

        @Volatile var jpegDecoder: JpegDecoder? = null

        /**
         * A strip or tile of a JPEG TIFF often leaves out its tables, which are stored once in the
         * TIFF instead; put them back in front so it's a whole JPEG again.
         */
        fun mergeJpegTables(tables: ByteArray?, chunk: ByteArray): ByteArray {
            if (tables == null || tables.size < 4 || chunk.size < 2) return chunk
            val t = if (tables[tables.size - 2] == 0xFF.toByte() && tables[tables.size - 1] == 0xD9.toByte())
                tables.copyOf(tables.size - 2) else tables
            val c = if (chunk[0] == 0xFF.toByte() && chunk[1] == 0xD8.toByte()) chunk.copyOfRange(2, chunk.size) else chunk
            return t + c
        }

        /** IEEE half precision to a number. */
        fun halfToFloat(h: Int): Double {
            val sign = if (h and 0x8000 != 0) -1.0 else 1.0
            val exp = (h shr 10) and 0x1F
            val frac = h and 0x3FF
            return sign * when (exp) {
                0 -> frac / 1024.0 * Math.pow(2.0, -14.0)
                31 -> if (frac == 0) Double.POSITIVE_INFINITY else Double.NaN
                else -> (1 + frac / 1024.0) * Math.pow(2.0, (exp - 15).toDouble())
            }
        }

        /**
         * TIFF LZW (most significant bit first, early change). Every string the decoder knows is a run
         * of bytes it has already written, so a code is just (where, how long) into the output, and
         * decoding one is a single copy.
         */
        fun lzwDecode(input: ByteArray, expected: Int): ByteArray {
            val out = ByteArray(expected)
            val offs = IntArray(4096)
            val lens = IntArray(4096)
            var pos = 0
            var next = 258; var width = 9
            var old = -1; var oldOff = 0; var oldLen = 0
            var bitBuf = 0; var bitCnt = 0; var ip = 0
            while (pos < expected) {
                while (bitCnt < width) {
                    if (ip >= input.size) return if (pos == expected) out else out.copyOf(pos)
                    bitBuf = (bitBuf shl 8) or input[ip++].u(); bitCnt += 8
                }
                bitCnt -= width
                val code = (bitBuf ushr bitCnt) and ((1 shl width) - 1)
                bitBuf = bitBuf and ((1 shl bitCnt) - 1)
                if (code == 257) break
                if (code == 256) { next = 258; width = 9; old = -1; continue }
                val start = pos
                when {
                    code < 256 -> out[pos++] = code.toByte()
                    old == -1 -> break // a string code before any string: damaged
                    code < next -> {
                        val n = minOf(lens[code], expected - pos)
                        System.arraycopy(out, offs[code], out, pos, n); pos += n
                    }
                    code == next -> { // the string being defined: the previous one plus its own first byte
                        val n = minOf(oldLen, expected - pos)
                        System.arraycopy(out, oldOff, out, pos, n); pos += n
                        if (pos < expected) out[pos++] = out[oldOff]
                    }
                    else -> break
                }
                // New entry: the previous string plus the first byte of this one, which follows it in the output.
                if (old != -1 && next < 4096) { offs[next] = oldOff; lens[next] = oldLen + 1; next++ }
                old = code; oldOff = start; oldLen = pos - start
                if (next + 1 >= (1 shl width) && width < 12) width++
            }
            return if (pos == expected) out else out.copyOf(pos)
        }

        fun inflate(input: ByteArray, expected: Int): ByteArray {
            val inf = Inflater()
            inf.setInput(input)
            val out = ByteArray(expected)
            var n = 0
            while (n < expected && !inf.finished()) {
                val r = inf.inflate(out, n, expected - n)
                if (r == 0 && (inf.needsInput() || inf.needsDictionary())) break
                n += r
            }
            inf.end()
            return out
        }

        fun unpackBits(input: ByteArray, expected: Int): ByteArray {
            val out = ByteArray(expected)
            var i = 0; var o = 0
            while (i < input.size && o < expected) {
                val n = input[i++].toInt()
                if (n >= 0) {
                    val c = minOf(n + 1, input.size - i, expected - o)
                    System.arraycopy(input, i, out, o, c); i += n + 1; o += c
                } else if (n != -128) {
                    if (i >= input.size) break
                    val b = input[i++]
                    repeat(minOf(-n + 1, expected - o)) { out[o++] = b }
                }
            }
            return out
        }
    }
}

internal fun Byte.u() = toInt() and 0xFF
