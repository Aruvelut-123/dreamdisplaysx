package com.dreamdisplays.api.media.sink

import com.dreamdisplays.api.Unstable

/**
 * Consumer for decoded video frames. Usually implemented by a texture upload queue.
 *
 * @since 1.8.x
 */
@Unstable
fun interface VideoFrameSink {
    /** Accepts one decoded [frame]. */
    fun onFrame(frame: DecodedVideoFrame)

    companion object {
        /** Sink that intentionally drops every frame. */
        val DISCARD: VideoFrameSink = VideoFrameSink { }
    }
}
