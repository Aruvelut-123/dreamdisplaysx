package com.dreamdisplayx.platform.server.registrar

import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.commands.subcommands.*
import com.dreamdisplayx.platform.server.credentials.CredentialActions
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.managers.DisplayGroupManager
import com.dreamdisplayx.api.playback.model.PlaybackAction
import com.dreamdisplayx.platform.server.playback.TimelineManager
import com.dreamdisplayx.platform.server.playback.FullscreenBroadcastManager
import com.dreamdisplayx.platform.server.proxy.ProxyNetwork
import com.dreamdisplayx.platform.server.registrar.CommandRegistrar.fullscreenFlagsNode
import com.dreamdisplayx.platform.server.utils.MessageUtil
import com.dreamdisplayx.platform.server.utils.ScheduleTimeUtil
import com.dreamdisplayx.platform.server.utils.net.PaperV2Networking
import com.mojang.brigadier.Command
import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import com.mojang.brigadier.suggestion.Suggestions
import com.mojang.brigadier.suggestion.SuggestionsBuilder
import com.mojang.brigadier.tree.CommandNode
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.arnodoelinger.platformweaver.PaperOnly
//? if >=1.20.5 {
import io.papermc.paper.command.brigadier.CommandSourceStack
//?}
import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import java.util.concurrent.CompletableFuture

/**
 * Command registrar. Uses `Brigadier` to build the `/display` command tree. See
 * `VanillaCommandTree.kt` for the shared `Fabric` / `NeoForge` equivalent.
 */
@PaperOnly
object CommandRegistrar {
    /** Suggestion tokens for the fullscreen `quality` flag. */
    private val QUALITY_SUGGESTIONS = listOf("auto", "360", "480", "720", "1080")

    /** Selector tokens suggested for the fullscreen `target` argument, alongside online player names. */
    private val TARGET_SELECTORS = listOf("@a", "@p", "@r", "@s", "@e")

    /**
     * The fullscreen-start flags, in the one order they may be given in. `server` comes first: it
     * picks the broadest scope (which backend, or the whole network) before `target`/`radius`
     * narrow who within that scope actually sees it.
     */
    private val FULLSCREEN_FLAGS =
        listOf("server", "target", "radius", "mode", "forced", "transient", "volume", "looped", "quality")

