package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.dreamdisplays.platform.server.utils.net.PacketUtil
import com.dreamdisplays.platform.server.utils.net.VanillaPacketUtil
import com.dreamdisplays.platform.server.ModLoaderOnly
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display off` command. Disables display rendering for the sender or for another
 * player when the caller holds the `toggleOthers` permission.
 */
@PaperOnly
class OffCommand : SubCommand {
    override val name = "off"
    override val permission: String? = null
    override val playerOnly = false

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val target = resolveTarget(sender, args) ?: return
        val selfTarget = sender is Player && sender.uniqueId == target.uniqueId

        if (!selfTarget && !sender.hasPermission(PaperServer.config.permissions.toggleOthers)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(sender)
            return
        }

        if (!PlayerManager.isDisplaysEnabled(target)) {
            PacketUtil.sendDisplayEnabled(target, false)
            ServerMessage.DISPLAY_ALREADY_DISABLED.sendTo(target)
            if (!selfTarget) {
                MessageUtil.sendColoredMessage(
                    sender,
                    MessageUtil.formatPrintf(sender, ServerMessage.DISPLAY_ALREADY_DISABLED_TARGET, target.name)
                )
            }
            return
        }

        PlayerManager.setDisplaysEnabled(target, false)
        PacketUtil.sendDisplayEnabled(target, false)
        ServerMessage.DISPLAY_DISABLED.sendTo(target)
        if (!selfTarget) {
            MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatPrintf(sender, ServerMessage.DISPLAY_DISABLED_TARGET, target.name)
            )
        }
    }

    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        if (args.size != 2) return []
        if (!sender.hasPermission(PaperServer.config.permissions.toggleOthers)) return []
        return Bukkit.getOnlinePlayers().map { it.name }.sorted()
    }

    private fun resolveTarget(sender: CommandSender, args: Array<String?>): Player? {
        if (args.size == 1) {
            return sender as? Player ?: run {
                ServerMessage.DISPLAY_WRONG_COMMAND.sendTo(sender)
                null
            }
        }
        if (args.size != 2) {
            ServerMessage.DISPLAY_WRONG_COMMAND.sendTo(sender)
            return null
        }

        val targetName = args[1]?.trim().orEmpty()
        if (targetName.isBlank()) {
            ServerMessage.DISPLAY_WRONG_COMMAND.sendTo(sender)
            return null
        }

        val target = Bukkit.getPlayerExact(targetName)
        if (target != null) return target

        MessageUtil.sendColoredMessage(sender, MessageUtil.formatPrintf(sender, ServerMessage.DISPLAY_TARGET_NOT_FOUND, targetName))
        return null
    }
}

/** Shared `Fabric` / `NeoForge` implementation of the `/display off` command. */
@ModLoaderOnly
object VanillaOffCommand {
    /** Disables displays for the executing player or the named [targetName], checking op-level permission for the latter. */
    fun execute(ctx: CommandContext<CommandSourceStack>, targetName: String? = null): Int {
        val self = ctx.source.entity as? ServerPlayer
        val config = VanillaServerState.config

        val target: ServerPlayer = if (targetName == null) {
            self ?: run {
                ctx.source.sendFailure(Component.literal("This command must be used in-game or with a player argument."))
                return 0
            }
        } else {
            ctx.source.server.playerList.getPlayerByName(targetName) ?: run {
                val msg = config.getMessageForPlayer(self, "displayTargetNotFound") as? String ?: "Player not found: %s"
                if (self != null) MessageUtil.sendColoredMessage(self, String.format(msg, targetName))
                else ctx.source.sendFailure(Component.literal(String.format(msg, targetName)))
                return 0
            }
        }

        val selfTarget = self?.uuid == target.uuid

        if (!selfTarget &&
            (self == null || !VanillaPermissions.has(
                self,
                config.permissions.toggleOthers,
                VanillaPermissions.Fallback.OP
            ))
        ) {
            if (self != null) ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(self)
            else ctx.source.sendFailure(Component.literal("Missing permission."))
            return 0
        }

        if (!PlayerManager.isDisplaysEnabled(target)) {
            VanillaPacketUtil.sendDisplayEnabled(target, false)
            ServerMessage.DISPLAY_ALREADY_DISABLED.sendTo(target)
            if (!selfTarget) {
                val msg = config.getMessageForPlayer(self, "display.already-disabled.target") as? String
                if (msg != null) MessageUtil.sendColoredMessage(self, String.format(msg, target.name.string))
            }
            return 1
        }

        PlayerManager.setDisplaysEnabled(target, false)
        VanillaPacketUtil.sendDisplayEnabled(target, false)
        ServerMessage.DISPLAY_DISABLED.sendTo(target)
        if (!selfTarget) {
            val msg = config.getMessageForPlayer(self, "display.disabled.target") as? String
            if (msg != null) MessageUtil.sendColoredMessage(self, String.format(msg, target.name.string))
        }
        return 1
    }
}
