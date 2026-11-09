package com.dreamdisplayx.media.player.ffmpeg

import com.dreamdisplayx.util.log.DreamLog
import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVCodecParameters
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec
import org.bytedeco.ffmpeg.global.avutil
import org.bytedeco.ffmpeg.global.swresample
import org.bytedeco.ffmpeg.swresample.SwrContext

/**
 * Decodes one audio stream and hands out S16LE interleaved stereo PCM.
 *
 * ## Why the output format is fixed
 *
 * Every consumer downstream (the desktop Java Sound line, the Android
 * `AudioTrack` budget, the DSP stages and the replay PCM ring) is written for
 * 44.1 kHz stereo signed 16-bit little-endian. Forcing that shape here means a
 * codec sample-rate change, a mono rendition or a float-planar decoder never
 * leaks a format difference into four other files; `swresample` does the
 * conversion once, on the decode thread.
 *
 * ## What this class does not do
 *
 * It does not own a packet or a frame loop and it does not touch the demuxer:
 * [FfmpegDemuxer] reads audio packets and hands them in, exactly as it does for
 * video. That keeps the "one reader, many decoders" rule that makes the video
 * path seekable.
 *
 * ## Threading
 *
 * Non-thread-safe. `open` / `decode` / `flush` / `close` must all run on the
 * same thread — the decode loop.
 */
