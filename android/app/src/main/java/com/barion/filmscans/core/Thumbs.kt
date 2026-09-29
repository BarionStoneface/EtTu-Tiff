package com.barion.filmscans.core

/** A small preview straight from the TIFF, without converting it. */
class Thumb(val width: Int, val height: Int, val argb: IntArray)

object Thumbs {
    /** Samples every Nth row and column so the long side is about [maxSide] pixels. */
    fun sample(t: TiffReader, maxSide: Int = 320): Thumb {
        t.checkSupported()
        val step = maxOf(1, (maxOf(t.width, t.height) + maxSide - 1) / maxSide)
        val tw = (t.width + step - 1) / step
        val th = (t.height + step - 1) / step
        val out = IntArray(tw * th)
        val oc = t.outChannels
        t.readRows(wantRow = { it % step == 0 }) { y0, rows, px ->
            for (r in 0 until rows) {
                val y = y0 + r
                if (y % step != 0) continue
                val ty = y / step
                if (ty >= th) continue
                val base = r * t.width * oc
                for (tx in 0 until tw) {
                    val i = base + tx * step * oc
                    val c = if (oc == 1) {
                        val g = px[i].u(); (g shl 16) or (g shl 8) or g
                    } else (px[i].u() shl 16) or (px[i + 1].u() shl 8) or px[i + 2].u()
                    out[ty * tw + tx] = c or (0xFF shl 24)
                }
            }
        }
        return Thumb(tw, th, out)
    }
}
