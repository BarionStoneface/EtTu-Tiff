package com.barion.filmscans

import android.content.Context
import android.provider.DocumentsContract
import android.provider.DocumentsContract.Document
import androidx.documentfile.provider.DocumentFile

/**
 * A file or folder with its name, kind, size and date already read. Asking a DocumentFile for
 * any of these is a separate request to the storage provider every time, and sorting a folder
 * by name asks over and over; reading them once per folder keeps listing quick.
 */
class Doc(val file: DocumentFile, val name: String, val isDir: Boolean, val size: Long, val modified: Long) {
    val uri get() = file.uri
    val ext get() = ext(name)
    val isFile get() = !isDir
    /** The ._name files a Mac leaves next to everything. */
    val isAppleDouble get() = name.startsWith("._")
}

/** A zip found in a picked folder, and the folder it sits in. */
class FoundZip(val doc: Doc, val parent: DocumentFile)

object Docs {
    private class Row(val name: String?, val mime: String?, val size: Long, val modified: Long)

    /** Everything in [dir], in two requests to the provider however many files there are. */
    fun list(ctx: Context, dir: DocumentFile): List<Doc> {
        val kids = dir.listFiles()
        val rows = HashMap<String, Row>()
        runCatching {
            val children = DocumentsContract.buildChildDocumentsUriUsingTree(dir.uri, DocumentsContract.getDocumentId(dir.uri))
            val cols = arrayOf(Document.COLUMN_DOCUMENT_ID, Document.COLUMN_DISPLAY_NAME, Document.COLUMN_MIME_TYPE,
                Document.COLUMN_SIZE, Document.COLUMN_LAST_MODIFIED)
            ctx.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) rows[c.getString(0)] = Row(c.getString(1), c.getString(2), c.getLong(3), c.getLong(4))
            }
        }
        return kids.map { f ->
            val r = runCatching { DocumentsContract.getDocumentId(f.uri) }.getOrNull()?.let { rows[it] }
            if (r != null) Doc(f, r.name ?: "", r.mime == Document.MIME_TYPE_DIR, r.size, r.modified)
            else Doc(f, f.name ?: "", f.isDirectory, f.length(), f.lastModified()) // provider didn't answer the batch
        }
    }
}
