package com.dreamdisplayx.platform.server.utils

import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.entity.Player
import org.slf4j.LoggerFactory

/** Optional protection integrations. No protection plugin is a hard dependency. */
@PaperOnly
object ClaimProtection {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ClaimProtection")
    private val optionalPlugins = setOf("worldguard", "griefprevention", "lands", "residence", "redprotect", "towny")

    /** Returns true when the player may build at every location in the proposed display. */
    fun canBuild(player: Player, locations: Iterable<Location>): Boolean = locations.all { canBuildAt(player, it) }

    /** Queries WorldGuard and known claim plugins without linking against their APIs. */
    fun canBuildAt(player: Player, location: Location): Boolean {
        if (!worldGuardAllows(player, location)) return false
        if (!griefPreventionAllows(player, location)) return false
        if (!residenceAllows(player, location)) return false
        if (!landsAllows(player, location)) return false
        if (!townyAllows(player, location)) return false
        return true
    }

    private fun worldGuardAllows(player: Player, location: Location): Boolean = runCatching {
        val wgClass = Class.forName("com.sk89q.worldguard.WorldGuard")
        val wg = wgClass.getMethod("getInstance").invoke(null)
        val container = wg.javaClass.getMethod("getPlatform").invoke(wg)
            .let { it.javaClass.getMethod("getRegionContainer").invoke(it) }
        val query = container.javaClass.getMethod("createQuery").invoke(container)
        val adapter = Class.forName("com.sk89q.worldguard.bukkit.BukkitAdapter")
        val wgLocation = adapter.getMethod("adapt", Location::class.java).invoke(null, location)
        val wgPlayer = adapter.getMethod("adapt", Player::class.java).invoke(null, player)
        val flags = Class.forName("com.sk89q.worldguard.protection.flags.Flags")
        val build = flags.getField("BUILD").get(null)
        val testState = query.javaClass.methods.firstOrNull {
            it.name == "testState" && it.parameterTypes.size == 3
        } ?: return true
        val flagArray = java.lang.reflect.Array.newInstance(testState.parameterTypes[2].componentType, 1)
        java.lang.reflect.Array.set(flagArray, 0, build)
        testState.invoke(query, wgLocation, wgPlayer, flagArray) as? Boolean ?: true
    }.onFailure { error ->
        if (error !is ClassNotFoundException) logger.debug("WorldGuard check unavailable: {}", error.message)
    }.getOrDefault(true)

    private fun griefPreventionAllows(player: Player, location: Location): Boolean = runCatching {
        val api = Class.forName("me.ryanhamshire.GriefPrevention.GriefPrevention").getField("instance").get(null)
        val data = api.javaClass.getMethod("dataStore").invoke(api)
        val claim = data.javaClass.getMethod("getClaimAt", Location::class.java, Boolean::class.java, Any::class.java).invoke(data, location, true, null)
        claim == null || claim.javaClass.getMethod("allowBuild", Player::class.java, Any::class.java).invoke(claim, player, null) == null
    }.getOrElse { true }

    private fun residenceAllows(player: Player, location: Location): Boolean = runCatching {
        val manager = Class.forName("com.bekvon.bukkit.residence.Residence").getMethod("getInstance").invoke(null)
        val container = manager.javaClass.getMethod("getResidenceManager").invoke(manager)
        val residence = container.javaClass.getMethod("getByLoc", Location::class.java).invoke(container, location) ?: return true
        residence.javaClass.getMethod("PlayerCanBuild", Player::class.java, Boolean::class.java).invoke(residence, player, false) as Boolean
    }.getOrElse { true }

    private fun landsAllows(player: Player, location: Location): Boolean = runCatching {
        val api = Class.forName("me.angeschossen.lands.api.LandsIntegration").getMethod("of").invoke(null)
        val world = location.world ?: return true
        val area = api.javaClass.getMethod("getArea", world.javaClass, Int::class.java, Int::class.java).invoke(api, world, location.blockX, location.blockZ) ?: return true
        area.javaClass.methods.firstOrNull { it.name == "hasRoleFlag" }?.let {
            (it.invoke(area, player.uniqueId, "BLOCK_PLACE") as? Boolean) ?: true
        } ?: true
    }.getOrElse { true }

    private fun townyAllows(player: Player, location: Location): Boolean = runCatching {
        val tu = Class.forName("com.palmergames.bukkit.towny.TownyAPI").getMethod("getInstance").invoke(null)
        val resident = tu.javaClass.getMethod("getResident", Player::class.java).invoke(tu, player) ?: return true
        val townBlock = tu.javaClass.getMethod("getTownBlock", Location::class.java).invoke(tu, location) ?: return true
        townBlock.javaClass.methods.firstOrNull { it.name == "hasAccess" }?.let {
            (it.invoke(townBlock, resident, "BUILD") as? Boolean) ?: true
        } ?: true
    }.getOrElse { true }

    /** Supports claim plugins exposing a simple canBuild/canBuildAt(Player, Location) method. */
    private fun invokeClaimCheck(plugin: Any, player: Player, location: Location): Boolean? = runCatching {
        val method = plugin.javaClass.methods.firstOrNull {
            it.name in setOf("canBuild", "canBuildAt", "mayBuild") &&
                it.parameterTypes.contentEquals(arrayOf(Player::class.java, Location::class.java))
        } ?: return null
        method.invoke(plugin, player, location) as? Boolean
    }.onFailure { logger.debug("Claim check failed for {}: {}", plugin.javaClass.name, it.message) }.getOrNull()
}
