package com.dreamdisplayx.api.media.stream.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.StreamPreferences
import com.dreamdisplayx.api.media.stream.model.StreamSet

@DreamDisplaysXUnstableApi
interface StreamSelector {
    fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet
}
