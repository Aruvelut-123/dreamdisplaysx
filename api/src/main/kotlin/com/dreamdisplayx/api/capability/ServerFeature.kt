package com.dreamdisplayx.api.capability

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.util.WireEnum
import com.dreamdisplayx.api.util.wireEnumValueOfOrNull

/**
 * Server capabilities advertised during negotiation; string tokens stay centralized in this enum.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
enum class ServerFeature(override val wire: String) : WireEnum {
    /** Server supports selecting playback modes. */
    MODES("modes"),

    /** Server supports watch-party sessions. */
    WATCH_PARTY("watch_party"),

    /** Server supports broadcast playback. */
    BROADCAST("broadcast"),

    /**
     * Server can resolve region membership (i.e. `WorldGuard` is installed), so the region access
     * level is a real choice. Absent, the level would let nobody in and isn't worth offering.
     */
    REGION_ACCESS("region_access");

    companion object {
        /** Playback-related features every server implementation supports unconditionally. */
        val playbackFeatures: List<ServerFeature> = listOf(MODES, WATCH_PARTY, BROADCAST)

        /** Playback-related feature tokens for string-based wire protocols. */
        val playbackFeatureWires: List<String> = playbackFeatures.toWire()

        /** Decodes a feature token, or returns `null` when unknown. */
        fun fromWire(raw: String): ServerFeature? = wireEnumValueOfOrNull(raw)
    }
}

/** Converts feature enums to their wire tokens. */
@DreamDisplaysXUnstableApi
fun Iterable<ServerFeature>.toWire(): List<String> = map { it.wire }
