package com.barion.filmscans

import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.documentfile.provider.DocumentFile

/** Where the JPEGs went, and opening them in the phone's own apps. */
object Places {
    /** "Internal storage/DCIM/Scans/Roll 12 JPEG" from a folder's document address. */
    fun label(uri: Uri): String {
        val id = runCatching { DocumentsContract.getDocumentId(uri) }.getOrNull() ?: return uri.toString()
        val volume = id.substringBefore(':')
        val path = id.substringAfter(':', "")
        val root = if (volume.equals("primary", ignoreCase = true)) "Internal storage" else "SD card"
        return if (path.isEmpty()) root else "$root/$path"
    }

    private fun folderUri(o: Output) = o.roll.outputUri ?: o.roll.folder.uri

    /** Opens the first photo in the gallery/viewer; swipe from there. */
    fun viewPhotos(ctx: Context, o: Output) {
        val name = o.firstJpeg ?: return
        val folder = DocumentFile.fromTreeUri(ctx, folderUri(o))
        val photo = folder?.findFile(name)
        if (photo == null) { toast(ctx, "Couldn't find $name — it's in ${label(folderUri(o))}"); return }
        val view = Intent(Intent.ACTION_VIEW).setDataAndType(photo.uri, "image/jpeg")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        try {
            ctx.startActivity(view)
        } catch (e: ActivityNotFoundException) {
            toast(ctx, "No photo viewer found. The photos are in ${label(folderUri(o))}")
        }
    }

    /** Opens the folder in the phone's file browser. */
    fun openFolder(ctx: Context, o: Output) {
        val uri = folderUri(o)
        val intents = listOf(
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, DocumentsContract.Document.MIME_TYPE_DIR),
            Intent(Intent.ACTION_VIEW).setDataAndType(uri, "resource/folder"),
        )
        for (i in intents) {
            i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            try { ctx.startActivity(i); return } catch (_: ActivityNotFoundException) { }
        }
        toast(ctx, "Open My Files and go to ${label(uri)}")
    }

    private fun toast(ctx: Context, msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()
}
