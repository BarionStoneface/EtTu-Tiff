package com.barion.filmscans

import android.net.Uri
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneId

/**
 * A file's own date (what a file manager, or Windows after copying it off the phone, shows), set
 * to the scan date. Android lets apps do this on some phones and folders and not others, so it's
 * tried, read back, and reported; the scan date inside each photo is what galleries use either way.
 */
object FileDates {
    /** Sets the file's modified date; true only if the phone kept it. */
    fun set(uri: Uri, date: LocalDateTime): Boolean = runCatching {
        val path = Places.filePath(uri) ?: return false
        val file = File(path)
        val ms = date.atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        file.setLastModified(ms) && Math.abs(file.lastModified() - ms) < 2000
    }.getOrDefault(false)

    /** One line for the log, from how many files took the date and how many didn't. */
    fun summary(set: Int, refused: Int): String? = when {
        set + refused == 0 -> null
        refused == 0 -> "File dates set to the scan dates."
        set == 0 -> "This phone doesn't let apps set file dates, so the files show today's date. The scan date " +
            "is inside each photo, which galleries use; on a PC, LabUnzip's \"Fix dates\" sets the file dates from it."
        else -> "File dates set to the scan dates for $set file(s); the phone refused $refused."
    }
}
