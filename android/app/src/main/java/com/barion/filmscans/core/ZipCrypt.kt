package com.barion.filmscans.core

import java.io.EOFException
import java.io.FilterInputStream
import java.io.InputStream
import java.security.MessageDigest
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

class PasswordNeeded(name: String) : ZipFormatException("${name.substringAfterLast('/')} is password-protected")
class WrongPassword(name: String) : ZipFormatException("wrong password for ${name.substringAfterLast('/')}")

/**
 * Password-protected zips: the old "ZipCrypto" kind that Windows and most zippers make, and
 * WinZip's AES (128, 192 or 256 bit), which 7-Zip and WinZip make when asked for AES.
 */
object ZipCrypt {
    private const val AUTH = 10

    /** The ways a password's letters may have been turned into bytes: UTF-8, then old DOS and Windows code pages. */
    private fun encodings(password: String): List<ByteArray> = listOfNotNull(
        password.toByteArray(Charsets.UTF_8),
        runCatching { password.toByteArray(charset("IBM437")) }.getOrNull(),
        password.toByteArray(Charsets.ISO_8859_1),
    ).distinctBy { it.contentHashCode() }

    fun saltLength(strength: Int) = 4 + strength * 4
    /** Bytes in front of the data (salt and check) and after it (the AES check code), for an entry of [item]. */
    fun overhead(item: ZipItem) = if (item.aesStrength > 0) saltLength(item.aesStrength) + 2 + AUTH else 12

    /**
     * The decrypted contents of [item], from [raw]: its encrypted bytes, exactly the entry's
     * compressed size. Throws [WrongPassword] when the password doesn't fit.
     */
    fun open(raw: InputStream, item: ZipItem, password: String): InputStream =
        if (item.aesStrength > 0) openAes(raw, item, password, item.compressedSize - overhead(item))
        else openZipCrypto(raw, item, password)

    /** For an entry whose size isn't known ahead (read start to finish): decrypts byte by byte. */
    fun feed(raw: ByteFeed, item: ZipItem, password: String): Pair<ByteFeed, () -> Unit> {
        if (item.aesStrength == 0) {
            val header = ByteArray(12) { needByte(raw).toByte() }
            val keys = zipCryptoKeys(header, item, password)
            return ByteFeed { val c = raw.next(); if (c < 0) -1 else keys.decrypt(c) } to {}
        }
        val salt = ByteArray(saltLength(item.aesStrength)) { needByte(raw).toByte() }
        val check = ByteArray(2) { needByte(raw).toByte() }
        val (ctr, mac) = aesKeys(salt, check, item, password)
        val one = ByteArray(1)
        val decrypted = ByteFeed {
            val c = raw.next()
            if (c < 0) -1 else { one[0] = c.toByte(); mac.update(one); ctr.apply(one, 0, 1); one[0].toInt() and 0xFF }
        }
        val finish = { checkAuth(mac, ByteArray(AUTH) { needByte(raw).toByte() }, item) }
        return decrypted to finish
    }

    private fun needByte(f: ByteFeed) = f.next().also { if (it < 0) throw EOFException("zip is truncated") }

    // ---- ZipCrypto

    private class Keys(password: ByteArray) {
        private var k0 = 0x12345678
        private var k1 = 0x23456789
        private var k2 = 0x34567890
        init { password.forEach { update(it.toInt() and 0xFF) } }
        private fun crc(old: Int, c: Int) = CRC_TABLE[(old xor c) and 0xFF] xor (old ushr 8)
        private fun update(c: Int) {
            k0 = crc(k0, c)
            k1 = (k1 + (k0 and 0xFF)) * 134775813 + 1
            k2 = crc(k2, k1 ushr 24)
        }
        fun decrypt(c: Int): Int {
            val t = (k2 or 2) and 0xFFFF
            val p = (c xor ((t * (t xor 1)) ushr 8)) and 0xFF
            update(p)
            return p
        }
    }

    private fun zipCryptoKeys(header: ByteArray, item: ZipItem, password: String): Keys {
        // The header's last byte must match the file's check value: its CRC, or its time when the CRC comes after the data.
        val expect = if (item.flags and 8 != 0) (item.dosTime ushr 8) and 0xFF else (item.crc ushr 24).toInt() and 0xFF
        for (pw in encodings(password)) {
            val keys = Keys(pw)
            var last = 0
            for (b in header) last = keys.decrypt(b.toInt() and 0xFF)
            if (last == expect) return keys
        }
        throw WrongPassword(item.name)
    }

