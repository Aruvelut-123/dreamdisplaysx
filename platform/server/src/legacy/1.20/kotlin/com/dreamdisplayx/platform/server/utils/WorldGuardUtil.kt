package com.dreamdisplayx.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Location
import org.bukkit.entity.Player
import org.jspecify.annotations.NullMarked

/**
 * Legacy (1.20.1) `WorldGuard` façade.
 *
 * `worldguard-bukkit` is not on the 1.20.1 classpath (that build is Fabric-only and the classic-loom
 * configuration drops the Paper-only dependencies), so the reflective bridge cannot be linked. The
 * public surface is kept identical so the shared callers — `CreateCommand`, `DisplayManager`,
 * `DisplayActions`, `V2Paper` — keep compiling; every query answers "nothing is protected", which is
 * exactly what the modern implementation answers when `WorldGuard` is not installed.
 */
@PaperOnly
@NullMarked
object WorldGuardRegions {
    /** Always false on 1.20.1: `WorldGuard` is never present. */
    fun isAvailable(): Boolean = false

    /** Always false on 1.20.1: no regions can be resolved. */
    fun isProtectedTerritory(location: Location): Boolean = false

    /** Always false on 1.20.1: no regions can be resolved. */
    fun isRegionOwner(player: Player, location: Location): Boolean = false

    /** Always false on 1.20.1: no regions can be resolved. */
    fun isRegionMember(player: Player, location: Location): Boolean = false
}
