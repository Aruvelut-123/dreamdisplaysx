/**
 * 全平台统一 MediaCodec 硬解切换桥接
 *
 * 核心约束：
 * 1. 桌面端（非 Android）永远返回 isAvailable()=false，不加载任何 Android 专用类，保留 libvlc 行为
 * 2. Android 端通过 config 开关控制：`ffmpeg.enableAndroidMediaCodec` 为 true 时启动硬解路径
 * 3. NDK 后端（mediacodec-ndk）优先，因为它走原生 C API，不需要游戏 JVM 有 android.* 框架类
 *    （Pojav/Zalith 等 game JVM 的 JNI 后端 LoadLibrary 会因为缺少 android/os/Environment 而崩溃）
 * 4. JNI 后端仅在确认环境有 android.* 时备用
 * 5. 输出 YUV420P 帧，供现有 FrameSurface / FrameUploaderPipeline 消费
 * 6. 桌面编译时不加入任何 android.* 引用，由 OsInfo.isAndroid 判断
 */
package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.OsInfo
import com.dreamdisplayx.util.log.DreamLog
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.javacpp.Loader
import java.util.concurrent.atomic.AtomicBoolean

internal object FfmpegMediaCodecBridge {
    private val logger = DreamLog.of("FfmpegMediaCodecBridge")
    /** 是否已探测到当前环境是否可用 */
    @Volatile
    private var probed = false
    /** 探测结果：true=Android + FFmpeg build 包含 MediaCodec decoder */
    @Volatile
    private var available = false

    /** 探测一次（必须在类加载后由上层调用一次） */
    fun probe(): Boolean {
        if (probed) return available
        probed = true
        if (!OsInfo.isAndroid) {
            logger.info("Not Android platform, MediaCodec bridge disabled for desktop")
            available = false
            return false
        }
        // 使用 JavaCPP 的 Loader 自动提取 so，避免手动路径
        return try {
            // 载入 avcodec so（会自动从 maven artifact / jar 解压对应平台的 .so）
            Loader.load(org.bytedeco.ffmpeg.global.avcodec::class.java)
            // 探测可用的 decoder 名称
            val h264 = avcodec.avcodec_find_decoder_by_name("h264_mediacodec") != null
            val hevc = avcodec.avcodec_find_decoder_by_name("hevc_mediacodec") != null
            logger.info("MediaCodec decoder probe: h264=$h264 hevc=$hevc")
            available = h264 || hevc
            if (!available) {
                logger.warn("FFmpeg build does not contain h264_mediacodec/hevc_mediacodec, bridge disabled")
            }
            available
        } catch (e: Throwable) {
            logger.warn("MediaCodec probe failed (native lib not found?): ${e.message}")
            available = false
            false
        }
    }

    /** 是否可用（已探测结果） */
    fun isAvailable(): Boolean {
        if (!probed) probe()
        return available
    }

    /** 重置探测状态（当配置变更时调用） */
    fun reset() {
        probed = false
        available = false
    }
}