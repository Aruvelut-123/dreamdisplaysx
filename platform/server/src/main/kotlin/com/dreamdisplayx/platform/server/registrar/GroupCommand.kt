package com.dreamdisplayx.platform.server.registrar

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.commands.subcommands.VideoCommand
import com.dreamdisplayx.platform.server.managers.DisplayGroupManager
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.playback.TimelineManager
import com.dreamdisplayx.platform.server.utils.MessageUtil
import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import org.bukkit.entity.Player
import java.util.UUID

/** Experimental Paper `/display group` commands for V3 same-content playback. */
@DreamDisplaysXUnstableApi
object GroupCommand {
    fun node(): LiteralArgumentBuilder<CommandSourceStack> = Commands.literal("group")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }
        .then(Commands.literal("create").then(Commands.argument("name", StringArgumentType.word()).executes { c ->
            val name = StringArgumentType.getString(c, "name")
            c.source.sender.sendMessage(if (DisplayGroupManager.create(name)) "Created display group $name." else "Group already exists: $name.")
            Command.SINGLE_SUCCESS
        }))
        .then(Commands.literal("delete").then(Commands.argument("name", StringArgumentType.word()).executes { c ->
            val name = StringArgumentType.getString(c, "name")
            c.source.sender.sendMessage(if (DisplayGroupManager.delete(name)) "Deleted display group $name." else "Unknown display group: $name.")
            Command.SINGLE_SUCCESS
        }))
        .then(Commands.literal("add").then(Commands.argument("name", StringArgumentType.word()).then(Commands.argument("display", StringArgumentType.word()).executes { c ->
            val player = c.source.sender as Player
            val display = DisplayManager.resolveByIdOrPrefix(StringArgumentType.getString(c, "display")) as? com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
            val ok = display != null && DisplayGroupManager.add(StringArgumentType.getString(c, "name"), display)
            player.sendMessage(if (ok) "Display added to group." else "Unable to add display to group.")
            Command.SINGLE_SUCCESS
        })))
        .then(Commands.literal("remove").then(Commands.argument("name", StringArgumentType.word()).then(Commands.argument("display", StringArgumentType.word()).executes { c ->
            val ok = DisplayGroupManager.remove(StringArgumentType.getString(c, "name"), UUID.fromString(StringArgumentType.getString(c, "display")))
            c.source.sender.sendMessage(if (ok) "Display removed from group." else "Display is not in that group.")
            Command.SINGLE_SUCCESS
        })))
        .then(Commands.literal("play").then(Commands.argument("name", StringArgumentType.word()).then(Commands.argument("url", StringArgumentType.greedyString()).executes { c ->
            val raw = StringArgumentType.getString(c, "url").trim()
            val parts = raw.split(" ")
            DisplayGroupManager.setVideo(StringArgumentType.getString(c, "name"), parts.first(), parts.getOrNull(1) ?: "")
            c.source.sender.sendMessage("Group content updated and synchronized.")
            Command.SINGLE_SUCCESS
        })))
        .then(Commands.literal("control").then(Commands.argument("name", StringArgumentType.word()).then(Commands.argument("action", StringArgumentType.word()).executes { c ->
            val action = when (StringArgumentType.getString(c, "action").lowercase()) {
                "play" -> PlaybackAction.PLAY
                "pause" -> PlaybackAction.PAUSE
                "restart" -> PlaybackAction.RESTART
                else -> null
            } ?: return@executes 0
            DisplayGroupManager.members(StringArgumentType.getString(c, "name")).forEach { TimelineManager.applyScheduled(it, action) }
            c.source.sender.sendMessage("Group playback command applied.")
            Command.SINGLE_SUCCESS
        })))
}
