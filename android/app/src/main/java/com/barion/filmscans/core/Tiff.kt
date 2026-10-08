package com.barion.filmscans.core

import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.Inflater

/** Random access to a file's bytes (a FileChannel on the phone, a RandomAccessFile in tests). */
interface ByteSource {
    val size: Long
    fun read(pos: Long, buf: ByteArray, off: Int = 0, len: Int = buf.size)
}

class TiffException(msg: String) : IOException(msg)

/**
 * Reads the first image of a baseline TIFF: its tags, and its pixels streamed a
 * few rows at a time as 8-bit gray or RGB, so a 100 MP scan never has to fit in memory.
 * Handles 1/8/16-bit, gray/RGB/CMYK/palette, strips or tiles, chunky or planar,
 * and no/LZW/Deflate/PackBits compression with or without the horizontal predictor.
 */
class TiffReader(private val src: ByteSource, image: Boolean = true) {
    private var little = true
    val tags = HashMap<Int, Entry>()
    val exifTags = HashMap<Int, Entry>()

    class Entry(val type: Int, val count: Long, val raw: ByteArray, private val little: Boolean) {
        private fun u16(i: Int) = if (little) (raw[i].u() or (raw[i + 1].u() shl 8))
        else ((raw[i].u() shl 8) or raw[i + 1].u())
        private fun u32(i: Int): Long = if (little)
            (raw[i].u().toLong() or (raw[i + 1].u().toLong() shl 8) or (raw[i + 2].u().toLong() shl 16) or (raw[i + 3].u().toLong() shl 24))
        else ((raw[i].u().toLong() shl 24) or (raw[i + 1].u().toLong() shl 16) or (raw[i + 2].u().toLong() shl 8) or raw[i + 3].u().toLong())

