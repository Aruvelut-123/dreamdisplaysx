//? if <1.20.5 {
package com.dreamdisplayx.platform.server.registrar

import com.mojang.brigadier.CommandDispatcher
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.exceptions.CommandSyntaxException
import com.mojang.brigadier.tree.LiteralCommandNode
import io.github.arnodoelinger.platformweaver.PaperOnly
import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.command.BlockCommandSender
import org.bukkit.command.Command
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.plugin.java.JavaPlugin

/** Pre-1.20.6 stand-in for `Paper`'s `CommandSourceStack`: the [sender] and where it stands. */
@PaperOnly
class CommandSourceStack(val sender: CommandSender) {
    val location: Location
        get() = (sender as? Entity)?.location
            ?: (sender as? BlockCommandSender)?.block?.location
            ?: Bukkit.getWorlds().first().spawnLocation
}

/**
 * Pre-1.20.5 stand-in for `paper-api`'s `io.papermc.paper.command.brigadier.Commands`.
 *
 * Both are nothing but generic factories over brigadier's own builders, so forwarding here keeps
 * every `/display` tree source-identical across versions: the command files import `Commands` from
 * the Paper package on 1.20.5+ and resolve this object (same package, no import needed) below it.
 */
@PaperOnly
object Commands {
    fun literal(name: String): LiteralArgumentBuilder<CommandSourceStack> =
        LiteralArgumentBuilder.literal(name)

    fun <T : Any> argument(name: String, type: ArgumentType<T>): RequiredArgumentBuilder<CommandSourceStack, T> =
        RequiredArgumentBuilder.argument(name, type)
}

/** Registers a brigadier tree as a `Bukkit` command on pre-1.20.6 `Paper`. */
@PaperOnly
object LegacyPaperCommands {
    fun register(plugin: JavaPlugin, root: LiteralCommandNode<CommandSourceStack>, description: String) {
        val dispatcher = CommandDispatcher<CommandSourceStack>().also { it.root.addChild(root) }
        val command = object : Command(root.name, description, "/${root.name}", emptyList()) {
            override fun execute(sender: CommandSender, label: String, args: Array<out String>): Boolean {
                try {
                    dispatcher.execute(input(args), CommandSourceStack(sender))
                } catch (e: CommandSyntaxException) {
                    sender.sendMessage(e.message ?: "Invalid command.")
                }
                return true
            }

            override fun tabComplete(sender: CommandSender, alias: String, args: Array<out String>): List<String> {
                val parse = dispatcher.parse(input(args), CommandSourceStack(sender))
                return dispatcher.getCompletionSuggestions(parse).join().list.map { it.text }
            }

            private fun input(args: Array<out String>): String = ([root.name] + args).joinToString(" ")
        }
        plugin.server.commandMap.register(plugin.name.lowercase(), command)
    }
}
//?}
