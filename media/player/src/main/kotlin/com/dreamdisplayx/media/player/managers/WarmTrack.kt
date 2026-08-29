package com.dreamdisplayx.media.player.managers

/** An audio track eligible for pre-warming, and how its decoder has to reach a position. */
internal data class WarmTrack(val url: String, val seekByDecoding: Boolean)