package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.api.media.model.VideoQuality
import com.dreamdisplays.shared.playback.FullscreenMode
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.PaperSurfaces
import com.dreamdisplays.platform.server.baseMaterial
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.managers.DisplayManager
import com.dreamdisplays.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplays.platform.server.playback.FullscreenRadiusTarget
import com.dreamdisplays.platform.server.playback.FullscreenSessionInfo
import com.dreamdisplays.platform.server.proxy.ProxyBridge
import com.dreamdisplays.platform.server.proxy.ProxyNetwork
import com.dreamdisplays.platform.server.proxy.VanillaProxyBridge
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.RegionUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*

/** Outcome of a `/display fullscreen start` attempt, for the platform trees to turn into a reply message. */
sealed class FullscreenStartResult {
    data class Started(val sessionId: String, val reach: Int) : FullscreenStartResult()
    data object NoTargets : FullscreenStartResult()
    data object AlreadyRunning : FullscreenStartResult()
    data object ForcedDisallowed : FullscreenStartResult()
}

/** The `/display fullscreen start` flags as typed by the sender, shared by the Paper and vanilla command trees. */
data class FullscreenStartOptions(
    val serverScope: String?,
    val players: String?,
    val radiusBlocks: Double?,
    val radiusX: Double?,
    val radiusY: Double?,
    val radiusZ: Double?,
    val mode: String?,
    val forced: Boolean,
    val transientSession: Boolean,
    val volume: Float?,
    val loop: Boolean,
    val quality: String?,
) {
    /** [mode] parsed, or null when it is absent or not a [FullscreenMode]. */
    val fullscreenMode: FullscreenMode? get() = mode?.let { runCatching { FullscreenMode.valueOf(it.uppercase()) }.getOrNull() }
}

/** The server config's fullscreen limits. */
data class FullscreenLimits(val defaultMode: FullscreenMode, val allowForced: Boolean, val qualityCap: Int)

/** Where the sender stands, the default center of a radius target. */
data class FullscreenOrigin(val world: String, val x: Double, val y: Double, val z: Double)

/**
 * Shared `/display fullscreen` logic, common to both the `Paper` and `Fabric` / `NeoForge` command trees
 * see `PaperFullscreenCommand` and `FabricFullscreenCommand`).
 */
object FullscreenCommand {
    /** Owner of sessions started without a player: from the console, a command block, or a function. */
    val SERVER_OWNER: UUID = UUID(0L, 0L)

    /** Starts a session on [display]. */
    fun start(
        display: DisplayData,
        virtual: Boolean,
        ownerId: UUID,
        options: FullscreenStartOptions,
        limits: FullscreenLimits,
        origin: FullscreenOrigin,
        resolveTarget: (String) -> Set<UUID>,
    ): FullscreenStartResult {
        if (options.forced && !limits.allowForced) return FullscreenStartResult.ForcedDisallowed

        val namedTargets = options.players
            ?.split(",")?.map { it.trim() }?.filter { it.isNotEmpty() }
            ?.flatMap(resolveTarget)?.toSet()?.takeIf { it.isNotEmpty() }
        val radius = options.radiusBlocks?.let { blocks ->
            FullscreenRadiusTarget(
                origin.world, options.radiusX ?: origin.x, options.radiusY ?: origin.y, options.radiusZ ?: origin.z, blocks,
            )
        }
        if (namedTargets == null && radius == null) return FullscreenStartResult.NoTargets

        val sessionId = FullscreenBroadcastManager.start(
            sessionId = UUID.randomUUID().toString().take(8),
            display = display,
            virtual = virtual,
            transientSession = options.transientSession,
            ownerId = ownerId,
            mode = options.fullscreenMode ?: limits.defaultMode,
            forced = options.forced,
            volume = options.volume ?: -1f,
            loop = options.loop,
            quality = clampQuality(options.quality, limits.qualityCap),
            title = "",
            namedTargets = namedTargets,
            radius = radius,
        ) ?: return FullscreenStartResult.AlreadyRunning

        val reach = FullscreenBroadcastManager.list().firstOrNull { it.sessionId == sessionId }?.reach ?: 0
        return FullscreenStartResult.Started(sessionId, reach)
    }

