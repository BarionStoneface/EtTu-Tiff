package com.barion.filmscans.pc

import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.Frame
import com.barion.filmscans.core.JpegEncoder
import com.barion.filmscans.core.Metadata
import com.barion.filmscans.core.RollMeta
import com.barion.filmscans.core.UnzipPlan
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.file.Files
import java.time.LocalDateTime
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The PC app's file handling, on real files in a temporary folder. */
class EngineTest {
    private lateinit var tmp: File

    @Before fun setUp() {
        tmp = Files.createTempDirectory("ettutiff-test").toFile()
        System.setProperty("ettutiff.data", File(tmp, "appdata").path)
        ImageIoJpeg.install()
    }

    private fun resource(name: String) = javaClass.classLoader!!.getResource("tiff/$name")!!.readBytes()

    private fun jpeg(w: Int = 48, h: Int = 32, segments: List<ByteArray> = emptyList()): ByteArray {
        val o = ByteArrayOutputStream()
        val enc = JpegEncoder(o, w, h, 3, 90)
        enc.writeHeader(segments)
        enc.writeRows(ByteArray(w * 3 * h) { (it * 7).toByte() }, h)
        enc.finish()
        return o.toByteArray()
    }

    /** Everything from the start of the image data: what tagging must never change. */
    private fun imageData(j: ByteArray): ByteArray {
        var p = 2
        while (j[p + 1].toInt() and 0xFF != 0xDA) p += 2 + ((j[p + 2].toInt() and 0xFF shl 8) or (j[p + 3].toInt() and 0xFF))
        return j.copyOfRange(p, j.size)
    }

    private val scanned = LocalDateTime.of(2019, 4, 12, 14, 30)

    @Test fun convertsARollDeletesOnlyWhatWasChosenAndRenamesTheFolder() {
        val roll = File(tmp, "Roll 1 TIFF").apply { mkdirs() }
        File(roll, "000001.tif").writeBytes(resource("rgb_u8_strips.tif"))
        File(roll, "000002.tif").writeBytes(resource("rgb_u16_lzw_pred2_tiled.tif"))
        File(roll, "000001.thm").writeBytes(byteArrayOf(1, 2, 3))

        val found = Rolls.find(tmp, mutableListOf())
        assertEquals(1, found.size)
        val info = found[0]
        assertEquals(listOf("000001.tif", "000002.tif"), info.files.map { it.name })
        assertEquals(listOf("000001.thm"), info.sidecars.map { it.name })

        val names = info.files.map { stem(it.name) }
        val undated = RollPlan(info, names, RollMeta(film = "Kodak Portra 400"), keep = false)
        if (info.files.any { !it.date.embedded }) assertTrue(undated.problems().any { "no scan date" in it })

        val plan = RollPlan(info, names, RollMeta(film = "Kodak Portra 400"), keep = false, dateText = "2019-04-12 14:30", deleteSidecars = true)
        assertEquals(emptyList<String>(), plan.problems())
        for (i in info.files.indices) Convert.one(plan, i, Credits("Test Person"), 95)
        assertEquals(1, Convert.deleteSidecars(info))
        val moved = Convert.renameFolder(plan).getOrThrow()

        assertEquals("Roll 1 JPEG", moved.name)
        assertEquals(listOf("000001.jpg", "000002.jpg"), listed(moved).map { it.name })
        for (f in listed(moved)) {
            val date = FileSource(f).use { Dates.fromImage(it) }.first()
            // Undated scans get the typed date; dated ones keep their own. Either way the file says the same.
            assertEquals(date.date, FileTimes.modified(f))
            assertTrue(String(f.readBytes(), Charsets.ISO_8859_1).contains("Kodak Portra 400"))
        }
    }

