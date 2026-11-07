package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.render.backend.model.RenderBackend
import com.dreamdisplayx.api.render.texture.model.TextureUploadPath
import com.mojang.blaze3d.systems.RenderSystem

/** Small runtime probes for renderer backends that replace or virtualize OpenGL. */
internal object RenderBackendCompat {
    /** True when the `VulkanMod` renderer replacement is installed. */
    val isVulkanModLoaded: Boolean by lazy {
        isFabricModLoaded("vulkanmod") || isNeoForgeModLoaded("vulkanmod")
    }

    /** True when raw OpenGL calls are safe (real GL backend and no known virtualized GLES bridge). */
    fun canUseDirectOpenGl(): Boolean = isOpenGlBackend() && !isVulkanModLoaded && !isVirtualizedGlesBridge()

    /**
     * True when a bridge translates desktop OpenGL onto OpenGL ES underneath us.
     *
     * Such a bridge advertises the desktop extensions a real driver would, so every capability probe
     * here succeeds — but the parts of the upload path that rely on real driver behaviour do not:
     * `glFenceSync` / `glClientWaitSync` are emulated and can burn a full timeout per frame, and a
     * `PBO` -> texture copy may be accepted without producing a visible texture.
     *
     * The match is deliberately on the **`mobilegl` prefix**, not on the full `mobileglues` name. The
     * bridge was renamed between releases: the 1.10.8.2 reports came from `MobileGlues`, and a
     * 1.20.1 Fabric log then arrived from `MobileGL` — `Espryt (MobileGL Core) (Adreno (TM) 642L,
     * OpenGL ES 3.2)` / `4.6.0 MobileGL 26.09-dev, Direct (OpenGL ES) Backend`. Matching the longer
     * string silently treated the renamed bridge as a real driver, which re-enabled the `PBO` ring
     * with its fence waits and brought the frame-rate collapse back. Any future fork of this family
     * keeps the prefix, so the prefix is the stable part of the name.
     *
     * Keep the probe string-based and independent of Android classes: Pojav/FCL exposes the renderer
     * through launcher and LWJGL properties before the first texture is allocated, and this must be
     * answerable from the client-hello path where no GL context exists yet.
     */
    internal fun isVirtualizedGlesBridge(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Boolean = fingerprintValues(environment, properties)
        .any { value -> BRIDGE_NAME_MARKERS.any { marker -> value.contains(marker, ignoreCase = true) } }

    /**
     * Every string that can name the active renderer, launcher-supplied or LWJGL-reported.
     *
     * Returned separately from the match so the chosen upload path can log what it actually saw: a
     * probe that only reports `false` cannot be audited when the next bridge ships under a third name.
     */
    internal fun fingerprintValues(
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

    /**
     * Name prefixes of known OpenGL-on-GLES translation layers.
     *
     * `mobilegl` covers both `MobileGlues` and its successor `MobileGL`; see
     * [isVirtualizedGlesBridge] for why the short form is the one that must be matched.
     */
    private val BRIDGE_NAME_MARKERS = listOf("mobilegl")

    /** Best-effort typed active render backend. */
    fun backend(): RenderBackend = runCatching {
        val deviceClass = backendFingerprint()
        when {
            isVulkanModLoaded -> RenderBackend.VULKAN_MOD
            "vulkan" in deviceClass -> RenderBackend.VULKAN
            isOpenGlFingerprint(deviceClass) -> RenderBackend.OPENGL
            else -> RenderBackend.OTHER
        }
    }.getOrDefault(RenderBackend.UNKNOWN)

    /** Texture-upload path taken for this backend (direct PBO vs. command encoder). */
    fun textureUploadPath(): TextureUploadPath =
        if (canUseDirectOpenGl()) TextureUploadPath.DIRECT_OPENGL_PBO else TextureUploadPath.COMMAND_ENCODER

    /** True when the active render device is a real OpenGL backend. */
    fun isOpenGlBackend(): Boolean {
        val deviceClass = backendFingerprint()
        return isOpenGlFingerprint(deviceClass)
    }

    /** Fingerprint of the active render device. */
    private fun backendFingerprint(): String =
    //? if >=1.21.11 {
        //? if >=26.2 {
        RenderSystem.getDevice().deviceInfo.backendName().lowercase()
    //?} else
    /*RenderSystem.getDevice().backendName.lowercase()*/
    //?} else
    /*RenderSystem.getBackendDescription().lowercase()*/

    /** True if the given [value] is a known OpenGL backend fingerprint. */
    private fun isOpenGlFingerprint(value: String): Boolean =
        "opengl" in value || "lwjgl" in value || value.substringAfterLast('.').startsWith("gl")

    /** True if the Fabric mod [id] is loaded. */
    private fun isFabricModLoaded(id: String): Boolean = runCatching {
        val loaderClass = Class.forName("net.fabricmc.loader.api.FabricLoader")
        val loader = loaderClass.getMethod("getInstance").invoke(null)
        loaderClass.getMethod("isModLoaded", String::class.java).invoke(loader, id) as Boolean
    }.getOrDefault(false)

    /** True if the NeoForge mod [id] is loaded. */
    private fun isNeoForgeModLoaded(id: String): Boolean = runCatching {
        val modListClass = Class.forName("net.neoforged.fml.ModList")
        val modList = modListClass.getMethod("get").invoke(null)
        modListClass.getMethod("isLoaded", String::class.java).invoke(modList, id) as Boolean
    }.getOrDefault(false)

    private fun systemProperties(): Map<String, String> =
        System.getProperties().stringPropertyNames().associateWith { System.getProperty(it).orEmpty() }
}
