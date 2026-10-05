package com.dreamdisplayx.media.player.stream

import com.dreamdisplayx.api.media.stream.model.MediaStream
import com.dreamdisplayx.api.media.stream.model.MediaStreamType
import kotlin.test.Test
import kotlin.test.assertEquals

class MediaStreamSelectorTest {
    @Test
    fun unknownDirectHeightUsesTextureCeilingInsteadOfFourK() {
        val stream = MediaStream(
            url = "https://media.example/video.mp4",
            type = MediaStreamType.VIDEO,
            codec = "h264",
            width = null,
            height = null,
            fps = null,
            bitrate = null,
            audioTrackName = null,
            audioTrackLang = null,
        )

        assertEquals(2560 to 1440, MediaStreamSelector.targetDimensions(stream, Int.MAX_VALUE, 2560 to 1440))
        assertEquals(1920 to 1080, MediaStreamSelector.targetDimensions(stream, Int.MAX_VALUE, 0 to 0))
    }

    @Test
    fun audioSelectionKeyDistinguishesSameLanguageNames() {
        val commentary = MediaStream(
            url = "https://media.example/commentary.m4a",
            type = MediaStreamType.AUDIO,
            codec = "aac",
            width = null,
            height = null,
            fps = null,
            bitrate = 128_000,
            audioTrackName = "Commentary",
            audioTrackLang = "en",
        )
        val dub = commentary.copy(audioTrackName = "Dub")

        assertEquals(false, commentary.audioSelectionKey == dub.audioSelectionKey)
        assertEquals(true, commentary.matchesAudioPreference("en"))
    }

    @Test
    fun knownQualityStillUsesCanonicalDecoderDimensions() {
        assertEquals(1920 to 1080, MediaStreamSelector.targetDimensions(null, 1080, 2560 to 1440))
    }
}
