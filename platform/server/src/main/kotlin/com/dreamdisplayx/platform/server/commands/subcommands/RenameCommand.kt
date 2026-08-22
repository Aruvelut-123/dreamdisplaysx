package com.dreamdisplayx.platform.server.commands.subcommands

import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.VanillaServerState
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplayx.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.meta.ServerCoroutines
import com.dreamdisplayx.platform.server.utils.MessageUtil
import com.dreamdisplayx.platform.server.utils.VanillaPermissions
import io.github.arnodoelinger.platformweaver.PaperOnly
import com.mojang.brigadier.context.CommandContext
import kotlinx.coroutines.launch
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player

/**
 * Handles the `/display rename <id> <new_name>` command. Addresses a display by its unique id (or a
 * unique id prefix), then assigns it a new human-readable display name. The owner may rename their
 * own displays; players holding the `nameOthers` (OP) permission may rename anyone's.
 */
object RenameCommand {
    /** Renames the display addressed by [idToken] to [newName]; returns 1 on success. */
    fun execute(ctx: CommandContext<CommandSourceStack>, idToken: String, newName: String): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val data = resolveByIdPrefix(idToken) as? VanillaDisplayData ?: run {
            MessageUtil.sendMessage(player, "noDisplay")
            return 0
        }

        // The owner may rename their own display; everyone else needs the nameOthers (OP) permission.
        val isOwner = data.ownerId == player.uuid
        if (!isOwner &&
            !VanillaPermissions.has(
                player,
                VanillaServerState.config.permissions.nameOthers,
                VanillaPermissions.Fallback.OP,
            )
        ) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return 0
        }

        val normalized = normalizeDisplayName(newName)
        if (normalized == null) {
            MessageUtil.sendMessage(player, "invalidName")
            return 0
        }
        if (DisplayManager.isNameTaken(normalized, data.id)) {
            MessageUtil.sendMessage(player, "nameTaken")
            return 0
        }

        data.name = normalized
        ServerCoroutines.io.launch { VanillaServerState.storage?.saveDisplay(data) }

        val receivers = DisplayManager.getReceivers(data, ctx.source.server)
        if (receivers.isNotEmpty()) DisplayManager.sendUpdate(data, receivers)

        MessageUtil.sendMessage(player, "renamedDisplay", data.id.toString().take(8), normalized)
        return 1
    }

    /** Resolves a display from an id or a unique id prefix (short names are no longer used to address). */
    private fun resolveByIdPrefix(idToken: String): DisplayData? {
        runCatching { java.util.UUID.fromString(idToken) }.getOrNull()?.let { exact ->
            DisplayManager.getDisplayData(exact)?.let { return it }
        }
        if (idToken.length < 4) return null
        val matches = DisplayManager.getDisplays().filter { it.id.toString().startsWith(idToken, ignoreCase = true) }
        return matches.singleOrNull()
    }
}

/**
 * Paper plugin version of the `/display rename <id> <new_name>` command. Addresses a display by its
 * unique id (or a unique id prefix), then assigns it a new human-readable display name. The owner may
 * rename their own displays; players holding the `nameOthers` permission may rename anyone's.
 */
@PaperOnly
class PaperRenameCommand : SubCommand {
    override val name = "rename"
    override val permission = PaperServer.config.permissions.name
    override val playerOnly = true

    /** Renames the display addressed by [args[0]] (id / `this`) to [args[1]]. */
    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        val token = args.getOrNull(0) ?: return MessageUtil.sendMessage(player, "noDisplay")
        val newName = args.getOrNull(1) ?: return MessageUtil.sendMessage(player, "invalidName")

        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return

        // The owner may rename their own display; everyone else needs the nameOthers permission.
        val isOwner = data.ownerId == player.uniqueId
        if (!isOwner && !player.hasPermission(PaperServer.config.permissions.nameOthers)) {
            MessageUtil.sendMessage(player, "displayCommandMissingPermission")
            return
        }

        val normalized = normalizeDisplayName(newName)
        if (normalized == null) {
            MessageUtil.sendMessage(player, "invalidName")
            return
        }
        if (DisplayManager.isNameTaken(normalized, data.id)) {
            MessageUtil.sendMessage(player, "nameTaken")
            return
        }

        data.name = normalized
        PaperServer.getInstance().storage.saveDisplay(data)

        val receivers = DisplayManager.getReceivers(data)
        if (receivers.isNotEmpty()) DisplayManager.sendUpdate(data, receivers)

        MessageUtil.sendMessage(player, "renamedDisplay", data.id.toString().take(8), normalized)
    }
}
