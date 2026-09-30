package com.barion.filmscans

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Environment
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Where the JPEGs went, and opening them in the phone's own apps. */
object Places {
    private fun docId(uri: Uri) = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull()

    /** "Internal storage/DCIM/Scans/Roll 12 JPEG" from a folder's document address. */
    fun label(uri: Uri): String {
        val id = docId(uri) ?: return uri.toString()
        val path = id.substringAfter(':', "")
        val root = if (id.substringBefore(':').equals("primary", true)) "Internal storage" else "SD card"
        return if (path.isEmpty()) root else "$root/$path"
    }

    /** The real file path, e.g. /storage/emulated/0/DCIM/Scans, for local storage folders. */
    @Suppress("DEPRECATION")
    fun filePath(uri: Uri): String? {
        if (uri.authority != "com.android.externalstorage.documents") return null
        val id = docId(uri) ?: return null
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val base = if (volume.equals("primary", true)) Environment.getExternalStorageDirectory().path else "/storage/$volume"
        return if (path.isEmpty()) base else "$base/$path"
    }

    /**
     * Has the media index pick up new files now (not whenever it gets round to it).
     * Returns the gallery address of the first one, which any photo viewer can open.
     */
    fun scan(ctx: Context, folder: Uri, names: List<String>): Uri? {
        val dir = filePath(folder) ?: return null
        if (names.isEmpty()) return null
        val paths = names.map { "$dir/$it" }
        val found = arrayOfNulls<Uri>(paths.size)
        val latch = CountDownLatch(paths.size)
        MediaScannerConnection.scanFile(ctx, paths.toTypedArray(), Array(paths.size) { "image/jpeg" }) { path, uri ->
            val i = paths.indexOf(path)
            if (i >= 0) found[i] = uri
            latch.countDown()
        }
        latch.await(20, TimeUnit.SECONDS)
        return found.firstOrNull { it != null }
    }

    /** Free space where [folder] lives, or null if it can't be told. */
    fun freeBytes(folder: Uri): Long? = filePath(folder)?.let { runCatching { android.os.StatFs(it).availableBytes }.getOrNull() }

    /** Where the destination picker opens: the phone's Pictures folder. */
    val pictures: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Pictures")

    /** Opens the first photo in the gallery; swipe from there. */
    fun viewPhotos(ctx: Context, o: Output) {
        val folder = o.roll.outputUri ?: o.roll.folder.uri
        // The gallery's own address for the photo: works in Samsung Gallery and Google Photos.
        o.media?.let { media ->
            if (start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(media, "image/jpeg"))) return
        }
        // Fallback: the file's address through the folder the app was given.
        val name = o.firstJpeg ?: return
        val photo = DocumentFile.fromTreeUri(ctx, folder)?.findFile(name)
        if (photo != null && start(ctx, Intent(Intent.ACTION_VIEW).setDataAndType(photo.uri, "image/jpeg")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))) return
        toast(ctx, "Couldn't open a photo viewer. The photos are in ${label(folder)}")
    }

    /** Opens the folder in My Files (Samsung) or the Files app. */
    fun openFolder(ctx: Context, o: Output) {
        val uri = o.roll.outputUri ?: o.roll.folder.uri
        val path = filePath(uri)
        val id = docId(uri)
        val tries = buildList {
            if (path != null) add(Intent("samsung.myfiles.intent.action.LAUNCH_MY_FILES")
                .putExtra("samsung.myfiles.intent.extra.START_PATH", path))
            if (id != null) add(Intent(Intent.ACTION_VIEW)
                .setDataAndType(DocumentsContract.buildDocumentUri(uri.authority, id), DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
            add(Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR)
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION))
        }
        if (tries.none { start(ctx, it) }) toast(ctx, "Open My Files and go to ${label(uri)}")
    }

    private fun start(ctx: Context, i: Intent): Boolean = try {
        ctx.startActivity(i); true
    } catch (_: ActivityNotFoundException) { false } catch (_: SecurityException) { false }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
