package com.dreamdisplays.api.platform.identity

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.util.WireEnum
import com.dreamdisplays.api.util.wireEnumValueOf

/**
 * Stable ids for known platform adapters.
 *
 * @since 1.8.x
 */
@Unstable
enum class PlatformId(override val wire: String) : WireEnum {
    /** `Fabric` mod loader. */
    FABRIC("fabric"),

    /** `NeoForge` mod loader. */
    NEOFORGE("neoforge"),

    /** `Paper` server. */
    PAPER("paper"),

    /** Unknown platform. */
    UNKNOWN("unknown");

    companion object {
        fun fromWire(raw: String?): PlatformId = wireEnumValueOf(raw, UNKNOWN, entries)
    }
}
