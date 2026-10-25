package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.managers.PlayerManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender

/**
 * Handles the `/display stats` command. Prints a per-mod-version breakdown of currently
 * connected players that have reported their client version.
 */
@PaperOnly
class StatsCommand : SubCommand {
    override val name = "stats"
    override val permission = PaperServer.config.permissions.stats

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val versions = PlayerManager.getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        ServerMessage.DISPLAY_STATS_HEADER.sendTo(sender)

        for ((version, count) in counts) {
            MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatPrintf(sender, ServerMessage.DISPLAY_STATS_ENTRY, version, count)
            )
        }

        val total = counts.values.sum()
        MessageUtil.sendColoredMessage(sender, MessageUtil.formatPrintf(sender, ServerMessage.DISPLAY_STATS_TOTAL, total))
    }
}

/**
 * Shared `Fabric` / `NeoForge` implementation of the `/display stats` command.
 */
@ModLoaderOnly
object VanillaStatsCommand {
    /** Prints a per-mod-version breakdown of currently connected players that have reported their client version. */
    fun execute(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer

        val versions = PlayerManager.getVersions()
        val counts = versions.values
            .filterNotNull()
            .groupingBy { it }
            .eachCount()
            .toSortedMap()

        if (player != null) {
            ServerMessage.DISPLAY_STATS_HEADER.sendTo(player)
            for ((version, count) in counts) {
                MessageUtil.sendColoredMessage(
                    player,
                    MessageUtil.formatPrintf(player, ServerMessage.DISPLAY_STATS_ENTRY, version, count)
                )
            }
            val total = counts.values.sum()
            MessageUtil.sendColoredMessage(player, MessageUtil.formatPrintf(player, ServerMessage.DISPLAY_STATS_TOTAL, total))
        } else {
            ctx.source.sendSystemMessage(Component.literal(MessageUtil.messageFor(player, ServerMessage.DISPLAY_STATS_HEADER)))
            for ((version, count) in counts) {
                ctx.source.sendSystemMessage(
                    Component.literal(
                        MessageUtil.formatPrintf(
                            player,
                            ServerMessage.DISPLAY_STATS_ENTRY,
                            version,
                            count
                        )
                    )
                )
            }
            val total = counts.values.sum()
            ctx.source.sendSystemMessage(
                Component.literal(
                    MessageUtil.formatPrintf(
                        player,
                        ServerMessage.DISPLAY_STATS_TOTAL,
                        total
                    )
                )
            )
        }

        return 1
    }
}
