package com.barion.filmscans.pc

import com.barion.filmscans.core.Metadata
import org.apache.commons.imaging.Imaging
import org.apache.commons.imaging.formats.jpeg.JpegImageMetadata
import org.apache.commons.imaging.formats.jpeg.exif.ExifRewriter
import org.apache.commons.imaging.formats.tiff.constants.ExifTagConstants
import org.apache.commons.imaging.formats.tiff.constants.TiffTagConstants
import org.apache.commons.imaging.formats.tiff.write.TiffOutputSet
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime

/**
 * Writes a date taken into a lab's JPEG, so photo apps sort it by when it was scanned. Only the
 * dates are added; the picture and the rest of its details are left as they are.
 */
object ExifDate {
    fun write(file: File, date: LocalDateTime) {
        val bytes = file.readBytes()
        val set = (Imaging.getMetadata(bytes) as? JpegImageMetadata)?.exif?.outputSet ?: TiffOutputSet()
        val s = Metadata.exifDate(date)
        val exif = set.getOrCreateExifDirectory()
        exif.removeField(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL)
        exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_ORIGINAL, s)
        if (exif.findField(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED) == null) exif.add(ExifTagConstants.EXIF_TAG_DATE_TIME_DIGITIZED, s)
        val root = set.getOrCreateRootDirectory()
        if (root.findField(TiffTagConstants.TIFF_TAG_DATE_TIME) == null) root.add(TiffTagConstants.TIFF_TAG_DATE_TIME, s)
        val tmp = File(file.path + ".exif")
        try {
            tmp.outputStream().buffered().use { ExifRewriter().updateExifMetadataLossless(bytes, it, set) }
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } finally {
            tmp.delete()
        }
    }
}