    private fun openZipCrypto(raw: InputStream, item: ZipItem, password: String): InputStream {
        val header = ByteArray(12).also { readFully(raw, it) }
        val keys = zipCryptoKeys(header, item, password)
        return object : FilterInputStream(raw) {
            override fun read(): Int { val c = super.read(); return if (c < 0) -1 else keys.decrypt(c) }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                val n = super.read(b, off, len)
                for (i in off until off + maxOf(n, 0)) b[i] = keys.decrypt(b[i].toInt() and 0xFF).toByte()
                return n
            }
            override fun skip(n: Long): Long { var k = 0L; while (k < n && read() >= 0) k++; return k }
        }
    }

    // ---- WinZip AES

    /**
     * AES in counter mode as WinZip uses it: a little-endian counter starting at 1, which Java's own
     * counter mode (big-endian) can't do. Each counter block is encrypted on its own through CBC with
     * a zero start: feeding it the counter mixed with the previous output undoes the chaining, so
     * every output is plain AES of its counter.
     */
    class Ctr(key: ByteArray) {
        private val cipher = Cipher.getInstance("AES/CBC/NoPadding")
            .apply { init(Cipher.ENCRYPT_MODE, SecretKeySpec(key, "AES"), javax.crypto.spec.IvParameterSpec(ByteArray(16))) }
        private val counter = ByteArray(16)
        private val input = ByteArray(16)
        private val stream = ByteArray(16)
        private var used = 16
        fun apply(b: ByteArray, off: Int, len: Int) {
            for (i in off until off + len) {
                if (used == 16) {
                    var j = 0
                    while (j < 16) { counter[j]++; if (counter[j].toInt() != 0) break; j++ }
                    for (k in 0 until 16) input[k] = (counter[k].toInt() xor stream[k].toInt()).toByte()
                    cipher.update(input, 0, 16, stream, 0)
                    used = 0
                }
                b[i] = (b[i].toInt() xor stream[used++].toInt()).toByte()
            }
        }
    }

    private fun aesKeys(salt: ByteArray, check: ByteArray, item: ZipItem, password: String): Pair<Ctr, Mac> {
        val keyLen = 8 + item.aesStrength * 8
        for (pw in encodings(password)) {
            val derived = pbkdf2(pw, salt, 1000, keyLen * 2 + 2)
            if (derived[keyLen * 2] != check[0] || derived[keyLen * 2 + 1] != check[1]) continue
            val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(derived.copyOfRange(keyLen, keyLen * 2), "HmacSHA1")) }
            return Ctr(derived.copyOfRange(0, keyLen)) to mac
        }
        throw WrongPassword(item.name)
    }

    private fun checkAuth(mac: Mac, auth: ByteArray, item: ZipItem) {
        if (!MessageDigest.isEqual(mac.doFinal().copyOf(AUTH), auth))
            throw ZipFormatException("${item.name.substringAfterLast('/')} is damaged (its AES check failed)")
    }

    private fun openAes(raw: InputStream, item: ZipItem, password: String, dataLength: Long): InputStream {
        if (dataLength < 0) throw ZipFormatException("damaged zip: ${item.name}")
        val salt = ByteArray(saltLength(item.aesStrength)).also { readFully(raw, it) }
        val check = ByteArray(2).also { readFully(raw, it) }
        val (ctr, mac) = aesKeys(salt, check, item, password)
        return object : InputStream() {
            private var left = dataLength
            private var checked = false
            override fun read(): Int { val b = ByteArray(1); return if (read(b, 0, 1) < 0) -1 else b[0].toInt() and 0xFF }
            override fun read(b: ByteArray, off: Int, len: Int): Int {
                if (len == 0) return 0
                if (left == 0L) {
                    if (!checked) { checked = true; checkAuth(mac, ByteArray(AUTH).also { readFully(raw, it) }, item) }
                    return -1
                }
                val n = raw.read(b, off, minOf(len.toLong(), left).toInt())
                if (n < 0) throw EOFException("zip is truncated")
                mac.update(b, off, n)
                ctr.apply(b, off, n)
                left -= n
                return n
            }
        }
    }

    /** PBKDF2 with HMAC-SHA1, done here so phone and PC turn the password into bytes the same way. An empty key pads to the same as one zero byte. */
    private fun pbkdf2(password: ByteArray, salt: ByteArray, iterations: Int, length: Int): ByteArray {
        val mac = Mac.getInstance("HmacSHA1").apply { init(SecretKeySpec(if (password.isEmpty()) ByteArray(1) else password, "HmacSHA1")) }
        val out = ByteArray(length)
        var block = 1
        var pos = 0
        while (pos < length) {
            mac.update(salt)
            mac.update(byteArrayOf((block ushr 24).toByte(), (block ushr 16).toByte(), (block ushr 8).toByte(), block.toByte()))
            var u = mac.doFinal()
            val t = u.copyOf()
            repeat(iterations - 1) {
                u = mac.doFinal(u)
                for (i in t.indices) t[i] = (t[i].toInt() xor u[i].toInt()).toByte()
            }
            val n = minOf(t.size, length - pos)
            System.arraycopy(t, 0, out, pos, n)
            pos += n; block++
        }
        return out
    }

    private fun readFully(input: InputStream, b: ByteArray) {
        var got = 0
        while (got < b.size) { val n = input.read(b, got, b.size - got); if (n < 0) throw EOFException("zip is truncated"); got += n }
    }

    private val CRC_TABLE = IntArray(256) { n ->
        var c = n
        repeat(8) { c = if (c and 1 != 0) (c ushr 1) xor 0xEDB88320.toInt() else c ushr 1 }
        c
    }
}
