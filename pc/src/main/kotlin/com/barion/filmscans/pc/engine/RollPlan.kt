package com.barion.filmscans.pc

import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.RollMeta
import java.io.File
import java.time.LocalDateTime

/**
 * Everything chosen for one roll, fixed at the moment you press the button: the screen's
 * fields become this, and the conversion and the dry run both work from it.
 */
class RollPlan(
    val roll: RollInfo,
    /** The new name of each file, without .jpg, in the same order as [RollInfo.files]. */
    val names: List<String>,
    val meta: RollMeta,
    /** Keep the originals (TIFFs, or the lab's JPEGs) rather than replacing them. */
    val keep: Boolean,
    val dateText: String = "",
    /** Use the files' own dates for scans with no date inside: only when chosen. */
    val useFileDates: Boolean = false,
    /** Replace JPEGs already in the folder that have the same names. */
    val overwrite: Boolean = false,
    val renameFolder: Boolean = !roll.jpegRoll,
    val newFolderName: String = if (roll.jpegRoll) roll.name else jpegFolderName(roll.name),
    /** Keeping a JPEG roll's originals: the tagged copies go into this new folder beside it. */
    val copiesFolder: String = "${roll.name} tagged",
    /** .thm, .xmp and other info files: only ever deleted when chosen. */
    val deleteSidecars: Boolean = false,
) {
    val files get() = roll.files
    val overrideDate: LocalDateTime? = Dates.parse(dateText.trim())
    val undated get() = files.count { !it.date.embedded }

    /** The date written into file [i], or null when none has been chosen. */
    fun scanDate(i: Int): LocalDateTime? {
        val f = files[i]
        return if (f.date.embedded) f.date.date else overrideDate ?: f.date.date.takeIf { useFileDates }
    }

    /** The roll's name as written into each photo: the new folder name only if the folder really gets it. */
    val rollName: String get() = if (!keep && renameFolder && !roll.jpegRoll) cleanName(newFolderName) else roll.name

    /** Where tagged copies go when a JPEG roll's originals are kept. */
    val copiesDir: File get() = File(roll.folder.absoluteFile.parentFile ?: roll.folder, cleanName(copiesFolder))

    /** Whether the folder gets a new name once everything converted. */
    val renames get() = !keep && renameFolder && cleanName(newFolderName).let { it.isNotEmpty() && it != roll.name }

    /**
     * JPEGs already in the folder that would be written over. A lab JPEG being tagged in place doesn't
     * count against its own name; tagged copies go into a new, empty folder, so nothing is replaced there.
     */
    fun replacing(): List<String> {
        if (roll.jpegRoll && keep) return emptyList()
        val own = if (roll.jpegRoll) files.map { it.name.lowercase() }.toSet() else emptySet()
        return names.map { "$it.jpg" }.filter { it.lowercase() in roll.existing && it.lowercase() !in own }
    }

    fun problems(): List<String> {
        val out = mutableListOf<String>()
        names.map { it.lowercase() }.groupingBy { it }.eachCount().filter { it.value > 1 }.keys
            .forEach { out += "Two files would both be named \"$it.jpg\"" }
        if (names.any { it.isBlank() }) out += "A file name is empty"
        names.firstOrNull { it.isNotBlank() && cleanName(it) != it }
            ?.let { out += "\"$it\" can't be a file name on Windows; \"${cleanName(it)}\" would work" }
        if (dateText.isNotBlank() && overrideDate == null) out += "Scan date isn't a date (use 2019-04-12 or 2019-04-12 14:30)"
        if (undated > 0 && dateText.isBlank() && !useFileDates)
            out += "$undated scan(s) have no scan date: enter it, or choose to use their file dates"
        val replacing = replacing()
        if (replacing.isNotEmpty() && !overwrite) out += "${replacing.size} JPEG(s) with these names are already in the folder"
        if (!keep && renameFolder && !roll.jpegRoll) {
            val n = cleanName(newFolderName)
            if (n.isEmpty()) out += "The new folder name is empty"
            else if (!n.equals(roll.name, true) && (n.lowercase() in roll.siblings || File(roll.folder.absoluteFile.parentFile, n).exists()))
                out += "There's already a folder called \"$n\" next to this one"
        }
        if (!keep && roll.jpegRoll) {
            // Renaming in place: a new name mustn't be another of these JPEGs' current name.
            val clash = names.withIndex().firstOrNull { (i, n) -> files.withIndex().any { (j, o) -> j != i && o.name.equals("$n.jpg", true) } }
            if (clash != null) out += "${clash.value}.jpg is the name another of these JPEGs has now; change the added text"
        }
        if (keep && roll.jpegRoll) {
            val n = cleanName(copiesFolder)
            if (n.isEmpty()) out += "The folder for the tagged copies has no name"
            else if (roll.folder.absoluteFile.parentFile == null) out += "This is the top of a drive, so there's nowhere beside it for the tagged copies"
            else if (n.equals(roll.name, true) || n.lowercase() in roll.siblings || copiesDir.exists())
                out += "There's already a folder called \"$n\": choose another name for the tagged copies"
        }
        files.filter { it.error != null }.forEach { out += "${it.name}: ${it.error}" }
        return out
    }
}
