package com.dreamdisplayx.media.player.ffmpeg

/**
 * 判断 FFmpeg 会话该不该为音频单独开一个输入。
 *
 * ## 为什么单独成一个对象
 *
 * 这个判断决定「有没有声音」：DASH 把音频拆成独立一路时，视频那个容器里根本没有
 * 音频流，若仍只用主 demuxer，画面正常而声音永远静音，且没有任何异常指向原因。
 *
 * 反过来，muxed 源里视频容器自带音频流时若又去开音频 URL，就会出现两条音轨同时
 * 推进、设备被重复写入。两个方向的错都只在真机上才听得出来，所以判据放在这里，
 * 由纯 JVM 单测钉住，而不是埋在 [FfmpegSession] 的 lambda 里。
 */
internal object FfmpegAudioSourcePolicy {

    /**
     * 起播时是否需要为音频另开输入。
     *
     * 容器自带音频流时一律不另开：那是最常见也最省的情况，多开一个输入只会
     * 让音轨互相追赶。
     *
     * @param videoUrl 视频输入 URL
     * @param audioUrl [com.dreamdisplayx.media.player.stream.ActiveStreams.currentAudio] 的 URL
     * @param videoHasAudio 主 demuxer 是否已定位到音频流
     */
    fun needsSeparateInput(videoUrl: String, audioUrl: String, videoHasAudio: Boolean): Boolean {
        if (videoHasAudio) return false
        return isSeparateUrl(videoUrl, audioUrl)
    }

    /**
     * [audioUrl] 是否是「独立于视频的另一个输入」。
     *
     * 空 URL 表示解析器没给出音频，此时无可打开；与视频同一个 URL 表示 muxed，
     * 再开一次也只会得到一个没有音频的容器。
     */
    fun isSeparateUrl(videoUrl: String, audioUrl: String): Boolean =
        audioUrl.isNotBlank() && audioUrl != videoUrl
}
