package com.barion.filmscans.pc

import com.barion.filmscans.core.Thumbs
import com.barion.filmscans.core.TiffReader
import java.awt.geom.AffineTransform
import java.awt.image.AffineTransformOp
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO

/** Java's own JPEG decoder, for JPEG-compressed TIFFs (the phone uses its own). */
object ImageIoJpeg : TiffReader.JpegDecoder {
    override fun decode(jpeg: ByteArray): TiffReader.Decoded? {
        val img = ImageIO.read(ByteArrayInputStream(jpeg)) ?: return null
        val w = img.width; val h = img.height
        val px = ByteArray(w * h * 3)
        val row = IntArray(w)
        for (y in 0 until h) {
            img.getRGB(0, y, w, 1, row, 0, w)
            for (x in 0 until w) {
                val c = row[x]; val i = (y * w + x) * 3
                px[i] = (c shr 16).toByte(); px[i + 1] = (c shr 8).toByte(); px[i + 2] = c.toByte()
            }
        }
        return TiffReader.Decoded(w, h, 3, px)
    }

    fun install() { if (TiffReader.jpegDecoder == null) TiffReader.jpegDecoder = this }
}

/** Previews decoded straight from the scans, turned the way they should display. */
object Previews {
    fun of(f: File, maxSide: Int, orientation: Int = 1): BufferedImage? = runCatching {
        if (isJpeg(f.name)) jpeg(f, maxSide, orientation) else tiff(f, maxSide)
    }.getOrNull()

    private fun tiff(f: File, maxSide: Int): BufferedImage = FileSource(f).use { src ->
        val t = TiffReader(src)
        val th = Thumbs.sample(t, maxSide)
        val img = BufferedImage(th.width, th.height, BufferedImage.TYPE_INT_RGB)
        img.setRGB(0, 0, th.width, th.height, th.argb, 0, th.width)
        turn(img, t.orientation)
    }

    private fun jpeg(f: File, maxSide: Int, orientation: Int): BufferedImage? = ImageIO.createImageInputStream(f).use { iis ->
        val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: return null
        try {
            reader.input = iis
            val step = maxOf(1, maxOf(reader.getWidth(0), reader.getHeight(0)) / maxSide)
            val img = reader.read(0, reader.defaultReadParam.apply { setSourceSubsampling(step, step, 0, 0) })
            turn(img, orientation)
        } finally {
            reader.dispose()
        }
    }

    /** Applies an EXIF orientation (1-8) to the pixels, for showing on screen only. */
    fun turn(img: BufferedImage, o: Int): BufferedImage {
        if (o !in 2..8) return img
        val w = img.width.toDouble(); val h = img.height.toDouble()
        val t = AffineTransform()
        when (o) {
            2 -> { t.translate(w, 0.0); t.scale(-1.0, 1.0) }
            3 -> { t.translate(w, h); t.rotate(Math.PI) }
            4 -> { t.translate(0.0, h); t.scale(1.0, -1.0) }
            5 -> { t.rotate(Math.PI / 2); t.scale(1.0, -1.0) }
            6 -> { t.translate(h, 0.0); t.rotate(Math.PI / 2) }
            7 -> { t.scale(-1.0, 1.0); t.translate(-h, 0.0); t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
            8 -> { t.translate(0.0, w); t.rotate(3 * Math.PI / 2) }
        }
        val swap = o >= 5
        val out = BufferedImage(if (swap) img.height else img.width, if (swap) img.width else img.height, BufferedImage.TYPE_INT_RGB)
        AffineTransformOp(t, AffineTransformOp.TYPE_NEAREST_NEIGHBOR).filter(rgb(img), out)
        return out
    }

    private fun rgb(img: BufferedImage): BufferedImage = if (img.type == BufferedImage.TYPE_INT_RGB) img else
        BufferedImage(img.width, img.height, BufferedImage.TYPE_INT_RGB).also { it.graphics.apply { drawImage(img, 0, 0, null); dispose() } }
}
