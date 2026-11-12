package com.dreamdisplayx.media.player.process

/**
 * 缩略图缩放与像素转换的纯算术部分。
 *
 * ## 为什么单独拆出来
 *
 * 真正取帧要开 FFmpeg 解封装 + 解码器，只能在有原生运行时的机器上跑；但「把一帧
 * BGRA 摆进 W×H 的缩略图里」全是整数运算，而它恰好是最容易悄悄错的地方：
 *
 * - 直接按目标尺寸拉伸 → 人脸变扁（缩略图卡片上非常明显）；
 * - 加了黑边但宽高算错 1 像素 → 右侧/底部出现一条亮边；
 * - 奇数宽度取一半 → 色度平面越界，某些设备上直接崩。
 *
 * 这些都不抛异常，只是「看起来不对」，所以必须能脱离设备验证
 * （`ThumbScalePolicyTest`）。
 */
internal object ThumbScalePolicy {

    /**
     * 把 [srcW]×[srcH] 等比放进 [dstW]×[dstH]，返回实际绘制矩形。
     *
     * @return `[x, y, w, h]`，全部已钳进目标范围且 w/h >= 1
     */
    fun letterbox(srcW: Int, srcH: Int, dstW: Int, dstH: Int): IntArray {
        if (srcW <= 0 || srcH <= 0 || dstW <= 0 || dstH <= 0) return intArrayOf(0, 0, dstW.coerceAtLeast(0), dstH.coerceAtLeast(0))
        // 取两个方向缩放的较小者：这是「完整放进」的定义，任一方向先到边即停。
        val scaleW = dstW.toDouble() / srcW
        val scaleH = dstH.toDouble() / srcH
        val scale = minOf(scaleW, scaleH)
        val dw = (srcW * scale).toInt().coerceIn(1, dstW)
        val dh = (srcH * scale).toInt().coerceIn(1, dstH)
        return intArrayOf((dstW - dw) / 2, (dstH - dh) / 2, dw, dh)
    }

    /**
     * BGRA（FFmpeg 的 `AV_PIX_FMT_BGRA`，每像素 4 字节，A 在最前还是最后取决于字节序，
     * 这里按 FFmpeg 的定义：B、G、R、A）打包成 `0xRRGGBB`。
     *
     * @param bgra 至少 `offset + 4` 字节
     * @param offset 该像素的起始下标
     * @return 0xRRGGBB；越界时返回 0（黑）
     */
    fun bgraToRgbInt(bgra: ByteArray, offset: Int): Int {
        if (offset < 0 || offset + 3 >= bgra.size) return 0
        val b = bgra[offset].toInt() and 0xFF
        val g = bgra[offset + 1].toInt() and 0xFF
        val r = bgra[offset + 2].toInt() and 0xFF
        // alpha 在 offset+3，缩略图不需要
        return (r shl 16) or (g shl 8) or b
    }

    /**
     * 一帧 BGRA 的行距是否等于紧密排列的 `w*4`。
     *
     * FFmpeg 的 `sws_scale` 输出是我们自己给的 stride（紧密），但这条判据留着是为了
     * 在有人改成 `av_frame_get_buffer` 分配之后再读像素时立刻能发现 stride 不再是
     * `w*4`——那时按 `w*4` 取下标会读错行，画面出现斜向撕裂。
     */
    fun isPackedRows(strideBytes: Int, width: Int): Boolean = strideBytes == width * 4

    /** 一行像素在 [strideBytes] 行距下的起始下标。 */
    fun rowOffset(row: Int, strideBytes: Int): Int = row * strideBytes
}