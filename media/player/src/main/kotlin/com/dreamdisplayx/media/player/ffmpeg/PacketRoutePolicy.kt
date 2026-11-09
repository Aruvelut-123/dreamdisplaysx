package com.dreamdisplayx.media.player.ffmpeg

/**
 * Decides which consumer a freshly read packet belongs to.
 *
 * ## Why this is a separate file rather than a companion object
 *
 * [FfmpegDemuxer] cannot be loaded without the FFmpeg natives: its companion
 * holds values produced by calling into `avutil` (`AVERROR_EOF()`), so merely
 * touching `FfmpegDemuxer.Companion` triggers `Loader.load` and blows up with
 * `UnsatisfiedLinkError` on any machine without the runtime. A test that only
 * wants to check stream-index routing must not pay that, and on CI it cannot.
 *
 * Keeping this pure also means the sentinel rules are covered by the ordinary
 * unit-test job rather than a manual smoke test — which matters, because the
 * failure mode here is an infinite read loop on a malformed source, not an
 * exception.
 */
internal object PacketRoutePolicy {

    /** Where one demuxed packet has to go. */
    enum class Route {
        /** The video decoder. */
        VIDEO,

        /** The audio decoder. */
        AUDIO,

        /** Subtitle / data / malformed packet: drop it and read again. */
        SKIP,
    }

    /**
     * Classifies [packetStreamIndex] against the two stream indices the demuxer
     * located.
     *
     * Both indices are `-1` when that stream is absent, and a malformed packet
     * also reports `-1`. The guards below are what keep those three cases apart:
     *
     * - a negative packet index is never a real stream, so it is skipped before
     *   any comparison — otherwise a broken packet on an audio-less source
     *   matches the `audioStreamIndex` sentinel and is fed to the audio decoder;
     * - video is compared first, so a container that lists one stream as both
     *   keeps the picture alive;
     * - audio requires `audioStreamIndex >= 0`, so "no audio" can never match.
     */
    fun classify(packetStreamIndex: Int, videoStreamIndex: Int, audioStreamIndex: Int): Route = when {
        packetStreamIndex < 0 -> Route.SKIP
        packetStreamIndex == videoStreamIndex -> Route.VIDEO
        audioStreamIndex >= 0 && packetStreamIndex == audioStreamIndex -> Route.AUDIO
        else -> Route.SKIP
    }
}