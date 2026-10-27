package com.barion.filmscans.core

import java.io.InputStream

/** Where a decoder gets its bytes, one at a time: 0 to 255, or -1 at the end. */
fun interface ByteFeed { fun next(): Int }

/**
 * Inflates Deflate64 ("enhanced deflate", zip method 9), which Windows uses for big files in
 * its own zip folders: deflate with a 64 KB window, longer matches and two more distance codes.
 * With [deflate64] false it inflates ordinary deflate. It reads only the bytes the compressed
 * data takes, so in a zip read start to finish the next file's header is left where it was.
 */
class Inflate64(private val feed: ByteFeed, private val deflate64: Boolean = true) : InputStream() {
    private val window = ByteArray(1 shl 16)
    private var wpos = 0
    private var written = 0L
    private var bitBuf = 0L
    private var bitCount = 0
    private var inBlock = false
    private var last = false
    private var stored = false
    private var storedLeft = 0
    private var copyLeft = 0
    private var copyDist = 0
    private var lens = FIXED_LENS
    private var dists = FIXED_DISTS

    /** True once the last block has been read and everything in it handed out. */
    val finished get() = last && !inBlock && copyLeft == 0

    class Huffman(val count: IntArray, val symbol: IntArray)

    private fun bits(n: Int): Int {
        while (bitCount < n) {
            val b = feed.next()
            if (b < 0) throw ZipFormatException("compressed data ends early")
            bitBuf = bitBuf or (b.toLong() shl bitCount)
            bitCount += 8
        }
        val v = (bitBuf and ((1L shl n) - 1)).toInt()
        bitBuf = bitBuf ushr n
        bitCount -= n
        return v
    }

    private fun decode(h: Huffman): Int {
        var code = 0; var first = 0; var index = 0
        for (len in 1..15) {
            code = code or bits(1)
            val c = h.count[len]
            if (code - c < first) return h.symbol[index + (code - first)]
            index += c; first += c
            first = first shl 1; code = code shl 1
        }
        throw ZipFormatException("damaged compressed data")
    }

    private fun put(b: Byte) {
        window[wpos] = b
        wpos = (wpos + 1) and 0xFFFF
        written++
    }

    private fun startBlock() {
        last = bits(1) == 1
        when (bits(2)) {
            0 -> {
                // Stored: whatever is left of the current byte is padding.
                bitBuf = 0; bitCount = 0
                val len = byte() or (byte() shl 8)
                val nlen = byte() or (byte() shl 8)
                if (len != nlen.inv() and 0xFFFF) throw ZipFormatException("damaged compressed data")
                stored = true; storedLeft = len
            }
            1 -> { stored = false; lens = FIXED_LENS; dists = FIXED_DISTS }
            2 -> { stored = false; dynamic() }
            else -> throw ZipFormatException("damaged compressed data")
        }
        inBlock = true
    }

    private fun byte(): Int = feed.next().also { if (it < 0) throw ZipFormatException("compressed data ends early") }

    private fun dynamic() {
        val nlen = bits(5) + 257
        val ndist = bits(5) + 1
        val ncode = bits(4) + 4
        if (nlen > 286 || ndist > (if (deflate64) 32 else 30)) throw ZipFormatException("damaged compressed data")
        val lengths = IntArray(320)
        for (i in 0 until ncode) lengths[ORDER[i]] = bits(3)
        val lencode = build(lengths, 19)
        val all = IntArray(nlen + ndist)
        var i = 0
        while (i < nlen + ndist) {
            val sym = decode(lencode)
            if (sym < 16) { all[i++] = sym; continue }
            if (sym == 16 && i == 0) throw ZipFormatException("damaged compressed data")
            val value = if (sym == 16) all[i - 1] else 0
            val rep = when (sym) { 16 -> 3 + bits(2); 17 -> 3 + bits(3); else -> 11 + bits(7) }
            if (i + rep > nlen + ndist) throw ZipFormatException("damaged compressed data")
            repeat(rep) { all[i++] = value }
        }
        if (all[256] == 0) throw ZipFormatException("damaged compressed data")
        lens = build(all.copyOfRange(0, nlen), nlen)
        dists = build(all.copyOfRange(nlen, nlen + ndist), ndist)
    }

