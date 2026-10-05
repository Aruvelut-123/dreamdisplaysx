package com.dreamdisplayx.media.player.subtitle

/** One parsed subtitle cue shown between [startNanos] (inclusive) and [endNanos] (exclusive). */
data class SubtitleCue(
    val startNanos: Long,
    val endNanos: Long,
    val text: String,
) {
    init {
        require(endNanos > startNanos) { "Subtitle cue must end after it starts." }
        require(text.isNotBlank()) { "Subtitle cue text must not be blank." }
    }
}
