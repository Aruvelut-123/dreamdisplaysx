package com.dreamdisplayx.api.media.player

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * Read-only playback-relevant configuration the media player needs from the host application.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface PlaybackConfig {
    /** Default per-display volume (0–2.0) applied to a freshly created player. */
    val defaultDisplayVolume: Double

    /** Whether hardware-accelerated decoding should be attempted. */
    val useHwAccel: Boolean

    /** Whether the current user may use premium quality tiers (e.g. >1080p). */
    val isPremium: Boolean

    /** Whether the GPU-side YUV (planar I420) render path is active; selects the planar decode output. */
    val gpuYuvActive: Boolean

    /**
     * Ordered list of FFmpeg hwaccel backend names to try for video decode, in priority order.
     * The first candidate that the FFmpeg build supports and the GPU driver can open will be used;
     * if none works, the video pipe falls back to software decode.  Empty = software only.
     *
     * Known values: `"qsv"`, `"cuda"`, `"nvdec"`, `"amf"`, `"d3d11va"`, `"vaapi"`, `"vulkan"`,
     * `"videotoolbox"`, `"dxva2"`, `"mediacodec"`.
     */
    val hwAccelCandidates: List<String> get() = emptyList()
}
