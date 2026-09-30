package com.barion.filmscans.core

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

class ZipWalkTest {
    /** Builds a zip; [stored] entries are written uncompressed, as some zippers do for inner zips. */
    private fun zip(files: Map<String, ByteArray>, stored: Set<String> = emptySet()): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { z ->
            for ((name, data) in files) {
                val e = ZipEntry(name)
                e.time = 1_555_000_000_000L
                if (name in stored) {
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun walk(bytes: ByteArray): Map<String, String> {
        val got = LinkedHashMap<String, String>()
        ZipWalk.walk(ByteArrayInputStream(bytes)) { path, data, time ->
            got[path] = String(data.readBytes()); assertEquals(1_555_000_000_000L / 2000, time / 2000)
        }
        return got
    }

    @Test fun zipsInsideZips() {
        val roll1 = zip(mapOf("scan001.tif" to "a".toByteArray(), "scan002.tif" to "b".toByteArray(), "__MACOSX/._scan001.tif" to "x".toByteArray()))
        val roll2 = zip(mapOf("Roll 2/scan001.tif" to "c".toByteArray(), "Thumbs.db" to "x".toByteArray()))
        val deeper = zip(mapOf("Roll 3.zip" to zip(mapOf("f.tif" to "d".toByteArray()))))
        val order = zip(mapOf(
            "readme.txt" to "hi".toByteArray(),
            "Roll 1.zip" to roll1,
            "rolls/Roll 2.zip" to roll2,
            "More.zip" to deeper,
            "../evil.tif" to "no".toByteArray(),
        ), stored = setOf("Roll 1.zip"))
        assertEquals(mapOf(
            "readme.txt" to "hi",
            "Roll 1/scan001.tif" to "a",
            "Roll 1/scan002.tif" to "b",
            "rolls/Roll 2/Roll 2/scan001.tif" to "c",
            "More/Roll 3/f.tif" to "d",
        ), walk(order))
    }

    @Test fun bigEntriesStreamThrough() {
        val big = ByteArray(5_000_000) { (it % 251).toByte() }
        val order = zip(mapOf("Roll.zip" to zip(mapOf("big.tif" to big))))
        var n = 0
        ZipWalk.walk(ByteArrayInputStream(order)) { _, data, _ -> n = data.readBytes().size }
        assertEquals(big.size, n)
    }
}
