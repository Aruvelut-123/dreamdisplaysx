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
    fun canUseDirectOpenGl(): Boolean = isOpenGlBackend() && !isVulkanModLoaded && !isMobileGlues()

    /**
     * MobileGlues advertises desktop OpenGL extensions on top of GLES. Its PBO / BGRA upload path can
     * accept the calls without producing a visible texture, so use Minecraft's command encoder there.
     * Keep the probe string-based and independent of Android classes: Pojav/FCL exposes the renderer
     * through launcher and LWJGL properties before the first texture is allocated.
     */
    internal fun isMobileGlues(
        environment: Map<String, String> = System.getenv(),
        properties: Map<String, String> = systemProperties(),
    ): Boolean = sequenceOf(
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
    ).filterNotNull().any { it.contains("mobileglues", ignoreCase = true) }

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
