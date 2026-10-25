package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component.text
import net.kyori.adventure.text.event.ClickEvent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import kotlin.math.max

/**
 * Handles the `/display list` command. Renders a paged, filterable listing of all registered
 * displays with teleport and URL buttons for in-game players.
 */
@PaperOnly
class ListCommand : SubCommand {
    private companion object {
        const val PAGE_SIZE = 10
        val LEGACY_SERIALIZER: LegacyComponentSerializer = LegacyComponentSerializer.legacyAmpersand()
    }

    private data class ListQuery(
        val displays: List<PaperDisplayData>,
        val page: Int,
    )

    override val name = "list"
    override val permission = PaperServer.config.permissions.list

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val displays = sortedDisplays()
        if (displays.isEmpty()) {
            ServerMessage.NO_DISPLAYS_FOUND.sendTo(sender)
            return
        }

        val ownerNameCache = mutableMapOf<UUID, String?>()
        val query = parseQuery(sender, args, displays, ownerNameCache) ?: return
        if (query.displays.isEmpty()) {
            ServerMessage.NO_DISPLAYS_FOUND.sendTo(sender)
            return
        }
        val totalPages = pageCount(query.displays.size)
        val page = query.page.coerceIn(1, totalPages)
        val startIndex = (page - 1) * PAGE_SIZE
        val endExclusive = minOf(startIndex + PAGE_SIZE, query.displays.size)
        val pageDisplays = query.displays.subList(startIndex, endExclusive)

        ServerMessage.DISPLAY_LIST_HEADER.sendTo(sender)
        MessageUtil.sendColoredMessage(
            sender,
            MessageUtil.formatIndexed(
                sender,
                "displayListPageLine",
                page.toString(),
                totalPages.toString(),
                query.displays.size.toString()
            )
        )