internal class FfmpegAudioDecoder(
    private val codecParameters: AVCodecParameters,
    private val debugLabel: String,
) : AutoCloseable {

    private val logger = DreamLog.of("FfmpegAudioDecoder")

    private var codecCtx: AVCodecContext? = null
    private var swr: SwrContext? = null
    private var frame: AVFrame? = null

    /** Output sample rate; [TARGET_RATE] unless a test asserts otherwise. */
    val sampleRate: Int get() = TARGET_RATE

    /** Output channel count; always stereo. */
    val channels: Int get() = TARGET_CHANNELS

    /** Bytes in one output frame (stereo, 2 bytes per sample). */
    val bytesPerFrame: Int get() = TARGET_CHANNELS * 2

    @Volatile
    var lastError: String = ""
        private set

    /** Source sample rate as declared by the container; diagnostics only. */
    @Volatile
    var sourceSampleRate: Int = 0
        private set

    /** Source channel count as declared by the container; diagnostics only. */
    @Volatile
    var sourceChannels: Int = 0
        private set

    /** Source codec name, for logs. */
    @Volatile
    var sourceCodec: String = ""
        private set

    /**
     * Opens the decoder and the resampler.
     *
     * @return true when [decode] may be called
     */
    fun open(): Boolean {
        if (codecCtx != null) return true
        return try {
            val codecId = codecParameters.codec_id()
            // A dedicated decoder, never `avcodec_find_decoder` with a guess: an
            // audio rendition is often AAC-LC but can be Opus, MP3 or AC-3, and
            // picking the wrong one decodes to noise rather than failing.
            val codec: AVCodec = avcodec.avcodec_find_decoder(codecId)
                ?: run {
                    lastError = "no decoder for codec id $codecId (${FfmpegDemuxer.codecName(codecId)})"
                    return false
                }
            val ctx = avcodec.avcodec_alloc_context3(codec)
                ?: run {
                    lastError = "avcodec_alloc_context3 returned null"
                    return false
                }
            if (avcodec.avcodec_parameters_to_context(ctx, codecParameters) < 0) {
                lastError = "avcodec_parameters_to_context failed"
                avcodec.avcodec_free_context(ctx)
                return false
            }
            if (avcodec.avcodec_open2(ctx, codec, null as org.bytedeco.ffmpeg.avutil.AVDictionary?) < 0) {
                lastError = "avcodec_open2 failed for ${FfmpegDemuxer.codecName(codecId)}"
                avcodec.avcodec_free_context(ctx)
                return false
            }
            codecCtx = ctx
            sourceCodec = FfmpegDemuxer.codecName(codecId)
            sourceSampleRate = ctx.sample_rate()
            sourceChannels = ctx.ch_layout().nb_channels()

            if (!openResampler(ctx)) {
                close()
                return false
            }
            frame = avutil.av_frame_alloc()
            if (frame == null) {
                lastError = "av_frame_alloc returned null"
                close()
                return false
            }
            logger.info(
                "{} audio decoder opened: codec={} src={}Hz/{}ch -> {}Hz/{}ch s16",
                debugLabel, sourceCodec, sourceSampleRate, sourceChannels, TARGET_RATE, TARGET_CHANNELS,
            )
            true
        } catch (t: Throwable) {
            lastError = "open threw ${t::class.simpleName}: ${t.message}"
            logger.error("{} audio decoder open failed: {}", debugLabel, t.message)
            close()
            false
        }
    }

    /** Builds the swresample context that converts the source shape into our fixed output shape. */
    private fun openResampler(ctx: AVCodecContext): Boolean {
        val srcLayout = ctx.ch_layout()
        val srcRate = ctx.sample_rate()
        if (srcRate <= 0 || srcLayout.nb_channels() <= 0) {
            lastError = "invalid source audio shape ${srcRate}Hz/${srcLayout.nb_channels()}ch"
            return false
        }
        // There is no `av_channel_layout_alloc` in FFmpeg 8's public API; an
        // AVChannelLayout is a plain value struct, so it is allocated in Java
        // memory and filled by `av_channel_layout_default`, which is what
        // swresample expects on the output side.
        val outLayout = AVChannelLayout()
        avutil.av_channel_layout_default(outLayout, TARGET_CHANNELS)

        val ctxSwr = swresample.swr_alloc()
        if (ctxSwr == null) {
            lastError = "swr_alloc returned null"
            return false
        }
        val setOptsRc = swresample.swr_alloc_set_opts2(
            ctxSwr, outLayout, avutil.AV_SAMPLE_FMT_S16, TARGET_RATE,
            srcLayout, ctx.sample_fmt(), srcRate,
            0, null,
        )
        if (setOptsRc < 0) {
            lastError = "swr_alloc_set_opts2 failed: rc=$setOptsRc"
            swresample.swr_free(ctxSwr)
            return false
        }
        if (swresample.swr_init(ctxSwr) < 0) {
            lastError = "swr_init failed"
            swresample.swr_free(ctxSwr)
            return false
        }
        swr = ctxSwr
        return true
    }

    /**
     * Decodes one packet.
     *
     * Returns the PCM ready for the output device, or `null` when the packet
     * produced no output (a decoder may buffer several packets before emitting a
     * frame). Bytes are interleaved S16LE stereo at [sampleRate].
     */
    fun decode(packet: AVPacket, packetSize: Int): DecodedPcm? {
        val ctx = codecCtx ?: return null
        val f = frame ?: return null
        val resampler = swr ?: return null
        if (packetSize <= 0) return null
        return try {
            if (avcodec.avcodec_send_packet(ctx, packet) < 0) {
                // A rejected packet is not fatal: a damaged fragment should be
                // skipped, not end the track.
                lastError = "avcodec_send_packet rejected a packet"
                return null
            }
            receiveFrames(ctx, f, resampler)
        } catch (t: Throwable) {
            lastError = "decode threw ${t::class.simpleName}: ${t.message}"
            logger.warn("{} audio decode failed: {}", debugLabel, t.message)
            null
        }
    }

    /**
     * Drains frames the decoder still holds after the demuxer hit EOF.
     *
     * Without this the last few hundred milliseconds of every track are lost,
     * because a decoder legitimately buffers several packets before emitting the
     * final frame.
     */
    fun flush(): DecodedPcm? {
        val ctx = codecCtx ?: return null
        val f = frame ?: return null
        val resampler = swr ?: return null
        return try {
            avcodec.avcodec_send_packet(ctx, null)
            receiveFrames(ctx, f, resampler)
        } catch (t: Throwable) {
            lastError = "flush threw ${t::class.simpleName}: ${t.message}"
            null
        }
    }

    /** Pulls however many frames the decoder has ready and converts them all into one buffer. */
    private fun receiveFrames(ctx: AVCodecContext, f: AVFrame, resampler: SwrContext): DecodedPcm? {
        var collected: ByteArray? = null
        while (true) {
            val rc = avcodec.avcodec_receive_frame(ctx, f)
            if (rc < 0) break
            val chunk = convert(f, resampler) ?: continue
            collected = if (collected == null) chunk else collected + chunk
        }
        val pcm = collected ?: return null
        return DecodedPcm(pcm, sampleRate, channels)
    }

    /** Runs swresample over one decoded frame and packs the result as S16LE stereo. */
    private fun convert(f: AVFrame, resampler: SwrContext): ByteArray? {
        val inSamples = f.nb_samples()
        if (inSamples <= 0) return null
        // Worst case is the source count scaled to the target rate, plus the
        // resampler's own internal delay; swr_get_out_samples is the documented
        // bound and avoids a fixed-size guess.
        val maxOut = swresample.swr_get_out_samples(resampler, inSamples)
        if (maxOut <= 0) return null

        // `swr_convert_frame` is used instead of the raw plane call: an AAC
        // decoder emits **planar** audio (`fltp`), where each channel lives in
        // its own plane and `data(0)` holds only the left channel. Building the
        // interleaved input by hand from plane 0 would silently play half the
        // sound (and half of it at the wrong rate). The frame-level overload
        // reads the plane layout from the frame itself.
        val outFrame = avutil.av_frame_alloc() ?: return null
        try {
            outFrame.format(avutil.AV_SAMPLE_FMT_S16)
            outFrame.sample_rate(TARGET_RATE)
            avutil.av_channel_layout_default(outFrame.ch_layout(), TARGET_CHANNELS)
            outFrame.nb_samples(maxOut)
            if (avutil.av_frame_get_buffer(outFrame, 0) < 0) return null

            val rc = swresample.swr_convert_frame(resampler, outFrame, f)
            if (rc < 0) return null
            val produced = outFrame.nb_samples()
            if (produced <= 0) return null

            // Interleaved S16 stereo: every sample is in plane 0, `produced`
            // frames of `bytesPerFrame` bytes.
            val plane = outFrame.data(0) ?: return null
            val out = ByteArray(produced * bytesPerFrame)
            plane.get(out, 0, out.size)
            return out
        } finally {
            runCatching { avutil.av_frame_free(outFrame) }
        }
    }

    /** Frees every native resource. Repeat calls are safe. */
    override fun close() {
        runCatching {
            frame?.let { avutil.av_frame_free(it) }
            swr?.let { swresample.swr_free(it) }
            codecCtx?.let { avcodec.avcodec_free_context(it) }
        }.onFailure { logger.warn("{} audio decoder close partially failed: {}", debugLabel, it.message) }
        frame = null
        swr = null
        codecCtx = null
    }

    /** One decoded and converted chunk of audio. */
    class DecodedPcm(
        @JvmField val bytes: ByteArray,
        @JvmField val sampleRate: Int,
        @JvmField val channels: Int,
    ) {
        /** Playback duration of this chunk in nanoseconds. */
        fun nanos(): Long = Companion.nanosOf(bytes.size, sampleRate, channels)

        companion object {
            /** Converts a byte count to a duration, for pacing and clock reporting. */
            fun nanosOf(byteCount: Int, sampleRate: Int, channels: Int): Long {
                val frames = byteCount / (channels * 2)
                return if (sampleRate <= 0) 0L else frames * 1_000_000_000L / sampleRate
            }
        }
    }

    companion object {
        /** Output sample rate every consumer is written for. */
        const val TARGET_RATE = 44100

        /** Output channel count. */
        const val TARGET_CHANNELS = 2
    }
}
