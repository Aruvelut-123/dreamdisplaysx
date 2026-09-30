package com.dreamdisplayx.media.player.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MediaUtilTest {
    @Test
    fun `truncate keeps short strings intact`() {
        assertEquals("abc", MediaUtil.truncate("abc"))
        assertEquals("null", MediaUtil.truncate(null))
    }

    @Test
    fun `truncate applies the default 120-char cap`() {
        val s = "x".repeat(150)
        val out = MediaUtil.truncate(s)
        assertTrue(out.startsWith("x".repeat(120)))
        assertTrue(out.endsWith("...(150)"))
    }

    @Test
    fun `truncate honors an explicit max length`() {
        val s = "abcdefghij"
        assertEquals("abcdefghij", MediaUtil.truncate(s, 20))
        assertEquals("abcd...(10)", MediaUtil.truncate(s, 4))
    }

    @Test
    fun `isTransientError matches known markers only`() {
        assertTrue(MediaUtil.isTransientError("HTTP 403 Forbidden"))
        assertTrue(MediaUtil.isTransientError("Connection reset by peer"))
        assertFalse(MediaUtil.isTransientError("libvlc error [state=Stopped]"))
    }

    @Test
    fun `isInterestingStderr filters benign teardown noise`() {
        assertFalse(MediaUtil.isInterestingStderr("Task finished with error: Invalid argument"))
        assertTrue(MediaUtil.isInterestingStderr("avcodec error: decode failure"))
    }

    @Test
    fun `appendCapped keeps every line while under the cap`() {
        val sb = StringBuilder()
        MediaUtil.appendCapped(sb, "first")
        MediaUtil.appendCapped(sb, "second")
        assertEquals("first\nsecond\n", sb.toString())
    }

    @Test
    fun `appendCapped bounds a long session and drops the oldest output`() {
        val sb = StringBuilder()
        MediaUtil.appendCapped(sb, "oldest")
        repeat(65) { MediaUtil.appendCapped(sb, "z".repeat(1_024)) }

        assertFalse(sb.contains("oldest"), "The front of the buffer must be trimmed away.")
        assertTrue(sb.length <= 64 * 1024, "Buffer stayed at or below the cap, was ${sb.length}.")
        assertTrue(sb.trimEnd().endsWith("z"), "The newest output must survive the trim.")
    }
}
