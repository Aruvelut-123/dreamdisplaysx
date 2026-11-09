/**
 * FFmpeg 全平台媒体解码器调度器
 *
 * 设计：
 * 1. 桌面端（非 Android）永远使用软解，回退到 libvlc，由 FfmpegMediaCodecBridge.isAvailable() 返回 false
 * 2. Android 端通过 config `ffmpeg.enableAndroidMediaCodec` 控制：
 *    - true 时：尝试 NDK 后端硬解，若失败回退到 libvlc 既有路径
 *    - false 时：直接使用 libvlc 既有路径
 * 3. 所有编译时类均在 `com.dreamdisplayx.media.player.ffmpeg` 包下，
 *    桌面编译时 `OsInfo.isAndroid` 为 false，因此不会引入任何 android.* 类
 */
package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.log.DreamLog

internal object FfmpegCodecScheduler {
    private val logger = DreamLog.of("FfmpegCodecScheduler")
    /** 当前是否走 FFmpeg 硬解/软解路径 */
    @Volatile
    var useFfmpeg: Boolean = false

    /** 探测一次（由配置开关触发） */
    fun probeAndSet(enableAndroidMediaCodec: Boolean) {
        if (!OsInfo.isAndroid) {
            // 桌面端：始终使用软解回退（libvlc），不加载任何 Android 类
            useFfmpeg = false
            logger.info("Desktop platform: MediaCodec disabled, using libvlc fallback")
            return
        }
        if (enableAndroidMediaCodec && FfmpegMediaCodecBridge.isAvailable()) {
            useFfmpeg = true
            logger.info("Android: MediaCodec NDK hard decode enabled")
        } else {
            useFfmpeg = false
            if (enableAndroidMediaCodec) {
                logger.warn("Android: MediaCodec not available, falling back to libvlc")
            } else {
                logger.info("Android: MediaCodec disabled by config, using libvlc")
            }
        }
    }

    /** 重置状态（配置变更时调用） */
    fun reset() {
        useFfmpeg = false
        FfmpegMediaCodecBridge.reset()
    }
}