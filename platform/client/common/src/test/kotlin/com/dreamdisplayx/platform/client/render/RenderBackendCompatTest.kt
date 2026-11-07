package com.dreamdisplayx.platform.client.render

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class RenderBackendCompatTest {
    @Test
    fun `detects MobileGlues from launcher and LWJGL fingerprints`() {
        assertTrue(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = mapOf("POJAVEXEC_EGL" to "libmobileglues.so"),
                properties = emptyMap(),
            ),
        )
        assertTrue(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = emptyMap(),
                properties = mapOf("org.lwjgl.opengl.GL_VERSION" to "4.0.0 MobileGlues 1.3.3"),
            ),
        )
    }

    @Test
    fun `detects the renamed MobileGL bridge, not just the MobileGlues spelling`() {
        // The regression this pins: the 1.10.8.2 reports came from MobileGlues, and a later 1.20.1
        // Fabric log arrived from `MobileGL` — `Espryt (MobileGL Core) (Adreno (TM) 642L, OpenGL ES
        // 3.2)` / `4.6.0 MobileGL 26.09-dev, Direct (OpenGL ES) Backend`. Matching only the long
        // spelling made every conservative branch silently stop applying after the rename, so the
        // PBO ring with its emulated fence waits was taken again on a GLES bridge.
        assertTrue(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = emptyMap(),
                properties = mapOf(
                    "org.lwjgl.opengl.GL_RENDERER" to "Espryt (MobileGL Core) (Adreno (TM) 642L, OpenGL ES 3.2)",
                ),
            ),
        )
        assertTrue(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = emptyMap(),
                properties = mapOf(
                    "org.lwjgl.opengl.GL_VERSION" to
                        "4.6.0 MobileGL 26.09-dev, Direct (OpenGL ES) Backend, GIT@cbbaf77",
                ),
            ),
        )
        // A launcher that names the library rather than the renderer must be caught too.
        assertTrue(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = mapOf("POJAVEXEC_EGL" to "/data/lib/libmobilegl.so"),
                properties = emptyMap(),
            ),
        )
    }

    @Test
    fun `a real driver is not mistaken for a bridge`() {
        // The prefix match must not become a wildcard: a genuine Adreno/GL driver exposes "OpenGL ES"
        // in its version string without any translator in front of it.
        assertFalse(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = mapOf("POJAVEXEC_EGL" to "libEGL.so"),
                properties = mapOf(
                    "org.lwjgl.opengl.GL_RENDERER" to "Adreno (TM) 642L",
                    "org.lwjgl.opengl.GL_VERSION" to "OpenGL ES 3.2 v@0502.0",
                ),
            ),
        )
        assertFalse(
            RenderBackendCompat.isVirtualizedGlesBridge(
                environment = mapOf("POJAVEXEC_EGL" to "libzink.so"),
                properties = mapOf("org.lwjgl.opengl.GL_RENDERER" to "zink (Mesa)"),
            ),
        )
    }

    @Test
    fun `the fingerprint is exposed so a future rename stays diagnosable`() {
        // The probe is string-based, so a rename is its failure mode. The uploader logs these values
        // on its single path line; that is what makes a third rename readable from a user log.
        val seen = RenderBackendCompat.fingerprintValues(
            environment = mapOf("POJAVEXEC_GL" to "libmobilegl.so"),
            properties = mapOf("org.lwjgl.opengl.GL_RENDERER" to "Espryt (MobileGL Core)"),
        ).toList()
        assertTrue(seen.any { "mobilegl" in it.lowercase() })
    }
}