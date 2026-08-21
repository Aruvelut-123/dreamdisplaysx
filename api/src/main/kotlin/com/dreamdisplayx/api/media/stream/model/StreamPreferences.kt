package com.dreamdisplayx.api.media.stream.model

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi

@DreamDisplaysXUnstableApi
data class StreamPreferences(
    val maxHeight: Int?,
    val preferFps60: Boolean,
    val preferredAudioTrack: String?,
    val preferredAudioLanguage: String?,
    val allowHdr: Boolean,
) {
    companion object {
        val DEFAULT = StreamPreferences(
            maxHeight = null,
            preferFps60 = true,
            preferredAudioTrack = null,
            preferredAudioLanguage = null,
            allowHdr = false,
        )
    }
}
