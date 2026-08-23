package com.dreamdisplayx.media.player.process

import com.dreamdisplayx.util.OsInfo

/**
 * Hardware-accelerated video decoder backends supported by the platform.
 *
 * JavaCPP / FFmpeg handles hardware acceleration internally; this enum reports
 * what the host OS can provide for capability handshake ([ClientHello.hwAccelBackend]).
 */
enum class HwAccelBackend {
    /** Apple platforms (VideoToolbox). */
    VIDEOTOOLBOX,

    /** Windows `Direct3D 11` Video Acceleration. */
    D3D11VA,

    /** Linux `Video Acceleration API`. */
    VAAPI,

    /** NVIDIA CUDA / NVDEC. */
    CUDA,

    /** Android `MediaCodec`. */
    MEDIACODEC,

    /** Software decoding only. */
    NONE;

    companion object {
        /**
         * Picks a sensible default backend for the host OS. We deliberately pick the most broadly
         * compatible option per-platform rather than the absolute fastest: a stream that fails to
         * decode is worse than a stream that decodes a bit slower.
         */
        fun detectDefault(): HwAccelBackend = when {
            OsInfo.isAndroid -> MEDIACODEC
            OsInfo.isMac -> VIDEOTOOLBOX
            OsInfo.isWindows -> D3D11VA
            OsInfo.isLinux -> VAAPI
            else -> NONE
        }
    }
}