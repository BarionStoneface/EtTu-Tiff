package com.barion.filmscans.pc

import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.JpegRetag
import com.barion.filmscans.core.Metadata
import com.barion.filmscans.core.Scan
import com.barion.filmscans.core.TiffReader
import java.awt.Desktop
import java.io.BufferedOutputStream
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import javax.imageio.ImageIO
import javax.imageio.event.IIOReadWarningListener

/** Deleting: to the Recycle Bin where Windows allows it, otherwise for good. */
object Trash {
    val available: Boolean by lazy {
        runCatching { Desktop.isDesktopSupported() && Desktop.getDesktop().isSupported(Desktop.Action.MOVE_TO_TRASH) }.getOrDefault(false)
    }

    fun delete(f: File): Boolean = if (available) runCatching { Desktop.getDesktop().moveToTrash(f) }.getOrDefault(false) || f.deleteRecursively()
        else f.deleteRecursively()
}

/** Turning a roll's files into JPEGs (or tagging the lab's), and tidying the folder after. */
object Convert {
    /**
     * Converts (or tags) file [i] of [p]. The JPEG is written under a temporary name and only gets its
     * real name once it's complete and reads back; an original is deleted only after that. Returns
     * whether the JPEG's own dates could be set to the scan date.
     */
    fun one(p: RollPlan, i: Int, credits: Credits, quality: Int, progress: (Float) -> Unit = {}): Boolean {
        val f = p.files[i]
        val date = p.scanDate(i) ?: error("no scan date chosen")
        val target = p.names[i] + ".jpg"
        val dir = if (f.isJpeg && p.keep) copiesDir(p) else p.roll.folder
        val out = File(dir, target)
        val sameFile = out.absolutePath.equals(f.file.absolutePath, ignoreCase = true)
        if (f.isJpeg && !p.keep && p.files.withIndex().any { (j, o) -> j != i && o.name.equals(target, true) })
            error("$target is another of this roll's JPEGs")
        if (!sameFile && out.exists() && !p.overwrite) error("$target is already there")

        val part = File(dir, "$target.part")
        var originalGone = false
        try {
            FileSource(f.file).use { src ->
                BufferedOutputStream(part.outputStream(), 1 shl 16).use { os ->
                    if (f.isJpeg) {
                        val info = JpegRetag.info(src)
                        val frame = JpegRetag.frame(info, f.name, date, i + 1, p.rollName)
                        JpegRetag.rewrite(src, Metadata.segments(frame, p.meta, credits), os)
                    } else {
                        val t = TiffReader(src)
                        val frame = Scan.frame(t, f.name, date, i + 1, p.rollName)
                        Scan.convert(t, frame, p.meta, credits, os, quality, progress)
                    }
                }
            }
            verify(part, f.width, f.height)
            // Tagging in place under the same name: the original makes way for the tagged file.
            if (sameFile) {
                if (!Trash.delete(f.file)) error("couldn't replace the original")
                originalGone = true
            }
            Files.move(part.toPath(), out.toPath(), StandardCopyOption.REPLACE_EXISTING)
        } catch (e: Throwable) {
            // Once the original has made way, the finished file is all there is: keep it.
            if (originalGone) throw IllegalStateException("tagged file left as ${part.name}; rename it to $target", e)
            part.delete()
            throw e
        }
        val dated = FileTimes.set(out, date)
        if (!p.keep && !sameFile && !Trash.delete(f.file))
            error(if (f.isJpeg) "tagged as $target, but the original couldn't be removed" else "JPEG saved, but the TIFF couldn't be deleted")
        return dated
    }

    /** The new folder beside a JPEG roll for its tagged copies, made the first time it's needed. */
    private fun copiesDir(p: RollPlan): File = synchronized(p) {
        p.copiesDir.also { if (!it.isDirectory && !it.mkdirs()) error("couldn't create the folder ${it.name}") }
    }

    /**
     * Checks a written JPEG: its size, its end marker, and that it decodes all the way through
     * (at a sixteenth of the size, which still reads every byte of image data).
     */
    fun verify(file: File, width: Int, height: Int) {
        FileSource(file).use { src ->
            val info = JpegRetag.info(src)
            if (info.width != width || info.height != height) error("the JPEG didn't read back correctly (${info.width}x${info.height})")
            val end = ByteArray(2).also { src.read(src.size - 2, it) }
            if (end[0] != 0xFF.toByte() || end[1] != 0xD9.toByte()) error("the JPEG is incomplete")
        }
        ImageIO.createImageInputStream(file).use { iis ->
            val reader = ImageIO.getImageReaders(iis).asSequence().firstOrNull() ?: error("the JPEG didn't decode")
            try {
                reader.input = iis
                var warning: String? = null
                reader.addIIOReadWarningListener(IIOReadWarningListener { _, w -> warning = w })
                val param = reader.defaultReadParam.apply { setSourceSubsampling(16, 16, 0, 0) }
                try {
                    reader.read(0, param)
                } catch (e: javax.imageio.IIOException) {
                    // Java can't decode some colour types (CMYK) that are still fine JPEGs; the checks above stand.
                    if (e.message?.contains("Unsupported", ignoreCase = true) != true) throw IllegalStateException("the JPEG didn't decode (${e.message})")
                }
                warning?.let { error("the JPEG didn't decode cleanly ($it)") }
            } finally {
                reader.dispose()
            }
        }
    }

    /** Deletes the roll's info files; returns how many went. */
    fun deleteSidecars(roll: RollInfo): Int = roll.sidecars.count { Trash.delete(it) }

    /** Renames the roll's folder; returns the folder's new place, or the error. */
    fun renameFolder(p: RollPlan): Result<File> = runCatching {
        val to = File(p.roll.folder.absoluteFile.parentFile ?: error("the top of a drive can't be renamed"), cleanName(p.newFolderName))
        if (to.exists() && !to.absolutePath.equals(p.roll.folder.absolutePath, ignoreCase = true)) error("there's already a folder called ${to.name}")
        Files.move(p.roll.folder.toPath(), to.toPath())
        to
    }.recoverCatching { e ->
        throw IllegalStateException(when (e) {
            is java.nio.file.AccessDeniedException, is java.nio.file.FileSystemException ->
                "couldn't rename the folder (is it open in Explorer or another program?)"
            else -> e.message ?: "couldn't rename the folder"
        })
    }
}
