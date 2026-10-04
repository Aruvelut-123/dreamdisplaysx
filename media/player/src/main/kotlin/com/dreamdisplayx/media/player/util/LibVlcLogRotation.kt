package com.dreamdisplayx.media.player.util

import java.io.File
import java.io.IOException
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.zip.GZIPOutputStream

/** Rotates a verbose libvlc log into a timestamped gzip archive before a new run starts. */
internal object LibVlcLogRotation {
    private val timestampFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss")
        .withZone(ZoneId.systemDefault())

    /**
     * Compresses [file] when it contains an older run, removes the original only after the
     * archive is complete, and keeps at most [maxArchives] compressed runs beside it.
     */
    fun rotate(file: File, now: Instant = Instant.now(), maxArchives: Int = DEFAULT_MAX_ARCHIVES): File? {
        if (!file.isFile || file.length() == 0L) return null
        val parent = file.parentFile ?: File(".")
        if (!parent.exists() && !parent.mkdirs() && !parent.isDirectory) {
            throw IOException("Unable to create log directory: ${parent.absolutePath}")
        }

        val archive = uniqueArchive(file, timestampFormat.format(now))
        try {
            file.inputStream().buffered().use { input ->
                GZIPOutputStream(archive.outputStream().buffered()).use { output ->
                    input.copyTo(output)
                }
            }
            if (!file.delete()) throw IOException("Unable to remove rotated log: ${file.absolutePath}")
        } catch (error: Throwable) {
            archive.delete()
            throw error
        }
        prune(file, maxArchives.coerceAtLeast(1))
        return archive
    }

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

    private const val DEFAULT_MAX_ARCHIVES = 10
}
