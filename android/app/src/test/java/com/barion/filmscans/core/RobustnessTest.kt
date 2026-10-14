package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.time.LocalDateTime
import java.util.Random
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * Damaged or hostile files end in a plain TIFF or zip error, quickly, without a crash or a runaway
 * allocation. The fuzz tests mutate real files thousands of ways; the others are the cases fuzzing found.
 */
class RobustnessTest {
    private fun fixture(name: String) = javaClass.classLoader!!.getResource("tiff/$name")!!.readBytes()

    /** An 8x8 RGB TIFF whose directory claims [claims] (tag to LONG value) instead of the truth. */
    private fun claiming(vararg claims: Pair<Int, Long>) = TestTiff.tiff(8, 8, 3, 8, TestTiff.image(8, 8, 3, 255), rps = 8,
        claim = claims.associate { it.first to (4 to TestTiff.longs(it.second)) })

    private fun readAll(b: ByteArray): Throwable? = runCatching {
        val t = TiffReader(BytesSource(b))
        Dates.fromTiff(t)
        t.checkSupported()
        Thumbs.sample(t, 24)
        Scan.convert(t, Scan.frame(t, "x.tif", LocalDateTime.of(2020, 1, 1, 0, 0), 1, "R"), RollMeta(), Credits("A"), ByteArrayOutputStream())
    }.exceptionOrNull()

    private fun clean(e: Throwable?) = e == null || e is TiffException || e is IOException

    @Test fun absurdHeadersFailFastWithATiffError() {
        val cases = mapOf(
            "width 2^30" to claiming(256 to (1L shl 30), 257 to 2L),
            "60000 square in one strip" to claiming(256 to 60000L, 257 to 60000L, 278 to 60000L),
            "width 2^31-1" to claiming(256 to Int.MAX_VALUE.toLong(), 257 to 1L),
            "height 2^31-1" to claiming(256 to 1L, 257 to Int.MAX_VALUE.toLong(), 278 to 1L),
            "width 0" to claiming(256 to 0L),
            "tile width but no tile length" to claiming(322 to 50000L),
            "zero tile size" to claiming(322 to 0L, 323 to 0L),
            "RGB with one sample" to claiming(277 to 1L),
            "data offset past the end" to claiming(273 to 1_000_000L),
        )
        for ((label, b) in cases) {
            val t0 = System.nanoTime()
            val e = readAll(b)
            val ms = (System.nanoTime() - t0) / 1_000_000
            assertTrue("$label: $e", e is TiffException)
            assertTrue("$label took $ms ms", ms < 2000)
        }
    }

    @Test fun truncatedPixelDataIsAnErrorNotBlackRows() {
        val whole = TestTiff.tiff(16, 16, 3, 8, TestTiff.image(16, 16, 3, 255), rps = 16)
        val e = readAll(whole.copyOf(whole.size / 2)) // the IFD is at the end, so put it back where the header says
        assertTrue("$e", e is TiffException || e is IOException)
    }

    private fun mutate(base: ByteArray, rnd: Random): ByteArray {
        var b = base.copyOf()
        repeat(1 + rnd.nextInt(3)) {
            when (rnd.nextInt(6)) {
                0, 1 -> repeat(1 + rnd.nextInt(8)) { b[rnd.nextInt(b.size)] = rnd.nextInt(256).toByte() }
                2 -> { val p = rnd.nextInt(maxOf(1, b.size - 2)); val v = listOf(0, 1, 0x7FFF, 0xFFFF)[rnd.nextInt(4)]; b[p] = v.toByte(); b[p + 1] = (v shr 8).toByte() }
                3 -> { val p = rnd.nextInt(maxOf(1, b.size - 4)); val v = listOf(0L, 1L, 0x7FFFFFFFL, 0xFFFFFFFFL, 0x80000000L, b.size.toLong())[rnd.nextInt(6)]
                    for (k in 0 until 4) b[p + k] = (v shr (8 * k)).toByte() }
                4 -> b = b.copyOf(1 + rnd.nextInt(b.size))
                5 -> { val p = rnd.nextInt(b.size); val n = rnd.nextInt(64); b = b.copyOfRange(0, p) + ByteArray(n) { rnd.nextInt(256).toByte() } + b.copyOfRange(p, b.size) }
            }
        }
        return b
    }

    @Test fun mutatedTiffsNeverCrash() {
        val files = listOf("rgb_u8_strips", "rgb_u16_lzw_pred2_tiled", "rgb_u16_planar_deflate", "rgb_f32_deflate_pred3",
            "gray_u12_packed", "bigtiff_rgb_u8_deflate_tiled", "rgb_u32_none").map { fixture("$it.tif") }
        val rnd = Random(7)
        repeat(700) { i ->
            val b = mutate(files[rnd.nextInt(files.size)], rnd)
            val e = readAll(b)
            assertTrue("mutation $i: $e", clean(e))
        }
    }

    private fun smallJpeg(): ByteArray {
        val out = ByteArrayOutputStream()
        val enc = JpegEncoder(out, 40, 30, 3, 90)
        val f = Frame("lab001.jpg", LocalDateTime.of(2019, 4, 12, 14, 3, 22), 40, 30, dpi = 300.0, scannerMake = "NORITSU KOKI",
            scannerModel = "QSS-32", icc = ByteArray(600) { it.toByte() }, frameNumber = 1, rollName = "Roll 1")
        enc.writeHeader(Metadata.segments(f, RollMeta(camera = "Nikon FM2", film = "Kodak Portra 400", boxIso = 400), Credits("Test Person", License.CC_BY)))
        enc.writeRows(ByteArray(40 * 30 * 3) { (it * 7).toByte() }, 30); enc.finish()
        return out.toByteArray()
    }

