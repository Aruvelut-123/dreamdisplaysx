package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.api.media.source.model.CustomMediaKind
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * [FfmpegNetworkPolicy] 的行为钉板。
 *
 * 这个策略的失效模式是「安静的」：白名单漏加时 `avformat_open_input` 只是返回负值，
 * 上层把它记进 `lastError` 然后回退，用户看到的是「某些链接黑屏」，日志里没有任何
 * 一行指向协议白名单。所以每一条判据都在这里固定下来。
 */
class FfmpegNetworkPolicyTest {

    // ---------------------------------------------------------------- isManifest

    @Test
    fun `m3u8 by extension is a manifest`() {
        assertTrue(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/live/master.m3u8"))
    }

    @Test
    fun `mpd by extension is a manifest`() {
        assertTrue(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/dash/manifest.mpd"))
    }

    @Test
    fun `m3u is treated as a manifest`() {
        // 少数老式编码器发 `.m3u`；HLS demuxer 两个都认。
        assertTrue(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/audio/playlist.m3u"))
    }

    @Test
    fun `extension matching is case insensitive`() {
        assertTrue(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/LIVE/Master.M3U8"))
    }

    @Test
    fun `a progressive mp4 is not a manifest`() {
        assertFalse(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/vod/movie.mp4"))
    }

    @Test
    fun `a fragment url is not a manifest`() {
        // HLS 分片本身走 http，和直链一样不需要白名单——真正需要白名单的是读清单的那次打开。
        assertFalse(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/live/seg-00001.ts"))
    }

    @Test
    fun `an extensionless url is not treated as a manifest`() {
        // 拿不准时这里选择「不放宽」，因为假阳性（对直链加白名单）是安全的，
        // 而真正的无扩展名清单由解析器在 resolve() 阶段按 Content-Type 判定，
        // 那一步会把 kind 传进来（见下面的 kind 优先级用例）。
        assertFalse(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/stream/12345"))
    }

    @Test
    fun `a query string does not defeat the extension check`() {
        assertTrue(
            FfmpegNetworkPolicy.isManifest(
                "https://cdn.example.com/live/master.m3u8?sign=abc123&t=1700000000",
            ),
        )
    }

    @Test
    fun `a manifest-looking query parameter is deliberately ignored`() {
        // `?format=m3u8` 是对方的输入，不能拿它决定我们的安全边界；
        // 路径里没有扩展名就当直链，真清单会由 kind 分支兜住。
        assertFalse(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/play?format=m3u8"))
    }

    @Test
    fun `a fragment after the extension still matches`() {
        assertTrue(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/master.m3u8#tracks"))
    }

    @Test
    fun `a dot in the host is not read as an extension`() {
        // 主机名里的 `.` 不能参与扩展名判断，否则 `example.com` 会被当成扩展名 `com`。
        assertFalse(FfmpegNetworkPolicy.isManifest("https://media.example.com"))
    }

    @Test
    fun `a trailing dot yields no extension`() {
        assertFalse(FfmpegNetworkPolicy.isManifest("https://cdn.example.com/video."))
    }

    // ---------------------------------------------------------------- kind 优先

    @Test
    fun `an explicit HLS kind wins over an extensionless url`() {
        // 解析器按响应体判定出来的类型是权威的，必须能覆盖扩展名猜不出结论的情况。
        assertTrue(
            FfmpegNetworkPolicy.isManifest("https://cdn.example.com/stream/12345", CustomMediaKind.HLS),
        )
    }

    @Test
    fun `an explicit DASH kind wins over a mp4 extension`() {
        assertTrue(
            FfmpegNetworkPolicy.isManifest("https://cdn.example.com/odd.mp4", CustomMediaKind.DASH),
        )
    }

    @Test
    fun `an explicit progressive kind wins over an m3u8 extension`() {
        assertFalse(
            FfmpegNetworkPolicy.isManifest("https://cdn.example.com/odd.m3u8", CustomMediaKind.PROGRESSIVE),
        )
    }

    @Test
    fun `an explicit audio-only kind is not a manifest`() {
        assertFalse(
            FfmpegNetworkPolicy.isManifest("https://cdn.example.com/track.m3u", CustomMediaKind.AUDIO_ONLY),
        )
    }

    @Test
    fun `an unknown kind falls back to the extension`() {
        assertTrue(
            FfmpegNetworkPolicy.isManifest("https://cdn.example.com/master.m3u8", CustomMediaKind.UNKNOWN),
        )
        assertFalse(
            FfmpegNetworkPolicy.isManifest("https://cdn.example.com/movie.mp4", CustomMediaKind.UNKNOWN),
        )
    }

    // ---------------------------------------------------------------- needsManifestProtocols

    @Test
    fun `needsManifestProtocols agrees with the extension half of isManifest`() {
        // 调用点手里往往只有 URL，这两个判断必须是同一个事实的两个入口。
        assertTrue(FfmpegNetworkPolicy.needsManifestProtocols("https://cdn.example.com/a.m3u8"))
        assertFalse(FfmpegNetworkPolicy.needsManifestProtocols("https://cdn.example.com/a.mp4"))
    }

    // ---------------------------------------------------------------- optionsFor

    @Test
    fun `a plain progressive url yields no options`() {
        assertTrue(FfmpegNetworkPolicy.optionsFor("https://cdn.example.com/movie.mp4").isEmpty())
    }

    @Test
    fun `a manifest yields exactly the protocol whitelist`() {
        val opts = FfmpegNetworkPolicy.optionsFor("https://cdn.example.com/master.m3u8")
        assertEquals(1, opts.size)
        assertEquals("protocol_whitelist", opts[0].first)
        assertEquals(FfmpegNetworkPolicy.MANIFEST_PROTOCOLS, opts[0].second)
    }

    @Test
    fun `the whitelist pins the protocols a manifest actually needs`() {
        // 值本身也要钉住：少了 `tls` 会让 https 分片打不开，多了 `file` 会开一个
        // 本地读取面（SSRF / 信息泄露最常用的一条）。
        assertEquals("http,https,tls,tcp", FfmpegNetworkPolicy.MANIFEST_PROTOCOLS)
        val parts = FfmpegNetworkPolicy.MANIFEST_PROTOCOLS.split(',')
        assertTrue(parts.contains("http"))
        assertTrue(parts.contains("https"))
        assertTrue(parts.contains("tls"))
        assertTrue(parts.contains("tcp"))
        assertFalse(parts.contains("file"))
        assertFalse(parts.contains("crypto"))
        assertFalse(parts.contains("data"))
        assertFalse(parts.contains("pipe"))
    }

    @Test
    fun `the whitelist is emitted after referer and user agent`() {
        val opts = FfmpegNetworkPolicy.optionsFor(
            "https://cdn.example.com/master.m3u8",
            referer = "https://bilibili.com",
            userAgent = "TestAgent/1.0",
        )
        assertEquals(listOf("headers", "user_agent", "protocol_whitelist"), opts.map { it.first })
    }

    @Test
    fun `referer is rendered as an HTTP header line`() {
        val opts = FfmpegNetworkPolicy.optionsFor(
            "https://cdn.example.com/movie.mp4",
            referer = "https://www.bilibili.com/",
        )
        assertEquals(1, opts.size)
        assertEquals("headers", opts[0].first)
        // FFmpeg 的 headers 选项要求行尾 CRLF，缺了会让部分 CDN 直接 403。
        assertEquals("Referer: https://www.bilibili.com/\r\n", opts[0].second)
    }

    @Test
    fun `a blank referer is treated as absent`() {
        assertTrue(
            FfmpegNetworkPolicy.optionsFor("https://cdn.example.com/movie.mp4", referer = "   ").isEmpty(),
        )
    }

    @Test
    fun `a blank user agent is treated as absent`() {
        assertTrue(
            FfmpegNetworkPolicy.optionsFor("https://cdn.example.com/movie.mp4", userAgent = "").isEmpty(),
        )
    }

    @Test
    fun `referer and user agent are independent`() {
        val onlyUa = FfmpegNetworkPolicy.optionsFor(
            "https://cdn.example.com/movie.mp4",
            userAgent = "TestAgent/1.0",
        )
        assertEquals(listOf("user_agent"), onlyUa.map { it.first })

        val onlyReferer = FfmpegNetworkPolicy.optionsFor(
            "https://cdn.example.com/movie.mp4",
            referer = "https://www.bilibili.com/",
        )
        assertEquals(listOf("headers"), onlyReferer.map { it.first })
    }

    // ---------------------------------------------------------------- PROBE_OPTIONS

    @Test
    fun `probe options are bounded and independent of the url`() {
        // 这两个值对所有输入都一样：太大会拖慢起播，太小会让部分 DASH 源
        // 在 find_stream_info 阶段拿不到分辨率。
        assertEquals(
            listOf("analyzeduration" to "3000000", "probesize" to "1000000"),
            FfmpegNetworkPolicy.PROBE_OPTIONS,
        )
    }

    @Test
    fun `probe options never include a whitelist`() {
        // 两类选项分开的原因：白名单只对清单有意义，混在一起会让
        // 「这个 URL 是不是清单」和「探测多久」纠缠在同一个断言里。
        assertFalse(FfmpegNetworkPolicy.PROBE_OPTIONS.any { it.first == "protocol_whitelist" })
    }
}
