package com.barion.filmscans.core

import java.io.OutputStream

/**
 * Baseline JPEG encoder that takes rows as they are read, so memory use stays at
 * eight rows no matter how big the scan is. Colour is always 4:4:4 (no chroma
 * subsampling, unlike Android's own encoder), and gray scans stay one-channel.
 *
 * Usage: [writeHeader] with any metadata segments, [writeRows] until every row
 * has been given, then [finish].
 */
class JpegEncoder(
    private val out: OutputStream,
    private val width: Int,
    private val height: Int,
    private val channels: Int, // 1 = gray, 3 = RGB
    quality: Int = 100,
) {
    private val yTable = IntArray(64)
    private val cTable = IntArray(64)
    private val fdtblY = FloatArray(64)
    private val fdtblC = FloatArray(64)
    private val ydcHT = huffTable(DC_LUM_BITS, DC_LUM_VALS)
    private val yacHT = huffTable(AC_LUM_BITS, AC_LUM_VALS)
    private val cdcHT = huffTable(DC_CHR_BITS, DC_CHR_VALS)
    private val cacHT = huffTable(AC_CHR_BITS, AC_CHR_VALS)

    private val stripe = ByteArray(8 * width * channels)
    private var stripeRows = 0
    private var rowsDone = 0

    private var bitBuf = 0
    private var bitCnt = 0
    private val obuf = ByteArray(1 shl 16)
    private var olen = 0

    private var dcY = 0; private var dcCb = 0; private var dcCr = 0
    private val blkY = FloatArray(64); private val blkCb = FloatArray(64); private val blkCr = FloatArray(64)
    private val du = IntArray(64)

    init {
        require(channels == 1 || channels == 3)
        val q = quality.coerceIn(1, 100)
        val scale = if (q < 50) 5000 / q else 200 - q * 2
        for (i in 0 until 64) {
            yTable[ZIGZAG[i]] = ((Y_QT[i] * scale + 50) / 100).coerceIn(1, 255)
            cTable[ZIGZAG[i]] = ((C_QT[i] * scale + 50) / 100).coerceIn(1, 255)
        }
        var k = 0
        for (row in 0 until 8) for (col in 0 until 8) {
            fdtblY[k] = (1.0 / (yTable[ZIGZAG[k]] * AASF[row] * AASF[col] * 8.0)).toFloat()
            fdtblC[k] = (1.0 / (cTable[ZIGZAG[k]] * AASF[row] * AASF[col] * 8.0)).toFloat()
            k++
        }
    }

    /** SOI, then [segments] (complete APPn/COM segments, markers included), then the tables. */
    fun writeHeader(segments: List<ByteArray>) {
        out.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        segments.forEach { out.write(it) }
        // DQT
        val nt = if (channels == 3) 2 else 1
        putMarker(0xDB, 2 + nt * 65)
        out.write(0); yTable.forEach { out.write(it) }
        if (channels == 3) { out.write(1); cTable.forEach { out.write(it) } }
        // SOF0
        putMarker(0xC0, 8 + 3 * channels)
        out.write(8); put16(height); put16(width); out.write(channels)
        for (c in 0 until channels) { out.write(c + 1); out.write(0x11); out.write(if (c == 0) 0 else 1) }
        // DHT
        val tables = mutableListOf(0x00 to (DC_LUM_BITS to DC_LUM_VALS), 0x10 to (AC_LUM_BITS to AC_LUM_VALS))
        if (channels == 3) {
            tables += 0x01 to (DC_CHR_BITS to DC_CHR_VALS); tables += 0x11 to (AC_CHR_BITS to AC_CHR_VALS)
        }
        putMarker(0xC4, 2 + tables.sumOf { 17 + it.second.second.size })
        for ((id, t) in tables) {
            out.write(id)
            for (i in 1..16) out.write(t.first[i])
            t.second.forEach { out.write(it) }
        }
        // SOS
        putMarker(0xDA, 6 + 2 * channels)
        out.write(channels)
        for (c in 0 until channels) { out.write(c + 1); out.write(if (c == 0) 0x00 else 0x11) }
        out.write(0); out.write(63); out.write(0)
    }

    /** [rows] rows of width*channels bytes, top to bottom. */
    fun writeRows(pixels: ByteArray, rows: Int) {
        val rowLen = width * channels
        for (r in 0 until rows) {
            if (rowsDone >= height) return
            System.arraycopy(pixels, r * rowLen, stripe, stripeRows * rowLen, rowLen)
            stripeRows++; rowsDone++
            if (stripeRows == 8 || rowsDone == height) encodeStripe()
        }
    }

    fun finish() {
        if (bitCnt > 0) writeBits((1 shl (8 - bitCnt)) - 1, 8 - bitCnt) // pad the last byte with 1s
        flushOut()
        out.write(byteArrayOf(0xFF.toByte(), 0xD9.toByte()))
        out.flush()
    }

    private fun encodeStripe() {
        val rowLen = width * channels
        // Repeat the last real row to fill the stripe (edge padding).
        for (r in stripeRows until 8) System.arraycopy(stripe, (stripeRows - 1) * rowLen, stripe, r * rowLen, rowLen)
        var x = 0
        while (x < width) {
            var k = 0
            for (yy in 0 until 8) {
                val base = yy * rowLen
                for (xx in 0 until 8) {
                    val px = minOf(x + xx, width - 1)
                    if (channels == 1) {
                        blkY[k] = (stripe[base + px].u() - 128).toFloat()
                    } else {
                        val p = base + px * 3
                        val r = stripe[p].u().toFloat(); val g = stripe[p + 1].u().toFloat(); val b = stripe[p + 2].u().toFloat()
                        blkY[k] = 0.299f * r + 0.587f * g + 0.114f * b - 128f
                        blkCb[k] = -0.168736f * r - 0.331264f * g + 0.5f * b
                        blkCr[k] = 0.5f * r - 0.418688f * g - 0.081312f * b
                    }
                    k++
                }
            }
            dcY = processDU(blkY, fdtblY, dcY, ydcHT, yacHT)
            if (channels == 3) {
                dcCb = processDU(blkCb, fdtblC, dcCb, cdcHT, cacHT)
                dcCr = processDU(blkCr, fdtblC, dcCr, cdcHT, cacHT)
            }
            x += 8
        }
        stripeRows = 0
    }

    private fun processDU(data: FloatArray, fdtbl: FloatArray, dc: Int, htdc: Array<IntArray>, htac: Array<IntArray>): Int {
        fdct(data)
        for (i in 0 until 64) {
            val v = data[i] * fdtbl[i]
            du[ZIGZAG[i]] = if (v > 0f) (v + 0.5f).toInt() else (v - 0.5f).toInt()
        }
        val diff = du[0] - dc
        if (diff == 0) writeCode(htdc[0]) else {
            val cat = category(diff)
            writeCode(htdc[cat]); writeBits(bitsFor(diff, cat), cat)
        }
        var end0 = 63
        while (end0 > 0 && du[end0] == 0) end0--
        if (end0 == 0) { writeCode(htac[0x00]); return du[0] }
        var i = 1
        while (i <= end0) {
            val start = i
            while (du[i] == 0 && i <= end0) i++
            var zeros = i - start
            if (zeros >= 16) {
                repeat(zeros shr 4) { writeCode(htac[0xF0]) }
                zeros = zeros and 0xF
            }
            val cat = category(du[i])
            writeCode(htac[(zeros shl 4) + cat])
            writeBits(bitsFor(du[i], cat), cat)
            i++
        }
        if (end0 != 63) writeCode(htac[0x00])
        return du[0]
    }

    private fun category(v: Int): Int {
        var a = if (v < 0) -v else v
        var n = 0
        while (a != 0) { n++; a = a shr 1 }
        return n
    }

    private fun bitsFor(v: Int, cat: Int) = if (v >= 0) v else v + (1 shl cat) - 1

    private fun writeCode(c: IntArray) = writeBits(c[0], c[1])

    private fun writeBits(value: Int, len: Int) {
        var n = len - 1
        while (n >= 0) {
            bitBuf = (bitBuf shl 1) or ((value shr n) and 1)
            bitCnt++
            if (bitCnt == 8) {
                putByte(bitBuf and 0xFF)
                if (bitBuf and 0xFF == 0xFF) putByte(0)
                bitBuf = 0; bitCnt = 0
            }
            n--
        }
    }

    private fun putByte(b: Int) {
        if (olen == obuf.size) flushOut()
        obuf[olen++] = b.toByte()
    }

    private fun flushOut() { out.write(obuf, 0, olen); olen = 0 }

    private fun putMarker(m: Int, len: Int) { out.write(0xFF); out.write(m); put16(len) }
    private fun put16(v: Int) { out.write((v shr 8) and 0xFF); out.write(v and 0xFF) }

    /** AAN float forward DCT (IJG jfdctflt), in place, on an 8x8 block. */
    private fun fdct(d: FloatArray) {
        for (pass in 0 until 2) {
            for (u in 0 until 8) {
                val o = if (pass == 0) u * 8 else u
                val s = if (pass == 0) 1 else 8
                val d0 = d[o]; val d1 = d[o + s]; val d2 = d[o + 2 * s]; val d3 = d[o + 3 * s]
                val d4 = d[o + 4 * s]; val d5 = d[o + 5 * s]; val d6 = d[o + 6 * s]; val d7 = d[o + 7 * s]
                val t0 = d0 + d7; val t7 = d0 - d7; val t1 = d1 + d6; val t6 = d1 - d6
                val t2 = d2 + d5; val t5 = d2 - d5; val t3 = d3 + d4; val t4 = d3 - d4
                var t10 = t0 + t3; val t13 = t0 - t3; var t11 = t1 + t2; var t12 = t1 - t2
                d[o] = t10 + t11; d[o + 4 * s] = t10 - t11
                val z1 = (t12 + t13) * 0.707106781f
                d[o + 2 * s] = t13 + z1; d[o + 6 * s] = t13 - z1
                t10 = t4 + t5; t11 = t5 + t6; t12 = t6 + t7
                val z5 = (t10 - t12) * 0.382683433f
                val z2 = 0.541196100f * t10 + z5
                val z4 = 1.306562965f * t12 + z5
                val z3 = t11 * 0.707106781f
                val z11 = t7 + z3; val z13 = t7 - z3
                d[o + 5 * s] = z13 + z2; d[o + 3 * s] = z13 - z2
                d[o + s] = z11 + z4; d[o + 7 * s] = z11 - z4
            }
        }
    }

    companion object {
        /** natural index -> zigzag position */
        val ZIGZAG = intArrayOf(
            0, 1, 5, 6, 14, 15, 27, 28, 2, 4, 7, 13, 16, 26, 29, 42,
            3, 8, 12, 17, 25, 30, 41, 43, 9, 11, 18, 24, 31, 40, 44, 53,
            10, 19, 23, 32, 39, 45, 52, 54, 20, 22, 33, 38, 46, 51, 55, 60,
            21, 34, 37, 47, 50, 56, 59, 61, 35, 36, 48, 49, 57, 58, 62, 63)
        private val AASF = doubleArrayOf(1.0, 1.387039845, 1.306562965, 1.175875602, 1.0, 0.785694958, 0.541196100, 0.275899379)
        private val Y_QT = intArrayOf(
            16, 11, 10, 16, 24, 40, 51, 61, 12, 12, 14, 19, 26, 58, 60, 55,
            14, 13, 16, 24, 40, 57, 69, 56, 14, 17, 22, 29, 51, 87, 80, 62,
            18, 22, 37, 56, 68, 109, 103, 77, 24, 35, 55, 64, 81, 104, 113, 92,
            49, 64, 78, 87, 103, 121, 120, 101, 72, 92, 95, 98, 112, 100, 103, 99)
        private val C_QT = IntArray(64) { 99 }.also {
            val top = intArrayOf(17, 18, 24, 47, 18, 21, 26, 66, 24, 26, 56, 99, 47, 66, 99, 99)
            for (r in 0 until 4) for (c in 0 until 4) it[r * 8 + c] = top[r * 4 + c]
        }
        private val DC_LUM_BITS = intArrayOf(0, 0, 1, 5, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0, 0, 0)
        private val DC_LUM_VALS = IntArray(12) { it }
        private val DC_CHR_BITS = intArrayOf(0, 0, 3, 1, 1, 1, 1, 1, 1, 1, 1, 1, 0, 0, 0, 0, 0)
        private val DC_CHR_VALS = IntArray(12) { it }
        private val AC_LUM_BITS = intArrayOf(0, 0, 2, 1, 3, 3, 2, 4, 3, 5, 5, 4, 4, 0, 0, 1, 0x7d)
        private val AC_LUM_VALS = intArrayOf(
            0x01, 0x02, 0x03, 0x00, 0x04, 0x11, 0x05, 0x12, 0x21, 0x31, 0x41, 0x06, 0x13, 0x51, 0x61, 0x07,
            0x22, 0x71, 0x14, 0x32, 0x81, 0x91, 0xa1, 0x08, 0x23, 0x42, 0xb1, 0xc1, 0x15, 0x52, 0xd1, 0xf0,
            0x24, 0x33, 0x62, 0x72, 0x82, 0x09, 0x0a, 0x16, 0x17, 0x18, 0x19, 0x1a, 0x25, 0x26, 0x27, 0x28,
            0x29, 0x2a, 0x34, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48, 0x49,
            0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68, 0x69,
            0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x83, 0x84, 0x85, 0x86, 0x87, 0x88, 0x89,
            0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5, 0xa6, 0xa7,
            0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3, 0xc4, 0xc5,
            0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda, 0xe1, 0xe2,
            0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf1, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa)
        private val AC_CHR_BITS = intArrayOf(0, 0, 2, 1, 2, 4, 4, 3, 4, 7, 5, 4, 4, 0, 1, 2, 0x77)
        private val AC_CHR_VALS = intArrayOf(
            0x00, 0x01, 0x02, 0x03, 0x11, 0x04, 0x05, 0x21, 0x31, 0x06, 0x12, 0x41, 0x51, 0x07, 0x61, 0x71,
            0x13, 0x22, 0x32, 0x81, 0x08, 0x14, 0x42, 0x91, 0xa1, 0xb1, 0xc1, 0x09, 0x23, 0x33, 0x52, 0xf0,
            0x15, 0x62, 0x72, 0xd1, 0x0a, 0x16, 0x24, 0x34, 0xe1, 0x25, 0xf1, 0x17, 0x18, 0x19, 0x1a, 0x26,
            0x27, 0x28, 0x29, 0x2a, 0x35, 0x36, 0x37, 0x38, 0x39, 0x3a, 0x43, 0x44, 0x45, 0x46, 0x47, 0x48,
            0x49, 0x4a, 0x53, 0x54, 0x55, 0x56, 0x57, 0x58, 0x59, 0x5a, 0x63, 0x64, 0x65, 0x66, 0x67, 0x68,
            0x69, 0x6a, 0x73, 0x74, 0x75, 0x76, 0x77, 0x78, 0x79, 0x7a, 0x82, 0x83, 0x84, 0x85, 0x86, 0x87,
            0x88, 0x89, 0x8a, 0x92, 0x93, 0x94, 0x95, 0x96, 0x97, 0x98, 0x99, 0x9a, 0xa2, 0xa3, 0xa4, 0xa5,
            0xa6, 0xa7, 0xa8, 0xa9, 0xaa, 0xb2, 0xb3, 0xb4, 0xb5, 0xb6, 0xb7, 0xb8, 0xb9, 0xba, 0xc2, 0xc3,
            0xc4, 0xc5, 0xc6, 0xc7, 0xc8, 0xc9, 0xca, 0xd2, 0xd3, 0xd4, 0xd5, 0xd6, 0xd7, 0xd8, 0xd9, 0xda,
            0xe2, 0xe3, 0xe4, 0xe5, 0xe6, 0xe7, 0xe8, 0xe9, 0xea, 0xf2, 0xf3, 0xf4, 0xf5, 0xf6, 0xf7, 0xf8,
            0xf9, 0xfa)

        /** Code table indexed by symbol: [code, length]. */
        private fun huffTable(bits: IntArray, vals: IntArray): Array<IntArray> {
            val t = Array(256) { intArrayOf(0, 0) }
            var code = 0; var k = 0
            for (len in 1..16) {
                repeat(bits[len]) { t[vals[k++]] = intArrayOf(code, len); code++ }
                code = code shl 1
            }
            return t
        }
    }
}
