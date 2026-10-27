package com.barion.filmscans.core

import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.io.PushbackInputStream
import java.time.ZoneId
import java.util.zip.Inflater

/**
 * Walks every file in a zip, including files inside zips inside it. An inner zip is read
 * straight out of the outer stream and its files are reported under a folder named after
 * it ("Order.zip" holding "Roll 1.zip" gives "Roll 1/scan001.tif"), so nothing is unzipped twice.
 * Reads stored, deflate and Deflate64 files, and password-protected ones given [password].
 */
object ZipWalk {
    private const val MAX_DEPTH = Archive.MAX_DEPTH
    private const val LOC = 0x04034b50L
    private const val DESCRIPTOR = 0x08074b50L
    /** What can follow the last file: the directory, its end record, or the rarer records around them. */
    private val ENDS = setOf(0x02014b50L, 0x06054b50L, 0x06064b50L, 0x07064b50L, 0x08064b50L, 0x05054b50L)

    /** Lets an inner zip be read without closing the outer one. */
    private class NoClose(input: InputStream) : FilterInputStream(input) { override fun close() {} }

    /**
     * Calls [visit] with (path, data, time) for each file. [data] must be read before returning.
     * [time] is the file's date in the zip, in epoch millis (or -1).
     */
    fun walk(input: InputStream, password: String? = null, visit: (String, InputStream, Long) -> Unit) =
        walk(Reader(input), password, visit, "", 0)

    private fun walk(r: Reader, password: String?, visit: (String, InputStream, Long) -> Unit, prefix: String, depth: Int) {
        var first = true
        while (true) {
            val sig = r.u32OrEnd() ?: break
            if (sig != LOC) {
                if (sig in ENDS) break
                throw ZipFormatException(if (first) "not a zip file" else "damaged zip")
            }
            first = false
            entry(r, password) { item, data ->
                if (item.isDirectory) return@entry
                val path = Archive.safePath(item.name) ?: return@entry
                val time = item.time?.atZone(ZoneId.systemDefault())?.toInstant()?.toEpochMilli() ?: -1L
                if (Archive.isZip(path) && depth < MAX_DEPTH) {
                    // A ".zip" that turns out not to be one is unpacked as an ordinary file.
                    val peek = PushbackInputStream(data, 4)
                    val head = ByteArray(4)
                    var n = 0
                    while (n < 4) { val k = peek.read(head, n, 4 - n); if (k < 0) break; n += k }
                    peek.unread(head, 0, n)
                    val isZip = n == 4 && (head[0].toInt() and 0xFF) == 0x50 && head[1].toInt() == 0x4B && head[2].toInt() == 3 && head[3].toInt() == 4
                    if (isZip) walk(Reader(NoClose(peek)), password, visit, prefix + path.dropLast(4) + "/", depth + 1)
                    else visit(prefix + path, peek, time)
                } else visit(prefix + path, data, time)
            }
        }
    }

    /** Reads one file's header and hands its contents to [use]; afterwards the reader is at the next header. */
    private fun entry(r: Reader, password: String?, use: (ZipItem, InputStream) -> Unit) {
        r.u16() // version needed
        val flags = r.u16()
        val method = r.u16()
        val rawTime = r.u16()
        val date = r.u16()
        val crc = r.u32()
        var csize = r.u32()
        var usize = r.u32()
        val nameLen = r.u16()
        val extraLen = r.u16()
        val rawName = r.bytes(nameLen)
        val extra = r.bytes(extraLen)
        var zip64 = false
        var aes = 0
        var realMethod = method
        var e = 0
        while (e + 4 <= extra.size) {
            val id = le(extra, e, 2).toInt(); val len = le(extra, e + 2, 2).toInt()
            val d = e + 4
            if (d + len > extra.size) break
            if (id == 0x0001 && len >= 16) { zip64 = true; usize = le(extra, d, 8); csize = le(extra, d + 8, 8) }
            else if (id == 0x9901 && len >= 7) { aes = extra[d + 4].toInt() and 0xFF; realMethod = le(extra, d + 5, 2).toInt() }
            e = d + len
        }
        val name = ZipIndex.decodeName(rawName, flags).replace('\\', '/')
        val item = ZipItem(name, method, flags, crc, csize, usize, 0, ZipIndex.dosTime(date, rawTime), name.endsWith("/"),
            rawTime, aes, realMethod)
        item.unreadable?.let { throw ZipFormatException("can't unzip ${name.substringAfterLast('/')}: $it") }
        if (item.encrypted && password == null) throw PasswordNeeded(name)
        val descriptor = flags and 8 != 0

        if (!descriptor || csize > 0) {
            // The size is known: read exactly that much, whatever the data inside does.
            val raw = Bounded(r, csize)
            val dec = if (item.encrypted) ZipCrypt.open(raw, item, password!!) else raw
            val data = ZipIndex.decompress(dec, realMethod)
            use(item, NoClose(data))
            drain(data); drain(dec); drain(raw)
        } else {
            // No size until after the data: only compressed data, which marks its own end, can be read like this.
            when {
                realMethod == 8 && !item.encrypted -> {
                    val data = NativeInflate(r)
                    use(item, NoClose(data))
                    drain(data)
                }
                realMethod == 8 || realMethod == 9 -> {
                    val plain = ByteFeed { r.byte() }
                    val (feed, finish) = if (item.encrypted) ZipCrypt.feed(plain, item, password!!) else plain to {}
                    val data = Inflate64(feed, deflate64 = realMethod == 9)
                    use(item, NoClose(data))
                    drain(data)
                    finish()
                }
                else -> throw ZipFormatException("can't read ${name.substringAfterLast('/')} start to finish: its size is only given after it")
            }
        }
        if (descriptor) {
            // Optional signature, then the check value and the two sizes.
            val sig = r.u32()
            if (sig != DESCRIPTOR) { /* that was the check value */ } else r.u32()
            r.skip(if (zip64) 16 else 8)
        }
    }

