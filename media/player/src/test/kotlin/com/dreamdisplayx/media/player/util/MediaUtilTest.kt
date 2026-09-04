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
}