        pageDisplays.forEachIndexed { localIndex, d ->
            val index = startIndex + localIndex + 1
            val owner =
                getOwnerName(d.ownerId, ownerNameCache) ?: MessageUtil.messageFor(sender, ServerMessage.DISPLAY_LIST_UNKNOWN_OWNER)
            val worldName = d.pos1.world?.name ?: MessageUtil.messageFor(sender, ServerMessage.DISPLAY_LIST_UNKNOWN_WORLD)
            val idShort = d.shortLabel
            val url = d.url.ifBlank { MessageUtil.messageFor(sender, ServerMessage.DISPLAY_LIST_UNAVAILABLE_URL) }
            val baseLine = MessageUtil.formatIndexed(
                sender,
                "displayListEntry",
                index.toString(),
                owner,
                d.pos1.blockX.toString(),
                d.pos1.blockY.toString(),
                d.pos1.blockZ.toString(),
                url
            )
            val details = MessageUtil.formatIndexed(
                sender,
                "displayListDetails",
                worldName,
                d.width.toString(),
                d.height.toString(),
                d.isSync.toString(),
                idShort
            )
            val fullLine = baseLine + details

            if (sender !is Player) {
                MessageUtil.sendColoredMessage(sender, fullLine)
                return@forEachIndexed
            }

            val component = LEGACY_SERIALIZER.deserialize(fullLine)
                .append(
                    text(MessageUtil.messageFor(sender, ServerMessage.DISPLAY_LIST_TP_BUTTON))
                        .color(NamedTextColor.GREEN)
                        .clickEvent(
                            ClickEvent.runCommand(
                                "/tp ${d.pos1.blockX} ${d.pos1.blockY} ${d.pos1.blockZ}"
                            )
                        )
                )

            val finalComponent = if (d.url.isBlank()) {
                component
            } else {
                component.append(
                    text(MessageUtil.messageFor(sender, ServerMessage.DISPLAY_LIST_URL_BUTTON)).color(NamedTextColor.RED)
                        .clickEvent(ClickEvent.openUrl(d.url))
                )
            }

            MessageUtil.sendComponent(sender, finalComponent)
        }
    }

    override fun complete(sender: CommandSender, args: Array<String?>): List<String> {
        val displays = sortedDisplays()
        val ownerNameCache = mutableMapOf<UUID, String?>()

        return when (args.size) {
            2 -> mutableSetOf<String>().apply {
                addAll(ListFilter.tokens)
                addAll(pageSuggestions(displays.size))
            }.sorted()

            3 -> when (ListFilter.fromToken(args[1])) {
                ListFilter.MINE -> {
                    if (sender !is Player) {
                        []
                    } else {
                        pageSuggestions(displays.count { it.ownerId == sender.uniqueId })
                    }
                }

                ListFilter.SYNC -> pageSuggestions(displays.count { it.isSync })
                ListFilter.WORLD -> displays
                    .mapNotNull { it.pos1.world?.name }
                    .distinct()
                    .sorted()

                ListFilter.OWNER -> displays
                    .mapNotNull { getOwnerName(it.ownerId, ownerNameCache) }
                    .distinct()
                    .sorted()

                else -> []
            }

            4 -> when (ListFilter.fromToken(args[1])) {
                ListFilter.WORLD -> pageSuggestions(filterByWorld(displays, args[2].orEmpty()).size)
                ListFilter.OWNER -> pageSuggestions(filterByOwner(displays, args[2].orEmpty(), ownerNameCache).size)
                else -> []
            }

            else -> []
        }
    }

    private fun parseQuery(
        sender: CommandSender,
        args: Array<String?>,
        displays: List<PaperDisplayData>,
        ownerNameCache: MutableMap<UUID, String?>,
    ): ListQuery? {
        if (args.size == 1) return ListQuery(displays, 1)

        val selector = args.getOrNull(1) ?: return wrong(sender)
        val pageOnly = selector.toIntOrNull()
        if (pageOnly != null) {
            if (args.size != 2) return wrong(sender)
            return ListQuery(displays, pageOnly)
        }

        return when (ListFilter.fromToken(selector)) {
            ListFilter.MINE -> {
                if (sender !is Player) {
                    ServerMessage.COMMAND_PLAYERS_ONLY.sendTo(sender)
                    return null
                }
                if (args.size !in 2..3) return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(2)) ?: return null
                ListQuery(displays.filter { it.ownerId == sender.uniqueId }, page)
            }

            ListFilter.SYNC -> {
                if (args.size !in 2..3) return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(2)) ?: return null
                ListQuery(displays.filter { it.isSync }, page)
            }

            ListFilter.WORLD -> {
                if (args.size !in 3..4) return wrong(sender)
                val worldName = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(3)) ?: return null
                ListQuery(filterByWorld(displays, worldName), page)
            }

            ListFilter.OWNER -> {
                if (args.size !in 3..4) return wrong(sender)
                val ownerName = args.getOrNull(2)?.takeIf { it.isNotBlank() } ?: return wrong(sender)
                val page = parseOptionalPage(sender, args.getOrNull(3)) ?: return null
                ListQuery(filterByOwner(displays, ownerName, ownerNameCache), page)
            }

            else -> wrong(sender)
        }
    }

    private fun parseOptionalPage(sender: CommandSender, rawPage: String?): Int? {
        if (rawPage == null) return 1
        val page = rawPage.toIntOrNull()
        if (page == null) {
            ServerMessage.DISPLAY_WRONG_COMMAND.sendTo(sender)
            return null
        }
        return page
    }

    private fun filterByWorld(displays: List<PaperDisplayData>, worldName: String): List<PaperDisplayData> {
        return displays.filter { it.pos1.world?.name?.equals(worldName, ignoreCase = true) == true }
    }

    private fun filterByOwner(
        displays: List<PaperDisplayData>,
        ownerName: String,
        ownerNameCache: MutableMap<UUID, String?>,
    ): List<PaperDisplayData> {
        return displays.filter { display ->
            getOwnerName(display.ownerId, ownerNameCache)?.equals(ownerName, ignoreCase = true) == true ||
                    display.ownerId.toString().equals(ownerName, ignoreCase = true)
        }
    }

    private fun getOwnerName(ownerId: UUID, ownerNameCache: MutableMap<UUID, String?>): String? {
        return ownerNameCache.getOrPut(ownerId) { Bukkit.getOfflinePlayer(ownerId).name }
    }

    private fun sortedDisplays(): List<PaperDisplayData> {
        return DisplayManager.getDisplays()
            .filterIsInstance<PaperDisplayData>()
            .sortedWith(
                compareBy(
                    { it.pos1.world?.name ?: "" },
                    { it.pos1.blockX },
                    { it.pos1.blockY },
                    { it.pos1.blockZ },
                    { it.id.toString() }
                )
            )
    }

    private fun pageCount(size: Int): Int = max(1, (size + PAGE_SIZE - 1) / PAGE_SIZE)

    private fun pageSuggestions(size: Int): List<String> = (1..pageCount(size)).map { it.toString() }

    private fun wrong(sender: CommandSender): ListQuery? {
        ServerMessage.DISPLAY_WRONG_COMMAND.sendTo(sender)
        return null
    }
}

/** Shared `Fabric` / `NeoForge` implementation of the `/display list` command. */
@ModLoaderOnly
object VanillaListCommand {
    private const val PAGE_SIZE = 10