    private fun clampQuality(requested: String?, qualityCap: Int): String {
        if (qualityCap <= 0) return requested ?: ""
        val height = VideoQuality.parse(requested).targetHeight
        return if (height == null || height > qualityCap) qualityCap.toString() else requested ?: ""
    }

    /** Stops a session by its own id, by the id of the real display it's hosted on, or every session for `all`. Returns how many were stopped. */
    fun stop(idOrAll: String): Int {
        if (idOrAll.equals("all", ignoreCase = true)) {
            val ids = FullscreenBroadcastManager.list().map { it.sessionId }
            ids.forEach(FullscreenBroadcastManager::stop)
            return ids.size
        }
        if (FullscreenBroadcastManager.stop(idOrAll)) return 1
        val displayId = runCatching { UUID.fromString(idOrAll) }.getOrNull() ?: return 0
        val sessionId = FullscreenBroadcastManager.sessionIdForDisplay(displayId) ?: return 0
        return if (FullscreenBroadcastManager.stop(sessionId)) 1 else 0
    }

    /** Live sessions for the `list` subcommand. */
    fun list(): List<FullscreenSessionInfo> = FullscreenBroadcastManager.list()

    /** Suggestion tokens for `/display fullscreen stop`: every live session id, plus `all`. */
    fun stopSuggestions(): List<String> = FullscreenBroadcastManager.list().map { it.sessionId } + "all"
}

/** Paper adapter: resolves Bukkit sender/player state and turns [FullscreenCommand] results into chat replies. */
@PaperOnly
object PaperFullscreenCommand {
    /**
     * Handles `/display fullscreen start <id> [<flags>]`. Works without a player too (console, command
     * blocks): [origin] is then the default radius center, and only `this` and `@s` need a player.
     */
    fun start(
        sender: CommandSender,
        origin: Location,
        id: String,
        options: FullscreenStartOptions,
    ) {
        val player = sender as? Player
        val id = if (id.equals("this", ignoreCase = true)) {
            if (player == null) return ServerMessage.FULLSCREEN_PLAYER_ONLY.sendTo(sender)
            val block = player.getTargetBlock(null, 32)
            val data = DisplayManager.isContains(block.location)
                ?: return MessageUtil.sendMessage(
                    sender,
                    if (PaperSurfaces.of(PaperServer.config.settings.baseMaterial).accepts(block.type.name)) {
                        "noDisplay"
                    } else {
                        "displayVideoWrongTargetBlock"
                    },
                )
            data.id.toString()
        } else id
        if (options.serverScope != null) {
            if (options.radiusBlocks != null) return ServerMessage.FULLSCREEN_NETWORK_RADIUS_UNSUPPORTED.sendTo(sender)
            if (!ProxyNetwork.isConnected()) return ServerMessage.FULLSCREEN_NETWORK_NO_PROXY.sendTo(sender)
                        val resolvedUrl = FullscreenBroadcastManager.resolveNetworkFullscreenUrl(id)
            val networkOwner = player?.uniqueId ?: FullscreenCommand.SERVER_OWNER
            val sent = if (resolvedUrl != null) {
                ProxyBridge.startNetworkFullscreen(
                    rider = player,
                    ownerId = networkOwner,
                    scope = options.serverScope,
                    url = resolvedUrl,
                    mode = options.fullscreenMode,
                    forced = options.forced,
                    volume = options.volume,
                    loop = options.loop,
                    quality = options.quality,
                    targetsRaw = options.players,
                )
            } else {
                // Unknown here, but display ids are per-backend - the one being named very likely
                // lives on another server, so ask the network before reporting it as missing.
                ProxyBridge.startNetworkFullscreenByDisplayId(
                    rider = player,
                    ownerId = networkOwner,
                    scope = options.serverScope,
                    token = id,
                    mode = options.fullscreenMode,
                    forced = options.forced,
                    volume = options.volume,
                    loop = options.loop,
                    quality = options.quality,
                    targetsRaw = options.players,
                )
            }
            if (!sent) return ServerMessage.FULLSCREEN_NETWORK_NO_PLAYERS.sendTo(sender)
            return ServerMessage.FULLSCREEN_NETWORK_QUEUED.sendTo(sender)
        }
        val ownerId = player?.uniqueId ?: FullscreenCommand.SERVER_OWNER
        val resolved = FullscreenBroadcastManager.resolveOrCreateDisplay(id, ownerId)
            ?: return ServerMessage.FULLSCREEN_NO_DISPLAY.sendTo(sender)
        val (display, virtual) = resolved
        val config = PaperServer.config.settings
        val result = FullscreenCommand.start(
            display = display,
            virtual = virtual,
            ownerId = ownerId,
            options = options,
            limits = FullscreenLimits(config.fullscreenDefaultMode, config.fullscreenAllowForced, config.fullscreenQualityCap),
            origin = FullscreenOrigin(origin.world.name, origin.x, origin.y, origin.z),
            resolveTarget = { token -> resolveTargetToken(player, origin, token) },
        )
        reply(sender, result)
    }