    @Test fun keepingTheTiffsChangesNothingElse() {
        val roll = File(tmp, "Roll 2").apply { mkdirs() }
        val tif = File(roll, "a.tif").apply { writeBytes(resource("rgb_u8_strips.tif")) }
        val before = tif.readBytes()
        val info = Rolls.find(roll, mutableListOf()).single()
        val plan = RollPlan(info, listOf("Portra_a"), RollMeta(), keep = true, dateText = "2019-04-12")
        Convert.one(plan, 0, Credits(), 100)
        assertArrayEquals(before, tif.readBytes())
        assertTrue(File(roll, "Portra_a.jpg").isFile)
        assertFalse(File(roll, "Portra_a.jpg.part").exists())
    }

    @Test fun tagsLabJpegsIntoCopiesOrInPlaceWithoutTouchingThePicture() {
        val roll = File(tmp, "Lab").apply { mkdirs() }
        val lab = jpeg(segments = listOf(Metadata.segment(0xFE, "lab comment".toByteArray())))
        File(roll, "000001.jpg").writeBytes(lab)
        val info = Rolls.find(roll, mutableListOf()).single()
        assertTrue(info.jpegRoll)

        // Keeping the originals: a new folder beside the roll.
        val keep = RollPlan(info, listOf("000001"), RollMeta(camera = "Nikon FM2"), keep = true, dateText = "2020-01-02")
        assertEquals(emptyList<String>(), keep.problems())
        Convert.one(keep, 0, Credits(), 100)
        val copy = File(tmp, "Lab tagged/000001.jpg")
        assertArrayEquals(lab, File(roll, "000001.jpg").readBytes())
        assertArrayEquals(imageData(lab), imageData(copy.readBytes()))
        assertFalse(String(copy.readBytes(), Charsets.ISO_8859_1).contains("lab comment"))
        assertTrue(String(copy.readBytes(), Charsets.ISO_8859_1).contains("Nikon FM2"))
        // The copies folder now exists, so a second run asks for another name.
        assertTrue(RollPlan(info, listOf("000001"), RollMeta(), keep = true, dateText = "2020-01-02").problems().any { "already a folder" in it })

        // In place, same name: the tagged file takes the original's place.
        val inPlace = RollPlan(info, listOf("000001"), RollMeta(camera = "Leica M6"), keep = false, dateText = "2020-01-02")
        Convert.one(inPlace, 0, Credits(), 100)
        val now = File(roll, "000001.jpg").readBytes()
        assertArrayEquals(imageData(lab), imageData(now))
        assertTrue(String(now, Charsets.ISO_8859_1).contains("Leica M6"))
        assertEquals(listOf("000001.jpg"), listed(roll).map { it.name })
    }

    private fun zip(entries: Map<String, ByteArray>, stored: Boolean = false, time: LocalDateTime = scanned): ByteArray {
        val o = ByteArrayOutputStream()
        ZipOutputStream(o).use { z ->
            for ((name, data) in entries) {
                val e = ZipEntry(name)
                e.timeLocal = time
                if (stored) {
                    e.method = ZipEntry.STORED; e.size = data.size.toLong(); e.compressedSize = data.size.toLong()
                    e.crc = CRC32().apply { update(data) }.value
                }
                z.putNextEntry(e); z.write(data); z.closeEntry()
            }
        }
        return o.toByteArray()
    }

