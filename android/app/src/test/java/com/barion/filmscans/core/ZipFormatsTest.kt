package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayInputStream
import java.util.zip.CRC32

/** Zips made by other tools (see tools/make_zip_fixtures.py): passwords of both kinds, and Deflate64. */
class ZipFormatsTest {
    private val pw = "film soup ñ"

    private fun fixture(name: String) = javaClass.getResourceAsStream("/zip/$name")!!.readBytes()
    private fun crc(b: ByteArray) = CRC32().also { it.update(b) }.value

    /** Every file read in place, by path. */
    private fun readAll(bytes: ByteArray, password: String?): Map<String, ByteArray> {
        val listing = Archive.list(ArchiveTest.Mem(bytes))
        return listing.files.associate { f ->
            val data = ZipIndex.open(f.container, f.item, password).use { it.readBytes() }
            assertEquals(f.path, f.item.size, data.size.toLong())
            if (!(f.item.aesStrength > 0 && f.item.crc == 0L)) assertEquals(f.path, f.item.crc, crc(data))
            f.path to data
        }
    }

    /** Every file read start to finish, as a zip that can't be read in place is. */
    private fun walkAll(bytes: ByteArray, password: String?): Map<String, ByteArray> {
        val got = LinkedHashMap<String, ByteArray>()
        ZipWalk.walk(ByteArrayInputStream(bytes), password) { path, data, _ -> got[path] = data.readBytes() }
        return got
    }

    private fun sameFiles(a: Map<String, ByteArray>, b: Map<String, ByteArray>) {
        assertEquals(a.keys, b.keys)
        for (k in a.keys) assertTrue(k, a.getValue(k).contentEquals(b.getValue(k)))
    }

    @Test fun passwordKindsReadInPlaceAndStartToFinish() {
        for (name in listOf("zipcrypto.zip", "aes128.zip", "aes256.zip")) {
            val bytes = fixture(name)
            val listing = Archive.list(ArchiveTest.Mem(bytes))
            assertTrue(name, listing.needsPassword)
            assertTrue(name, listing.skipped.isEmpty())
            val inPlace = readAll(bytes, pw)
            assertEquals(name, "hello\n", String(inPlace.getValue("readme.txt")))
            assertEquals(name, 150_000, inPlace.getValue("Roll 1/notes.txt").size)
            sameFiles(inPlace, walkAll(bytes, pw))
        }
        assertEquals("left as it is ".repeat(50), String(readAll(fixture("aes256.zip"), pw).getValue("stored.txt")))
    }

    @Test fun wrongOrMissingPasswordIsSaidPlainly() {
        for (name in listOf("zipcrypto.zip", "aes128.zip", "aes256.zip", "locked-inner.zip")) {
            val bytes = fixture(name)
            val listing = Archive.list(ArchiveTest.Mem(bytes))
            assertTrue(name, Archive.passwordFits(listing, pw))
            assertFalse(name, Archive.passwordFits(listing, "film soup n"))
            assertFalse(name, Archive.passwordFits(listing, "wrong"))
            val f = listing.files.firstOrNull { it.item.encrypted } ?: continue
            try { ZipIndex.open(f.container, f.item).readBytes(); fail(name) } catch (_: PasswordNeeded) {}
            try { ZipIndex.open(f.container, f.item, "wrong").readBytes(); fail(name) } catch (_: WrongPassword) {}
            try { walkAll(bytes, null); fail(name) } catch (_: PasswordNeeded) {}
            try { walkAll(bytes, "wrong"); fail(name) } catch (_: WrongPassword) {}
        }
    }

    @Test fun lockedZipInsideLockedZip() {
        val bytes = fixture("locked-inner.zip")
        val listing = Archive.list(ArchiveTest.Mem(bytes))
        // Stored but locked, so it can't be read in place: it's unzipped as it streams.
        assertEquals(listOf("Inner"), listing.sealed.map { it.folder })
        val inner = listing.sealed.single()
        val got = LinkedHashMap<String, ByteArray>()
        ZipIndex.open(inner.container, inner.item, pw).use { s -> ZipWalk.walk(s, pw) { p, d, _ -> got[p] = d.readBytes() } }
        assertEquals(setOf("readme.txt", "Roll 1/notes.txt"), got.keys)
        assertEquals(setOf("Inner/readme.txt", "Inner/Roll 1/notes.txt", "readme.txt"), walkAll(bytes, pw).keys)
    }

    @Test fun deflate64() {
        for (name in listOf("deflate64.zip", "deflate64-stream.zip")) {
            val bytes = fixture(name)
            val listing = Archive.list(ArchiveTest.Mem(bytes))
            assertTrue(name, listing.skipped.isEmpty())
            assertFalse(name, listing.needsPassword)
            val inPlace = readAll(bytes, null)
            // Repeated from 40 KB back in one 40,000 byte match: only Deflate64 can say that.
            val long = inPlace.getValue("long.bin")
            assertEquals(80_000, long.size)
            assertTrue(long.copyOfRange(0, 40_000).contentEquals(long.copyOfRange(40_000, 80_000)))
            sameFiles(inPlace, walkAll(bytes, null))
        }
    }

    @Test fun deflate64DecoderAlsoInflatesPlainDeflate() {
        val data = ByteArray(300_000) { (it * 7 % 251 + it / 5000).toByte() }
        val def = java.util.zip.Deflater(9, true).apply { setInput(data); finish() }
        val buf = ByteArray(400_000)
        val n = def.deflate(buf)
        val s = ByteArrayInputStream(buf, 0, n)
        val out = Inflate64({ s.read() }, deflate64 = false).readBytes()
        assertTrue(out.contentEquals(data))
    }

    @Test fun unknownCompressionIsNamed() {
        val item = ZipItem("a.txt", 14, 0, 0, 1, 1, 0, null, false)
        assertEquals("it uses a compression type this app can't read (LZMA)", item.unreadable)
    }
}
