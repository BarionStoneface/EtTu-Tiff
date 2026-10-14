package com.barion.filmscans.core

import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.zip.Deflater

/** Small TIFFs made in memory for tests. */
object TestTiff {
    fun shorts(vararg v: Int): ByteArray = ByteBuffer.allocate(v.size * 2).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putShort(it.toShort()) } }.array()
    fun longs(vararg v: Long): ByteArray = ByteBuffer.allocate(v.size * 4).order(ByteOrder.LITTLE_ENDIAN).also { b -> v.forEach { b.putInt(it.toInt()) } }.array()

    /**
     * Minimal little-endian TIFF: one strip per [rps] rows. [extra] adds text tags; [claim] writes
     * whatever a tag's directory entry should say (type to bytes), however wrong, for damage tests.
     */
    fun tiff(w: Int, h: Int, spp: Int, bits: Int, pixels: IntArray, compression: Int = 1, rps: Int = 16,
             extra: Map<Int, String> = emptyMap(), claim: Map<Int, Pair<Int, ByteArray>> = emptyMap()): ByteArray {
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
        entries[256] = 4 to longs(w.toLong()); entries[257] = 4 to longs(h.toLong())
        entries[258] = 3 to shorts(*IntArray(spp) { bits })
        entries[259] = 3 to shorts(compression)
        entries[262] = 3 to shorts(if (spp == 3) 2 else 1)
        entries[277] = 3 to shorts(spp); entries[278] = 4 to longs(rps.toLong())
        extra.forEach { (t, s) -> entries[t] = 2 to (s.toByteArray() + 0) }
        entries[279] = 4 to longs(*LongArray(strips.size) { strips[it].size.toLong() })
        entries[273] = 4 to longs(*LongArray(strips.size)) // placeholder
        // layout: header, pixel data, value blobs, IFD
        val out = ByteArrayOutputStream()
        out.write(byteArrayOf('I'.code.toByte(), 'I'.code.toByte(), 42, 0, 0, 0, 0, 0))
        val offs = strips.map { s -> out.size().toLong().also { out.write(s) } }
        entries[273] = 4 to longs(*offs.toLongArray())
        entries.putAll(claim)
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

    fun image(w: Int, h: Int, spp: Int, max: Int) = IntArray(w * h * spp) { i ->
        val p = i / spp; val x = p % w; val y = p / w; val c = i % spp
        val v = (Math.sin(x / 13.0 + c) + Math.cos(y / 7.0) + 2) / 4
        (v * max).toInt()
    }
}
