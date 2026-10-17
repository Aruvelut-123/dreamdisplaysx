package com.dreamdisplays.api.media.stream

import com.dreamdisplays.api.Unstable

@Unstable
interface StreamSelector {
    fun select(streams: List<MediaStream>, preferences: StreamPreferences): StreamSet
}
