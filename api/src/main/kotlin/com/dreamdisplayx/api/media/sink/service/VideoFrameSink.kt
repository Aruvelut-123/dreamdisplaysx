package com.dreamdisplayx.api.media.sink.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.sink.model.DecodedVideoFrame

/**
 * Consumer for decoded video frames. Usually implemented by a texture upload queue.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface VideoFrameSink {
    /** Accepts one decoded [frame]. */
    fun onFrame(frame: DecodedVideoFrame)

    companion object {
        /** Sink that intentionally drops every frame. */
        val DISCARD: VideoFrameSink = VideoFrameSink { }
    }
}
