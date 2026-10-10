package com.barion.filmscans

import android.content.Context
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.DocumentsContract
import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.JpegRetag
import com.barion.filmscans.core.Metadata
import com.barion.filmscans.core.RollMeta
import com.barion.filmscans.core.Scan
import com.barion.filmscans.core.TiffReader
import java.io.BufferedOutputStream
import java.time.LocalDateTime

/** Turning a roll's files into JPEGs (or tagging the lab's), and tidying the folder after. */
object Convert {
    /**
     * Convert one file. The TIFF is deleted only after the JPEG is written and read back. Returns
     * whether the JPEG's own file date could be set to the scan date (see [FileDates]).
     */
    fun one(ctx: Context, roll: Roll, f: ScanFile, index: Int, meta: RollMeta, credits: Credits, quality: Int,
            keepTiff: Boolean, progress: (Float) -> Unit): Boolean {
        if (f.isJpeg) return tag(ctx, roll, f, index, meta, credits, keepTiff)
        val target = f.newName + ".jpg"
        val existing = roll.existing[target.lowercase()]
        if (existing != null && !roll.overwrite) error("$target is already there")
        val outDoc = existing ?: roll.folder.createFile("image/jpeg", f.newName) ?: error("couldn't create $target")
        val date = scanDate(roll, f)
        try {
            UriSource.open(ctx, f.doc.uri).use { src ->
                val t = TiffReader(src)
                val frame = Scan.frame(t, f.name, date, index + 1, rollName(roll, keepTiff))
                val os = ctx.contentResolver.openOutputStream(outDoc.uri, if (existing != null) "wt" else "w")
                    ?: error("couldn't write $target")
                BufferedOutputStream(os, 1 shl 16).use { Scan.convert(t, frame, meta, credits, it, quality, progress) }
            }
            verify(ctx, outDoc.uri, f)
        } catch (e: Throwable) {
            if (existing == null) runCatching { outDoc.delete() }
            throw e
        }
        val dated = FileDates.set(outDoc.uri, date)
        if (!keepTiff && !f.doc.delete()) throw IllegalStateException("JPEG saved, but the TIFF couldn't be deleted")
        return dated
    }

    private fun scanDate(roll: Roll, f: ScanFile): LocalDateTime = if (f.date.embedded) f.date.date else roll.overrideDate()
        ?: f.date.date.takeIf { roll.useFileDates } ?: error("no scan date chosen")

    /** The roll's name as written into each photo: the new folder name only if the folder really gets it. */
    private fun rollName(roll: Roll, keep: Boolean) = if (!keep && roll.renameFolder) cleanName(roll.newFolderName) else roll.name

    /**
     * Tags a lab JPEG without re-saving the picture (see [JpegRetag]). Keeping the originals, the tagged
     * copy goes into the roll's copies folder. Replacing them, the original is only removed once the
     * tagged file is written and reads back; under the same name, the new file is written alongside
     * first and swapped in.
     */
    private fun tag(ctx: Context, roll: Roll, f: ScanFile, index: Int, meta: RollMeta, credits: Credits, keep: Boolean): Boolean {
        val date = scanDate(roll, f)
        val target = f.newName + ".jpg"
        val sameName = !keep && target.equals(f.name, ignoreCase = true)
        val dir = if (keep) roll.copiesDir() else roll.folder
        if (!keep && roll.files.any { it !== f && it.name.equals(target, true) }) error("$target is another of this roll's JPEGs")
        if (!keep && !sameName) roll.existing[target.lowercase()]?.let { old ->
            if (!roll.overwrite) error("$target is already there")
            if (!old.delete()) error("couldn't replace the $target already there")
        }
        val outDoc = (if (sameName) dir.createFile("application/octet-stream", "$target.part") else dir.createFile("image/jpeg", f.newName))
            ?: error("couldn't create $target")
        try {
            UriSource.open(ctx, f.doc.uri).use { src ->
                val info = JpegRetag.info(src)
                val frame = JpegRetag.frame(info, f.name, date, index + 1, rollName(roll, keep))
                val os = ctx.contentResolver.openOutputStream(outDoc.uri, "w") ?: error("couldn't write $target")
                BufferedOutputStream(os, 1 shl 16).use { JpegRetag.rewrite(src, Metadata.segments(frame, meta, credits), it) }
            }
            verify(ctx, outDoc.uri, f)
        } catch (e: Throwable) {
            runCatching { outDoc.delete() }
            throw e
        }
        if (keep) return FileDates.set(outDoc.uri, date)
        if (!sameName) {
            if (!f.doc.delete()) error("tagged as $target, but the original couldn't be removed")
            return FileDates.set(outDoc.uri, date)
        }
        // Same name: swap the tagged file in for the original.
        if (!f.doc.delete()) { runCatching { outDoc.delete() }; error("couldn't replace the original") }
        val renamed = runCatching { DocumentsContract.renameDocument(ctx.contentResolver, outDoc.uri, target) }.getOrNull()
        if (renamed != null) return FileDates.set(renamed, date)
        // This folder can't rename files: copy the tagged file to the real name instead.
        val final = dir.createFile("image/jpeg", f.newName) ?: error("tagged file left as $target.part; rename it to $target")
        ctx.contentResolver.openInputStream(outDoc.uri)!!.use { i -> ctx.contentResolver.openOutputStream(final.uri)!!.use { i.copyTo(it, 1 shl 16) } }
        verify(ctx, final.uri, f)
        outDoc.delete()
        return FileDates.set(final.uri, date)
    }

    private fun verify(ctx: Context, uri: Uri, f: ScanFile) {
        val o = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, o) }
        if (o.outWidth != f.width || o.outHeight != f.height)
            error("the JPEG didn't read back correctly (${o.outWidth}x${o.outHeight})")
        // Decode the whole stream at low resolution to make sure it isn't truncated.
        val small = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bmp = ctx.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, small) }
            ?: error("the JPEG didn't decode")
        bmp.recycle()
    }

    fun deleteSidecars(roll: Roll): Int = roll.sidecars.count { runCatching { it.delete() }.getOrDefault(false) }

    /** Returns the error, or null when renamed. */
    fun renameFolder(ctx: Context, roll: Roll): String? {
        val newName = cleanName(roll.newFolderName)
        if (newName.isEmpty() || newName == roll.name) return null
        return try {
            val r = DocumentsContract.renameDocument(ctx.contentResolver, roll.folder.uri, newName)
            roll.outputUri = r
            if (r == null) "couldn't rename the folder" else null
        } catch (e: Exception) {
            if (roll.isRoot) "the folder you picked can't be renamed from inside the app; rename it to \"$newName\" in My Files"
            else e.message ?: "couldn't rename the folder"
        }
    }
}
