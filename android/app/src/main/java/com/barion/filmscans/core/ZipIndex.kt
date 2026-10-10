package com.barion.filmscans.core

import java.io.IOException
import java.io.InputStream
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.Inflater
import java.util.zip.InflaterInputStream

class ZipFormatException(msg: String) : IOException(msg)

/** A byte range of another source, read in place: how a zip stored inside a zip is opened without copying it. */
class SubSource(private val parent: ByteSource, private val start: Long, override val size: Long) : ByteSource {
    init { require(start >= 0 && size >= 0 && start + size <= parent.size) { "range outside the file" } }
    override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
        if (pos < 0 || pos + len > size) throw java.io.EOFException("read past the end")
        parent.read(start + pos, buf, off, len)
    }
}

/** One file in a zip's central directory. [time] is the date as stored, in the zipper's local time. */
class ZipItem(
    val name: String,
    val method: Int,
    val flags: Int,
    val crc: Long,
    val compressedSize: Long,
    val size: Long,
    val localHeaderOffset: Long,
    val time: LocalDateTime?,
    val isDirectory: Boolean,
) {
    val encrypted get() = flags and 1 != 0
    val stored get() = method == 0
    /** Why this entry can't be read, or null if it can. */
    val unreadable: String? get() = when {
        encrypted -> "it's password-protected"
        method != 0 && method != 8 -> "it uses a compression type this app can't read (method $method)"
        else -> null
    }
}

/**
 * Reads a zip from its central directory, which is at the end of the file, so listing
 * everything takes a few small reads however big the zip is. Handles ZIP64 (over 4 GB or
 * 65,535 files), UTF-8 and old DOS (CP437) file names, and data prepended to the zip.
 */
object ZipIndex {
    private const val EOCD = 0x06054b50L
    private const val EOCD64 = 0x06064b50L
    private const val EOCD64_LOCATOR = 0x07064b50L
    private const val CEN = 0x02014b50L
    private const val LOC = 0x04034b50L
    private const val MAX_DIRECTORY = 512L * 1024 * 1024

