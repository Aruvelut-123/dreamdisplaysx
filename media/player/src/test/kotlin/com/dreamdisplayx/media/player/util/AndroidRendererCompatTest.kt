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
    fun theRenamedMobileGlBridgeStillGetsTheConservativeThreadPool() {
        // The bridge ships as `MobileGL` now (its renderer string is `Espryt (MobileGL Core)`), and
        // matching only the longer `MobileGlues` spelling silently restored the wide 4-thread pool on
        // the one backend that needs the narrow one, because its GL translation buffers compete for
        // the same native memory as the decoder.
        val properties = mapOf(
            "org.lwjgl.opengl.GL_RENDERER" to "Espryt (MobileGL Core) (Adreno (TM) 642L, OpenGL ES 3.2)",
        )
        assertTrue(AndroidRendererCompat.isMobileGlues(emptyMap(), properties))
        assertEquals(2, AndroidRendererCompat.avcodecThreads(emptyMap(), properties))
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