    private fun resolveTargetToken(sender: Player?, origin: Location, token: String): Set<UUID> = when {
        token.equals("@a", ignoreCase = true) || token.equals("@e", ignoreCase = true) ->
            Bukkit.getOnlinePlayers().map { it.uniqueId }.toSet()

        token.equals("@s", ignoreCase = true) -> setOfNotNull(sender?.uniqueId)
        token.equals("@p", ignoreCase = true) ->
            Bukkit.getOnlinePlayers()
                .filter { it.world == origin.world }
                .minByOrNull { it.location.distanceSquared(origin) }
                ?.let { setOf(it.uniqueId) } ?: emptySet()

        token.equals("@r", ignoreCase = true) ->
            Bukkit.getOnlinePlayers().randomOrNull()?.let { setOf(it.uniqueId) } ?: emptySet()

        token.startsWith("%") ->
            Bukkit.getOnlinePlayers().filter { it.hasPermission("group.${token.substring(1)}") }.map { it.uniqueId }
                .toSet()

        else -> Bukkit.getPlayerExact(token)?.uniqueId?.let { setOf(it) } ?: emptySet()
    }

    /**
     * Handles `/display fullscreen stop <sessionId|displayId|all>`. `all` only ever stops local
     * sessions — a network-wide stop-all would need its own proxy-side bookkeeping and isn't worth
     * it for a rarely-typed admin command; stop network sessions by their own id instead.
     */
    fun stop(sender: CommandSender, idOrAll: String) {
        val networkIds =
            if (idOrAll.equals("all", ignoreCase = true)) FullscreenCommand.list().map { it.sessionId }
            else [idOrAll]

        val count = FullscreenCommand.stop(idOrAll)

        val forwarded = sender is Player && ProxyNetwork.isConnected() && networkIds.isNotEmpty()
        if (forwarded) networkIds.forEach { ProxyBridge.stopNetworkFullscreen(sender, it) }

        when {
            count > 0 -> MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(sender, ServerMessage.FULLSCREEN_STOPPED, count.toString())
            )

            forwarded -> ServerMessage.FULLSCREEN_NETWORK_STOP_QUEUED.sendTo(sender)
            else -> ServerMessage.FULLSCREEN_STOP_FAILED.sendTo(sender)
        }
    }

    /** Handles `/display fullscreen list`: local sessions plus the last known network roster. */
    fun list(sender: CommandSender) {
        val local = FullscreenCommand.list()
        val network = ProxyNetwork.networkSessions()
        if (sender is Player && ProxyNetwork.isConnected()) ProxyBridge.requestNetworkSessions(sender)
        if (local.isEmpty() && network.isEmpty()) return ServerMessage.FULLSCREEN_LIST_EMPTY.sendTo(sender)
        local.forEach { s ->
            MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(
                    sender, "fullscreenListEntry",
                    s.sessionId, s.displayId.toString(), s.virtual.toString(), s.reach.toString(),
                ),
            )
        }
        network.forEach { n ->
            MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(
                    sender, "fullscreenListNetworkEntry",
                    n.sessionId, n.scope, n.totalReach.toString(),
                ),
            )
        }
    }

    /** Online player names, for the `target` argument's suggestion list. */
    fun onlinePlayerNames(): List<String> = Bukkit.getOnlinePlayers().map { it.name }

    /** Suggestion tokens for `/display fullscreen stop`. */
    fun stopSuggestions(): List<String> = FullscreenCommand.stopSuggestions()

    private fun reply(sender: CommandSender, result: FullscreenStartResult) {
        when (result) {
            is FullscreenStartResult.Started -> MessageUtil.sendColoredMessage(
                sender,
                MessageUtil.formatIndexed(sender, ServerMessage.FULLSCREEN_STARTED, result.sessionId, result.reach.toString()),
            )

            FullscreenStartResult.NoTargets -> ServerMessage.FULLSCREEN_NO_TARGETS.sendTo(sender)
            FullscreenStartResult.AlreadyRunning -> ServerMessage.FULLSCREEN_ALREADY_RUNNING.sendTo(sender)
            FullscreenStartResult.ForcedDisallowed -> ServerMessage.FULLSCREEN_FORCED_DISALLOWED.sendTo(sender)
        }
    }
}

