package com.barion.filmscans.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.time.LocalDateTime

class RetagTest {
    private val labDate = LocalDateTime.of(2024, 3, 5, 14, 22, 11)

    /** A real JPEG, as a lab might send it: JFIF, its own EXIF and XMP, a colour profile, a comment, a GPS-ish tag. */
    private fun labJpeg(w: Int = 40, h: Int = 24): ByteArray {
        val o = ByteArrayOutputStream()
        val labFrame = Frame("lab.jpg", LocalDateTime.of(2001, 1, 1, 1, 1, 1), w, h, orientation = 6, dpi = 300.0,
            scannerMake = "NORITSU KOKI", scannerModel = "HS-1800")
        val enc = JpegEncoder(o, w, h, 3, 90)
        enc.writeHeader(listOf(
            Metadata.segment(0xE0, byteArrayOf('J'.code.toByte(), 'F'.code.toByte(), 'I'.code.toByte(), 'F'.code.toByte(), 0, 1, 1, 1, 0, 72, 0, 72, 0, 0)),
            Metadata.exif(labFrame, RollMeta(notes = "Old Note"), Credits()),
            Metadata.segment(0xE1, (JpegHeader.XMP_ID + """<x:xmpmeta><rdf:Description xmp:MetadataDate="2024-03-05T14:22:11-05:00" exif:GPSLatitude="51,30.0N"/></x:xmpmeta>""").toByteArray()),
            Metadata.segment(0xE2, "ICC_PROFILE\u0000".toByteArray(Charsets.ISO_8859_1) + byteArrayOf(1, 1) + ByteArray(300) { 7 }),
            Metadata.segment(0xFE, "old lab comment".toByteArray()),
            Metadata.segment(0xED, "Photoshop 3.0\u0000old iptc".toByteArray(Charsets.ISO_8859_1)),
        ))
        val px = ByteArray(w * 3 * h) { (it * 31).toByte() }
        enc.writeRows(px, h)
        enc.finish()
        return o.toByteArray()
    }

    private fun sosOffset(j: ByteArray): Int {
        var p = 2
        while (true) {
            val m = j[p + 1].u()
            if (m == 0xDA) return p
            p += 2 + ((j[p + 2].u() shl 8) or j[p + 3].u())
        }
    }

    @Test fun picturesAreCopiedByteForByteAndOnlyMetadataChanges() {
        val lab = labJpeg()
        val info = JpegRetag.info(BytesSource(lab))
        assertEquals(40, info.width); assertEquals(24, info.height); assertEquals(6, info.orientation)
        assertEquals(300.0, info.dpi!!, 0.01); assertEquals("NORITSU KOKI", info.scannerMake)
        assertFalse(info.taggedByThisApp)

        val frame = JpegRetag.frame(info, "000001.jpg", labDate, 1, "Roll 1")
        val meta = RollMeta(camera = "Nikon FM2", film = "Kodak Portra 400", boxIso = 400)
        val out = ByteArrayOutputStream()
        JpegRetag.rewrite(BytesSource(lab), Metadata.segments(frame, meta, Credits("Test Person")), out)
        val tagged = out.toByteArray()

        // Image data untouched.
        assertArrayEquals(lab.copyOfRange(sosOffset(lab), lab.size), tagged.copyOfRange(sosOffset(tagged), tagged.size))
        val text = String(tagged, Charsets.ISO_8859_1)
        // Old metadata gone, new in.
        assertFalse(text.contains("old lab comment")); assertFalse(text.contains("old iptc"))
        assertFalse(text.contains("GPSLatitude")); assertFalse(text.contains("Old Note"))
        assertTrue(text.contains("Kodak Portra 400")); assertTrue(text.contains("Copyright 2024 Test Person"))
        assertTrue(text.contains("xmpMM:PreservedFileName=\"000001.jpg\""))
        // Kept: colour profile, JFIF, the scanner, orientation and resolution.
        assertTrue(text.contains("ICC_PROFILE")); assertTrue(text.startsWith("ÿØÿà"))
        val again = JpegRetag.info(BytesSource(tagged))
        assertEquals(6, again.orientation); assertEquals(300.0, again.dpi!!, 0.01)
        assertEquals("NORITSU KOKI", again.scannerMake); assertEquals("NORITSU KOKI HS-1800", again.scannerModel)
        assertEquals(40, again.width)
        assertTrue(again.taggedByThisApp)
        assertEquals(labDate, Dates.fromJpeg(BytesSource(tagged)).first().date)
        // Doing it twice gives the same file.
        val twice = ByteArrayOutputStream()
        JpegRetag.rewrite(BytesSource(tagged), Metadata.segments(frame, meta, Credits("Test Person")), twice)
        assertArrayEquals(tagged, twice.toByteArray())
    }

    @Test fun notAJpeg() {
        assertTrue(runCatching { JpegRetag.info(BytesSource(ByteArray(100))) }.isFailure)
        assertTrue(runCatching { JpegRetag.rewrite(BytesSource(ByteArray(100)), emptyList(), ByteArrayOutputStream()) }.isFailure)
    }
}