    private fun drain(s: InputStream) { val buf = ByteArray(1 shl 16); while (s.read(buf) >= 0) Unit }

    private fun le(b: ByteArray, i: Int, n: Int): Long { var v = 0L; for (k in n - 1 downTo 0) v = (v shl 8) or (b[i + k].toLong() and 0xFF); return v }

    /** A zip read start to finish through its own buffer, so a decoder can hand back what it didn't use. */
    private class Reader(private val src: InputStream) {
        val buf = ByteArray(1 shl 16)
        var pos = 0
        var lim = 0
        fun fill(): Boolean {
            if (pos < lim) return true
            var n: Int
            do { n = src.read(buf, 0, buf.size) } while (n == 0)
            pos = 0; lim = maxOf(n, 0)
            return n > 0
        }
        fun byte(): Int = if (fill()) buf[pos++].toInt() and 0xFF else -1
        fun need(): Int = byte().also { if (it < 0) throw EOFException("zip is truncated") }
        fun u16() = need() or (need() shl 8)
        fun u32(): Long = u16().toLong() or (u16().toLong() shl 16)
        fun u32OrEnd(): Long? { if (!fill()) return null; return u32() }
        fun bytes(n: Int) = ByteArray(n) { need().toByte() }
        fun skip(n: Long) {
            var left = n
            while (left > 0) {
                if (!fill()) throw EOFException("zip is truncated")
                val k = minOf(left, (lim - pos).toLong()).toInt()
                pos += k; left -= k
            }
        }
    }

    /** Exactly [length] bytes from the reader. */
    private class Bounded(private val r: Reader, private var left: Long) : InputStream() {
        override fun read(): Int { if (left == 0L) return -1; left--; return r.need() }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            if (left == 0L) return -1
            if (!r.fill()) throw EOFException("zip is truncated")
            val n = minOf(len.toLong(), left, (r.lim - r.pos).toLong()).toInt()
            System.arraycopy(r.buf, r.pos, b, off, n)
            r.pos += n; left -= n
            return n
        }
    }

    /** The phone's own (fast) inflater, given the reader's buffer; what it doesn't use goes back. */
    private class NativeInflate(private val r: Reader) : InputStream() {
        private val inf = Inflater(true)
        private var done = false
        override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
        override fun read(b: ByteArray, off: Int, len: Int): Int {
            if (len == 0) return 0
            while (true) {
                if (done) return -1
                if (inf.finished()) {
                    r.pos = r.lim - inf.remaining
                    inf.end(); done = true
                    return -1
                }
                if (inf.needsInput()) {
                    if (!r.fill()) throw EOFException("zip is truncated")
                    inf.setInput(r.buf, r.pos, r.lim - r.pos)
                    r.pos = r.lim
                }
                val n = try { inf.inflate(b, off, len) } catch (e: java.util.zip.DataFormatException) { throw ZipFormatException("damaged compressed data") }
                if (n > 0) return n
                if (inf.needsDictionary()) throw ZipFormatException("damaged compressed data")
            }
        }
    }
}
