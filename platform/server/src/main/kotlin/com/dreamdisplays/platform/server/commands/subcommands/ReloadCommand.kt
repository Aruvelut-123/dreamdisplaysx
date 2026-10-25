package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender

/**
 * Handles the `/display reload` command. Re-reads `config.yml` from disk at runtime
 * and confirms success or failure to the sender.
 */
@PaperOnly
class ReloadCommand : SubCommand {
    override val name = "reload"
    override val permission = PaperServer.config.permissions.reload

    override fun execute(sender: CommandSender, args: Array<String?>) =
        runCatching { PaperServer.config.reload() }.fold(
            onSuccess = {
                ServerMessage.CONFIG_RELOADED.sendTo(sender)
                ServerMessage.CONFIG_RELOAD_SUMMARY.sendTo(sender)
            },
            onFailure = {
                ServerMessage.CONFIG_RELOAD_FAILED.sendTo(sender)
            }
        )
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display reload` command.
 */
@ModLoaderOnly
object VanillaReloadCommand {
    /** Reloads the server config from disk; replies with success or failure to the command source. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        runCatching { VanillaServerState.config.reload() }.fold(
            onSuccess = {
                player?.let {
                    ServerMessage.CONFIG_RELOADED.sendTo(it)
                    ServerMessage.CONFIG_RELOAD_SUMMARY.sendTo(it)
                } ?: ctx.source.sendSystemMessage(Component.literal("Dream Displays config reloaded."))
            },
            onFailure = { e ->
                player?.let {
                    ServerMessage.CONFIG_RELOAD_FAILED.sendTo(it)
                } ?: ctx.source.sendFailure(Component.literal("Failed to reload config: ${e.message}"))
            }
        )
        return 1
    }
}