    /** Builds the full `Brigadier` tree for the `/display` command with all subcommands. */
    fun buildDisplayCommand(): LiteralCommandNode<CommandSourceStack> = LiteralArgumentBuilder.literal("display")
        .executes { ctx ->
            HelpCommand().execute(ctx.source.sender, emptyArray())
            Command.SINGLE_SUCCESS
        }
        .then(simple("help", HelpCommand()))
        .then(
            LiteralArgumentBuilder.literal("create")
                .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.create) }
                .executes { ctx ->
                    CreateCommand().execute(ctx.source.sender, emptyArray())
                    Command.SINGLE_SUCCESS
                }
                .then(
                    RequiredArgumentBuilder.argument("name", StringArgumentType.greedyString())
                        .executes { ctx ->
                            CreateCommand().execute(
                                ctx.source.sender,
                                arrayOf(StringArgumentType.getString(ctx, "name")),
                            )
                            Command.SINGLE_SUCCESS
                        }
                )
        )
        .then(
            LiteralArgumentBuilder.literal("rename")
                .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.name) }
                .then(
                    RequiredArgumentBuilder.argument("id", BareTokenCommandArgumentType)
                        .suggests { _, b ->
                            b.suggest("this")
                            FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                            b.buildFuture()
                        }
                        .then(
                            RequiredArgumentBuilder.argument("new_name", StringArgumentType.greedyString())
                                .executes { ctx ->
                                    PaperRenameCommand().execute(
                                        ctx.source.sender,
                                        arrayOf(
                                            StringArgumentType.getString(ctx, "id"),
                                            StringArgumentType.getString(ctx, "new_name"),
                                        ),
                                    )
                                    Command.SINGLE_SUCCESS
                                }
                        )
                )
        )
        .then(
            simpleWithThis(
                "delete",
                DeleteCommand()
            ) { it.sender is Player })
        .then(
            simpleWithThis(
                "info",
                InfoCommand()
            ) { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.info) })
        .then(simple("stats", StatsCommand()) { it.sender.hasPermission(PaperServer.config.permissions.stats) })
        .then(simple("reload", ReloadCommand()) { it.sender.hasPermission(PaperServer.config.permissions.reload) })
        .then(videoSubCommand())
        .then(groupSubCommand())
        .then(scheduleSubCommand())
        .then(listSubCommand())
        .then(toggleSubCommand("on", OnCommand()))
        .then(toggleSubCommand("off", OffCommand()))
        .then(fullscreenSubCommand())
        .then(loginSubCommand())
        .then(logoutSubCommand())
        .build()

    /** `/display login <platform> <token>` — saves an encrypted global credential (OP-only) and broadcasts to all players. */
    private fun loginSubCommand(): LiteralArgumentBuilder<CommandSourceStack> = LiteralArgumentBuilder.literal("login")
        .requires { it.sender.isOp || it.sender.hasPermission(PaperServer.config.permissions.login) }
        .then(
            RequiredArgumentBuilder.argument("platform", StringArgumentType.word())
                .then(
                    RequiredArgumentBuilder.argument("token", StringArgumentType.greedyString())
                        .executes { ctx ->
                            val sender = ctx.source.sender
                            val player = sender as? Player ?: return@executes 0
                            val platform = StringArgumentType.getString(ctx, "platform")
                            val token = StringArgumentType.getString(ctx, "token")
                            if (token.isBlank()) {
                                MessageUtil.sendMessage(player, "loginTokenEmpty")
                                return@executes 1
                            }
                            if (!CredentialActions.isSupported(platform)) {
                                MessageUtil.sendMessage(player, "loginUnsupportedPlatform", platform)
                                return@executes 1
                            }
                            val snapshot = CredentialActions.globalLogin(platform, token)
                            // Broadcast to all online players
                            val onlinePlayers = Bukkit.getOnlinePlayers()
                            if (onlinePlayers.isNotEmpty()) {
                                PaperV2Networking.send(onlinePlayers.toList(), snapshot)
                            }
                            MessageUtil.sendMessage(player, "loginSuccess", platform)
                            1
                        }
                )
        )

    /** `/display logout <platform>` — removes the global credential (OP-only) and broadcasts to all players. */
    private fun logoutSubCommand(): LiteralArgumentBuilder<CommandSourceStack> = LiteralArgumentBuilder.literal("logout")
        .requires { it.sender.isOp || it.sender.hasPermission("dreamdisplayx.logout") }
        .then(
            RequiredArgumentBuilder.argument("platform", StringArgumentType.word())
                .executes { ctx ->
                    val sender = ctx.source.sender
                    val player = sender as? Player ?: return@executes 0
                    val platform = StringArgumentType.getString(ctx, "platform")
                    val snapshot = CredentialActions.globalLogout(platform)
                    // Broadcast to all online players
                    val onlinePlayers = Bukkit.getOnlinePlayers()
                    if (onlinePlayers.isNotEmpty()) {
                        PaperV2Networking.send(onlinePlayers.toList(), snapshot)
                    }
                    MessageUtil.sendMessage(player, "logoutSuccess", platform)
                    1
                }
        )

    /** Builds the `/display group` same-content playback command tree. */
    private fun groupSubCommand() = GroupCommand.node()

    /** Builds a simple no-argument subcommand node optionally guarded by a permission check. */
    private fun simple(
        name: String,
        cmd: SubCommand,
        check: ((CommandSourceStack) -> Boolean)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        var builder = LiteralArgumentBuilder.literal(name)
        if (check != null) builder = builder.requires(check)
        return builder.executes { ctx ->
            cmd.execute(ctx.source.sender, emptyArray())
            Command.SINGLE_SUCCESS
        }
    }

    /** Like [simple], but adds branches for `this` (raycast) and an explicit id argument. */
    private fun simpleWithThis(
        name: String,
        cmd: SubCommand,
        check: ((CommandSourceStack) -> Boolean)? = null,
    ): LiteralArgumentBuilder<CommandSourceStack> {
        var builder = LiteralArgumentBuilder.literal(name)
        if (check != null) builder = builder.requires(check)
        return builder
            .then(
                LiteralArgumentBuilder.literal("this").executes { ctx ->
                    cmd.execute(ctx.source.sender, arrayOf("this"))
                    Command.SINGLE_SUCCESS
                }
            )
            .then(
                RequiredArgumentBuilder.argument("id", BareTokenCommandArgumentType)
                    .suggests { _, b ->
                        FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                        b.buildFuture()
                    }
                    .executes { ctx ->
                        cmd.execute(ctx.source.sender, arrayOf(StringArgumentType.getString(ctx, "id")))
                        Command.SINGLE_SUCCESS
                    }
            )
    }

    /**
     * Builds the `/display video this|<id> <url> [lang]` subcommand — see [simpleWithThis] for why
     * both `this` and an explicit id are offered.
     */
    private fun videoSubCommand() = LiteralArgumentBuilder.literal("video")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.video) }
        .then(LiteralArgumentBuilder.literal("this").then(videoUrlArgument { "this" }))
        .then(
            RequiredArgumentBuilder.argument("id", BareTokenCommandArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .then(videoUrlArgument { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /** The `<url> [lang]` greedy argument under `/display video this|<id> <url> [lang]`. */
    private fun videoUrlArgument(token: (CommandContext<CommandSourceStack>) -> String) =
        // greedyString captures the rest of the input (URL + optional lang separated by space)
        RequiredArgumentBuilder.argument("url_and_lang", StringArgumentType.greedyString())
            .suggests { _, builder ->
                // Only suggest lang codes when the input looks like "url lang_prefix"
                if (builder.remaining.contains(' ')) {
                    val prefix = builder.remaining.substringAfterLast(' ')
                    VideoCommand.languageSuggestions
                        .filter { it.startsWith(prefix, ignoreCase = true) }
                        .forEach { builder.suggest(builder.remaining.substringBeforeLast(' ') + " " + it) }
                }
                builder.buildFuture()
            }
            .executes { ctx ->
                val raw = StringArgumentType.getString(ctx, "url_and_lang").trim()
                val parts = raw.split(" ")
                val url = parts[0]
                val lang = if (parts.size > 1) parts.last() else ""
                VideoCommand().execute(ctx.source.sender, arrayOf(token(ctx), url, lang))
                Command.SINGLE_SUCCESS
            }

    /**
     * Builds the `/display schedule this|<id> [play|pause|cancel] [<HH:mm[:ss]>]` subcommand.
     */
    private fun scheduleSubCommand() = LiteralArgumentBuilder.literal("schedule")
        .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.schedule) }
        .then(
            LiteralArgumentBuilder.literal("this")
                .executes { ctx ->
                    ScheduleCommand().execute(ctx.source.sender, arrayOf("this", null, null))
                    Command.SINGLE_SUCCESS
                }
                .then(
                    LiteralArgumentBuilder.literal("cancel").executes { ctx ->
                        ScheduleCommand().execute(ctx.source.sender, arrayOf("this", "cancel", null))
                        Command.SINGLE_SUCCESS
                    }
                )
                .then(scheduleActionNode("play") { "this" })
                .then(scheduleActionNode("pause") { "this" })
        )
        .then(
            RequiredArgumentBuilder.argument("id", BareTokenCommandArgumentType)
                .suggests { _, b ->
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { b.suggest(it) }
                    b.buildFuture()
                }
                .executes { ctx ->
                    ScheduleCommand().execute(
                        ctx.source.sender,
                        arrayOf(StringArgumentType.getString(ctx, "id"), null, null),
                    )
                    Command.SINGLE_SUCCESS
                }
                .then(
                    LiteralArgumentBuilder.literal("cancel").executes { ctx ->
                        ScheduleCommand().execute(
                            ctx.source.sender,
                            arrayOf(StringArgumentType.getString(ctx, "id"), "cancel", null),
                        )
                        Command.SINGLE_SUCCESS
                    }
                )
                .then(scheduleActionNode("play") { ctx -> StringArgumentType.getString(ctx, "id") })
                .then(scheduleActionNode("pause") { ctx -> StringArgumentType.getString(ctx, "id") })
        )

    /**
     * The `play`/`pause` literal under `/display schedule this|<id>`: bare (no time yet) reveals the
     * player's current local time so they have an anchor to schedule against, and offers the
     * `<HH:mm[:ss]>` argument to actually set it.
     */
    private fun scheduleActionNode(action: String, token: (CommandContext<CommandSourceStack>) -> String) =
        LiteralArgumentBuilder.literal(action)
            .executes { ctx ->
                ScheduleCommand().execute(ctx.source.sender, arrayOf(token(ctx), action, null))
                Command.SINGLE_SUCCESS
            }
            .then(scheduleTimeArgument(action, token))

    /**
     * The `<HH:mm[:ss]>` argument under `/display schedule this|<id> play|pause <HH:mm[:ss]>`,
     * suggesting every minute of the player's local day.
     */
    private fun scheduleTimeArgument(action: String, token: (CommandContext<CommandSourceStack>) -> String) =
        RequiredArgumentBuilder.argument("time", BareTokenCommandArgumentType)
            .suggests { ctx, builder -> scheduleTimeSuggestions(ctx.source.sender as? Player, builder) }
            .executes { ctx ->
                ScheduleCommand().execute(
                    ctx.source.sender,
                    arrayOf(token(ctx), action, StringArgumentType.getString(ctx, "time")),
                )
                Command.SINGLE_SUCCESS
            }

    /**
     * Suggests every minute-of-day as `HH:mm`, player-local (via [ScheduleTimeUtil]). With nothing
     * typed yet, the first entry is "now" (rounded to the next minute) followed by a rolling window
     * of the next two hours.
     */
    private fun scheduleTimeSuggestions(player: Player?, builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val offset = player?.let { ScheduleTimeUtil.offsetMinutesOf(it.uniqueId) } ?: 0
        val nowMinute = ScheduleTimeUtil.minuteOfDay(ScheduleTimeUtil.currentSecondOfDay(offset))
        val firstMinute = (nowMinute + 1) % 1440
        val prefix = builder.remaining

        val candidateMinutes = if (prefix.isBlank()) {
            (0 until 120).map { (firstMinute + it) % 1440 }
        } else {
            (0 until 1440)
                .map { (firstMinute + it) % 1440 }
                .filter { ScheduleTimeUtil.format(it * 60).startsWith(prefix, ignoreCase = true) }
                .take(150)
        }

        candidateMinutes.forEach { minute ->
            val text = ScheduleTimeUtil.format(minute * 60)
            val secondsAhead = ScheduleTimeUtil.secondsUntil(minute * 60, offset)
            val tooltip = if (minute == firstMinute) "now" else ScheduleTimeUtil.compactCountdown(secondsAhead)
            builder.suggest(text, LiteralMessage(tooltip))
        }
        return builder.buildFuture()
    }

    /** Builds an on / off toggle subcommand that optionally targets another player. */
    private fun toggleSubCommand(name: String, cmd: SubCommand) = LiteralArgumentBuilder.literal(name)
        .executes { ctx ->
            cmd.execute(ctx.source.sender, arrayOf(name))
            Command.SINGLE_SUCCESS
        }
        .then(
            RequiredArgumentBuilder.argument("player", StringArgumentType.word())
                .requires { it.sender.hasPermission(PaperServer.config.permissions.toggleOthers) }
                .suggests { _, builder ->
                    Bukkit.getOnlinePlayers().forEach { builder.suggest(it.name) }
                    builder.buildFuture()
                }
                .executes { ctx ->
                    val playerName = StringArgumentType.getString(ctx, "player")
                    cmd.execute(ctx.source.sender, arrayOf(name, playerName))
                    Command.SINGLE_SUCCESS
                }
        )

    /**
     * Builds the `/display fullscreen start|stop|list` subcommand: server-forced fullscreen
     * broadcasts to players by name selector, radius, or both (combinable).
     */
    private fun fullscreenSubCommand(): LiteralArgumentBuilder<CommandSourceStack> = LiteralArgumentBuilder.literal("fullscreen")
        .executes { ctx ->
            (ctx.source.sender as? Player)?.let { player ->
                MessageUtil.sendColoredMessage(
                    ctx.source.sender,
                    "&f ${PaperServer.config.getMessageForPlayer(player, "displayHelpFullscreen")}"
                )
            }
            Command.SINGLE_SUCCESS
        }
        .then(fullscreenStartSubCommand())
        .then(
            LiteralArgumentBuilder.literal("stop")
                .requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenStop) }
                .then(
                    RequiredArgumentBuilder.argument("id", StringArgumentType.word())
                        .suggests { _, builder ->
                            PaperFullscreenCommand.stopSuggestions().forEach { builder.suggest(it) }
                            builder.buildFuture()
                        }
                        .executes { ctx ->
                            PaperFullscreenCommand.stop(ctx.source.sender, StringArgumentType.getString(ctx, "id"))
                            Command.SINGLE_SUCCESS
                        }
                )
        )
        .then(
            LiteralArgumentBuilder.literal("list")
                .requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenList) }
                .executes { ctx ->
                    PaperFullscreenCommand.list(ctx.source.sender)
                    Command.SINGLE_SUCCESS
                }
        )

    /** Builds the fullscreen start subcommand with optional flags in any order. */
    private fun fullscreenStartSubCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val flags = fullscreenFlagsNode()
        return LiteralArgumentBuilder.literal("start")
            .requires { it.sender is Player && it.sender.hasPermission(PaperServer.config.permissions.fullscreenStart) }
            .then(fullscreenIdOrUrlNode("id", flags))
            .then(fullscreenIdOrUrlNode("url", flags))
    }

    /** Builds the `id <id>` / `url <url>` branch under `/display fullscreen start`, both feeding the same `id` argument. */
    private fun fullscreenIdOrUrlNode(
        literalName: String,
        flags: List<CommandNode<CommandSourceStack>>,
    ) = LiteralArgumentBuilder.literal(literalName).then(
        RequiredArgumentBuilder.argument("id", BareTokenCommandArgumentType)
            .suggests { _, builder ->
                if (literalName == "id") {
                    builder.suggest("this")
                    FullscreenBroadcastManager.displayIdSuggestions().forEach { builder.suggest(it) }
                }
                builder.buildFuture()
            }
            .executes { ctx -> runFullscreenStart(ctx); Command.SINGLE_SUCCESS }
            .also { idArg -> flags.forEach { idArg.then(it) } }
    )

    /**
     * Bare space-delimited token with custom character validation. Kept local instead of
     * reusing [BareTokenArgumentType] because that one also registers Fabric/NeoForge sync
     * info, which the Paper tree never needs.
     */
    private object BareTokenCommandArgumentType : ArgumentType<String> {
        private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))

        override fun parse(reader: StringReader): String {
            val start = reader.cursor
            while (reader.canRead() && reader.peek() != ' ') reader.skip()
            if (reader.cursor == start) throw MISSING.create()
            return reader.string.substring(start, reader.cursor)
        }

    }

    /**
     * All fullscreen-start flags, any subset of them accepted but only in [FULLSCREEN_FLAGS] order:
     * each flag's subtree offers just the flags that follow it.
     */
    private fun fullscreenFlagsNode(): List<CommandNode<CommandSourceStack>> {
        var following = emptyList<CommandNode<CommandSourceStack>>()
        for (name in FULLSCREEN_FLAGS.asReversed()) {
            following = listOf(buildFullscreenFlagNode(name, following)) + following
        }
        return following
    }

    /** Attaches [children] plus a bare `.executes` (this flag alone, nothing further) to [node], then builds it. */
    private fun terminate(
        node: ArgumentBuilder<CommandSourceStack, *>,
        children: List<CommandNode<CommandSourceStack>>,
    ): CommandNode<CommandSourceStack> {
        node.executes { ctx -> runFullscreenStart(ctx); Command.SINGLE_SUCCESS }
        children.forEach { node.then(it) }
        return node.build()
    }

    /** Builds one flag's own literal / argument subtree, attaching the already-built [children] at every terminal. */
    private fun buildFullscreenFlagNode(
        name: String,
        children: List<CommandNode<CommandSourceStack>>
    ): CommandNode<CommandSourceStack> =
        when (name) {
            "server" -> LiteralArgumentBuilder.literal("server")
                .requires { it.sender.hasPermission(PaperServer.config.permissions.fullscreenNetwork) }
                .then(
                    terminate(
                        RequiredArgumentBuilder.argument("name", StringArgumentType.word())
                            .suggests { _, builder ->
                                (ProxyNetwork.serverNames() + "global")
                                    .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                    .forEach { builder.suggest(it) }
                                builder.buildFuture()
                            },
                        children,
                    )
                ).build()

            "target" -> LiteralArgumentBuilder.literal("target").then(
                terminate(
                    RequiredArgumentBuilder.argument("players", BareTokenCommandArgumentType)
                        .suggests { _, builder -> suggestPlayerNames(builder) },
                    children,
                )
            ).build()

            "radius" -> LiteralArgumentBuilder.literal("radius").then(
                terminate(RequiredArgumentBuilder.argument("blocks", DoubleArgumentType.doubleArg(0.0)), children).also { blocks ->
                    blocks.addChild(
                        RequiredArgumentBuilder.argument("x", DoubleArgumentType.doubleArg())
                            .then(
                                RequiredArgumentBuilder.argument("y", DoubleArgumentType.doubleArg())
                                    .then(terminate(RequiredArgumentBuilder.argument("z", DoubleArgumentType.doubleArg()), children))
                            )
                            .build()
                    )
                }
            ).build()

            "mode" -> LiteralArgumentBuilder.literal("mode")
                .then(terminate(LiteralArgumentBuilder.literal("standard"), children))
                .then(terminate(LiteralArgumentBuilder.literal("immersive"), children))
                .build()

            "forced" -> terminate(LiteralArgumentBuilder.literal("forced"), children)
            "transient" -> terminate(LiteralArgumentBuilder.literal("transient"), children)
            "volume" -> LiteralArgumentBuilder.literal("volume").then(
                terminate(RequiredArgumentBuilder.argument("volume", DoubleArgumentType.doubleArg(0.0, 200.0)), children)
            ).build()

            "looped" -> terminate(LiteralArgumentBuilder.literal("looped"), children)
            "quality" -> LiteralArgumentBuilder.literal("quality").then(
                terminate(
                    RequiredArgumentBuilder.argument("quality", StringArgumentType.word())
                        .suggests { _, builder ->
                            QUALITY_SUGGESTIONS.forEach { builder.suggest(it) }
                            builder.buildFuture()
                        },
                    children,
                )
            ).build()

            else -> error("Unknown fullscreen flag: $name")
        }

    /** Reads [name] from [ctx] if that argument was part of the parsed path, else null. */
    private fun <T : Any> tryArg(ctx: CommandContext<CommandSourceStack>, name: String, type: Class<T>): T? =
        runCatching { ctx.getArgument(name, type) }.getOrNull()

    /** Gathers every flag argument present on the parsed [ctx] path and delegates to [PaperFullscreenCommand.start]. */
    private fun runFullscreenStart(ctx: CommandContext<CommandSourceStack>) {
        val nodeNames = ctx.nodes.map { it.node.name }
        val mode = when {
            "standard" in nodeNames -> "standard"
            "immersive" in nodeNames -> "immersive"
            else -> null
        }
        PaperFullscreenCommand.start(
            ctx.source.sender,
            id = StringArgumentType.getString(ctx, "id"),
            serverScope = tryArg(ctx, "name", String::class.java),
            players = tryArg(ctx, "players", String::class.java),
            radiusBlocks = tryArg(ctx, "blocks", Double::class.javaObjectType)?.toDouble(),
            radiusX = tryArg(ctx, "x", Double::class.javaObjectType)?.toDouble(),
            radiusY = tryArg(ctx, "y", Double::class.javaObjectType)?.toDouble(),
            radiusZ = tryArg(ctx, "z", Double::class.javaObjectType)?.toDouble(),
            mode = mode,
            forced = "forced" in nodeNames,
            transientSession = "transient" in nodeNames,
            volume = tryArg(ctx, "volume", Double::class.javaObjectType)?.let { (it.toFloat() / 200f) },
            loop = "looped" in nodeNames,
            quality = tryArg(ctx, "quality", String::class.java),
        )
    }

    /** Suggests online player names for the last comma-separated fragment of the `players` argument. */
    private fun suggestPlayerNames(builder: SuggestionsBuilder): CompletableFuture<Suggestions> {
        val remaining = builder.remaining
        val prefix = remaining.substringAfterLast(',')
        val before = remaining.substringBeforeLast(',', "").let { if (it.isEmpty()) "" else "$it," }
        (TARGET_SELECTORS + PaperFullscreenCommand.onlinePlayerNames())
            .filter { it.startsWith(prefix, ignoreCase = true) }
            .forEach { builder.suggest(before + it) }
        return builder.buildFuture()
    }

    /** Builds the `/display list [filter] [value] [page]` subcommand with progressive suggestions. */
    private fun listSubCommand(): LiteralArgumentBuilder<CommandSourceStack> {
        val cmd = ListCommand()
        return LiteralArgumentBuilder.literal("list")
            .requires { it.sender.hasPermission(PaperServer.config.permissions.list) }
            .executes { ctx ->
                cmd.execute(ctx.source.sender, arrayOf("list"))
                Command.SINGLE_SUCCESS
            }
            .then(
                RequiredArgumentBuilder.argument("filter", StringArgumentType.word())
                    .suggests { ctx, builder ->
                        cmd.complete(ctx.source.sender, arrayOf("list", builder.remaining))
                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                            .forEach { builder.suggest(it) }
                        builder.buildFuture()
                    }
                    .executes { ctx ->
                        val filter = StringArgumentType.getString(ctx, "filter")
                        cmd.execute(ctx.source.sender, arrayOf("list", filter))
                        Command.SINGLE_SUCCESS
                    }
                    .then(
                        RequiredArgumentBuilder.argument("value", StringArgumentType.word())
                            .suggests { ctx, builder ->
                                val filter = StringArgumentType.getString(ctx, "filter")
                                cmd.complete(ctx.source.sender, arrayOf("list", filter, builder.remaining))
                                    .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                    .forEach { builder.suggest(it) }
                                builder.buildFuture()
                            }
                            .executes { ctx ->
                                val filter = StringArgumentType.getString(ctx, "filter")
                                val value = StringArgumentType.getString(ctx, "value")
                                cmd.execute(ctx.source.sender, arrayOf("list", filter, value))
                                Command.SINGLE_SUCCESS
                            }
                            .then(
                                RequiredArgumentBuilder.argument("page", StringArgumentType.word())
                                    .suggests { ctx, builder ->
                                        val filter = StringArgumentType.getString(ctx, "filter")
                                        val value = StringArgumentType.getString(ctx, "value")
                                        cmd.complete(
                                            ctx.source.sender,
                                            arrayOf("list", filter, value, builder.remaining)
                                        )
                                            .filter { it.startsWith(builder.remaining, ignoreCase = true) }
                                            .forEach { builder.suggest(it) }
                                        builder.buildFuture()
                                    }
                                    .executes { ctx ->
                                        val filter = StringArgumentType.getString(ctx, "filter")
                                        val value = StringArgumentType.getString(ctx, "value")
                                        val page = StringArgumentType.getString(ctx, "page")
                                        cmd.execute(ctx.source.sender, arrayOf("list", filter, value, page))
                                        Command.SINGLE_SUCCESS
                                    }
                            )
                    )
            )
    }
}
