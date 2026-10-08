package com.barion.filmscans.core

import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * File and folder names.
 *
 * The lab's own file name is never replaced, only added to: text goes before it and/or
 * after it, so 008030000001.tif with "LomoColor92" before and "RoyalWe" after becomes
 * LomoColor92_008030000001_RoyalWe.jpg. (Names can still be edited one by one, on purpose.)
 */
object Names {
    private val BAD = Regex("""[<>:"/\\|?*\x00-\x1f]""")
    // Windows refuses these as names, with or without an extension; they'd break copying to a PC.
    private val RESERVED = Regex("""(?i)^(con|prn|aux|nul|com[1-9]|lpt[1-9])(\..*)?$""")

    /** Safe on the phone, on SD cards and on Windows: bad characters become "_", trailing dots and spaces go. */
    fun clean(s: String): String {
        val r = BAD.replace(s, "_").trim().trimEnd('.', ' ')
        return if (RESERVED.matches(r)) "_$r" else r
    }

    /** What can be written in the added text, filled in per file. */
    const val TOKENS_HELP = "{nn} 01, {nnn} 001, {date}, {roll}, {film}"

    class Parts(val before: String = "", val join: String = "_", val after: String = "") {
        val isEmpty get() = before.isBlank() && after.isBlank()
    }

    /** [stem] (the lab's name, untouched) with the added text around it, tokens filled in. */
    fun around(stem: String, parts: Parts, n: Int, date: LocalDateTime?, roll: String, film: String): String {
        fun fill(t: String) = t
            .replace("{nnn}", "%03d".format(n)).replace("{nn}", "%02d".format(n)).replace("{n}", "$n")
            .replace("{date}", date?.let { DateTimeFormatter.ISO_LOCAL_DATE.format(it) } ?: "")
            .replace("{roll}", roll.trim()).replace("{film}", film.trim())
        val join = BAD.replace(parts.join, "") // a space is a fine joiner, so this isn't trimmed
        val before = clean(fill(parts.before))
        val after = clean(fill(parts.after))
        var name = stem
        if (before.isNotEmpty()) name = before + join + name
        if (after.isNotEmpty()) name = name + join + after
        return name
    }
}
