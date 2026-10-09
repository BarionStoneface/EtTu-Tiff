package com.barion.filmscans

import android.app.Application
import android.content.Context
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.barion.filmscans.core.Credits
import com.barion.filmscans.core.FILM_STOCKS
import com.barion.filmscans.core.License

/** Your name, licence, choices and past answers, kept on the phone between runs. */
class Settings(app: Application) {
    private val prefs = app.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var author by mutableStateOf(prefs.getString("author", "") ?: "")
    var license by mutableStateOf(runCatching { License.valueOf(prefs.getString("license", "")!!) }.getOrDefault(License.ALL_RIGHTS))
    var contact by mutableStateOf(prefs.getString("contact", "") ?: "")
    var quality by mutableIntStateOf(prefs.getInt("quality", 100))
    /** Keep the TIFFs and add JPEGs beside them, instead of replacing them. */
    var keepTiffs by mutableStateOf(prefs.getBoolean("keepTiffs", true))
    /** Delete a zip once everything in it is unzipped. Off unless chosen. */
    var deleteZips by mutableStateOf(prefs.getBoolean("deleteZips", false))
    /** Starting point for each roll's "delete info files" switch. Off unless chosen. */
    var deleteInfoFiles by mutableStateOf(prefs.getBoolean("deleteInfoFiles", false))
    /** Give the lab's JPEGs a date taken when unzipping, so galleries sort them by scan date. */
    var labJpegDates by mutableStateOf(prefs.getBoolean("labJpegDates", true))
    var theme by mutableStateOf(runCatching { AppTheme.valueOf(prefs.getString("theme", "")!!) }.getOrDefault(AppTheme.STUDIO))

    /** Past answers, offered in the dropdowns. Never filled in automatically. */
    val cameras = mutableStateListOf<String>().apply { addAll(history("cameras")) }
    val lenses = mutableStateListOf<String>().apply { addAll(history("lenses")) }
    val labs = mutableStateListOf<String>().apply { addAll(history("labs")) }
    val customFilms = mutableStateListOf<String>().apply { addAll(history("films")) }

    fun save() {
        prefs.edit().putString("author", author.trim()).putString("license", license.name)
            .putString("contact", contact.trim()).putInt("quality", quality)
            .putBoolean("keepTiffs", keepTiffs).putString("theme", theme.name)
            .putBoolean("deleteZips", deleteZips).putBoolean("deleteInfoFiles", deleteInfoFiles)
            .putBoolean("labJpegDates", labJpegDates).apply()
    }

    private fun history(key: String): List<String> =
        prefs.getString(key, "")!!.split('\n').filter { it.isNotBlank() }

    /** The camera, lens, lab and (if it isn't in the list) film of a roll, offered next time. */
    fun rememberAnswers(r: Roll) {
        remember("cameras", cameras, r.camera); remember("lenses", lenses, r.lens); remember("labs", labs, r.lab)
        if (r.film.isNotBlank() && FILM_STOCKS.none { it.name.equals(r.film.trim(), true) }) remember("films", customFilms, r.film)
    }

    private fun remember(key: String, list: MutableList<String>, value: String) {
        val v = value.trim()
        if (v.isEmpty()) return
        list.remove(v); list.add(0, v)
        while (list.size > 30) list.removeAt(list.lastIndex)
        prefs.edit().putString(key, list.joinToString("\n")).apply()
    }

    fun credits() = Credits(author.trim(), license, contact.trim())
}
