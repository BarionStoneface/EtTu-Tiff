package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

class ConvertTest {
    private fun convert(t: ByteArray): ByteArray {
        val r = TiffReader(BytesSource(t))
        val f = Scan.frame(r, "scan.tif", Dates.fromTiff(r).firstOrNull()?.date ?: LocalDateTime.of(2020, 1, 2, 3, 4, 5), 1, "Roll")
        val out = ByteArrayOutputStream()
        Scan.convert(r, f, RollMeta(camera = "Nikon FM2", film = "Kodak Portra 400", boxIso = 400, pushStops = 1),
            Credits("Test Person"), out)
        return out.toByteArray()
    }

    /** Walks the JPEG's segments: returns (width, height, components) from SOF0 and checks it ends in EOI. */
    private fun sof(j: ByteArray): Triple<Int, Int, Int> {
        assertEquals(0xFF, j[0].u()); assertEquals(0xD8, j[1].u())
        assertEquals(0xFF, j[j.size - 2].u()); assertEquals(0xD9, j[j.size - 1].u())
        var p = 2
        while (p < j.size) {
            assertEquals(0xFF, j[p].u())
            val m = j[p + 1].u(); val len = (j[p + 2].u() shl 8) or j[p + 3].u()
            if (m == 0xC0) return Triple((j[p + 7].u() shl 8) or j[p + 8].u(), (j[p + 5].u() shl 8) or j[p + 6].u(), j[p + 9].u())
            p += 2 + len
        }
        error("no SOF0")
    }

    // Pixel accuracy is checked against libjpeg outside the Android build (JVM unit tests here
    // can't decode JPEGs); these check structure and that every compression path reads.
    @Test fun rgb8Uncompressed() {
        assertEquals(Triple(101, 67, 3), sof(convert(TestTiff.tiff(101, 67, 3, 8, TestTiff.image(101, 67, 3, 255)))))
    }

    @Test fun rgb16Deflate() {
        assertEquals(Triple(64, 40, 3), sof(convert(TestTiff.tiff(64, 40, 3, 16, TestTiff.image(64, 40, 3, 65535), compression = 8))))
    }

    @Test fun gray8SingleStrip() {
        assertEquals(Triple(50, 30, 1), sof(convert(TestTiff.tiff(50, 30, 1, 8, TestTiff.image(50, 30, 1, 255), rps = 30))))
    }

    @Test fun sixteenBitRoundsTo8() {
        val px = intArrayOf(0, 128, 257, 32896, 65535, 65279, 1000, 60000)
        val t = TiffReader(BytesSource(TestTiff.tiff(8, 1, 1, 16, px, rps = 1)))
        var got = ByteArray(0)
        t.readRows { _, _, row -> got = row.copyOf(8) }
        assertEquals(listOf(0, 0, 1, 128, 255, 254, 4, 233), got.map { it.u() })
    }

    @Test fun keepsScannerAndScanDate() {
        val t = TestTiff.tiff(16, 16, 3, 8, TestTiff.image(16, 16, 3, 255),
            extra = mapOf(271 to "NORITSU KOKI", 272 to "QSS-32", 306 to "2019:04:12 14:03:22", 315 to "Lab Operator"))
        val j = String(convert(t), Charsets.ISO_8859_1)
        assertTrue(j.contains("2019:04:12 14:03:22"))
        assertTrue(j.contains("AnalogExif:Scanner=\"NORITSU KOKI QSS-32\""))
        assertTrue(j.contains("xmpMM:PreservedFileName=\"scan.tif\""))
        assertTrue(j.contains("Copyright 2019 Test Person. All rights reserved."))
        assertTrue(j.contains("Kodak Portra 400 shot at EI 800"))
        assertTrue(!j.contains("Lab Operator"))
    }

    @Test fun lzwRoundTrip() {
        // "TOBEORNOTTOBEORTOBEORNOT" LZW-coded, 9-bit codes, MSB first, with clear and EOI.
        val codes = intArrayOf(256, 84, 79, 66, 69, 79, 82, 78, 79, 84, 258, 260, 262, 267, 261, 263, 265, 257)
        val bits = java.util.BitSet(); var n = 0
        for (c in codes) for (b in 8 downTo 0) { if ((c shr b) and 1 == 1) bits.set(n); n++ }
        val bytes = ByteArray((n + 7) / 8) { i -> var v = 0; for (k in 0 until 8) if (bits[i * 8 + k]) v = v or (0x80 shr k); v.toByte() }
        assertEquals("TOBEORNOTTOBEORTOBEORNOT", String(TiffReader.lzwDecode(bytes, 100)))
    }

    @Test fun thumbnailSamplesPixels() {
        val w = 100; val h = 60
        val px = TestTiff.image(w, h, 3, 255)
        for (strips in listOf(1, 7, 60)) {
            val th = Thumbs.sample(TiffReader(BytesSource(TestTiff.tiff(w, h, 3, 8, px, rps = strips))), maxSide = 25)
            assertEquals(25, th.width); assertEquals(15, th.height) // every 4th pixel
            for (ty in 0 until th.height) for (tx in 0 until th.width) {
                val i = ((ty * 4) * w + tx * 4) * 3
                val want = (0xFF shl 24) or (px[i] shl 16) or (px[i + 1] shl 8) or px[i + 2]
                assertEquals(want, th.argb[ty * th.width + tx])
            }
        }
    }

    @Test fun dates() {
        assertEquals(LocalDateTime.of(2019, 4, 12, 14, 3, 22), Dates.parse("2019:04:12 14:03:22"))
        assertEquals(LocalDateTime.of(2019, 4, 12, 14, 5, 0), Dates.parse("2019-04-12T14:05:00+02:00"))
        assertEquals(LocalDateTime.of(2019, 4, 12, 0, 0), Dates.parse("2019-04-12"))
    }
}
