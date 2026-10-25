package com.dreamdisplays.platform.server.commands.subcommands

import com.dreamdisplays.platform.server.ModLoaderOnly
import com.dreamdisplays.api.playback.model.PlaybackAction
import com.dreamdisplays.platform.server.PaperServer
import com.dreamdisplays.platform.server.VanillaServerState
import com.dreamdisplays.platform.server.datatypes.display.DisplayData
import com.dreamdisplays.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplays.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplays.platform.server.datatypes.display.shortLabel
import com.dreamdisplays.platform.server.playback.ScheduledPlaybackManager
import com.dreamdisplays.platform.server.utils.MessageUtil
import com.dreamdisplays.platform.server.utils.ServerMessage
import com.dreamdisplays.platform.server.utils.sendTo
import com.dreamdisplays.platform.server.utils.ScheduleTimeUtil
import com.dreamdisplays.platform.server.utils.VanillaPermissions
import com.mojang.brigadier.context.CommandContext
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer
import org.bukkit.command.CommandSender
import org.bukkit.entity.Player
import java.util.*
import kotlin.time.Clock

private const val CANCEL_TOKEN = "cancel"
private const val PLAY_TOKEN = "play"
private const val PAUSE_TOKEN = "pause"

/**
 * Handles the `/display schedule` command: `this|<id> [play|pause|cancel] [<HH:mm[:ss]>]`.
 */
@PaperOnly
class ScheduleCommand : SubCommand {
    override val name = "schedule"
    override val permission = PaperServer.config.permissions.schedule
    override val playerOnly = true

    override fun execute(sender: CommandSender, args: Array<String?>) {
        val player = (sender as? Player) ?: return
        val token = args.getOrNull(0) ?: "this"

        val data = resolvePaperDisplayTarget(sender, player, token) as? PaperDisplayData ?: return
        if (!canManage(data, player.uniqueId, player.hasPermission(PaperServer.config.permissions.scheduleOthers))) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return
        }

        val localize =
            { key: String, values: List<String> -> MessageUtil.formatIndexed(player, key, *values.toTypedArray()) }
        val text = respond(data, args.getOrNull(1), args.getOrNull(2), player.uniqueId, localize)
        MessageUtil.sendColoredMessage(player, text)
    }
}

/** Shared `Fabric` / `NeoForge` implementation of the `/display schedule` command. */
@ModLoaderOnly
object VanillaScheduleCommand {
    fun execute(ctx: CommandContext<CommandSourceStack>, token: String, action: String?, time: String?): Int {
        val player = ctx.source.entity as? ServerPlayer
            ?: return ctx.source.sendFailure(Component.literal("Players only.")).let { 0 }

        val data = resolveVanillaDisplayTarget(player, token) as? VanillaDisplayData ?: return 0
        val canManageOthers = VanillaPermissions.has(
            player, VanillaServerState.config.permissions.scheduleOthers, VanillaPermissions.Fallback.OP,
        )
        if (!canManage(data, player.uuid, canManageOthers)) {
            ServerMessage.DISPLAY_COMMAND_MISSING_PERMISSION.sendTo(player)
            return 0
        }

        val localize =
            { key: String, values: List<String> -> MessageUtil.formatIndexed(player, key, *values.toTypedArray()) }
        val text = respond(data, action, time, player.uuid, localize)
        MessageUtil.sendColoredMessage(player, text)
        return 1
    }
}

private fun respond(
    data: DisplayData,
    action: String?,
    time: String?,
    playerId: UUID,
    localize: (String, List<String>) -> String,
): String = when (action) {
    null -> showCurrent(data, playerId, localize)
    CANCEL_TOKEN -> cancel(data, localize)
    PLAY_TOKEN -> onAction(data, PlaybackAction.PLAY, time, playerId, localize)
    PAUSE_TOKEN -> onAction(data, PlaybackAction.PAUSE, time, playerId, localize)
    else -> localize("scheduleInvalidTime", [])
}

private fun canManage(data: DisplayData, playerId: UUID, canManageOthers: Boolean): Boolean =
    data.ownerId == playerId || canManageOthers

private fun showCurrent(data: DisplayData, viewerId: UUID, localize: (String, List<String>) -> String): String {
    val at = data.scheduledStart ?: return localize("scheduleNoneSet", [])
    val action = data.scheduledAction ?: PlaybackAction.PLAY
    val localSecond = ScheduleTimeUtil.localSecondOfDay(at, ScheduleTimeUtil.offsetMinutesOf(viewerId))
    return localize(
        "scheduleCurrentSet",
        [localize(actionKey(action), []), ScheduleTimeUtil.format(localSecond, withSeconds = true)],
    )
}

private fun cancel(data: DisplayData, localize: (String, List<String>) -> String): String =
    if (ScheduledPlaybackManager.cancel(data.id)) localize("scheduleCancelled", [])
    else localize("scheduleNoneSet", [])

private fun onAction(
    data: DisplayData,
    action: PlaybackAction,
    time: String?,
    playerId: UUID,
    localize: (String, List<String>) -> String,
): String {
    val offset = ScheduleTimeUtil.offsetMinutesOf(playerId)
    if (time == null) {
        val nowSecond = ScheduleTimeUtil.currentSecondOfDay(offset)
        return localize("scheduleNeedsTime", [ScheduleTimeUtil.format(nowSecond, withSeconds = true)])
    }

    val targetSecond = ScheduleTimeUtil.parseSecondOfDay(time)
        ?: return localize("scheduleInvalidTime", [])

    val now = Clock.System.now()
    val nowSecond = ScheduleTimeUtil.localSecondOfDay(now, offset)
    val secondsAhead = ScheduleTimeUtil.secondsUntil(targetSecond, offset, now)
    val target = ScheduleTimeUtil.resolveNextOccurrence(targetSecond, offset, now)

    ScheduledPlaybackManager.schedule(data, target, action)

    val countdown = when {
        secondsAhead >= 3600 -> localize(
            "scheduleCountdownHours",
            [(secondsAhead / 3600).toString(), ((secondsAhead % 3600) / 60).toString()]
        )

        secondsAhead >= 60 -> localize(
            "scheduleCountdownMinutes",
            [(secondsAhead / 60).toString(), (secondsAhead % 60).toString()]
        )

        else -> localize("scheduleCountdownSeconds", [secondsAhead.toString()])
    }
    return localize(
        "scheduleSet",
        [
            ScheduleTimeUtil.format(nowSecond, withSeconds = true),
            data.shortLabel,
            localize(actionKey(action), []),
            ScheduleTimeUtil.format(targetSecond, withSeconds = true),
            countdown,
        ],
    )
}

private fun actionKey(action: PlaybackAction): String =
    if (action == PlaybackAction.PAUSE) "scheduleActionPause" else "scheduleActionPlay"
