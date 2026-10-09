package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TIFFs of many kinds, written by tifffile (the library the desktop converter used), each with
 * the 8-bit pixels an independent reader gets from it. Made by a script, kept in test resources.
 */
class TiffTypesTest {
    private fun bytes(name: String) = javaClass.classLoader!!.getResource("tiff/$name")!!.readBytes()

    private fun pixels(t: TiffReader): ByteArray {
        val out = ByteArray(t.width * t.height * t.outChannels)
        t.readRows { y, rows, px -> System.arraycopy(px, 0, out, y * t.width * t.outChannels, rows * t.width * t.outChannels) }
        return out
    }

    /** Decodes JPEG-compressed TIFF parts on the JVM the way the phone's decoder does on Android. */
    private val jvmJpeg = TiffReader.JpegDecoder { jpeg ->
        val img = javax.imageio.ImageIO.read(java.io.ByteArrayInputStream(jpeg)) ?: return@JpegDecoder null
        val px = ByteArray(img.width * img.height * 3)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            val c = img.getRGB(x, y); val i = (y * img.width + x) * 3
            px[i] = (c shr 16).toByte(); px[i + 1] = (c shr 8).toByte(); px[i + 2] = c.toByte()
        }
        TiffReader.Decoded(img.width, img.height, 3, px)
    }

    private fun check(name: String, tolerance: Int = 0) {
        val t = TiffReader(BytesSource(bytes("$name.tif")))
        t.checkSupported()
        val got = pixels(t)
        val want = bytes("$name.expected")
        assertEquals("$name size", want.size, got.size)
        var worst = 0
        for (i in want.indices) worst = maxOf(worst, Math.abs(want[i].u() - got[i].u()))
        assertTrue("$name: off by up to $worst", worst <= tolerance)
    }

    @Test fun plain8Bit() = check("rgb_u8_strips")
    @Test fun lzwTiledWithPredictor() = check("rgb_u16_lzw_pred2_tiled")
    @Test fun planarDeflate() = check("rgb_u16_planar_deflate")
    @Test fun float32WithFloatingPointPredictor() = check("rgb_f32_deflate_pred3")
    @Test fun float32BigEndianPredictor() = check("rgb_f32_pred3_bigendian")
    @Test fun halfFloat() = check("rgb_f16_none")
    @Test fun doubleGray() = check("gray_f64_none")
    @Test fun whole32Bit() = check("rgb_u32_none")
    @Test fun whole32BitWithPredictor() = check("rgb_u32_deflate_pred2")
    @Test fun signed16Bit() = check("gray_i16_none")
    @Test fun bigTiffTiled() = check("bigtiff_rgb_u8_deflate_tiled")
    @Test fun bigTiffBigEndian() = check("bigtiff_be_rgb_u16_pred2")
    @Test fun packed4Bit() = check("gray_u4_packed")
    @Test fun packed12Bit() = check("gray_u12_packed")

    @Test fun jpegCompressed() {
        val before = TiffReader.jpegDecoder
        TiffReader.jpegDecoder = jvmJpeg
        try {
            // Two JPEG decoders never agree exactly (rounding, chroma smoothing): close is right.
            check("rgb_jpeg_ycbcr", tolerance = 12)
            check("rgb_jpeg_tiled_rgb", tolerance = 12)
        } finally { TiffReader.jpegDecoder = before }
    }

    @Test fun jpegCompressedWithoutADecoderSaysSo() {
        val before = TiffReader.jpegDecoder
        TiffReader.jpegDecoder = null
        try {
            val e = runCatching { TiffReader(BytesSource(bytes("rgb_jpeg_ycbcr.tif"))).checkSupported() }.exceptionOrNull()
            assertTrue(e?.message.orEmpty().contains("JPEG"))
        } finally { TiffReader.jpegDecoder = before }
    }

    @Test fun mergesSharedJpegTables() {
        val tables = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 0xFF.toByte(), 0xD9.toByte())
        val chunk = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 3, 4)
        assertTrue(byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 4).contentEquals(TiffReader.mergeJpegTables(tables, chunk)))
        assertTrue(chunk.contentEquals(TiffReader.mergeJpegTables(null, chunk)))
    }

    @Test fun halfFloats() {
        assertEquals(1.0, TiffReader.halfToFloat(0x3C00), 0.0)
        assertEquals(0.5, TiffReader.halfToFloat(0x3800), 0.0)
        assertEquals(-2.0, TiffReader.halfToFloat(0xC000), 0.0)
        assertEquals(5.960464477539063E-8, TiffReader.halfToFloat(0x0001), 1e-20)
    }
}
