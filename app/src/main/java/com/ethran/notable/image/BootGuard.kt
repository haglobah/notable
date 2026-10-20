package com.ethran.notable.image

import java.io.File

/**
 * Safe mode's start counter (ADR-002). Every start counts as failed until [healthy] says the image
 * loaded and the first screen was drawn. After [LIMIT] failed starts in a row, the next start runs
 * built-ins only. A healthy start, or one in safe mode that gets there, resets the count.
 *
 * The count is a small file, written before anything of the image runs, so a crash while a
 * definition is evaluated still counts.
 */
class BootGuard(private val file: File) {
    /** Failed starts in a row before this one. */
    private val before: Int = read()

    /** Call once per process start, before the image starts; returns why it must stay off, or null. */
    fun begin(): String? {
        write(before + 1)
        return if (before >= LIMIT) "the last $before starts didn't finish loading the image" else null
    }

    /** This start reached "image loaded and first screen drawn". */
    fun healthy() = write(0)

    private fun read(): Int = runCatching { file.readText().trim().toInt() }.getOrDefault(0)

    private fun write(n: Int) {
        file.parentFile?.mkdirs()
        val tmp = File(file.path + ".tmp")
        tmp.writeText(n.toString())
        if (!tmp.renameTo(file)) file.writeText(n.toString())
    }

    companion object {
        const val LIMIT = 2
    }
}
