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
     * Ordered list of hardware-decode candidates for video playback.
     *
     * **Currently unread — both implementations return an empty list and nothing consumes it.**
     * The real choice is made by `FfmpegHwBackendPolicy`, which builds a per-platform chain
     * internally; there is no need to feed it from config.
     *
     * If this is ever wired up, the values must be **FFmpeg decoder names**
     * (`avcodec_find_decoder_by_name` inputs such as `h264_cuvid`, `h264_qsv`, `h264_vaapi`,
     * `h264_d3d11va`, `h264_videotoolbox`), *not* libvlc's `-hwaccel` accelerator names.
     * `"cuda"`, `"nvdec"` and `"amf"` are not decoders at all: the bytedeco builds register no
     * decoder under those names, so listing them would only produce a failed lookup per start.
     */
    val hwAccelCandidates: List<String> get() = emptyList()

    /**
     * Whether the standalone FFmpeg demux+decode pipeline should be used.
     *
     * This replaces the old `androidMediaCodecEnabled` which was Android-only. The new pipeline
     * works on all platforms where the FFmpeg native runtime is available (Android + desktop).
     *
     * Defaults to false so that hosts which do not implement it keep the existing behaviour.
     */
    val ffmpegPipelineEnabled: Boolean get() = false

    /**
     * Preferred Bilibili CDN mirror host, or null / empty to auto-select by bandwidth probe.
     *
     * When set to a known mirror hostname (e.g. `"upos-sz-mirrorcos.bilivideo.com"`), every
     * Bilibili stream URL's host is replaced with this value before playback starts — the API
     * returns the same content from any mirror.  Special values `"BASE_URL"` and `"BACKUP_URL"`
     * keep the original API host or the first backup URL respectively.
     */
    val bilibiliCdnMirror: String? get() = null
}
