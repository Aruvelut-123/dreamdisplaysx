package com.dreamdisplayx.api.platform.identity

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.util.WireEnum
import com.dreamdisplayx.api.util.wireEnumValueOf

/**
 * Stable ids for known platform adapters.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
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
        /** Returns the enum value corresponding to the given wire value, or [UNKNOWN] if not found. */
        fun fromWire(raw: String?): PlatformId = wireEnumValueOf(raw, UNKNOWN)
    }
}
