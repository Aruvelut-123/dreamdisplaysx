package com.dreamdisplayx.media.player.util

/** Small environment-only compatibility probes for Android launcher renderers. */
internal object AndroidRendererCompat {
    /**
     * True when the renderer is an OpenGL-on-GLES translation layer such as `MobileGlues` or its
     * successor `MobileGL`.
     *
     * The match is on the **`mobilegl` prefix**, not the full name: the bridge was reported as
     * `MobileGlues` in the 1.10.8.2 round and as `MobileGL` (`Espryt (MobileGL Core)`,
     * `4.6.0 MobileGL 26.09-dev`) in a 1.20.1 Fabric log, and matching only the long spelling made
     * the whole conservative path silently stop applying after the rename.
     *
     * The probe is deliberately string-based so it works before LWJGL creates an OpenGL context.
     */
    fun isMobileGlues(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Boolean = fingerprintValues(environment, properties)
        .any { value -> BRIDGE_NAME_MARKERS.any { marker -> value.contains(marker, ignoreCase = true) } }

    /** Every launcher/LWJGL string that can name the active renderer. */
    fun fingerprintValues(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Sequence<String> = sequenceOf(
        environment["POJAVEXEC_EGL"],
        environment["SDL_OPENGL_LIBRARY"],
        environment["LIBGL_EGL"],
        environment["SDL_EGL_LIBRARY"],
        environment["POJAVEXEC_GL"],
        environment["POJAVEXEC_RENDERER"],
        environment["MOBILEGLUES_RENDERER"],
        properties["org.lwjgl.opengl.libname"],
        properties["org.lwjgl.egl.libname"],
        properties["org.lwjgl.opengl.GL_VERSION"],
        properties["org.lwjgl.opengl.GL_RENDERER"],
        properties["org.lwjgl.opengl.GL_VENDOR"],
        properties["gl.renderer"],
    ).filterNotNull()

    /** Name prefixes of known OpenGL-on-GLES translation layers; `mobilegl` covers both spellings. */
    private val BRIDGE_NAME_MARKERS = listOf("mobilegl")

    /** Limits software decoder fan-out on MobileGlues, where GL and native video buffers share RAM. */
    fun avcodecThreads(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Int {
        val override = properties["dreamdisplayx.androidAvcodecThreads"]?.trim()?.toIntOrNull()
        return override?.coerceIn(1, 8) ?: if (isMobileGlues(environment, properties)) 2 else 4
    }

    private fun systemProperties(): Map<String, String> =
        System.getProperties().stringPropertyNames().associateWith { System.getProperty(it).orEmpty() }
}
