package com.barion.filmscans.core

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream

class ArchiveTest {
    class Mem(val b: ByteArray) : ByteSource {
        var bytesRead = 0L
        override val size = b.size.toLong()
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
            bytesRead += len
            System.arraycopy(b, pos.toInt(), buf, off, len)
        }
    }

    /** Pieces laid end to end; a [Zeros] piece takes no memory, so zips over 4 GB can be built. */
    class Pieces(private val parts: List<Any>) : ByteSource {
        class Zeros(val n: Long)
        private fun len(p: Any) = if (p is Zeros) p.n else (p as ByteArray).size.toLong()
        override val size = parts.sumOf { len(it) }
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int) {
            var p = pos; var o = off; var left = len; var base = 0L
            for (part in parts) {
                val l = len(part)
                if (left > 0 && p < base + l) {
                    val n = minOf(left.toLong(), base + l - p).toInt()
                    if (part is Zeros) java.util.Arrays.fill(buf, o, o + n, 0)
                    else System.arraycopy(part as ByteArray, (p - base).toInt(), buf, o, n)
                    p += n; o += n; left -= n
                }
                base += l
            }
            if (left > 0) throw java.io.EOFException()
        }
    }

    private val WHEN = LocalDateTime.of(2024, 3, 5, 14, 22, 10)

    private fun zip(files: Map<String, ByteArray>, stored: Set<String> = emptySet(), charset: java.nio.charset.Charset = Charsets.UTF_8): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, charset).use { z ->
            for ((name, data) in files) {
                val e = ZipEntry(name)
                e.time = WHEN.atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
                if (name in stored) {
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().also { it.update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun read(src: ByteSource, f: ArchiveFile) = ZipIndex.open(f.container, f.item).use { it.readBytes() }

    @Test fun listsLikeJavaZipFile() {
        val files = (1..40).associate { "Roll ${it % 4}/scan%03d.tif".format(it) to ByteArray(it * 997) { b -> (b * it).toByte() } }
        val bytes = zip(files, stored = files.keys.filterIndexed { i, _ -> i % 3 == 0 }.toSet())
        val tmp = java.io.File.createTempFile("listing", ".zip").apply { writeBytes(bytes); deleteOnExit() }
        val mine = ZipIndex.read(Mem(bytes))
        ZipFile(tmp).use { zf ->
            val theirs = zf.entries().toList()
            assertEquals(theirs.map { it.name }, mine.map { it.name })
            for ((a, b) in theirs.zip(mine)) {
                assertEquals(a.size, b.size); assertEquals(a.compressedSize, b.compressedSize)
                assertEquals(a.crc, b.crc); assertEquals(a.method, b.method)
                assertEquals(WHEN, b.time)
                assertArrayEquals(zf.getInputStream(a).readBytes(), ZipIndex.open(Mem(bytes), b).readBytes())
            }
        }
    }

    @Test fun nestedStoredZipsAreReadInPlace() {
        val big = ByteArray(40_000_000) { (it % 251).toByte() }
        val tiffs = zip(mapOf("Roll 1/000001.tif" to big, "Roll 1/000002.tif" to "two".toByteArray(), "Roll 2/000001.tif" to "three".toByteArray()),
            stored = setOf("Roll 1/000001.tif"))
        val jpegs = zip(mapOf("Roll 1/000001.jpg" to "j1".toByteArray()))
        val packed = zip(mapOf("x.tif" to "deflated inner".toByteArray()))
        val order = Mem(zip(mapOf(
            "Order 123/Tiffs.zip" to tiffs, "Order 123/Jpegs.zip" to jpegs, "Order 123/Packed.zip" to packed,
            "Order 123/readme.txt" to "hi".toByteArray(), "__MACOSX/._x" to "junk".toByteArray(),
            "../escape.tif" to "no".toByteArray(), "C:/escape.tif" to "no".toByteArray(),
        ), stored = setOf("Order 123/Tiffs.zip", "Order 123/Jpegs.zip")))
        val l = Archive.list(order)
        // Listing touched only the directories, not the 40 MB of scan inside.
        assertTrue("read ${order.bytesRead} bytes", order.bytesRead < 300_000)
        assertEquals(setOf("Order 123/Tiffs/Roll 1/000001.tif", "Order 123/Tiffs/Roll 1/000002.tif", "Order 123/Tiffs/Roll 2/000001.tif",
            "Order 123/Jpegs/Roll 1/000001.jpg", "Order 123/readme.txt"), l.files.map { it.path }.toSet())
        assertEquals(listOf("Order 123/Packed"), l.sealed.map { it.folder })
        assertArrayEquals("two".toByteArray(), read(order, l.files.first { it.path.endsWith("Roll 1/000002.tif") }))
        assertArrayEquals(big, read(order, l.files.first { it.path.endsWith("Roll 1/000001.tif") }))
        assertEquals(WHEN, l.files.first().item.time)
        // The compressed inner zip is unpacked by streaming it.
        val got = mutableListOf<String>()
        ZipWalk.walk(ZipIndex.open(l.sealed[0].container, l.sealed[0].item)) { p, d, _ -> got += p + "=" + String(d.readBytes()) }
        assertEquals(listOf("x.tif=deflated inner"), got)
    }

    @Test fun headReadsOnlyTheStart() {
        val data = ByteArray(5_000_000) { (it * 7).toByte() }
        val src = Mem(zip(mapOf("a.tif" to data)))
        val item = ZipIndex.read(src).single()
        assertArrayEquals(data.copyOf(65536), ZipIndex.head(src, item, 65536))
    }

    @Test fun dosAndUnicodeNames() {
        val cp = java.nio.charset.Charset.forName("IBM437")
        val bytes = zip(mapOf("Café Rôll/scan.tif" to "a".toByteArray()), charset = cp)
        assertEquals("Café Rôll/scan.tif", ZipIndex.read(Mem(bytes)).single().name)
        assertEquals("Ünïcode/ß.tif", ZipIndex.read(Mem(zip(mapOf("Ünïcode/ß.tif" to "a".toByteArray())))).single().name)
        // Info-ZIP's Unicode path field wins over a mangled DOS name.
        val out = ByteArrayOutputStream()
        ZipOutputStream(out, Charsets.US_ASCII).use { z ->
            val raw = "x?.tif"
            val utf = "xé.tif".toByteArray()
            val crc = CRC32().also { it.update(raw.toByteArray()) }.value
            val extra = ByteBuffer.allocate(4 + 5 + utf.size).order(ByteOrder.LITTLE_ENDIAN)
                .putShort(0x7075).putShort((5 + utf.size).toShort()).put(1).putInt(crc.toInt()).put(utf).array()
            z.putNextEntry(ZipEntry(raw).apply { setExtra(extra) }); z.write(1); z.closeEntry()
        }
        assertEquals("xé.tif", ZipIndex.read(Mem(out.toByteArray())).single().name)
        assertEquals(128, "ÇüéâäàåçêëèïîìÄÅÉæÆôöòûùÿÖÜ¢£¥₧ƒáíóúñÑªº¿⌐¬½¼¡«»░▒▓│┤╡╢╖╕╣║╗╝╜╛┐└┴┬├─┼╞╟╚╔╩╦╠═╬╧╨╤╥╙╘╒╓╫╪┘┌█▄▌▐▀αßΓπΣσµτΦΘΩδ∞φε∩≡±≥≤⌠⌡÷≈°∙·√ⁿ²■\u00A0".length)
    }

    @Test fun dataInFrontOfTheZip() {
        val z = zip(mapOf("a/b.tif" to "hello".toByteArray()))
        val src = Mem(ByteArray(12345) { 7 } + z)
        val l = Archive.list(src)
        assertEquals("hello", String(read(src, l.files.single())))
    }

    @Test fun notAZip() {
        assertTrue(runCatching { ZipIndex.read(Mem(ByteArray(1000))) }.isFailure)
        // A ".zip" inside that isn't one is unpacked as a plain file.
        val l = Archive.list(Mem(zip(mapOf("fake.zip" to "not a zip at all".toByteArray()), stored = setOf("fake.zip"))))
        assertEquals(listOf("fake.zip"), l.files.map { it.path })
    }

    /** Hand-built ZIP64: a 5 GB stored file, then a stored inner zip that starts past 4 GB. */
    @Test fun zip64OverFourGigabytes() {
        val inner = zip(mapOf("Roll 9/000001.tif" to "past 4 GB".toByteArray()))
        val bigSize = 5L * 1024 * 1024 * 1024
        fun le(n: Int, f: ByteBuffer.() -> Unit) = ByteBuffer.allocate(n).order(ByteOrder.LITTLE_ENDIAN).apply(f).array()
        val dosTime = (14 shl 11) or (22 shl 5) or 5; val dosDate = ((2024 - 1980) shl 9) or (3 shl 5) or 5
        val innerCrc = CRC32().also { it.update(inner) }.value.toInt()
        val n1 = "Tiffs/huge.tif".toByteArray(); val n2 = "Tiffs.zip".toByteArray()
        fun local(name: ByteArray, crc: Int, size: Long, z64: Boolean) = le(30 + name.size + (if (z64) 20 else 0)) {
            putInt(0x04034b50); putShort(45); putShort(0); putShort(0); putShort(dosTime.toShort()); putShort(dosDate.toShort())
            putInt(crc); putInt(if (z64) -1 else size.toInt()); putInt(if (z64) -1 else size.toInt())
            putShort(name.size.toShort()); putShort((if (z64) 20 else 0).toShort()); put(name)
            if (z64) { putShort(1); putShort(16); putLong(size); putLong(size) }
        }
        val l1 = local(n1, 0, bigSize, true)
        val off2 = l1.size + bigSize
        val l2 = local(n2, innerCrc, inner.size.toLong(), false)
        fun central(name: ByteArray, crc: Int, size: Long, offset: Long): ByteArray {
            val sizes = size >= 0xFFFFFFFFL; val off = offset >= 0xFFFFFFFFL
            val ex = (if (sizes) 16 else 0) + (if (off) 8 else 0)
            return le(46 + name.size + (if (ex > 0) 4 + ex else 0)) {
                putInt(0x02014b50); putShort(45); putShort(45); putShort(0); putShort(0)
                putShort(dosTime.toShort()); putShort(dosDate.toShort()); putInt(crc)
                putInt(if (sizes) -1 else size.toInt()); putInt(if (sizes) -1 else size.toInt())
                putShort(name.size.toShort()); putShort((if (ex > 0) 4 + ex else 0).toShort()); putShort(0)
                putShort(0); putShort(0); putInt(0); putInt(if (off) -1 else offset.toInt()); put(name)
                if (ex > 0) { putShort(1); putShort(ex.toShort()); if (sizes) { putLong(size); putLong(size) }; if (off) putLong(offset) }
            }
        }
        val c1 = central(n1, 0, bigSize, 0); val c2 = central(n2, innerCrc, inner.size.toLong(), off2)
        val cdStart = off2 + l2.size + inner.size
        val cdSize = (c1.size + c2.size).toLong()
        val rec64 = le(56) { putInt(0x06064b50); putLong(44); putShort(45); putShort(45); putInt(0); putInt(0); putLong(2); putLong(2); putLong(cdSize); putLong(cdStart) }
        val loc = le(20) { putInt(0x07064b50); putInt(0); putLong(cdStart + cdSize); putInt(1) }
        val eocd = le(22) { putInt(0x06054b50); putShort(0); putShort(0); putShort(-1); putShort(-1); putInt(-1); putInt(-1); putShort(0) }
        val src = Pieces(listOf(l1, Pieces.Zeros(bigSize), l2, inner, c1, c2, rec64, loc, eocd))

        val items = ZipIndex.read(src)
        assertEquals(listOf(bigSize, inner.size.toLong()), items.map { it.size })
        assertEquals(LocalDateTime.of(2024, 3, 5, 14, 22, 10), items[0].time)
        val l = Archive.list(src)
        assertEquals(listOf("Tiffs/huge.tif", "Tiffs/Roll 9/000001.tif"), l.files.map { it.path })
        assertEquals("past 4 GB", String(read(src, l.files[1])))
        assertArrayEquals(ByteArray(100), ZipIndex.head(src, items[0], 100))
    }

    // ---------------------------------------------------------------- dates

    private fun jpeg(f: Frame, withExif: Boolean, xmp: String?): ByteArray {
        val o = ByteArrayOutputStream()
        o.write(byteArrayOf(0xFF.toByte(), 0xD8.toByte()))
        o.write(Metadata.segment(0xE0, "JFIF\u0000\u0001\u0001".toByteArray(Charsets.ISO_8859_1)))
        if (withExif) o.write(Metadata.exif(f, RollMeta(), Credits()))
        if (xmp != null) o.write(Metadata.segment(0xE1, (JpegHeader.XMP_ID + xmp).toByteArray(Charsets.UTF_8)))
        o.write(byteArrayOf(0xFF.toByte(), 0xDA.toByte(), 0, 2, 1, 2, 3))
        return o.toByteArray()
    }

    @Test fun labThatOnlyWritesMetadataDate() {
        val xmp = """<x:xmpmeta><rdf:Description xmp:MetadataDate="2024-03-05T14:22:11-05:00" xmp:CreatorTool="Lab"/></x:xmpmeta>"""
        val d = Dates.fromImage(Mem(jpeg(Frame("a", WHEN, 1, 1), withExif = false, xmp = xmp)))
        assertEquals(listOf(LocalDateTime.of(2024, 3, 5, 14, 22, 11)), d.map { it.date })
        assertEquals("XMP xmp:MetadataDate", d[0].source)
    }

    @Test fun exifDateTakenComesFirst() {
        val xmp = """<rdf:Description xmp:MetadataDate="2025-01-01T00:00:00" xmp:CreateDate="2023-01-01T10:00:00"/>"""
        val d = Dates.fromImage(Mem(jpeg(Frame("a", WHEN, 1, 1), withExif = true, xmp = xmp)))
        assertEquals(WHEN, d[0].date); assertEquals("EXIF date taken", d[0].source)
        // Then digitized (also WHEN), then XMP CreateDate, then modify date, then MetadataDate.
        assertEquals(listOf("EXIF date taken", "EXIF date digitized", "XMP xmp:CreateDate", "EXIF modify date", "XMP xmp:MetadataDate"),
            d.map { it.source })
    }

    @Test fun zeroAndFutureDatesAreIgnored() {
        assertNull(Dates.usable(LocalDateTime.of(1970, 1, 1, 0, 0, 5)))
        assertNull(Dates.usable(LocalDateTime.of(1980, 1, 1, 0, 0)))
        assertNull(Dates.usable(LocalDateTime.of(1904, 1, 1, 0, 0).minusYears(10)))
        assertNull(Dates.usable(LocalDateTime.now().plusYears(3)))
        assertEquals(LocalDateTime.of(1980, 1, 1, 9, 0), Dates.usable(LocalDateTime.of(1980, 1, 1, 9, 0)))
        assertEquals(emptyList<DateFound>(), Dates.fromXmp("""<a xmp:MetadataDate="0000:00:00 00:00:00"/>"""))
        assertNull(ZipIndex.dosTime(0, 0))
    }

    // ---------------------------------------------------------------- names and plan

    @Test fun namesAddAroundTheLabNumber() {
        val p = Names.Parts(before = "LomoColor92", after = "RoyalWe")
        assertEquals("LomoColor92_008030000001_RoyalWe", Names.around("008030000001", p, 1, WHEN, "Roll", ""))
        assertEquals("008030000001", Names.around("008030000001", Names.Parts(), 1, WHEN, "Roll", ""))
        assertEquals("Portra 400 000001 07", Names.around("000001", Names.Parts("{film}", " ", "{nn}"), 7, WHEN, "R", "Portra 400"))
        assertEquals("2024-03-05-000001", Names.around("000001", Names.Parts("{date}", "-", ""), 1, WHEN, "R", ""))
        assertEquals("a_b_000001", Names.around("000001", Names.Parts("a/b", "_", "  "), 1, WHEN, "R", ""))
        assertEquals("_CON", Names.clean("CON"))
        assertEquals("_nul.txt", Names.clean("nul.txt"))
        assertEquals("Roll 1", Names.clean("Roll 1. . "))
        assertEquals("a_b", Names.clean("a:b"))
    }

    private fun listing(vararg paths: String): Listing {
        val src = Mem(zip(paths.associateWith { it.toByteArray() }))
        return Archive.list(src)
    }

    @Test fun planRenamesDropsAndSkipsFolders() {
        val l = listing("Tiffs/Roll 1/a.tif", "Tiffs/Roll 1/b.tif", "Tiffs/Roll 10/a.tif", "Tiffs/Roll 2/a.tif", "Jpegs/Roll 1/a.jpg")
        assertEquals(listOf("Jpegs", "Jpegs/Roll 1", "Tiffs", "Tiffs/Roll 1", "Tiffs/Roll 2", "Tiffs/Roll 10"),
            UnzipPlan.folders(l).map { it.path })
        assertEquals(2, UnzipPlan.folders(l).first { it.path == "Tiffs/Roll 1" }.files)

        val r = UnzipPlan.build(l, "Order", mapOf("Tiffs" to "", "Tiffs/Roll 1" to "Portra: 400"), setOf("Jpegs"))
        assertEquals(listOf("Order/Portra_ 400/a.tif", "Order/Portra_ 400/b.tif", "Order/Roll 10/a.tif", "Order/Roll 2/a.tif"),
            r.targets.map { it.path }.sorted())
        assertTrue(r.conflicts.isEmpty())

        // Dropping both Jpegs and Tiffs levels lands two rolls' a.* in different names, but two Roll 1s collide.
        val clash = UnzipPlan.build(listing("Tiffs/Roll 1/a.tif", "Jpegs/Roll 1/a.tif"), "", mapOf("Tiffs" to "", "Jpegs" to ""), emptySet())
        assertEquals(1, clash.conflicts.size)
        assertTrue(clash.conflicts[0].startsWith("Roll 1/a.tif"))
        // Case doesn't make two names different on the phone's storage.
        assertEquals(1, UnzipPlan.build(listing("A/x.tif", "a/X.TIF"), "", emptyMap(), emptySet()).conflicts.size)
    }
}
