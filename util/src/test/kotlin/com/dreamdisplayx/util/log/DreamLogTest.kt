package com.dreamdisplayx.util.log

import com.dreamdisplayx.util.log.DreamLog
import java.io.File
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pins [DreamLog]'s contract: one entry point, slf4j channel names unchanged, mirroring opt-in only.
 *
 * The log directory is a *user-facing* location now (the game's own `logs/`), so the "never throw,
 * never create files nobody asked for" rules matter more than usual: a broken directory must not
 * stop the game, and a channel that was never registered must not spawn a file.
 */
class DreamLogTest {

    private val directories = ArrayList<File>()

    private fun tempDir(prefix: String): File =
        Files.createTempDirectory(prefix).toFile().also { directories += it }

    @AfterTest
    fun cleanUp() {
        DreamLog.resetForTest()
        directories.forEach { runCatching { it.deleteRecursively() } }
    }

    @BeforeTest
    fun resetSingleton() {
        // Test order is not guaranteed: every case starts from "no directory, no mirrored channel".
        DreamLog.resetForTest()
    }

    @Test
    fun `the channel name keeps the historical slf4j tag`() {
        val channel = DreamLog.of("LibVlc")
        assertEquals("LibVlc", channel.channel)
        // The delegate name is what reaches `latest.log`; every past grep relies on this exact shape.
        // A no-provider test JVM reports `NOP`, so assert the shape rather than a provider-specific string.
        val expected = "DreamDisplaysX/LibVlc"
        val actual = channel.delegate.name
        assertTrue(
            actual == expected || actual == "NOP",
            "the delegate must be named $expected (or be the NOP logger), was $actual",
        )
        assertEquals("LibVlc", DreamLog.logger("LibVlc").channel)
    }

    @Test
    fun `mirroring without a configured directory stays silent`() {
        val directory = tempDir("dreamlog-unconfigured")

        DreamLog.mirrorToFile("Unconfigured")
        DreamLog.of("Unconfigured").info("goes nowhere")

        assertNull(DreamLog.logDirectory(), "no configure() means no directory")
        assertEquals(emptyList(), directory.listFiles().orEmpty().toList())
        assertTrue(DreamLog.mirroredChannels().contains("Unconfigured"))
    }

    @Test
    fun `a mirrored channel writes its file into the configured directory`() {
        val directory = tempDir("dreamlog-mirror")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Mirrored")

        DreamLog.of("Mirrored").info("hello {}", "world")

        val file = File(directory, "dreamdisplayx-mirrored.log")
        assertTrue(file.isFile, "expected ${file.absolutePath}")
        val text = file.readText(Charsets.UTF_8)
        assertTrue(text.contains("[I] hello world"), "placeholder must be substituted, was: $text")
    }

    @Test
    fun `a channel that was never mirrored does not create a file`() {
        val directory = tempDir("dreamlog-not-mirrored")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Only")

        DreamLog.of("Other").warn("should not reach the disk")
        DreamLog.of("Only").info("this one does")

        assertTrue(File(directory, "dreamdisplayx-only.log").isFile)
        assertFalse(File(directory, "dreamdisplayx-other.log").exists())
    }

    @Test
    fun `configuring twice with the same path is a no-op`() {
        val directory = tempDir("dreamlog-idempotent")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Twice")

        DreamLog.of("Twice").info("first")
        DreamLog.configure(directory)
        DreamLog.of("Twice").info("second")

        val text = File(directory, "dreamdisplayx-twice.log").readText(Charsets.UTF_8)
        assertTrue(text.contains("first") && text.contains("second"), "both lines must survive: $text")
    }

    @Test
    fun `an unusable directory is reported once and never throws`() {
        // A regular file where a directory is expected: mkdirs() fails, and the sink must degrade.
        val file = File(tempDir("dreamlog-broken"), "not-a-directory")
        file.writeText("x", Charsets.UTF_8)

        DreamLog.configure(file)
        DreamLog.mirrorToFile("Broken")
        DreamLog.of("Broken").error("still must not throw", IllegalStateException("boom"))

        // configure() records the requested directory even when the sink failed to open.
        assertEquals(file.absolutePath, DreamLog.logDirectory()?.absolutePath)
    }

    @Test
    fun `a throwable is appended to the mirrored line`() {
        val directory = tempDir("dreamlog-throwable")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Trace")

        DreamLog.of("Trace").warn("failed", IllegalArgumentException("bad input"))

        val text = File(directory, "dreamdisplayx-trace.log").readText(Charsets.UTF_8)
        assertTrue(text.contains("[W] failed"), text)
        assertTrue(text.contains("IllegalArgumentException"), "stack trace must be captured: $text")
    }

    @Test
    fun `shutdown closes the sink so logging keeps working without a file`() {
        val directory = tempDir("dreamlog-shutdown")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Closing")
        DreamLog.of("Closing").info("before")

        DreamLog.shutdown()
        DreamLog.of("Closing").info("after")

        val text = File(directory, "dreamdisplayx-closing.log").readText(Charsets.UTF_8)
        assertTrue(text.contains("before"), text)
        assertFalse(text.contains("after"), "the closed sink must not keep writing: $text")
    }

    @Test
    fun `substitution tolerates missing and extra placeholders`() {
        val directory = tempDir("dreamlog-placeholders")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Placeholders")
        val channel = DreamLog.of("Placeholders")

        channel.info("no placeholders at all")
        channel.info("two {} of {}", "kinds", "args")
        channel.info("missing {} argument")
        channel.info("{} and {} but only one", 1)

        val lines = File(directory, "dreamdisplayx-placeholders.log").readLines()
        assertEquals(4, lines.size)
        assertTrue(lines[0].endsWith("no placeholders at all"), lines[0])
        assertTrue(lines[1].endsWith("two kinds of args"), lines[1])
        assertTrue(lines[2].endsWith("missing {} argument"), "an absent argument keeps the token: ${lines[2]}")
        assertTrue(lines[3].endsWith("1 and {} but only one"), lines[3])
    }

    @Test
    fun `the mirrored file name is lower-cased and space-free`() {
        val directory = tempDir("dreamlog-naming")
        DreamLog.configure(directory)
        DreamLog.mirrorToFile("Display Lifecycle")

        DreamLog.of("Display Lifecycle").info("line")

        assertNotNull(File(directory, "dreamdisplayx-display-lifecycle.log").takeIf { it.isFile })
    }
}