    @Test fun unzipsANestedDownloadWhereThePlanSaysWithScanDates() {
        val tiffs = zip(mapOf("Roll 1/000001.tif" to resource("rgb_u8_strips.tif")))
        val jpegs = zip(mapOf("Roll 1/000001.jpg" to jpeg()))
        val order = File(tmp, "Order 123.zip").apply { writeBytes(zip(mapOf("Tiffs.zip" to tiffs, "Jpegs.zip" to jpegs), stored = true)) }

        val c = Unzip.read(order)
        assertEquals(setOf("Tiffs/Roll 1/000001.tif", "Jpegs/Roll 1/000001.jpg"), c.listing.files.map { it.path }.toSet())
        val plan = UnzipPlan.build(c.listing, "Order 123", mapOf("Tiffs/Roll 1" to "Portra"), emptySet())
        val dest = File(tmp, "Pictures").apply { mkdirs() }
        val out = Unzip.Outcome()
        Unzip.run(plan, dest, labJpegDates = true, out = out)
        c.close()

        assertEquals(emptyList<String>(), out.problems)
        assertEquals(2, out.written)
        // Exactly where the plan showed: one "Order 123" folder, not two.
        val tif = File(dest, "Order 123/Tiffs/Portra/000001.tif")
        val jpg = File(dest, "Order 123/Jpegs/Roll 1/000001.jpg")
        assertTrue(tif.isFile); assertTrue(jpg.isFile)
        assertFalse(File(dest, "Order 123/Order 123").exists())
        assertEquals(scanned, FileTimes.modified(jpg))
        // The lab JPEG had no date inside: it gets its date in the zip as its date taken.
        assertEquals(1, out.jpegsDated)
        assertEquals(DateFoundCheck("EXIF date taken", scanned), FileSource(jpg).use { Dates.fromImage(it).first() }.let { DateFoundCheck(it.source, it.date) })
        // Remembered for later, so the TIFF's date is known when the folder is opened.
        ZipDates.putAll(out.dates)
        assertEquals(scanned, Rolls.fileDate(tif).date)

        // Running it again leaves what's there alone.
        val again = Unzip.Outcome()
        Unzip.read(order).use { Unzip.run(UnzipPlan.build(it.listing, "Order 123", mapOf("Tiffs/Roll 1" to "Portra"), emptySet()), dest, true, again) }
        assertEquals(0, again.written); assertEquals(2, again.already)
    }

    private data class DateFoundCheck(val source: String, val date: LocalDateTime)

    @Test fun unzipsAnyZipLockedOrDeflate64() {
        for (name in listOf("zipcrypto.zip", "aes256.zip", "locked-inner.zip", "deflate64.zip")) {
            val zip = File(tmp, name).apply { writeBytes(this@EngineTest.javaClass.classLoader!!.getResource("zip/$name")!!.readBytes()) }
            val c = Unzip.read(zip)
            assertFalse(name, c.photo)
            val password = if (c.listing.needsPassword) "film soup ñ" else null
            val dest = File(tmp, "out-$name").apply { mkdirs() }
            val out = Unzip.Outcome()
            Unzip.run(UnzipPlan.build(c.listing, "", emptyMap(), emptySet()), dest, labJpegDates = false, out = out, password = password)
            c.close()
            assertEquals(name, emptyList<String>(), out.problems)
            assertEquals(name, "hello\n", File(dest, "readme.txt").readText())
            assertTrue(name, File(dest, if (name == "locked-inner.zip") "Inner/Roll 1/notes.txt" else "Roll 1/notes.txt").length() == 150_000L)
            assertFalse(name, dest.walk().any { it.name.endsWith(".part") })
        }
        // Without the password nothing locked is written, and it says why.
        val zip = File(tmp, "zipcrypto.zip")
        val out = Unzip.Outcome()
        Unzip.read(zip).use { Unzip.run(UnzipPlan.build(it.listing, "", emptyMap(), emptySet()), File(tmp, "nopw").apply { mkdirs() }, false, out) }
        assertEquals(0, out.written)
        assertTrue(out.problems.joinToString(), out.problems.all { it.endsWith("is password-protected") })
    }

    @Test fun datingAJpegKeepsItsOtherDetailsAndPicture() {
        val frame = Frame("x.jpg", scanned, 48, 32, scannerMake = "NORITSU KOKI", scannerModel = "HS-1800")
        val withExif = Metadata.exif(frame, RollMeta(), Credits())
        val f = File(tmp, "x.jpg").apply { writeBytes(jpeg(segments = listOf(withExif))) }
        val later = LocalDateTime.of(2021, 6, 7, 8, 9, 10)
        ExifDate.write(f, later)
        val b = f.readBytes()
        assertArrayEquals(imageData(jpeg(segments = listOf(withExif))), imageData(b))
        assertEquals(later, FileSource(f).use { Dates.fromImage(it).first() }.date)
        assertTrue(String(b, Charsets.ISO_8859_1).contains("NORITSU KOKI"))
    }