    @Test fun mutatedJpegsNeverCrash() {
        val base = smallJpeg()
        assertEquals(40, JpegRetag.info(BytesSource(base)).width)
        val rnd = Random(8)
        repeat(1000) { i ->
            val b = mutate(base, rnd)
            val e = runCatching {
                val s = BytesSource(b)
                Dates.fromImage(s)
                val info = JpegRetag.info(s)
                JpegRetag.rewrite(s, Metadata.segments(JpegRetag.frame(info, "x.jpg", LocalDateTime.of(2020, 1, 1, 0, 0), 1, "R"), RollMeta(), Credits("A")), ByteArrayOutputStream())
            }.exceptionOrNull()
            assertTrue("mutation $i: $e", clean(e))
        }
    }

    private fun zip(vararg files: Pair<String, ByteArray>, stored: Boolean = false): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in files) {
                val e = ZipEntry(name)
                if (stored) { e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong(); e.crc = java.util.zip.CRC32().also { it.update(data) }.value }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun exercise(b: ByteArray) {
        val listing = Archive.list(BytesSource(b))
        for (f in listing.files) {
            Dates.fromImage(ZipIndex.storedSource(f.container, f.item) ?: BytesSource(ZipIndex.head(f.container, f.item, 4096)))
            ZipIndex.open(f.container, f.item).use { it.readBytes() }
        }
        UnzipPlan.build(listing, "Top", emptyMap(), emptySet())
        UnzipPlan.folders(listing)
        ZipWalk.walk(ByteArrayInputStream(b)) { _, data, _ -> data.readBytes() }
    }

    @Test fun mutatedZipsNeverCrash() {
        val tif = fixture("rgb_u8_strips.tif")
        val inner = zip("Roll 2/b.tif" to tif)
        val base = zip("Order/Roll 1/a.tif" to tif) + ByteArray(0)
        val nested = zip("Order/Roll 1/a.tif" to tif, "Order/Tiffs.zip" to inner, stored = true)
        assertEquals(2, Archive.list(BytesSource(nested)).files.size)
        val rnd = Random(9)
        repeat(1000) { i ->
            val b = mutate(if (i % 2 == 0) base else nested, rnd)
            val e = runCatching { exercise(b) }.exceptionOrNull()
            assertTrue("mutation $i: $e", clean(e))
        }
    }

    /** Finds the central directory entry and patches a field in it. */
    private fun patchCen(b: ByteArray, at: Int, vararg v: Int): ByteArray {
        var p = b.size - 22
        while (p > 0 && !(b[p] == 0x50.toByte() && b[p + 1] == 0x4b.toByte() && b[p + 2] == 1.toByte() && b[p + 3] == 2.toByte())) p--
        val c = b.copyOf()
        v.forEachIndexed { i, x -> c[p + at + i] = x.toByte() }
        return c
    }

    @Test fun anEntryMustInflateToExactlyWhatTheZipSays() {
        val z = zip("Roll 1/a.tif" to ByteArray(200_000))
        fun open(b: ByteArray): Throwable? = runCatching {
            val f = Archive.list(BytesSource(b)).files.single()
            ZipIndex.open(f.container, f.item).use { it.readBytes() }
        }.exceptionOrNull()
        assertEquals(null, open(z))
        val liesSmall = patchCen(z, 24, 0xE8, 0x03, 0, 0) // says 1000 bytes: a zip bomb's shape
        assertTrue("${open(liesSmall)}", open(liesSmall) is ZipFormatException)
        val liesBig = patchCen(z, 24, 0, 0, 0x10, 0)      // says 1 MB: a cut-off download's shape
        assertTrue("${open(liesBig)}", open(liesBig) is ZipFormatException)
        val badCrc = patchCen(z, 16, 1, 2, 3, 4)
        assertTrue("${open(badCrc)}", open(badCrc) is ZipFormatException)
    }

    @Test fun aDamagedUtf8NameIsAZipError() {
        val z = zip("R\u00f6ll/a.tif" to ByteArray(10))
        // The name in the local header starts at offset 30; make its first byte invalid UTF-8.
        z[30] = 0xFF.toByte()
        val e = runCatching { ZipWalk.walk(ByteArrayInputStream(z)) { _, d, _ -> d.readBytes() } }.exceptionOrNull()
        assertTrue("$e", e is ZipFormatException)
    }

    @Test fun controlCharactersNeverReachTheXmp() {
        val f = Frame("a.tif", LocalDateTime.of(2020, 1, 1, 0, 0), 8, 8, scannerMake = "NORITSU\u0001 KOKI", scannerModel = "QSS\u001b")
        val xmp = String(Metadata.xmp(f, RollMeta(notes = "fine\u0007"), Credits("A\u0000B")), Charsets.UTF_8).substringAfter(JpegHeader.XMP_ID)
        assertTrue(xmp, !xmp.any { it < ' ' && it != '\n' && it != '\t' && it != '\r' })
        assertTrue(xmp.contains("NORITSU KOKI QSS"))
    }
}