        fun longs(): LongArray = LongArray(count.toInt()) { i ->
            when (type) {
                1, 2, 6, 7 -> raw[i].u().toLong()
                3, 8 -> u16(i * 2).toLong()
                4, 9, 13 -> u32(i * 4)
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
        val h = ByteArray(8)
        src.read(0, h)
        little = when {
            h[0] == 'I'.code.toByte() && h[1] == 'I'.code.toByte() -> true
            h[0] == 'M'.code.toByte() && h[1] == 'M'.code.toByte() -> false
            else -> throw TiffException("not a TIFF file")
        }
        val magic = u16(h, 2)
        if (magic == 43) throw TiffException("BigTIFF (over 4 GB) isn't supported")
        if (magic != 42) throw TiffException("not a TIFF file")
        readIfd(u32(h, 4), tags)
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

    /** 1 channel (gray) or 3 (RGB) in the output rows. */
    val outChannels: Int get() = if (photometric <= 1 && spp - extraSamples() <= 1) 1 else 3

    private fun extraSamples() = when (photometric) {
        0, 1 -> (spp - 1).coerceAtLeast(0)
        2 -> (spp - 3).coerceAtLeast(0)
        5 -> (spp - 4).coerceAtLeast(0)
        else -> 0
    }

    fun checkSupported() {
        if (sampleFormat == 3) throw TiffException("floating-point TIFFs aren't supported")
        if (bits !in setOf(1, 8, 16)) throw TiffException("$bits-bit TIFFs aren't supported")
        if (compression !in setOf(1, 5, 8, 32946, 32773))
            throw TiffException("TIFF compression type $compression isn't supported")
        if (photometric !in setOf(0, 1, 2, 3, 5)) throw TiffException("TIFF colour type $photometric isn't supported")
        if (predictor != 1 && predictor != 2) throw TiffException("TIFF predictor $predictor isn't supported")
    }

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
        val chunkRowBytes = (chunkW.toLong() * sppChunk * bits + 7).toInt() / 8
        val bytesPerSample = if (bits == 16) 2 else 1

        val oc = outChannels
        // Samples for one band of rows, all planes gathered as chunky 16-bit-or-8-bit values.
        val bandRows = if (!tiled && compression == 1) minOf(64, height) else chunkH
        val bandSamples = IntArray(bandRows * width * spp)
        val out = ByteArray(bandRows * width * oc)
        // Palette entries are 16-bit; like libtiff, treat a map with nothing over 255 as 8-bit.
        val colorMap = if (photometric == 3) tags[320]?.longs()?.let { m ->
            if (m.all { it <= 255 }) m else LongArray(m.size) { m[it] shr 8 }
        } else null
        val maxVal = if (bits == 16) 65535 else if (bits == 8) 255 else 1

        // Uncompressed strips are read a band of rows at a time straight from the file,
        // so a scan stored as one giant strip doesn't have to be loaded whole.
        val direct = !tiled && compression == 1
        val bandH = if (direct) minOf(64, height) else chunkH
        val bands = if (direct) (height + bandH - 1) / bandH else down
        val rowBuf = if (direct) ByteArray(chunkRowBytes) else ByteArray(0)

        fun unpack(data: ByteArray, rowOff: Int, r: Int, x0: Int, cols: Int, plane: Int) {
            for (c in 0 until cols) {
                for (s in 0 until sppChunk) {
                    val si = c * sppChunk + s
                    val v = when (bits) {
                        16 -> {
                            val p = rowOff + si * 2
                            if (p + 1 >= data.size) 0 else if (little) data[p].u() or (data[p + 1].u() shl 8)
                            else (data[p].u() shl 8) or data[p + 1].u()
                        }
                        8 -> { val p = rowOff + si; if (p >= data.size) 0 else data[p].u() }
                        else -> { // 1-bit
                            val p = rowOff + (si shr 3)
                            if (p >= data.size) 0 else (data[p].u() shr (7 - (si and 7))) and 1
                        }
                    }
                    val sample = if (planar == 2) plane else s
                    bandSamples[(r * width + x0 + c) * spp + sample] = v
                }
            }
        }

        for (dy in 0 until bands) {
            val rows = minOf(bandH, height - dy * bandH)
            // Thumbnails only need some rows: skip whole bands that hold none of them.
            if (wantRow != null && (dy * bandH until dy * bandH + rows).none(wantRow)) continue
            if (direct) {
                val rps = chunkH
                for (plane in 0 until planes) for (r in 0 until rows) {
                    val y = dy * bandH + r
                    if (wantRow != null && !wantRow(y)) continue
                    val idx = plane * down + y / rps
                    if (idx >= offsets.size) throw TiffException("image data is truncated")
                    val off = offsets[idx] + (y % rps).toLong() * chunkRowBytes
                    java.util.Arrays.fill(rowBuf, 0)
                    val n = minOf(chunkRowBytes.toLong(), src.size - off).toInt()
                    if (n > 0) src.read(off, rowBuf, 0, n)
                    if (predictor == 2) undoPredictor(rowBuf, chunkRowBytes, sppChunk, bytesPerSample)
                    unpack(rowBuf, 0, r, 0, width, plane)
                }
            } else for (plane in 0 until planes) {
                for (ax in 0 until across) {
                    val idx = plane * perPlane + dy * across + ax
                    if (idx >= offsets.size) throw TiffException("image data is truncated")
                    val data = decodeChunk(offsets[idx], counts?.getOrNull(idx), chunkRowBytes * chunkH, chunkRowBytes, sppChunk, bytesPerSample)
                    val x0 = ax * chunkW
                    val cols = minOf(chunkW, width - x0)
                    for (r in 0 until rows) unpack(data, r * chunkRowBytes, r, x0, cols, plane)
                }
            }
            // Convert the band to 8-bit gray or RGB.
            for (i in 0 until rows * width) {
                val b = i * spp
                val o = i * oc
                when (photometric) {
                    0 -> out[o] = (255 - to8(bandSamples[b], maxVal)).toByte()
                    1 -> out[o] = to8(bandSamples[b], maxVal).toByte()
                    2 -> for (k in 0 until 3) out[o + k] = to8(bandSamples[b + k], maxVal).toByte()
                    3 -> {
                        val n = 1 shl bits
                        val idxv = bandSamples[b]
                        for (k in 0 until 3) {
                            val cm = colorMap?.getOrNull(k * n + idxv) ?: 0L
                            out[o + k] = cm.toInt().toByte()
                        }
                    }
                    5 -> {
                        val cc = to8(bandSamples[b], maxVal); val m = to8(bandSamples[b + 1], maxVal)
                        val yy = to8(bandSamples[b + 2], maxVal); val k = to8(bandSamples[b + 3], maxVal)
                        out[o] = ((255 - cc) * (255 - k) / 255).toByte()
                        out[o + 1] = ((255 - m) * (255 - k) / 255).toByte()
                        out[o + 2] = ((255 - yy) * (255 - k) / 255).toByte()
                    }
                }
            }
            sink(dy * bandH, rows, out)
        }
    }

    private fun to8(v: Int, max: Int): Int = when (max) {
        255 -> v
        65535 -> (v * 255 + 32767) / 65535
        else -> if (v != 0) 255 else 0
    }

    private fun decodeChunk(offset: Long, count: Long?, expected: Int, rowBytes: Int, sppChunk: Int, bps: Int): ByteArray {
        val len = (count ?: expected.toLong()).coerceAtMost(src.size - offset).toInt().coerceAtLeast(0)
        val raw = ByteArray(len)
        src.read(offset, raw)
        val data = when (compression) {
            1 -> raw
            5 -> lzwDecode(raw, expected)
            8, 32946 -> inflate(raw, expected)
            32773 -> unpackBits(raw, expected)
            else -> throw TiffException("unsupported compression")
        }
        if (predictor == 2) undoPredictor(data, rowBytes, sppChunk, bps)
        return data
    }

    private fun undoPredictor(d: ByteArray, rowBytes: Int, stride: Int, bps: Int) {
        var row = 0
        while (row + rowBytes <= d.size) {
            if (bps == 1) {
                for (i in row + stride until row + rowBytes) d[i] = (d[i] + d[i - stride]).toByte()
            } else {
                val n = rowBytes / 2
                for (i in stride until n) {
                    val p = row + i * 2; val q = row + (i - stride) * 2
                    val cur = if (little) d[p].u() or (d[p + 1].u() shl 8) else (d[p].u() shl 8) or d[p + 1].u()
                    val prev = if (little) d[q].u() or (d[q + 1].u() shl 8) else (d[q].u() shl 8) or d[q + 1].u()
                    val v = (cur + prev) and 0xFFFF
                    if (little) { d[p] = v.toByte(); d[p + 1] = (v shr 8).toByte() }
                    else { d[p] = (v shr 8).toByte(); d[p + 1] = v.toByte() }
                }
            }
            row += rowBytes
        }
    }

    // ---- IFD parsing
    private fun readIfd(pos: Long, into: MutableMap<Int, Entry>) {
        val cnt = ByteArray(2); src.read(pos, cnt)
        val n = u16(cnt, 0)
        val buf = ByteArray(n * 12); src.read(pos + 2, buf)
        for (i in 0 until n) {
            val e = i * 12
            val tag = u16(buf, e)
            val type = u16(buf, e + 2)
            val count = u32(buf, e + 4)
            val size = TYPE_SIZE.getOrElse(type) { 0 } * count
            if (size <= 0 || size > 64L * 1024 * 1024) continue
            val raw = if (size <= 4) buf.copyOfRange(e + 8, e + 8 + size.toInt())
            else ByteArray(size.toInt()).also {
                val off = u32(buf, e + 8)
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

    companion object {
        const val TAG_EXIF_IFD = 34665
        private val TYPE_SIZE = intArrayOf(0, 1, 1, 2, 4, 8, 1, 1, 2, 4, 8, 4, 8, 4)

        fun lzwDecode(input: ByteArray, expected: Int): ByteArray {
            val out = ByteArrayOutputStream(expected)
            val prefix = IntArray(4096); val suffix = ByteArray(4096); val length = IntArray(4096)
            for (i in 0 until 256) { suffix[i] = i.toByte(); length[i] = 1; prefix[i] = -1 }
            val stack = ByteArray(4096)
            var next = 258; var width = 9; var old = -1
            var bitBuf = 0L; var bitCnt = 0; var pos = 0
            fun emit(code: Int): Byte {
                var c = code; var sp = 0
                while (c >= 0) { stack[sp++] = suffix[c]; c = prefix[c] }
                val first = stack[sp - 1]
                while (sp > 0) out.write(stack[--sp].toInt())
                return first
            }
            while (true) {
                while (bitCnt < width) {
                    if (pos >= input.size) return out.toByteArray()
                    bitBuf = (bitBuf shl 8) or input[pos++].u().toLong(); bitCnt += 8
                }
                val code = ((bitBuf shr (bitCnt - width)) and ((1L shl width) - 1)).toInt()
                bitCnt -= width
                if (code == 257) break
                if (code == 256) {
                    next = 258; width = 9; old = -1; continue
                }
                if (old == -1) {
                    if (code > 255) break
                    emit(code); old = code
                } else {
                    val first: Byte
                    if (code < next) {
                        first = emit(code)
                    } else {
                        // KwKwK case: old string + its own first byte
                        var c = old; while (prefix[c] >= 0) c = prefix[c]
                        first = suffix[c]
                        emit(old); out.write(first.toInt())
                    }
                    if (next < 4096) {
                        prefix[next] = old; suffix[next] = first; length[next] = length[old] + 1; next++
                    }
                    old = code
                }
                if (next + 1 >= (1 shl width) && width < 12) width++
                if (out.size() >= expected) break
            }
            return out.toByteArray()
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
