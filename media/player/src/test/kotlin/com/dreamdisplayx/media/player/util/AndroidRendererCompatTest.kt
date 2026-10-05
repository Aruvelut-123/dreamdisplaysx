package com.dreamdisplayx.media.player.util

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class AndroidRendererCompatTest {
    @Test
    fun detectsMobileGluesFromLauncherLibraryProperties() {
        val environment = mapOf("POJAVEXEC_EGL" to "libmobileglues.so")
        assertTrue(AndroidRendererCompat.isMobileGlues(environment, emptyMap()))
        assertEquals(2, AndroidRendererCompat.avcodecThreads(environment, emptyMap()))
    }

    @Test
    fun zinkDoesNotUseMobileGluesConservativeMode() {
        val environment = mapOf("POJAVEXEC_EGL" to "libzink.so")
        assertFalse(AndroidRendererCompat.isMobileGlues(environment, emptyMap()))
        assertEquals(4, AndroidRendererCompat.avcodecThreads(environment, emptyMap()))
    }

    @Test
    fun explicitThreadOverrideWins() {
        val properties = mapOf("dreamdisplayx.androidAvcodecThreads" to "6")
        assertEquals(6, AndroidRendererCompat.avcodecThreads(emptyMap(), properties))
    }
}