    fun read(src: ByteSource): List<ZipItem> {
        val (eocdPos, eocd) = findEocd(src)
        var count = u16(eocd, 10).toLong()
        var cdSize = u32(eocd, 12)
        var cdOffset = u32(eocd, 16)
        var cdEnd = eocdPos
        // ZIP64: the real values are in a second record, found through a locator just before.
        if (eocdPos >= 20 && (count == 0xFFFFL || cdSize == 0xFFFFFFFFL || cdOffset == 0xFFFFFFFFL || hasLocator(src, eocdPos))) {
            val loc = bytes(src, eocdPos - 20, 20)
            if (u32(loc, 0) == EOCD64_LOCATOR) {
                var recPos = u64(loc, 8)
                // A zip with data in front of it: the stored offset is short by that much.
                if (recPos + 56 > src.size || u32(bytes(src, recPos, 4), 0) != EOCD64) recPos = eocdPos - 20 - 56
                val rec = bytes(src, recPos, 56)
                if (u32(rec, 0) != EOCD64) throw ZipFormatException("damaged ZIP64 directory")
                count = u64(rec, 32); cdSize = u64(rec, 40); cdOffset = u64(rec, 48)
                cdEnd = recPos
            }
        }
        // Data in front of the zip (a self-extractor, or a zip inside something else) shifts every offset.
        val shift = cdEnd - (cdOffset + cdSize)
        if (shift < 0) throw ZipFormatException("damaged zip directory")
        if (cdSize > MAX_DIRECTORY) throw ZipFormatException("zip directory is too big")
        val cd = bytes(src, cdOffset + shift, cdSize.toInt())
        val out = ArrayList<ZipItem>(count.coerceAtMost(100_000).toInt())
        var p = 0
        while (p + 46 <= cd.size && out.size < count) {
            if (u32(cd, p) != CEN) throw ZipFormatException("damaged zip directory")
            val flags = u16(cd, p + 8)
            val method = u16(cd, p + 10)
            val time = dosTime(u16(cd, p + 14), u16(cd, p + 12))
            val crc = u32(cd, p + 16)
            var csize = u32(cd, p + 20)
            var usize = u32(cd, p + 24)
            val nameLen = u16(cd, p + 28); val extraLen = u16(cd, p + 30); val commentLen = u16(cd, p + 32)
            val extAttr = u32(cd, p + 38)
            var offset = u32(cd, p + 42)
            val nameStart = p + 46
            val extraStart = nameStart + nameLen
            if (extraStart + extraLen + commentLen > cd.size) throw ZipFormatException("damaged zip directory")
            val rawName = cd.copyOfRange(nameStart, extraStart)
            var name = decodeName(rawName, flags)
            // Extra fields: ZIP64 sizes, and the Info-ZIP Unicode name.
            var e = extraStart
            while (e + 4 <= extraStart + extraLen) {
                val id = u16(cd, e); val len = u16(cd, e + 2)
                val d = e + 4
                if (d + len > extraStart + extraLen) break
                if (id == 0x0001) {
                    var q = d
                    if (usize == 0xFFFFFFFFL && q + 8 <= d + len) { usize = u64(cd, q); q += 8 }
                    if (csize == 0xFFFFFFFFL && q + 8 <= d + len) { csize = u64(cd, q); q += 8 }
                    if (offset == 0xFFFFFFFFL && q + 8 <= d + len) { offset = u64(cd, q) }
                } else if (id == 0x7075 && len > 5 && cd[d].toInt() == 1) {
                    val c = CRC32().also { it.update(rawName) }.value
                    if (c == u32(cd, d + 1)) name = String(cd, d + 5, len - 5, Charsets.UTF_8)
                }
                e = d + len
            }
            name = name.replace('\\', '/')
            val isDir = name.endsWith("/") || (extAttr and 0x10L != 0L && usize == 0L)
            out += ZipItem(name, method, flags, crc, csize, usize, offset + shift, time, isDir)
            p = extraStart + extraLen + commentLen
        }
        return out
    }

    /** Where an entry's data starts: after its local header, whose name and extra lengths can differ from the directory's. */
    fun dataStart(src: ByteSource, item: ZipItem): Long {
        val h = bytes(src, item.localHeaderOffset, 30)
        if (u32(h, 0) != LOC) throw ZipFormatException("damaged zip: ${item.name}")
        return item.localHeaderOffset + 30 + u16(h, 26) + u16(h, 28)
    }

    /** A stored (uncompressed) entry read in place, with no copying. Null if it's compressed. */
    fun storedSource(src: ByteSource, item: ZipItem): ByteSource? {
        if (!item.stored || item.encrypted) return null
        return SubSource(src, dataStart(src, item), item.size)
    }

    /** The entry's contents as a stream. Stored entries are read in place; deflated ones are inflated as read. */
    fun open(src: ByteSource, item: ZipItem): InputStream {
        item.unreadable?.let { throw ZipFormatException("can't unzip ${item.name.substringAfterLast('/')}: $it") }
        val start = dataStart(src, item)
        val raw = SourceStream(src, start, if (item.stored) item.size else item.compressedSize)
        return if (item.stored) raw else Inflating(raw)
    }

    /** The first [limit] bytes of an entry: enough to read a scan's dates without unpacking all of it. */
    fun head(src: ByteSource, item: ZipItem, limit: Int): ByteArray {
        val n = minOf(item.size, limit.toLong()).toInt()
        val out = ByteArray(n)
        open(src, item).use { s ->
            var got = 0
            while (got < n) { val r = s.read(out, got, n - got); if (r < 0) break; got += r }
            return if (got == n) out else out.copyOf(got)
        }
    }

    private class Inflating(input: InputStream) : InflaterInputStream(input, Inflater(true), 1 shl 16) {
        override fun close() { super.close(); inf.end() }
    }

