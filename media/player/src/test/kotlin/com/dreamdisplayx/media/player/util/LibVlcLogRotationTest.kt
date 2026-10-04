package com.dreamdisplayx.media.player.util

import java.nio.file.Files
import java.time.Instant
import java.util.zip.GZIPInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class LibVlcLogRotationTest {
    @Test
    fun rotatesPreviousRunAndLeavesCurrentPathFree() {
        val directory = Files.createTempDirectory("libvlc-log-rotation").toFile()
        try {
            val current = directory.resolve("libvlc.log")
            current.writeText("old line\n", Charsets.UTF_8)

            val archive = LibVlcLogRotation.rotate(current, Instant.parse("2026-10-04T05:06:07Z"))

            assertNotNull(archive)
            assertFalse(current.exists())
            assertTrue(archive.exists())
            GZIPInputStream(archive.inputStream()).bufferedReader(Charsets.UTF_8).use {
                assertEquals("old line\n", it.readText())
            }
            assertNull(LibVlcLogRotation.rotate(current))
        } finally {
            directory.deleteRecursively()
        }
    }
}