    override fun read(): Int {
        val b = ByteArray(1)
        return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF
    }

    override fun read(b: ByteArray, off: Int, len: Int): Int {
        if (len == 0) return 0
        var n = 0
        while (n < len) {
            if (copyLeft > 0) {
                val c = window[(wpos - copyDist) and 0xFFFF]
                put(c); b[off + n++] = c; copyLeft--
                continue
            }
            if (!inBlock) { if (last) break; startBlock(); continue }
            if (stored) {
                if (storedLeft == 0) { inBlock = false; continue }
                val c = byte().toByte()
                put(c); b[off + n++] = c; storedLeft--
                continue
            }
            val sym = decode(lens)
            when {
                sym < 256 -> { val c = sym.toByte(); put(c); b[off + n++] = c }
                sym == 256 -> inBlock = false
                else -> {
                    val s = sym - 257
                    if (s >= 29) throw ZipFormatException("damaged compressed data")
                    val length = if (deflate64 && s == 28) 3 + bits(16) else LBASE[s] + bits(LEXT[s])
                    val d = decode(dists)
                    if (d >= (if (deflate64) 32 else 30)) throw ZipFormatException("damaged compressed data")
                    val dist = DBASE[d] + bits(DEXT[d])
                    if (dist > written || dist > (if (deflate64) 65536 else 32768)) throw ZipFormatException("damaged compressed data")
                    copyLeft = length; copyDist = dist
                }
            }
        }
        return if (n == 0) -1 else n
    }

    companion object {
        private val ORDER = intArrayOf(16, 17, 18, 0, 8, 7, 9, 6, 10, 5, 11, 4, 12, 3, 13, 2, 14, 1, 15)
        private val LBASE = intArrayOf(3, 4, 5, 6, 7, 8, 9, 10, 11, 13, 15, 17, 19, 23, 27, 31, 35, 43, 51, 59, 67, 83, 99, 115, 131, 163, 195, 227, 258)
        private val LEXT = intArrayOf(0, 0, 0, 0, 0, 0, 0, 0, 1, 1, 1, 1, 2, 2, 2, 2, 3, 3, 3, 3, 4, 4, 4, 4, 5, 5, 5, 5, 0)
        private val DBASE = intArrayOf(1, 2, 3, 4, 5, 7, 9, 13, 17, 25, 33, 49, 65, 97, 129, 193, 257, 385, 513, 769, 1025, 1537,
            2049, 3073, 4097, 6145, 8193, 12289, 16385, 24577, 32769, 49153)
        private val DEXT = intArrayOf(0, 0, 0, 0, 1, 1, 2, 2, 3, 3, 4, 4, 5, 5, 6, 6, 7, 7, 8, 8, 9, 9, 10, 10, 11, 11, 12, 12, 13, 13, 14, 14)

        /** Canonical Huffman codes from code lengths. Incomplete codes are allowed, as zlib allows them. */
        private fun build(lengths: IntArray, n: Int): Huffman {
            val count = IntArray(16)
            for (i in 0 until n) count[lengths[i]]++
            count[0] = 0
            val offs = IntArray(16)
            for (len in 1 until 15) offs[len + 1] = offs[len] + count[len]
            val symbol = IntArray(n)
            for (i in 0 until n) if (lengths[i] != 0) symbol[offs[lengths[i]]++] = i
            return Huffman(count, symbol)
        }

        private val FIXED_LENS = build(IntArray(288) { when { it < 144 -> 8; it < 256 -> 9; it < 280 -> 7; else -> 8 } }, 288)
        private val FIXED_DISTS = build(IntArray(32) { 5 }, 32)
    }
}