    /** Sequential reads of a byte range, a block at a time. */
    class SourceStream(private val src: ByteSource, start: Long, length: Long) : InputStream() {
        private var pos = start
        private val end = start + length
        private val buf = ByteArray(1 shl 16)
        private var bufStart = 0L
        private var bufLen = 0
        init { if (end > src.size) throw ZipFormatException("zip is truncated") }
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (pos >= end) return -1
            if (pos < bufStart || pos >= bufStart + bufLen) {
                bufStart = pos
                bufLen = minOf(buf.size.toLong(), end - pos).toInt()
                src.read(pos, buf, 0, bufLen)
            }
            val n = minOf(len.toLong(), bufStart + bufLen - pos).toInt()
            System.arraycopy(buf, (pos - bufStart).toInt(), b, off, n)
            pos += n
            return n
        }
        override fun skip(n: Long): Long { val k = minOf(n, end - pos).coerceAtLeast(0); pos += k; return k }
        override fun available(): Int = minOf(end - pos, Int.MAX_VALUE.toLong()).toInt()
    }

    // ---- end of central directory

    private fun findEocd(src: ByteSource): Pair<Long, ByteArray> {
        if (src.size < 22) throw ZipFormatException("not a zip file")
        val window = minOf(src.size, 22L + 65535).toInt()
        val start = src.size - window
        val tail = bytes(src, start, window)
        // Last record whose comment length reaches exactly to the end of the file; failing that, the last one.
        var fallback = -1
        for (i in window - 22 downTo 0) {
            if (u32(tail, i) != EOCD) continue
            if (i + 22 + u16(tail, i + 20) == window) return (start + i) to tail.copyOfRange(i, i + 22)
            if (fallback < 0) fallback = i
        }
        if (fallback >= 0) return (start + fallback) to tail.copyOfRange(fallback, fallback + 22)
        throw ZipFormatException("not a zip file")
    }

    private fun hasLocator(src: ByteSource, eocdPos: Long) = u32(bytes(src, eocdPos - 20, 4), 0) == EOCD64_LOCATOR

    // ---- names and dates

    private fun decodeName(raw: ByteArray, flags: Int): String {
        if (flags and 0x800 != 0) return String(raw, Charsets.UTF_8)
        if (raw.all { it >= 0 }) return String(raw, Charsets.US_ASCII)
        // Many zippers write UTF-8 without saying so; anything that isn't valid UTF-8 is old DOS code page 437.
        val dec = Charsets.UTF_8.newDecoder()
            .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
        return runCatching { dec.decode(java.nio.ByteBuffer.wrap(raw)).toString() }.getOrElse {
            buildString { raw.forEach { b -> val u = b.toInt() and 0xFF; append(if (u < 0x80) u.toChar() else CP437[u - 0x80]) } }
        }
    }

    private const val CP437 =
        "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀" +
            "αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■ "

    /** A DOS date and time as written, or null when it's blank or impossible. */
    fun dosTime(date: Int, time: Int): LocalDateTime? = runCatching {
        LocalDateTime.of(1980 + (date shr 9), (date shr 5) and 15, date and 31, time shr 11, (time shr 5) and 63, ((time and 31) * 2).coerceAtMost(59))
    }.getOrNull()

    // ---- little-endian helpers

    private fun bytes(src: ByteSource, pos: Long, n: Int): ByteArray {
        if (pos < 0 || pos + n > src.size) throw ZipFormatException("zip is truncated")
        return ByteArray(n).also { src.read(pos, it, 0, n) }
    }
    private fun u16(b: ByteArray, i: Int) = (b[i].toInt() and 0xFF) or ((b[i + 1].toInt() and 0xFF) shl 8)
    private fun u32(b: ByteArray, i: Int): Long = u16(b, i).toLong() or (u16(b, i + 2).toLong() shl 16)
    private fun u64(b: ByteArray, i: Int): Long = u32(b, i) or (u32(b, i + 4) shl 32)
}
