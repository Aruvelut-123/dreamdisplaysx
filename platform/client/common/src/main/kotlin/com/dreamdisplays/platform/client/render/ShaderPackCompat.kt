package com.dreamdisplays.platform.client.render

import com.dreamdisplays.core.render.ShaderBackend

/** Shader-pack detector: checks for `Iris`, `OptiFine`, or `Canvas` shader packs without adding hard dependencies. */
internal object ShaderPackCompat {
    /** True when any supported shader pack is currently in use. */
    val isShaderPackActive: Boolean get() = shaderBackend() != ShaderBackend.NONE

    /** Active shader backend, or [ShaderBackend.NONE]. */
    fun shaderBackend(): ShaderBackend = when {
        irisShaderPackActive() -> ShaderBackend.IRIS
        optifineShaderPackActive() -> ShaderBackend.OPTIFINE
        canvasRendererActive() -> ShaderBackend.CANVAS
        else -> ShaderBackend.NONE
    }

    private fun irisShaderPackActive(): Boolean = runCatching {
        val apiClass = Class.forName("net.irisshaders.iris.api.v0.IrisApi")
        val api = apiClass.getMethod("getInstance").invoke(null)
        api.javaClass.getMethod("isShaderPackInUse").invoke(api) as? Boolean == true
    }.getOrDefault(false)

    private fun optifineShaderPackActive(): Boolean = runCatching {
        Class.forName("net.optifine.Config").getMethod("isShaders").invoke(null) as? Boolean == true
    }.getOrDefault(false)

    private fun canvasRendererActive(): Boolean =
        classPresent("grondag.canvas.CanvasMod") || classPresent("io.vram.canvas.CanvasFabricMod")

    private fun classPresent(name: String): Boolean = runCatching {
        Class.forName(name, false, ShaderPackCompat::class.java.classLoader)
        true
    }.getOrDefault(false)
}
