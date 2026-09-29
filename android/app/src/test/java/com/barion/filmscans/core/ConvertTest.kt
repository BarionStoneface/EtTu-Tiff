package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.zip.Deflater

class ConvertTest {
    private class Mem(val b: ByteArray) : ByteSource {
        override val size = b.size.toLong()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) { System.arraycopy(b, pos.toInt(), buf, off, len) }
    }

    /** Minimal little-endian TIFF writer: one strip per [rps] rows. */
    private fun tiff(w: Int, h: Int, spp: Int, bits: Int, pixels: IntArray, compression: Int = 1, rps: Int = 16,
                     extra: Map<Int, String> = emptyMap()): ByteArray {
        val bps = bits / 8
        val rowBytes = w * spp * bps
        val strips = (0 until h step rps).map { y0 ->
            val rows = minOf(rps, h - y0)
            val raw = ByteBuffer.allocate(rows * rowBytes).order(ByteOrder.LITTLE_ENDIAN)
            for (i in y0 * w * spp until (y0 + rows) * w * spp) if (bits == 16) raw.putShort(pixels[i].toShort()) else raw.put(pixels[i].toByte())
            val a = raw.array()
            if (compression == 8) {
                val d = Deflater(); d.setInput(a); d.finish()
                val o = ByteArray(a.size * 2 + 64); val n = d.deflate(o); d.end(); o.copyOf(n)
            } else a
        }
        val entries = sortedMapOf<Int, Pair<Int, ByteArray>>() // tag -> (type, value bytes)
        fun shorts(vararg v: Int) = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
        fun longs(v: List<Int>) = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it) } }.array()
        entries[256] = 3 to shorts(w); entries[257] = 3 to shorts(h)
        entries[258] = 3 to shorts(*IntArray(spp) { bits })
        entries[259] = 3 to shorts(compression)
        entries[262] = 3 to shorts(if (spp == 3) 2 else 1)
        entries[277] = 3 to shorts(spp); entries[278] = 3 to shorts(rps)
        extra.forEach { (t, s) -> entries[t] = 2 to (s.toByteArray() + 0) }
        entries[279] = 4 to longs(strips.map { it.size })
        entries[273] = 4 to longs(strips.map { 0 }) // placeholder
        // layout: header, pixel data, value blobs, IFD
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 0, 0, 0, 0))
        val offs = strips.map { s -> out.size().also { out.write(s) } }
        entries[273] = 4 to longs(offs)
        val blobPos = HashMap<Int, Int>()
        entries.forEach { (t, v) -> if (v.second.size > 4) { if (out.size() % 2 == 1) out.write(0); blobPos[t] = out.size(); out.write(v.second) } }
        if (out.size() % 2 == 1) out.write(0)
        val ifd = out.size()
        val bb = ByteBuffer.allocate(2 + entries.size * 12 + 4).order(ByteOrder.LITTLE_ENDIAN)
        bb.putShort(entries.size.toShort())
        entries.forEach { (t, v) ->
            val size = when (v.first) { 3 -> 2; 4 -> 4; else -> 1 }
            bb.putShort(t.toShort()); bb.putShort(v.first.toShort()); bb.putInt(v.second.size / size)
            if (v.second.size <= 4) { bb.put(v.second); repeat(4 - v.second.size) { bb.put(0) } } else bb.putInt(blobPos[t]!!)
        }
        bb.putInt(0)
        out.write(bb.array())
        val all = out.toByteArray()
        ByteBuffer.wrap(all).order(ByteOrder.LITTLE_ENDIAN).putInt(4, ifd)
        return all
    }

    private fun image(w: Int, h: Int, spp: Int, max: Int) = IntArray(w * h * spp) { i ->
        val p = i / spp; val x = p % w; val y = p / w; val c = i % spp
        val v = (Math.sin(x / 13.0 + c) + Math.cos(y / 7.0) + 2) / 4
        (v * max).toInt()
    }

    private fun convert(t: ByteArray): ByteArray {
        val r = TiffReader(Mem(t))
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
        assertEquals(Triple(101, 67, 3), sof(convert(tiff(101, 67, 3, 8, image(101, 67, 3, 255)))))
    }

    @Test fun rgb16Deflate() {
        assertEquals(Triple(64, 40, 3), sof(convert(tiff(64, 40, 3, 16, image(64, 40, 3, 65535), compression = 8))))
    }

    @Test fun gray8SingleStrip() {
        assertEquals(Triple(50, 30, 1), sof(convert(tiff(50, 30, 1, 8, image(50, 30, 1, 255), rps = 30))))
    }

    @Test fun sixteenBitRoundsTo8() {
        val px = intArrayOf(0, 128, 257, 32896, 65535, 65279, 1000, 60000)
        val t = TiffReader(Mem(tiff(8, 1, 1, 16, px, rps = 1)))
        var got = ByteArray(0)
        t.readRows { _, _, row -> got = row.copyOf(8) }
        assertEquals(listOf(0, 0, 1, 128, 255, 254, 4, 233), got.map { it.u() })
    }

    @Test fun keepsScannerAndScanDate() {
        val t = tiff(16, 16, 3, 8, image(16, 16, 3, 255),
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
        val px = image(w, h, 3, 255)
        for (strips in listOf(1, 7, 60)) {
            val th = Thumbs.sample(TiffReader(Mem(tiff(w, h, 3, 8, px, rps = strips))), maxSide = 25)
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
