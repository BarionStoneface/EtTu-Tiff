package com.barion.filmscans.pc

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import com.barion.filmscans.core.Dates
import com.barion.filmscans.core.Names
import com.barion.filmscans.core.RollMeta
import java.io.File
import java.time.LocalDateTime

/** One scan on screen: its new name and preview. */
@Stable
class ScanFile(val info: ScanInfo) {
    val name get() = info.name
    val date get() = info.date
    val error get() = info.error
    val isJpeg get() = info.isJpeg
    val bits get() = info.bits
    var newName by mutableStateOf(stem(info.name))
    @Volatile var done = false
    var thumb by mutableStateOf<ImageBitmap?>(null)
}

/** One roll on screen, with everything you can choose for it. Nothing carries over from another roll by itself. */
@Stable
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

class Roll(val info: RollInfo, deleteInfoFiles: Boolean) : RollFields {
    val name get() = info.name
    val jpegRoll get() = info.jpegRoll
    val files = info.files.map(::ScanFile)
    val sidecars get() = info.sidecars
    val alreadyTagged get() = info.alreadyTagged

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
    /** Settings sets the starting point for TIFF rolls, where it goes with replacing the TIFFs; a lab JPEG roll starts with them kept. */
    var deleteSidecars by mutableStateOf(deleteInfoFiles && !jpegRoll)
    override var dateOverride by mutableStateOf("")
    var useFileDates by mutableStateOf(false)
    var overwrite by mutableStateOf(false)
    var include by mutableStateOf(!info.alreadyTagged)
    /** Where the JPEGs ended up, once done (the folder may have been renamed). */
    var outputDir: File? = null

    val undated get() = files.count { !it.date.embedded }
    val undatedFiles get() = files.filter { !it.date.embedded }
    val scanners get() = files.mapNotNull { it.info.scanner }.distinct()

    fun meta() = RollMeta(
        camera = camera.trim(), lens = lens.trim(), film = film.trim(), boxIso = iso.trim().toIntOrNull(),
        pushStops = push, tags = tags, notes = notes.trim(), lab = lab.trim(),
    )

    /** Everything chosen, as the conversion and dry run use it. */
    fun plan(keep: Boolean) = RollPlan(
        info, files.map { it.newName }, meta(), keep, dateOverride, useFileDates, overwrite,
        renameFolder, newFolderName, deleteSidecars,
    )

    /** Whether this roll's folder is left as it is: a TIFF roll whose TIFFs are kept. */
    fun keeps(keepTiffs: Boolean) = keepTiffs && !jpegRoll
    fun problems(keep: Boolean) = plan(keep).problems()
    fun replacing(keep: Boolean) = plan(keep).replacing()
    fun overrideDate(): LocalDateTime? = Dates.parse(dateOverride.trim())

    /** Puts the before/after text around every lab name. Names edited one by one are replaced. */
    fun applyNames() {
        val roll = if (renameFolder) newFolderName else name
        val parts = Names.Parts(before, join, after)
        files.forEachIndexed { i, f ->
            f.newName = Names.around(stem(f.name), parts, i + 1, f.date.date, roll, film).ifEmpty { stem(f.name) }
        }
    }

    /** Camera, film and the rest, from another roll. Only when asked. */
    fun copyDetailsFrom(o: Roll) {
        camera = o.camera; lens = o.lens; film = o.film; iso = o.iso; push = o.push; tags = o.tags
        notes = o.notes; lab = o.lab
        before = o.before; join = o.join; after = o.after
        applyNames()
    }

    /** True when a scan's name no longer has the lab's name in it (only possible by editing one by one). */
    fun labNameDropped() = files.any { !it.newName.contains(stem(it.name), ignoreCase = true) }
}