/** Shared `Fabric` / `NeoForge` adapter: resolves vanilla sender/player state and turns [FullscreenCommand] results into chat replies. */
@ModLoaderOnly
object VanillaFullscreenCommand {
    /**
     * Handles `/display fullscreen start <id> [<flags>]`. Works without a player too (console, command
     * blocks): the source's position is then the default radius center, and only `this` and `@s`
     * need a player.
     */
    fun start(
        ctx: CommandContext<CommandSourceStack>,
        id: String,
        options: FullscreenStartOptions,
    ): Int {
        val player = ctx.source.entity as? ServerPlayer
        val id = if (id.equals("this", ignoreCase = true)) {
            if (player == null) return tell(ctx, null, ServerMessage.FULLSCREEN_PLAYER_ONLY).let { 0 }
            val targetPos = RegionUtil.getTargetedBlockPos(player)
                ?: return ServerMessage.DISPLAY_VIDEO_WRONG_TARGET_BLOCK.sendTo(player).let { 0 }
            val worldKey = RegionUtil.getPlayerLevelKey(player)
            val data = DisplayManager.isContains(worldKey, targetPos)
                ?: return ServerMessage.NO_DISPLAY.sendTo(player).let { 0 }
            data.id.toString()
        } else id
        if (options.serverScope != null) {
            if (options.radiusBlocks != null) {
                tell(ctx, player, ServerMessage.FULLSCREEN_NETWORK_RADIUS_UNSUPPORTED)
                return 0
            }
            if (!ProxyNetwork.isConnected()) {
                tell(ctx, player, ServerMessage.FULLSCREEN_NETWORK_NO_PROXY)
                return 0
            }
                        val resolvedUrl = FullscreenBroadcastManager.resolveNetworkFullscreenUrl(id)
            val networkOwner = player?.uuid ?: FullscreenCommand.SERVER_OWNER
            val sent = if (resolvedUrl != null) {
                VanillaProxyBridge.startNetworkFullscreen(
                    rider = player,
                    ownerId = networkOwner,
                    scope = options.serverScope,
                    url = resolvedUrl,
                    mode = options.fullscreenMode,
                    forced = options.forced,
                    volume = options.volume,
                    loop = options.loop,
                    quality = options.quality,
                    targetsRaw = options.players,
                )
            } else {
                VanillaProxyBridge.startNetworkFullscreenByDisplayId(
                    rider = player,
                    ownerId = networkOwner,
                    scope = options.serverScope,
                    token = id,
                    mode = options.fullscreenMode,
                    forced = options.forced,
                    volume = options.volume,
                    loop = options.loop,
                    quality = options.quality,
                    targetsRaw = options.players,
                )
            }
            if (!sent) return tell(ctx, player, ServerMessage.FULLSCREEN_NETWORK_NO_PLAYERS).let { 0 }
            tell(ctx, player, ServerMessage.FULLSCREEN_NETWORK_QUEUED)
            return 1
        }
        val ownerId = player?.uuid ?: FullscreenCommand.SERVER_OWNER
        val resolved = FullscreenBroadcastManager.resolveOrCreateDisplay(id, ownerId) ?: run {
            tell(ctx, player, ServerMessage.FULLSCREEN_NO_DISPLAY)
            return 0
        }
        val origin = ctx.source.position
        val (display, virtual) = resolved
        val config = VanillaServerState.config.settings
        val result = FullscreenCommand.start(
            display = display,
            virtual = virtual,
            ownerId = ownerId,
            options = options,
            limits = FullscreenLimits(config.fullscreenDefaultMode, config.fullscreenAllowForced, config.fullscreenQualityCap),
            origin = FullscreenOrigin(RegionUtil.getLevelKey(ctx.source.level), origin.x, origin.y, origin.z),
            resolveTarget = { token -> resolveTargetToken(ctx, player, token) },
        )
        reply(ctx, player, result)
        return 1
    }