    /** Renders a paged, filterable listing of all displays; supports `mine`, `world`, `owner`, and `sync` filters. */
    fun execute(
        ctx: CommandContext<CommandSourceStack>,
        filter: String? = null,
        value: String? = null,
        pageStr: String? = null
    ): Int {
        val player = ctx.source.entity as? ServerPlayer
        val server = ctx.source.server

        val displays = sortedDisplays()
        if (displays.isEmpty()) {
            sendMsg(ctx, player, "noDisplaysFound")
            return 1
        }

        val ownerNameCache = mutableMapOf<UUID, String?>()

        fun getOwnerName(ownerId: UUID): String? =
            ownerNameCache.getOrPut(ownerId) {
                server.playerList.players.find { it.uuid == ownerId }?.name?.string
            }

        val filtered: List<VanillaDisplayData> = when (ListFilter.fromToken(filter)) {
            null -> {
                val pageNum = filter?.toIntOrNull()
                if (pageNum != null) {
                    sendPage(ctx, player, displays, ownerNameCache, pageNum)
                    return 1
                }
                displays
            }

            ListFilter.MINE -> if (player != null) displays.filter { it.ownerId == player.uuid } else displays
            ListFilter.WORLD -> value?.let { wn -> displays.filter { it.worldKey.endsWith(wn, ignoreCase = true) } }
                ?: displays

            ListFilter.OWNER -> value?.let { on ->
                displays.filter {
                    getOwnerName(it.ownerId)?.equals(
                        on,
                        ignoreCase = true
                    ) == true || it.ownerId.toString().equals(on, ignoreCase = true)
                }
            } ?: displays

            ListFilter.SYNC -> displays.filter { it.isSync }
        }

        if (filtered.isEmpty()) {
            sendMsg(ctx, player, "noDisplaysFound")
            return 1
        }

        val page = (pageStr?.toIntOrNull() ?: 1)
        sendPage(ctx, player, filtered, ownerNameCache, page)
        return 1
    }

    private fun sendPage(
        ctx: CommandContext<CommandSourceStack>,
        player: ServerPlayer?,
        displays: List<VanillaDisplayData>,
        ownerNameCache: MutableMap<UUID, String?>,
        page: Int,
    ) {
        val server = ctx.source.server
        fun getOwnerName(ownerId: UUID): String? =
            ownerNameCache.getOrPut(ownerId) {
                server.playerList.players.find { it.uuid == ownerId }?.name?.string
            }

        val totalPages = max(1, (displays.size + PAGE_SIZE - 1) / PAGE_SIZE)
        val p = page.coerceIn(1, totalPages)
        val startIndex = (p - 1) * PAGE_SIZE
        val endExclusive = minOf(startIndex + PAGE_SIZE, displays.size)
        val pageDisplays = displays.subList(startIndex, endExclusive)

        sendMsg(ctx, player, "displayListHeader")
        sendColoredMsg(
            ctx,
            player,
            MessageUtil.formatIndexed(
                player,
                "displayListPageLine",
                p.toString(),
                totalPages.toString(),
                displays.size.toString()
            )
        )

        pageDisplays.forEachIndexed { localIndex, d ->
            val index = startIndex + localIndex + 1
            val owner = getOwnerName(d.ownerId) ?: MessageUtil.messageFor(player, ServerMessage.DISPLAY_LIST_UNKNOWN_OWNER)
            val worldName = d.worldKey.substringAfterLast(':')
            val idShort = d.shortLabel
            val url = d.url.ifBlank { MessageUtil.messageFor(player, ServerMessage.DISPLAY_LIST_UNAVAILABLE_URL) }
            val baseLine = MessageUtil.formatIndexed(
                player,
                "displayListEntry",
                index.toString(),
                owner,
                d.minX.toString(),
                d.minY.toString(),
                d.minZ.toString(),
                url
            )
            val details = MessageUtil.formatIndexed(
                player,
                "displayListDetails",
                worldName,
                d.width.toString(),
                d.height.toString(),
                d.isSync.toString(),
                idShort
            )
            sendColoredMsg(ctx, player, baseLine + details)
        }
    }

    private fun sortedDisplays(): List<VanillaDisplayData> =
        DisplayManager.getDisplays().filterIsInstance<VanillaDisplayData>().sortedWith(
            compareBy(
                { it.worldKey },
                { it.minX },
                { it.minY },
                { it.minZ },
                { it.id.toString() }
            )
        )

    private fun sendMsg(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer?, key: String) {
        val msg = MessageUtil.messageFor(player, key)
        if (player != null) {
            MessageUtil.sendColoredMessage(player, msg)
        } else {
            ctx.source.sendSystemMessage(Component.literal(msg))
        }
    }

    private fun sendColoredMsg(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer?, msg: String) {
        if (player != null) {
            MessageUtil.sendColoredMessage(player, msg)
        } else {
            ctx.source.sendSystemMessage(Component.literal(msg))
        }
    }
}
