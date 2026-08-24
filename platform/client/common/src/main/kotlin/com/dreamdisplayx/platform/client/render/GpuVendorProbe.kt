package com.dreamdisplayx.platform.client.render

import com.mojang.blaze3d.systems.RenderSystem
import org.lwjgl.opengl.GL11

/**
 * Runtime probes for the GPU that is actually driving the renderer.
 *
 * Vendor detection mirrors what Sodium does: read the GPU name straight from the
 * render device (26.2 exposes `DeviceInfo.vendorName()` / `name()`) and fall back to
 * the raw LWJGL OpenGL renderer string on older versions / other backends.
 * All calls are best-effort; failures degrade to [Vendor.UNKNOWN].
 */
internal object GpuVendorProbe {
    internal enum class Vendor(val keywords: List<String>) {
        NVIDIA(listOf("nvidia", "geforce", "quadro", "tesla", "rtx", "gtx", "nvida")),
        INTEL(listOf("intel", "uhd", "iris", "arc", "hd graphics")),
        AMD(listOf("amd", "radeon", "ati", "firepro")),
        UNKNOWN(emptyList()),
    }

    /** Best-effort GPU vendor of the active render device. */
    fun vendor(): Vendor {
        val fingerprint = fingerprint()
        return Vendor.entries.firstOrNull { v ->
            v != Vendor.UNKNOWN && v.keywords.any { it in fingerprint }
        } ?: Vendor.UNKNOWN
    }

    /** Best-effort GPU model name reported by the renderer, lowercased. */
    fun deviceName(): String = fingerprint()

    /** Combined lowercased description of the active GPU. */
    private fun fingerprint(): String = runCatching {
    //? if >=26.2 {
        val info = RenderSystem.getDevice().deviceInfo
        listOf(info.vendorName(), info.name())
    //?} else
    /*listOf(
        org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_RENDERER),
        org.lwjgl.opengl.GL11.glGetString(org.lwjgl.opengl.GL11.GL_VENDOR),
    )*/
    //?}
    }.getOrNull()?.filterNotNull()?.joinToString(" ")?.lowercase() ?: ""
}