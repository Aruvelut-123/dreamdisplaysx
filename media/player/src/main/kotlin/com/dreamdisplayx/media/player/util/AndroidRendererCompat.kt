package com.dreamdisplayx.media.player.util

/** Small environment-only compatibility probes for Android launcher renderers. */
internal object AndroidRendererCompat {
    /**
     * MobileGlues exposes its library through one of these launcher properties/environment values.
     * The probe is deliberately string-based so it works before LWJGL creates an OpenGL context.
     */
    fun isMobileGlues(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Boolean {
        val values = sequenceOf(
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
        )
        return values.filterNotNull().any { it.contains("mobileglues", ignoreCase = true) }
    }

    /** Limits software decoder fan-out on MobileGlues, where GL and native video buffers share RAM. */
    fun avcodecThreads(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Int {
        val override = properties["dreamdisplayx.androidAvcodecThreads"]?.trim()?.toIntOrNull()
        return override?.coerceIn(1, 8) ?: if (isMobileGlues(environment, properties)) 2 else 4
    }

    /**
     * Explicit `-Ddreamdisplayx.androidAvcodecThreads=<n>` cap, or null to leave the pool to libvlc.
     *
     * Android must NOT receive a forced `--avcodec-threads` value by default: a two-thread software
     * decoder cannot keep up with a 480p stream and starves the game's own render thread down to a
     * couple of FPS. The old [avcodecThreads] default stays available for callers that still want the
     * MobileGlues heuristic, but the instance options only use this explicit override.
     */
    fun avcodecThreadOverride(
        properties: Map<String, String> = systemProperties(),
    ): Int? = properties["dreamdisplayx.androidAvcodecThreads"]?.trim()?.toIntOrNull()?.coerceIn(1, 8)

    private fun systemProperties(): Map<String, String> =
        System.getProperties().stringPropertyNames().associateWith { System.getProperty(it).orEmpty() }
}