    private fun resolveTargetToken(
        ctx: CommandContext<CommandSourceStack>,
        sender: ServerPlayer?,
        token: String,
    ): Set<UUID> {
        val online = ctx.source.server.playerList.players
        val level = ctx.source.level
        val origin = ctx.source.position
        return when {
            token.equals("@a", ignoreCase = true) || token.equals("@e", ignoreCase = true) ->
                online.map { it.uuid }.toSet()

            token.equals("@s", ignoreCase = true) -> setOfNotNull(sender?.uuid)
            token.equals("@p", ignoreCase = true) ->
                online
                    .filter { it.level() == level }
                    .minByOrNull { it.distanceToSqr(origin) }
                    ?.let { setOf(it.uuid) } ?: emptySet()

            token.equals("@r", ignoreCase = true) -> online.randomOrNull()?.let { setOf(it.uuid) } ?: emptySet()
            token.startsWith("%") ->
                online.filter {
                    VanillaPermissions.has(
                        it,
                        "group.${token.substring(1)}",
                        VanillaPermissions.Fallback.NOBODY
                    )
                }
                    .map { it.uuid }.toSet()

            else -> online.firstOrNull { it.gameProfile.name.equals(token, ignoreCase = true) }?.uuid?.let { setOf(it) }
                ?: emptySet()
        }
    }

