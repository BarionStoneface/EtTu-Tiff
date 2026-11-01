package com.barion.filmscans

import android.net.Uri
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.documentfile.provider.DocumentFile
import com.barion.filmscans.core.DateFound
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.RollMeta
import com.barion.filmscans.core.Scan
import java.time.LocalDateTime

/** Same rule as the desktop script: "Roll 12 TIFF" -> "Roll 12 JPEG", otherwise add " JPEG". */
fun jpegFolderName(name: String): String {
    val r = Regex("""(?i)\b(tiffs?|tifs?)\b""").replace(name, "JPEG")
    return if (r != name) r else "$name JPEG"
}

@Stable
class ScanFile(
    val doc: DocumentFile,
    val name: String,
    val width: Int,
    val height: Int,
    val bits: Int,
    val scanner: String?,
    val date: DateFound,
    val error: String?,
    val orientation: Int = 1,
) {
    /** A lab's JPEG, tagged without re-saving, rather than a TIFF to convert. */
    val isJpeg get() = ext(name) in JPEG_EXT
    var newName by mutableStateOf(stem(name))
    @Volatile var done = false
    var thumb by mutableStateOf<ImageBitmap?>(null)
}

/**
 * A roll's details as typed: on the rolls screen, or ahead of time in the unzip preview, from
 * where they're carried onto the roll once it's unzipped.
 */
interface RollFields {
    var camera: String
    var lens: String
    var film: String
    var iso: String
    var push: Int
    var tags: Set<String>
    var notes: String
    var lab: String
    var before: String
    var join: String
    var after: String
    var dateOverride: String

    /** Everything typed, from [o]. */
    fun fillFrom(o: RollFields) {
        camera = o.camera; lens = o.lens; film = o.film; iso = o.iso; push = o.push; tags = o.tags
        notes = o.notes; lab = o.lab; before = o.before; join = o.join; after = o.after; dateOverride = o.dateOverride
    }
}