    @Test fun fixDatesUsesTheDateInsideEachPhoto() {
        val dir = File(tmp, "From phone/Roll 3").apply { mkdirs() }
        val frame = Frame("a.jpg", scanned, 48, 32)
        val dated = File(dir, "a.jpg").apply { writeBytes(jpeg(segments = listOf(Metadata.exif(frame, RollMeta(), Credits())))) }
        val undated = File(dir, "b.jpg").apply { writeBytes(jpeg()) }
        val items = FixDates.look(File(tmp, "From phone"))
        assertEquals(2, items.size)
        assertNull(items.first { it.file == undated }.date)
        assertEquals(1 to 0, FixDates.apply(items))
        assertEquals(scanned, FileTimes.modified(dated))
    }

    @Test fun planProblemsCatchWhatWouldGoWrong() {
        val roll = File(tmp, "Roll 4").apply { mkdirs() }
        File(roll, "a.tif").writeBytes(resource("rgb_u8_strips.tif"))
        File(roll, "b.tif").writeBytes(resource("rgb_u8_strips.tif"))
        File(roll, "x.jpg").writeBytes(jpeg())
        val info = Rolls.find(roll, mutableListOf()).single()
        fun plan(vararg n: String, overwrite: Boolean = false) =
            RollPlan(info, n.toList(), RollMeta(), keep = true, dateText = "2019-04-12", overwrite = overwrite).problems()
        assertTrue(plan("same", "same").any { "both be named" in it })
        assertTrue(plan("a:b", "c").any { "Windows" in it })
        assertTrue(plan("x", "c").any { "already in the folder" in it })
        assertEquals(emptyList<String>(), plan("x", "c", overwrite = true))
        assertTrue(RollPlan(info, listOf("a", "b"), RollMeta(), keep = true, dateText = "12 April").problems().any { "isn't a date" in it })
    }

    @Test fun aCutShortJpegFailsTheCheck() {
        val good = jpeg(64, 48)
        val f = File(tmp, "cut.jpg").apply { writeBytes(good.copyOf(good.size - 40)) }
        assertTrue(runCatching { Convert.verify(f, 64, 48) }.isFailure)
        f.writeBytes(good)
        Convert.verify(f, 64, 48)
        assertTrue(runCatching { Convert.verify(f, 63, 48) }.isFailure)
    }

    @Test fun previewsTurnEachOrientationTheRightWay() {
        // A 3x2 picture with a different colour in every pixel.
        val img = BufferedImage(3, 2, BufferedImage.TYPE_INT_RGB)
        for (y in 0 until 2) for (x in 0 until 3) img.setRGB(x, y, y * 3 + x + 1)
        fun px(o: Int) = Previews.turn(img, o).let { t -> (0 until t.height).map { y -> (0 until t.width).map { x -> t.getRGB(x, y) and 0xFFFFFF } } }
        assertEquals(listOf(listOf(1, 2, 3), listOf(4, 5, 6)), px(1))
        assertEquals(listOf(listOf(3, 2, 1), listOf(6, 5, 4)), px(2))
        assertEquals(listOf(listOf(6, 5, 4), listOf(3, 2, 1)), px(3))
        assertEquals(listOf(listOf(4, 5, 6), listOf(1, 2, 3)), px(4))
        assertEquals(listOf(listOf(1, 4), listOf(2, 5), listOf(3, 6)), px(5))
        assertEquals(listOf(listOf(4, 1), listOf(5, 2), listOf(6, 3)), px(6))
        assertEquals(listOf(listOf(6, 3), listOf(5, 2), listOf(4, 1)), px(7))
        assertEquals(listOf(listOf(3, 6), listOf(2, 5), listOf(1, 4)), px(8))
    }
}
