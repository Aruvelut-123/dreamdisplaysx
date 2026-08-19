package com.dreamdisplayx.api.media.sink.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

/**
 * PCM audio output controlled by the media player. Implementations bridge to the platform mixer.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface AudioSink : AutoCloseable {
    /** Queues decoded PCM bytes with their media timestamp in microseconds. */
    fun onAudioData(pcmData: ByteArray, timestampUs: Long)

    /** Sets the output volume multiplier. */
    fun setVolume(volume: Float)

    /** Drops buffered audio so playback can resume after a seek or stream reset. */
    fun flush()

    /** True when the platform audio output is ready to accept data. */
    val isAvailable: Boolean
}
