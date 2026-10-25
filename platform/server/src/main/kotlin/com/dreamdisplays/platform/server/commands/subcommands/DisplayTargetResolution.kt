package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Resolves [token] to a [DisplayData] for `/display info|delete|video`, shared across all three since they all accept
 * the same `this` / id / name target syntax.
 */
@PaperOnly
fun resolvePaperDisplayTarget(sender: CommandSender, player: Player, token: String): DisplayData? {
    if (token.equals("this", ignoreCase = true)) {
        val block = player.getTargetBlock(null, 32)
        return DisplayManager.isContains(block.location) ?: run {
            ServerMessage.NO_DISPLAY.sendTo(sender)
            null
        }
    }
    if (!player.hasPermission(PaperServer.config.permissions.remote)) {
        ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(sender)
        return null
    }
    return DisplayManager.resolveByIdOrPrefix(token) ?: run {
        ServerMessage.NO_DISPLAY.sendTo(sender)
        null
    }
}

/** `Fabric` / `NeoForge` counterpart of [resolvePaperDisplayTarget]. */
fun resolveVanillaDisplayTarget(player: ServerPlayer, token: String): DisplayData? {
    if (token.equals("this", ignoreCase = true)) {
        val targetPos = RegionUtil.getTargetedBlockPos(player) ?: run {
            ServerMessage.NO_DISPLAY.sendTo(player)
            return null
        }
        val worldKey = RegionUtil.getPlayerLevelKey(player)
        return DisplayManager.isContains(worldKey, targetPos) ?: run {
            ServerMessage.NO_DISPLAY.sendTo(player)
            null
        }
    }
    if (!VanillaPermissions.has(player, VanillaServerState.config.permissions.remote, VanillaPermissions.Fallback.OP)) {
        ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
        return null
    }
    return DisplayManager.resolveByIdOrPrefix(token) ?: run {
        ServerMessage.NO_DISPLAY.sendTo(player)
        null
    }
}
