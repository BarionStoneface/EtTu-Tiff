package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ShrinkTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResource("tiff/$name")!!.readBytes()

    @Test fun longSideBecomesTheSizeAndNothingIsEnlarged() {
        assertEquals(1568 to 1045, Shrink.fit(6000, 4000, 1568))
        assertEquals(1045 to 1568, Shrink.fit(4000, 6000, 1568))
        assertEquals(800 to 600, Shrink.fit(800, 600, 1568))
        assertEquals(1568 to 1, Shrink.fit(20000, 2, 1568))
    }

    @Test fun shrunkTiffIsTheAverageOfTheScan() {
        for (name in listOf("rgb_u8_strips.tif", "gray_i16_none.tif", "rgb_u16_lzw_pred2_tiled.tif")) {
            val t = TiffReader(BytesSource(bytes(name)))
            val oc = t.outChannels
            val full = ByteArray(t.width * t.height * oc)
            t.readRows { y, rows, px -> System.arraycopy(px, 0, full, y * t.width * oc, rows * t.width * oc) }
            val (dw, dh) = Shrink.fit(t.width, t.height, maxOf(t.width, t.height) / 3)
            val small = Shrink.tiff(TiffReader(BytesSource(bytes(name))), dw, dh)
            assertEquals(dw * dh, small.size)
            // Every output pixel against its own average, worked out the slow way.
            for (oy in 0 until dh) for (ox in 0 until dw) {
                val sums = IntArray(3); var n = 0
                for (y in 0 until t.height) if (y.toLong() * dh / t.height == oy.toLong())
                    for (x in 0 until t.width) if (x.toLong() * dw / t.width == ox.toLong()) {
                        val i = (y * t.width + x) * oc
                        for (c in 0 until 3) sums[c] += full[i + if (oc == 1) 0 else c].u()
                        n++
                    }
                val p = small[oy * dw + ox]
                for (c in 0 until 3) {
                    val got = (p shr (16 - 8 * c)) and 0xFF
                    assertEquals("$name ($ox,$oy) channel $c", (sums[c] + n / 2) / n, got)
                }
            }
        }
    }

    /** An EXIF segment with a camera make, a location block pointing at its own values, and an orientation. */
    private fun exifWithGps(): ByteArray {
        val b = ByteBuffer.allocate(120).order(ByteOrder.LITTLE_ENDIAN)
        b.put('I'.code.toByte()).put('I'.code.toByte()).putShort(42).putInt(8)
        // IFD0 at 8: three entries, ends at 8 + 2 + 36 + 4 = 50.
        b.putShort(3)
        b.putShort(0x010F).putShort(2).putInt(4).put("Abc\u0000".toByteArray())
        b.putShort(0x0112).putShort(3).putInt(1).putShort(6).putShort(0)
        b.putShort(0x8825.toShort()).putShort(4).putInt(1).putInt(50)
        b.putInt(0)
        // GPS IFD at 50: one entry, latitude as three rationals at 68.
        b.putShort(1)
        b.putShort(2).putShort(5).putInt(3).putInt(68)
        b.putInt(0)
        repeat(6) { b.putInt(0x5A5A5A5A) }
        val tiff = b.array().copyOf(b.position())
        return Metadata.segment(0xE1, "Exif\u0000\u0000".toByteArray(Charsets.ISO_8859_1) + tiff)
    }

    private fun hasRun(b: ByteArray, v: Byte) = (0..b.size - 4).any { i -> (0 until 4).all { b[i + it] == v } }

    @Test fun locationIsTakenOutOfExifAndTheRestStays() {
        val seg = exifWithGps()
        assertTrue(hasRun(seg, 0x5A))
        val clean = Shrink.noGpsExif(seg)
        assertEquals(seg.size, clean.size)
        assertFalse(hasRun(clean, 0x5A))
        val t = TiffReader(BytesSource(clean.copyOfRange(10, clean.size)), image = false)
        assertNull(t.tags[0x8825])
        assertEquals("Abc", t.text(0x010F))
        assertEquals(6, t.orientation)
        // Nothing to take out: unchanged.
        assertTrue(clean.contentEquals(Shrink.noGpsExif(clean)))
    }

    @Test fun locationIsTakenOutOfXmp() {
        val xmp = """<rdf:Description exif:GPSLatitude="51,30.0N" exif:GPSLongitude='0,7.5W' dc:format="image/jpeg">""" +
            """<exif:GPSAltitude>12/1</exif:GPSAltitude><exif:GPSVersionID/><dc:title>Keep</dc:title></rdf:Description>"""
        val clean = Shrink.noGpsXmp(xmp)
        assertFalse(clean.contains("GPS"))
        assertTrue(clean.contains("dc:format=\"image/jpeg\""))
        assertTrue(clean.contains("<dc:title>Keep</dc:title>"))
    }

    @Test fun jpegKeepsDetailsAndProfileButNotLocationOrIptc() {
        val o = ByteArrayOutputStream()
        val enc = JpegEncoder(o, 16, 8, 3, 90)
        enc.writeHeader(listOf(
            exifWithGps(),
            Metadata.segment(0xE1, (JpegHeader.XMP_ID + """<x:xmpmeta><rdf:Description AnalogExif:Film="Portra 400" exif:GPSLatitude="51,30.0N"/></x:xmpmeta>""").toByteArray()),
            Metadata.segment(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1) + ByteArray(40) { 7 }),
            Metadata.segment(0xED, "Photoshop 3.0\u0000Sublocation".toByteArray(Charsets.ISO_8859_1)),
            Metadata.segment(0xFE, "Copyright 2024 Someone".toByteArray()),
        ))
        enc.writeRows(ByteArray(16 * 3 * 8) { 90 }, 8)
        enc.finish()
        val segs = Shrink.jpegSegments(BytesSource(o.toByteArray()))
        val text = String(segs.reduce { a, b -> a + b }, Charsets.ISO_8859_1)
        assertEquals(4, segs.size)
        assertTrue(text.contains("Portra 400")); assertTrue(text.contains("ICC_PROFILE")); assertTrue(text.contains("Copyright 2024"))
        assertFalse(text.contains("GPS")); assertFalse(text.contains("Sublocation"))

        // Put onto a freshly encoded picture in place of its own profile.
        val fresh = ByteArrayOutputStream()
        JpegEncoder(fresh, 16, 8, 3, 80).apply {
            writeHeader(listOf(Metadata.segment(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1, 9, 9))))
            writeRows(ByteArray(16 * 3 * 8) { 10 }, 8); finish()
        }
        val out = ByteArrayOutputStream()
        JpegRetag.rewrite(BytesSource(fresh.toByteArray()), segs, out, keepProfile = false)
        val result = String(out.toByteArray(), Charsets.ISO_8859_1)
        assertEquals(1, Regex("ICC_PROFILE").findAll(result).count())
        assertEquals(6, JpegRetag.info(BytesSource(out.toByteArray())).orientation)
        assertNotNull(Dates.fromJpeg(BytesSource(out.toByteArray())))
    }
}