    /**
     * Handles `/display fullscreen stop <sessionId|displayId|all>`. Mirrors `PaperFullscreenCommand.stop`'s network-id
     * resolution before delegating to the shared [FullscreenCommand.stop].
     */
    fun stop(ctx: CommandContext<CommandSourceStack>, idOrAll: String): Int {
        val player = ctx.source.entity as? ServerPlayer
        val networkIds =
            if (idOrAll.equals("all", ignoreCase = true)) FullscreenCommand.list().map { it.sessionId }
            else [idOrAll]

        val count = FullscreenCommand.stop(idOrAll)

        var forwarded = false
        if (player != null && ProxyNetwork.isConnected() && networkIds.isNotEmpty()) {
            forwarded = true
            networkIds.forEach { VanillaProxyBridge.stopNetworkFullscreen(player, it) }
        }

        val line = when {
            count > 0 -> MessageUtil.formatIndexed(player, ServerMessage.FULLSCREEN_STOPPED, count.toString())
            forwarded -> MessageUtil.messageFor(player, ServerMessage.FULLSCREEN_NETWORK_STOP_QUEUED)
            else -> MessageUtil.messageFor(player, ServerMessage.FULLSCREEN_STOP_FAILED)
        }
        if (player != null) MessageUtil.sendColoredMessage(
            player,
            line
        ) else ctx.source.sendSystemMessage(Component.literal(line))
        return count
    }

    /** Handles `/display fullscreen list`: local sessions plus the last known network roster. */
    fun list(ctx: CommandContext<CommandSourceStack>): Int {
        val player = ctx.source.entity as? ServerPlayer
        val local = FullscreenCommand.list()
        val network = ProxyNetwork.networkSessions()
        if (player != null && ProxyNetwork.isConnected()) VanillaProxyBridge.requestNetworkSessions(player)
        if (local.isEmpty() && network.isEmpty()) {
            val line = MessageUtil.messageFor(player, ServerMessage.FULLSCREEN_LIST_EMPTY)
            if (player != null) MessageUtil.sendColoredMessage(
                player,
                line
            ) else ctx.source.sendSystemMessage(Component.literal(line))
            return 0
        }
        local.forEach { s ->
            val line = MessageUtil.formatIndexed(
                player, "fullscreenListEntry",
                s.sessionId, s.displayId.toString(), s.virtual.toString(), s.reach.toString(),
            )
            if (player != null) MessageUtil.sendColoredMessage(
                player,
                line
            ) else ctx.source.sendSystemMessage(Component.literal(line))
        }
        network.forEach { n ->
            val line = MessageUtil.formatIndexed(
                player, "fullscreenListNetworkEntry",
                n.sessionId, n.scope, n.totalReach.toString(),
            )
            if (player != null) MessageUtil.sendColoredMessage(
                player,
                line
            ) else ctx.source.sendSystemMessage(Component.literal(line))
        }
        return local.size + network.size
    }

    /** Online player names, for the `target` argument's suggestion list. */
    fun onlinePlayerNames(ctx: CommandContext<CommandSourceStack>): List<String> =
        ctx.source.server.playerList.players.map { it.gameProfile.name }

    /** Suggestion tokens for `/display fullscreen stop`. */
    fun stopSuggestions(): List<String> = FullscreenCommand.stopSuggestions()

    private fun reply(ctx: CommandContext<CommandSourceStack>, player: ServerPlayer?, result: FullscreenStartResult) {
        when (result) {
            is FullscreenStartResult.Started ->
                tell(ctx, player, ServerMessage.FULLSCREEN_STARTED, result.sessionId, result.reach.toString())

            FullscreenStartResult.NoTargets -> tell(ctx, player, ServerMessage.FULLSCREEN_NO_TARGETS)
            FullscreenStartResult.AlreadyRunning -> tell(ctx, player, ServerMessage.FULLSCREEN_ALREADY_RUNNING)
            FullscreenStartResult.ForcedDisallowed -> tell(ctx, player, ServerMessage.FULLSCREEN_FORCED_DISALLOWED)
        }
    }

    private fun tell(
        ctx: CommandContext<CommandSourceStack>,
        player: ServerPlayer?,
        message: ServerMessage,
        vararg values: String,
    ) {
        val text = MessageUtil.formatIndexed(player, message, *values)
        if (player != null) {
            MessageUtil.sendColoredMessage(player, text)
        } else {
            ctx.source.sendSystemMessage(Component.literal(text.replace(Regex("&[0-9a-fk-or]"), "")))
        }
    }
}
