package com.dreamdisplayx.media.player.subtitle

import com.dreamdisplayx.api.media.source.model.SubtitleTrack
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class WebVttParserTest {
    @Test
    fun parsesCueIdentifiersTagsEntitiesAndMixedLineEndings() {
        val cues = WebVttParser.parse(
            "WEBVTT\r\n\r\n" +
                "intro\r\n00:00:01.250 --> 00:00:03.500 line:90%\r\n" +
                "<b>Hello</b> &amp; world\r\n\r\n",
        )

        assertEquals(1, cues.size)
        assertEquals(1_250_000_000L, cues[0].startNanos)
        assertEquals(3_500_000_000L, cues[0].endNanos)
        assertEquals("Hello & world", cues[0].text)
    }

    @Test
    fun lookupUsesHalfOpenCueIntervals() {
        val cues = WebVttParser.parse("WEBVTT\n\n00:01.000 --> 00:02.000\nfirst\n")

        assertNull(WebVttParser.cueAt(cues, 999_999_999L))
        assertEquals("first", WebVttParser.cueAt(cues, 1_000_000_000L)?.text)
        assertNull(WebVttParser.cueAt(cues, 2_000_000_000L))
    }

    @Test
    fun subtitleSelectionKeyDistinguishesSameLanguageRenditions() {
        val forced = SubtitleTrack("https://example.test/forced.vtt", "en", "English", isForced = true)
        val regular = SubtitleTrack("https://example.test/regular.vtt", "en", "English")

        assertEquals(false, forced.selectionKey == regular.selectionKey)
        assertEquals(true, forced.matchesPreference("en"))
    }

    @Test
    fun bilingualLookupAlignsOverlappingCues() {
        val primary = listOf(SubtitleCue(0L, 3_000_000_000L, "Hello"))
        val secondary = listOf(SubtitleCue(1_000_000_000L, 2_000_000_000L, "你好"))

        val cue = WebVttParser.bilingualCueAt(primary, secondary, 1_500_000_000L)

        assertEquals(1_000_000_000L, cue?.startNanos)
        assertEquals(2_000_000_000L, cue?.endNanos)
        assertEquals("Hello\n你好", cue?.text)
    }
}
