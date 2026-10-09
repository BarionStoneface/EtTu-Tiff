package com.barion.filmscans

import android.app.Application
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import com.barion.filmscans.core.TiffReader

/** Holds the one [AppModel], so work in progress outlives the screen. */
class EtTuTiffApp : Application() {
    val model by lazy { AppModel(this) }

    override fun onCreate() {
        super.onCreate()
        // JPEG-compressed TIFFs: each strip or tile is decoded by the phone's own JPEG decoder.
        TiffReader.jpegDecoder = TiffReader.JpegDecoder { jpeg ->
            val bmp = BitmapFactory.decodeByteArray(jpeg, 0, jpeg.size,
                BitmapFactory.Options().apply { inPreferredConfig = Bitmap.Config.ARGB_8888 }) ?: return@JpegDecoder null
            try {
                val w = bmp.width; val h = bmp.height
                val argb = IntArray(w * h)
                bmp.getPixels(argb, 0, w, 0, 0, w, h)
                val px = ByteArray(w * h * 3)
                for (i in argb.indices) {
                    val c = argb[i]
                    px[i * 3] = (c shr 16).toByte(); px[i * 3 + 1] = (c shr 8).toByte(); px[i * 3 + 2] = c.toByte()
                }
                TiffReader.Decoded(w, h, 3, px)
            } finally { bmp.recycle() }
        }
    }
}
