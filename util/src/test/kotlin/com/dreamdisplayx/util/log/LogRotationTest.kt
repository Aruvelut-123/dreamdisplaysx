package com.dreamdisplayx.util.log

import java.io.File
import java.nio.file.Files
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins the "rotate like Minecraft does" policy.
 *
 * The mod now writes its diagnostic logs into the *game's* `logs/` directory, which also holds
 * `latest.log` and every vanilla archive. An unbounded rotation there would eventually fill the
 * player's storage, so the retention bound is part of the contract rather than an implementation
 * detail: [LogRotation.prune] is what keeps the promise.
 */
class LogRotationTest {

    private val directories = ArrayList<File>()

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { directories += it }

    @AfterTest
    fun cleanUp() {
        directories.forEach { runCatching { it.deleteRecursively() } }
    }

    @Test
    fun `rotates a previous run into a timestamped gzip archive`() {
        val directory = tempDir("log-rotation-basic")
        val current = File(directory, "dreamdisplayx-libvlc.log")
        current.writeText("old line\n", Charsets.UTF_8)

        val archive = LogRotation.rotate(current, Instant.parse("2026-11-03T05:06:07Z"))

        assertNotNull(archive)
        assertFalse(current.exists(), "the live file must be freed for the new run")
        assertTrue(archive.name.endsWith(".gz"), "archives are compressed like vanilla's")
        assertTrue(archive.name.startsWith("dreamdisplayx-libvlc.log."))
        GZIPInputStream(archive.inputStream()).bufferedReader(Charsets.UTF_8).use {
            assertEquals("old line\n", it.readText())
        }
    }

    @Test
    fun `an empty or missing file is not rotated`() {
        val directory = tempDir("log-rotation-empty")
        val current = File(directory, "empty.log")

        assertNull(LogRotation.rotate(current), "missing files have nothing to archive")
        current.writeText("", Charsets.UTF_8)
        assertNull(LogRotation.rotate(current), "empty files would only create noise")
        val archives = directory.listFiles().orEmpty().filter { it.name.endsWith(".gz") }
        assertEquals(0, archives.size, "no archive may be created, found ${archives.map { it.name }}")
        assertEquals(0L, current.length(), "the empty live file must be left alone")
    }

    @Test
    fun `retention keeps only the newest archives`() {
        val directory = tempDir("log-rotation-retention")
        val current = File(directory, "dreamdisplayx-test.log")
        val base = Instant.parse("2026-11-03T00:00:00Z")

        for (index in 0 until 5) {
            current.writeText("run $index\n", Charsets.UTF_8)
            LogRotation.rotate(current, base.plusSeconds(index.toLong()), maxArchives = 2)
        }

        val archives = directory.listFiles().orEmpty().filter { it.name.endsWith(".gz") }
        assertEquals(2, archives.size, "maxArchives must bound the directory, was ${archives.map { it.name }}")
    }

    @Test
    fun `archives created in the same second do not overwrite each other`() {
        val directory = tempDir("log-rotation-collision")
        val current = File(directory, "collide.log")
        val now = Instant.parse("2026-11-03T00:00:00Z")

        current.writeText("first\n", Charsets.UTF_8)
        val first = LogRotation.rotate(current, now)
        current.writeText("second\n", Charsets.UTF_8)
        val second = LogRotation.rotate(current, now, maxArchives = 10)

        assertNotNull(first)
        assertNotNull(second)
        assertTrue(first != second, "a second rotation in the same second must use a suffix")
        GZIPInputStream(first.inputStream()).bufferedReader(Charsets.UTF_8).use {
            assertEquals("first\n", it.readText())
        }
        GZIPInputStream(second.inputStream()).bufferedReader(Charsets.UTF_8).use {
            assertEquals("second\n", it.readText())
        }
    }

    @Test
    fun `vanilla archive naming matches the logs directory convention`() {
        assertEquals("2026-11-03-1.log.gz", LogRotation.vanillaArchiveName("2026-11-03", 1))
        assertEquals("2026-11-03", LogRotation.vanillaStampFor(Instant.parse("2026-11-03T23:59:59Z")))
    }
}