class ZipPartsTest {
    private fun item(folder: String, name: String, size: Long) = ZipParts.Item("$folder/$name", size, folder)

    @Test fun noCapIsOneZip() {
        val items = List(10) { item("Roll", "$it.jpg", 5_000_000) }
        assertEquals(listOf(items), ZipParts.pack(items, null))
    }

    @Test fun foldersStayTogetherWhenTheyFit() {
        val a = List(3) { item("Album/Roll 1", "$it.jpg", 4_000_000) }
        val b = List(3) { item("Album/Roll 2", "$it.jpg", 4_000_000) }
        val parts = ZipParts.pack(a + b, 20_000_000)
        assertEquals(listOf(a, b), parts)
    }

    @Test fun aBigFolderIsSplitAndNoPartGoesOverTheCap() {
        val a = List(2) { item("Album/Roll 1", "$it.jpg", 3_000_000) }
        val b = List(9) { item("Album/Roll 2", "$it.jpg", 3_000_000) }
        val cap = 10_000_000L
        val parts = ZipParts.pack(a + b, cap)
        assertEquals(a + b, parts.flatten())
        // Roll 2 is too big for one zip, so it fills up the zip Roll 1 started.
        assertEquals(3, parts[0].size)
        for (p in parts) assertTrue(p.sumOf { ZipParts.cost(it) } + ZipParts.END <= cap)
    }

    @Test fun costCoversWhatAStoredZipReallyTakes() {
        val items = listOf(item("Album/Röll 1", "a.jpg", 1000), item("Album/Röll 1", "bé.jpg", 2500), item("Album", "c.jpg", 10))
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for (it in items) {
                val data = ByteArray(it.size.toInt()) { i -> i.toByte() }
                val e = ZipEntry(it.path).apply {
                    method = ZipEntry.STORED; size = it.size; compressedSize = it.size
                    crc = CRC32().apply { update(data) }.value; time = 1_700_000_000_000
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        assertTrue(out.size() <= items.sumOf { ZipParts.cost(it) } + ZipParts.END)
    }

    @Test fun names() {
        assertEquals("Album.zip", ZipParts.name("Album", 0, 1))
        assertEquals("Album part 2 of 3.zip", ZipParts.name("Album", 1, 3))
    }
}
