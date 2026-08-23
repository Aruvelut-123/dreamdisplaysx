package com.dreamdisplayx.platform.client.capabilities

import com.dreamdisplayx.api.media.stream.model.SupportedCodec
import com.dreamdisplayx.api.render.backend.model.RenderBackend
import com.dreamdisplayx.api.render.backend.model.ShaderBackend
import com.dreamdisplayx.api.render.texture.model.TextureUploadPath
import com.dreamdisplayx.core.protocol.common.packets.ClientHello
import com.dreamdisplayx.media.player.process.HwAccelBackend
import com.dreamdisplayx.platform.client.managers.WarmParkPolicy
import com.dreamdisplayx.platform.client.render.AsyncTextureUploader
import com.dreamdisplayx.platform.client.render.RenderBackendCompat
import com.dreamdisplayx.platform.client.render.ShaderPackCompat
import com.dreamdisplayx.platform.client.ui.VideoPopoutWindow
import java.time.Instant
import java.time.ZoneId

/**
 * Probes the running client for [ClientHello] capabilities. Popout support comes from the `GLFW`
 * shared-context check in [VideoPopoutWindow], hardware decode from the per-OS
 * [HwAccelBackend] default, and codec support from what the `FFmpeg` pipeline decodes.
 */
object MinecraftClientCapabilityDetector : ClientCapabilityDetector {
    /** Matches [AsyncTextureUploader]; a GL query needs a current context, which detect-time can't guarantee. */
    override val maxTextureSize: Int = 8192

    /** True when `GLFW` can create the shared-context popout window on this platform. */
    override val supportsPopout: Boolean get() = VideoPopoutWindow.isAvailable

    /** True when the host OS has a known `FFmpeg` hwaccel backend. */
    override val supportsHardwareDecode: Boolean get() = HwAccelBackend.detectDefault() != HwAccelBackend.NONE

    /** Codecs the `FFmpeg` decode pipeline accepts regardless of hwaccel availability. */
    override val supportedCodecs: List<SupportedCodec> = SupportedCodec.advertised

    /** Snapshots all probes into an immutable [ClientHello] for the handshake. */
    override fun detect(): ClientHello {
        val hwAccel = HwAccelBackend.detectDefault()
        val memory = ClientMemoryProbe.detected
        return ClientHello(
            supportsPopout = supportsPopout,
            supportsHardwareDecode = hwAccel != HwAccelBackend.NONE,
            supportsHighResolution = maxTextureSize >= 4096,
            maxTextureSize = maxTextureSize,
            supportedCodecs = supportedCodecs.map { it.wire },
            supportsPip = true,
            supportsAudio = true,
            renderBackend = safeString(RenderBackend.UNKNOWN.wire) { RenderBackendCompat.backend().wire },
            shaderBackend = safeString(ShaderBackend.UNKNOWN.wire) { ShaderPackCompat.shaderBackend().wire },
            textureUploadPath = safeString(TextureUploadPath.UNKNOWN.wire) { RenderBackendCompat.textureUploadPath().wire },
            hwAccelBackend = hwAccel.name.lowercase(),
            // Rust native backend removed; JavaCPP replaces all native decode functionality
            nativeBackendAvailable = false,
            nativeRgbaFramesEnabled = false,
            nativeYuvGpuEnabled = false,
            lavAvailable = false,
            lavInProcessEnabled = false,
            lavSurfaceInteropAvailable = false,
            lavZeroCopyEnabled = false,
            nativeUnavailableReason = "",
            lavUnavailableReason = "",
            systemRamMb = memory.systemRamMb,
            maxJvmMemoryMb = memory.maxJvmMemoryMb,
            dedicatedVramMb = memory.dedicatedVramMb,
            warmDisplayLimit = WarmParkPolicy.maxFullWarmDisplays,
            timeZoneOffsetMinutes = safeInt { ZoneId.systemDefault().rules.getOffset(Instant.now()).totalSeconds / 60 },
        )
    }

    /** Runs [block] and returns `0` on any exception. */
    private fun safeInt(block: () -> Int): Int = runCatching(block).getOrDefault(0)

    /** Runs [block] and returns `false` on any exception. */
    private fun safeBool(block: () -> Boolean): Boolean = runCatching(block).getOrDefault(false)

    /** Runs [block] and returns the empty string on any exception. */
    private fun safeString(default: String, block: () -> String): String =
        runCatching(block).getOrDefault(default).ifBlank { default }
}