@Stable
class Roll(
    val folder: DocumentFile,
    val isRoot: Boolean,
    val name: String,
    val files: List<ScanFile>,
    val sidecars: List<DocumentFile>,
    /** JPEGs already in the folder, by lowercase name. */
    val existing: Map<String, DocumentFile>,
    /** Other folders next to this one, lowercase, so a rename can't land on one of them. */
    private val siblings: Set<String>,
    deleteInfoFiles: Boolean,
    /** A folder of the lab's JPEGs: they're tagged, not converted. */
    val jpegRoll: Boolean = false,
    /** Every JPEG here was already tagged by this app, so the roll starts unticked. */
    val alreadyTagged: Boolean = false,
) : RollFields {
    override var camera by mutableStateOf("")
    override var lens by mutableStateOf("")
    override var film by mutableStateOf("")
    override var iso by mutableStateOf("")
    override var push by mutableIntStateOf(0)
    override var tags by mutableStateOf(setOf<String>())
    override var notes by mutableStateOf("")
    override var lab by mutableStateOf("")
    /** Text added before and after the lab's file name, which itself is kept whole. */
    override var before by mutableStateOf("")
    override var join by mutableStateOf("_")
    override var after by mutableStateOf("")
    var newFolderName by mutableStateOf(if (jpegRoll) name else jpegFolderName(name))
    var renameFolder by mutableStateOf(!jpegRoll)
    /**
     * .thm, .xmp and other info files: only ever deleted when chosen. Settings sets the starting point
     * for TIFF rolls, where it goes with replacing the TIFFs; a lab JPEG roll starts with them kept.
     */
    var deleteSidecars by mutableStateOf(deleteInfoFiles && !jpegRoll)
    override var dateOverride by mutableStateOf("")
    /** Use the files' own dates on the phone for scans with no date inside: only when chosen, as they may be download dates. */
    var useFileDates by mutableStateOf(false)
    /** Replace JPEGs already in the folder that have the same names. */
    var overwrite by mutableStateOf(false)
    var include by mutableStateOf(!alreadyTagged)
    /** The folder's address after renaming (renaming changes it). */
    var outputUri: Uri? = null

    val undated get() = files.count { !it.date.embedded }
    val scanners get() = files.mapNotNull { it.scanner }.distinct()

    fun meta() = RollMeta(
        camera = camera.trim(), lens = lens.trim(), film = film.trim(), boxIso = iso.trim().toIntOrNull(),
        pushStops = push, tags = tags, notes = notes.trim(), lab = lab.trim(),
    )

    fun parts() = Names.Parts(before, join, after)

    /** Puts the before/after text around every lab name. Names edited one by one are replaced. */
    fun applyNames() {
        val roll = if (renameFolder) newFolderName else name
        files.forEachIndexed { i, f ->
            f.newName = Names.around(stem(f.name), parts(), i + 1, f.date.date, roll, film).ifEmpty { stem(f.name) }
        }
    }

    /** Camera, film and the rest, from another roll. Only when asked: nothing carries over by itself. */
    fun copyDetailsFrom(o: Roll) {
        camera = o.camera; lens = o.lens; film = o.film; iso = o.iso; push = o.push; tags = o.tags
        notes = o.notes; lab = o.lab
        before = o.before; join = o.join; after = o.after
        applyNames()
    }

    fun overrideDate(): LocalDateTime? = Dates.parse(dateOverride.trim())

    /** The scans whose date would be the phone's file date. */
    val undatedFiles get() = files.filter { !it.date.embedded }

    /**
     * Whether this roll's folder is left as it is: TIFF rolls when the TIFFs are kept. A lab JPEG roll
     * is always tagged where it is, never copied, so its folder can be renamed and its info files
     * deleted whichever way the TIFFs go.
     */
    fun keeps(keepTiffs: Boolean) = keepTiffs && !jpegRoll

    fun problems(keepTiffs: Boolean): List<String> {
        val keep = keeps(keepTiffs)
        val out = mutableListOf<String>()
        val names = files.map { it.newName.lowercase() }
        names.groupingBy { it }.eachCount().filter { it.value > 1 }.keys.forEach { out += "Two files would both be named \"$it.jpg\"" }
        if (files.any { it.newName.isBlank() }) out += "A file name is empty"
        if (dateOverride.isNotBlank() && overrideDate() == null) out += "Scan date isn't a date (use 2019-04-12 or 2019-04-12 14:30)"
        if (undated > 0 && dateOverride.isBlank() && !useFileDates)
            out += "$undated scan(s) have no scan date: enter it, or choose to use their file dates"
        val replacing = replacing()
        if (replacing.isNotEmpty() && !overwrite) out += "${replacing.size} JPEG(s) with these names are already in the folder"
        if (!keep && renameFolder) {
            val n = cleanName(newFolderName)
            if (n.isEmpty()) out += "The new folder name is empty"
            else if (!n.equals(name, true) && n.lowercase() in siblings) out += "There's already a folder called \"$n\" next to this one"
        }
        if (jpegRoll) {
            // Renaming in place: a new name mustn't be another of these JPEGs' current name.
            val clash = files.firstOrNull { f -> files.any { o -> o !== f && o.name.equals(f.newName + ".jpg", true) } }
            if (clash != null) out += "${clash.newName}.jpg is the name another of these JPEGs has now; change the added text"
        }
        files.filter { it.error != null }.forEach { out += "${it.name}: ${it.error}" }
        return out
    }

    /** JPEGs already in the folder that would be written over. A lab JPEG being tagged in place doesn't count against its own name. */
    fun replacing(): List<String> {
        val own = if (jpegRoll) files.map { it.name.lowercase() }.toSet() else emptySet()
        return files.map { it.newName + ".jpg" }.filter { it.lowercase() in existing && it.lowercase() !in own }
    }

    /** True when a scan's name no longer has the lab's name in it (only possible by editing one by one). */
    fun labNameDropped() = files.any { !it.newName.contains(stem(it.name), ignoreCase = true) }
}
