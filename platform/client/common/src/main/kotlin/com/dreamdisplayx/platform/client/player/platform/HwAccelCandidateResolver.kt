package com.dreamdisplayx.platform.client.player.platform

import com.dreamdisplayx.api.render.backend.model.RenderBackend
import com.dreamdisplayx.platform.client.render.GpuVendorProbe
import com.dreamdisplayx.platform.client.render.GpuVendorProbe.Vendor
import com.dreamdisplayx.platform.client.render.RenderBackendCompat
import com.dreamdisplayx.util.OsInfo

/**
 * Decides the ordered list of FFmpeg hwaccel backend names to attempt for video decode.
 *
 * Selection rules (all platforms & versions):
 *  - macOS              → `videotoolbox` (only)
 *  - Windows Intel      → `qsv`, fallback `d3d11va`
 *  - Windows NVIDIA     → `cuda` (NVDEC), fallback `d3d11va`
 *  - Windows AMD        → `amf`, fallback `d3d11va`
 *  - Windows unknown GPU→ `d3d11va` (global Windows fallback)
 *  - Linux              → `vaapi` (global Linux fallback), `vulkan` preferred when a Vulkan
 *    render backend is active
 *  - Vulkan render backend (e.g. 26.2) prepends `vulkan` on every platform except macOS.
 *
 * The exact FFmpeg build capabilities are verified later by `HwAccelEnumerator` inside the video
 * pipe; the candidates here are the *desired* priority list, and an unavailable or failed backend
 * silently falls back to the next candidate, then to software.
 */
internal object HwAccelCandidateResolver {

    /** Version-independent resolved candidate list for the built-in "auto" detector. */
    fun autoCandidates(): List<String> {
        val backend = RenderBackendCompat.backend()
        val vulkanBackend = backend == RenderBackend.VULKAN || backend == RenderBackend.VULKAN_MOD
        return when {
            OsInfo.isMac -> listOf("videotoolbox")
            OsInfo.isWindows -> {
                val vendorPlan = when (GpuVendorProbe.vendor()) {
                    Vendor.NVIDIA -> listOf("cuda", "d3d11va")
                    Vendor.INTEL -> listOf("qsv", "d3d11va")
                    Vendor.AMD -> listOf("amf", "d3d11va")
                    Vendor.UNKNOWN -> listOf("d3d11va")
                }
                if (vulkanBackend) listOf("vulkan") + vendorPlan else vendorPlan
            }
            OsInfo.isLinux -> if (vulkanBackend) listOf("vulkan", "vaapi") else listOf("vaapi")
            else -> emptyList()
        }
    }

    /**
     * Resolves the user's configured decoder choice (from `Config.hwaccelDecoder`) into the
     * candidate list handed to the media pipeline:
     *  - `"auto"`      → [autoCandidates]
     *  - `"software"`  → empty list (software-only decode)
     *  - any backend name → that backend first, then the auto fallbacks (deduplicated).
     */
    fun resolve(configured: String, hwAccelAllowed: Boolean): List<String> {
        if (!hwAccelAllowed) return emptyList()
        val choice = configured.trim().lowercase()
        return when {
            choice.isEmpty() || choice == "auto" -> autoCandidates()
            choice == "software" || choice == "off" || choice == "none" -> emptyList()
            else -> (listOf(choice) + autoCandidates()).distinct()
        }
    }
}