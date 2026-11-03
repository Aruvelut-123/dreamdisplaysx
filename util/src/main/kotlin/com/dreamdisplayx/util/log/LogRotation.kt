package com.dreamdisplayx.util.log

import java.io.File
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/**
 * Rotation policy modelled on Minecraft's own `logs/` handling.
 *
 * Vanilla keeps `logs/latest.log` for the running session and renames the previous one to
 * `logs/YYYY-MM-DD-N.log.gz` (newest first, unbounded). The fork keeps the same shape but bounds the
 * archive count, because a mod log is not the only thing in that directory.
 *
 * Public (not `internal`) because `:media:player` rotates its verbose libvlc log through the same
 * implementation: two rotation policies drifting apart is how a "keep 10" promise quietly becomes
 * "keep 10 here, 10 there, 20 in total".
 *
 * Pure `java.io` so the policy can be unit-tested without a game, a logger, or libvlc.
 */
object LogRotation {
    /** `YYYY-MM-DD-N.log.gz`, matching vanilla's `logs/` naming. */
    private val vanillaStamp: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd").withZone(ZoneId.systemDefault())

    /**
     * Rotates [file] into `<file.name>.<yyyy-MM-dd_HH-mm-ss>.gz` and deletes the original.
     *
     * Returns the archive, or `null` when there was nothing to rotate (missing or empty file).
     * Throws only when the archive could not be written; the caller treats that as "logging is
     * broken, keep playing".
     */
    fun rotate(file: File, now: Instant = Instant.now(), maxArchives: Int = DEFAULT_MAX_ARCHIVES): File? {
        if (!file.isFile || file.length() == 0L) return null
        val parent = file.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw java.io.IOException("Unable to create log directory: ${parent.absolutePath}")
        }
        val stamp = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
            .withZone(ZoneId.systemDefault())
            .format(now)
        val archive = uniqueArchive(file, stamp)
        try {
            file.inputStream().buffered().use { input ->
                GZIPOutputStream(archive.outputStream().buffered()).use { output ->
                    input.copyTo(output)
                }
            }
            if (!file.delete()) throw java.io.IOException("Unable to remove rotated log: ${file.absolutePath}")
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
        prune(file, maxArchives.coerceAtLeast(1))
        return archive
    }

    /** `logs/2026-11-03-1.log.gz` — vanilla's archive name for the *primary* log file. */
    fun vanillaArchiveName(date: String, index: Int): String = "$date-$index.log.gz"

    private fun uniqueArchive(file: File, stamp: String): File {
        val name = file.name
        var candidate = File(file.parentFile, "$name.$stamp.gz")
        var suffix = 1
        while (candidate.exists()) {
            candidate = File(file.parentFile, "$name.$stamp.$suffix.gz")
            suffix++
        }
        return candidate
    }

    private fun prune(file: File, maxArchives: Int) {
        val prefix = "${file.name}."
        val archives = file.parentFile?.listFiles { child ->
            child.isFile && child.name.startsWith(prefix) && child.name.endsWith(".gz")
        }?.sortedByDescending { it.lastModified() }.orEmpty()
        archives.drop(maxArchives).forEach { it.delete() }
    }

    /** Keeps a bounded number of archives; ten matches the previous libvlc.log behaviour. */
    const val DEFAULT_MAX_ARCHIVES = 10

    /** `logs/YYYY-MM-DD-N.log.gz` used by the vanilla-style sweep. */
    fun vanillaStampFor(now: Instant): String = vanillaStamp.format(now)
}