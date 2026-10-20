package com.barion.filmscans.pc

import java.awt.Desktop
import java.io.File

/** Opening things in Windows, and the right-click menu entries. */
object Shell {
    val isWindows = System.getProperty("os.name").orEmpty().startsWith("Windows")

    /** The installed program, when running from the installer rather than from a build. */
    val launcher: String? get() = System.getProperty("jpackage.app-path")?.takeIf { File(it).isFile }

    fun open(f: File): Boolean = runCatching { Desktop.getDesktop().open(f); true }.getOrDefault(false)

    /** Opens the folder in Explorer with [f] selected. */
    fun showInFolder(f: File): Boolean = if (isWindows && f.exists())
        runCatching { ProcessBuilder("explorer.exe", "/select,${f.absolutePath}").start(); true }.getOrDefault(false)
    else open(if (f.isDirectory) f else f.absoluteFile.parentFile)

    private const val ROOT = "HKEY_CURRENT_USER\\Software\\Classes"
    private val KEYS = listOf(
        "Directory\\shell\\EtTuTiff" to ("Open in Et Tu, Tiff?" to "%1"),
        "Directory\\Background\\shell\\EtTuTiff" to ("Open this folder in Et Tu, Tiff?" to "%V"),
        "SystemFileAssociations\\.zip\\shell\\EtTuTiff" to ("Unzip with Et Tu, Tiff?" to "%1"),
    )

    /** Whether "Open in Et Tu, Tiff?" is on Explorer's right-click menu. */
    fun menuAdded(): Boolean = isWindows && runCatching {
        ProcessBuilder("reg", "query", "HKCU\\Software\\Classes\\Directory\\shell\\EtTuTiff").start().waitFor() == 0
    }.getOrDefault(false)

    /** Adds the right-click entries for folders and zips, for this Windows user only. Null when done, or why not. */
    fun addMenu(): String? {
        val exe = launcher ?: return "This only works in the installed app."
        fun q(s: String) = "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
        val lines = buildList {
            add("Windows Registry Editor Version 5.00")
            for ((key, v) in KEYS) {
                add(""); add("[$ROOT\\$key]"); add("@=${q(v.first)}"); add("\"Icon\"=${q(exe)}")
                add(""); add("[$ROOT\\$key\\command]"); add("@=${q("\"$exe\" \"${v.second}\"")}")
            }
        }
        return importReg(lines)
    }

    fun removeMenu(): String? = importReg(listOf("Windows Registry Editor Version 5.00") + KEYS.map { "\n[-$ROOT\\${it.first}]" })

    private fun importReg(lines: List<String>): String? = runCatching {
        val f = File.createTempFile("ettutiff", ".reg")
        try {
            // .reg files are UTF-16 with a byte order mark.
            f.writeText("﻿" + lines.joinToString("\r\n") + "\r\n", Charsets.UTF_16LE)
            val p = ProcessBuilder("reg", "import", f.absolutePath).redirectErrorStream(true).start()
            val said = p.inputStream.bufferedReader().readText()
            if (p.waitFor() != 0) said.trim().ifEmpty { "Windows refused the change." } else null
        } finally {
            f.delete()
        }
    }.getOrElse { it.message ?: "Windows refused the change." }
}
