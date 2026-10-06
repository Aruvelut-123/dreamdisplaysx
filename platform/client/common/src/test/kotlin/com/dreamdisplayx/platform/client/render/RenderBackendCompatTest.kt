package com.dreamdisplayx.platform.client.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderBackendCompatTest {
    @Test
    fun `detects MobileGlues from launcher and LWJGL fingerprints`() {
        assertTrue(
            RenderBackendCompat.isMobileGlues(
                environment = mapOf("POJAVEXEC_EGL" to "libmobileglues.so"),
                properties = emptyMap(),
            ),
        )
        assertTrue(
            RenderBackendCompat.isMobileGlues(
                environment = emptyMap(),
                properties = mapOf("org.lwjgl.opengl.GL_VERSION" to "4.0.0 MobileGlues 1.3.3"),
            ),
        )
        assertFalse(
            RenderBackendCompat.isMobileGlues(
                environment = mapOf("POJAVEXEC_EGL" to "libEGL.so"),
                properties = mapOf("org.lwjgl.opengl.GL_RENDERER" to "Adreno (TM) 750"),
            ),
        )
    }
}
