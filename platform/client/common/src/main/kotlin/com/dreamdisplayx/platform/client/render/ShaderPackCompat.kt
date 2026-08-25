package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.render.backend.model.ShaderBackend

/** Shader-pack detector: checks for `Iris`, `OptiFine`, or `Canvas` shader packs without adding hard dependencies. */
internal object ShaderPackCompat {
    /** True when any supported shader pack is currently in use. */
    val isShaderPackActive: Boolean get() = shaderBackend() != ShaderBackend.NONE

    /** Monotonic counter bumped every time the detected backend changes; render code uses it to invalidate caches. */
    @Volatile
    private var backendVersionCounter = 0L

    /** Last seen backend, for change detection. */
    @Volatile
    private var lastBackend = ShaderBackend.NONE

    /** Active shader backend, or [ShaderBackend.NONE]. */
    fun shaderBackend(): ShaderBackend {
        val current = computeBackend()
        if (current != lastBackend) {
            lastBackend = current
            backendVersionCounter++
        }
        return current
    }

    /** Version of the current shader state; bumped whenever the active pack backend changes. */
    val shaderStateVersion: Long get() = backendVersionCounter

    private fun computeBackend(): ShaderBackend = when {
        irisShaderPackActive() -> ShaderBackend.IRIS
        optifineShaderPackActive() -> ShaderBackend.OPTIFINE
        canvasRendererActive() -> ShaderBackend.CANVAS
        else -> ShaderBackend.NONE
    }

    /** `Iris` shaders — tries multiple API versions and internal Iris classes, then conservatively falls back. */
    private fun irisShaderPackActive(): Boolean {
        // Fast path: no Iris at all
        if (!irisPresent()) return false

        // Try every known API / method to detect an active pack.
        for (candidate in IRIS_DETECTORS) {
            try {
                val clazz = Class.forName(candidate.className)
                when (candidate.kind) {
                    DetectorKind.API_GET_INSTANCE -> {
                        val api = clazz.getMethod("getInstance").invoke(null)
                        if (api.javaClass.getMethod("isShaderPackInUse").invoke(api) as? Boolean == true) return true
                    }
                    DetectorKind.STATIC_GETTER -> {
                        val result = clazz.getMethod(candidate.methodName!!).invoke(null)
                        if (result != null) return true
                    }
                }
            } catch (_: Exception) { }
        }

        // Iris is present but all detection methods either failed or returned a definitive "no".
        // On 26.2 Fabric, even when shaders are disabled (enableShaders=false in iris.properties),
        // the Iris rendering layer intercepts the custom YUV RenderPipeline and the display goes
        // black (no frames rendered).  The only reliable path is the CPU RGB24 pipeline, which
        // requires that we conservatively treat Iris as active.  See 696bcbdf.
        return true
    }

    /** True if any known Iris class is present on the classpath. */
    private fun irisPresent(): Boolean =
        IRIS_CLASSES.any { runCatching { Class.forName(it); true }.getOrDefault(false) }

    /** `Optifine` shaders. */
    private fun optifineShaderPackActive(): Boolean = runCatching {
        Class.forName("net.optifine.Config").getMethod("isShaders").invoke(null) as? Boolean == true
    }.getOrDefault(false)

    /** `Canvas` shaders (it's an old project, but it's still in use by some people). */
    private fun canvasRendererActive(): Boolean =
        classPresent("grondag.canvas.CanvasMod") || classPresent("io.vram.canvas.CanvasFabricMod")

    /** True if the given class is present. */
    private fun classPresent(name: String): Boolean = runCatching {
        Class.forName(name, false, ShaderPackCompat::class.java.classLoader)
        true
    }.getOrDefault(false)

    private enum class DetectorKind { API_GET_INSTANCE, STATIC_GETTER }

    private data class IrisDetector(
        val className: String,
        val kind: DetectorKind,
        val methodName: String? = null,
    )

    private val IRIS_CLASSES = listOf(
        "net.irisshaders.iris.api.v0.IrisApi",
        "net.irisshaders.iris.api.v1.IrisApi",
        "net.irisshaders.iris.Iris",
        "net.irisshaders.iris.pipeline.IrisPipelineManager",
    )

    private val IRIS_DETECTORS = listOf(
        IrisDetector("net.irisshaders.iris.api.v0.IrisApi", DetectorKind.API_GET_INSTANCE),
        IrisDetector("net.irisshaders.iris.api.v1.IrisApi", DetectorKind.API_GET_INSTANCE),
        IrisDetector("net.irisshaders.iris.Iris", DetectorKind.STATIC_GETTER, "getShaderPack"),
    )
}
