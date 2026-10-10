package com.barion.filmscans

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.barion.filmscans.core.Thumbs
import com.barion.filmscans.core.TiffReader

/** Small previews for the rolls screen, decoded straight from the scans. */
object Thumbnails {
    /** A small preview decoded straight from the TIFF, turned the way it should display. */
    fun of(ctx: Context, f: ScanFile): ImageBitmap? = if (f.isJpeg) jpeg(ctx, f) else runCatching {
        UriSource.open(ctx, f.doc.uri).use { src ->
            val t = TiffReader(src)
            val th = Thumbs.sample(t, 320)
            var bmp = Bitmap.createBitmap(th.argb, th.width, th.height, Bitmap.Config.ARGB_8888)
            val m = orientationMatrix(t.orientation)
            if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
            bmp.asImageBitmap()
        }
    }.getOrNull()

    private fun jpeg(ctx: Context, f: ScanFile): ImageBitmap? = runCatching {
        val sample = Integer.highestOneBit(maxOf(1, maxOf(f.width, f.height) / 320))
        var bmp = ctx.contentResolver.openInputStream(f.doc.uri)?.use {
            BitmapFactory.decodeStream(it, null, BitmapFactory.Options().apply { inSampleSize = sample })
        } ?: return@runCatching null
        val m = orientationMatrix(f.orientation)
        if (!m.isIdentity) bmp = Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, m, true)
        bmp.asImageBitmap()
    }.getOrNull()

    private fun orientationMatrix(o: Int) = Matrix().apply {
        when (o) {
            2 -> setScale(-1f, 1f); 3 -> setRotate(180f); 4 -> setScale(1f, -1f)
            5 -> { setRotate(90f); postScale(-1f, 1f) }; 6 -> setRotate(90f)
            7 -> { setRotate(270f); postScale(-1f, 1f) }; 8 -> setRotate(270f)
        }
    }
}
