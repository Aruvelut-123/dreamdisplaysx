package com.dreamdisplayx.platform.server.registrar

import com.mojang.brigadier.LiteralMessage
import com.mojang.brigadier.StringReader
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.exceptions.SimpleCommandExceptionType
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
//? if >=1.20.5 {
import net.fabricmc.fabric.api.command.v2.ArgumentTypeRegistry.registerArgumentType
//?}
//? if >=1.19 {
import net.minecraft.commands.synchronization.ArgumentTypeInfos
import net.minecraft.commands.synchronization.SingletonArgumentInfo
import net.minecraft.core.Registry
import net.minecraft.core.registries.BuiltInRegistries
//?}
//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Space-delimited unquoted token for bare selectors, URLs in `/display fullscreen` commands.
 */
object BareTokenArgumentType : ArgumentType<String> {
    private val MISSING = SimpleCommandExceptionType(LiteralMessage("Expected a value."))
    val ID: Identifier =
        //? if >=1.21 {
        Identifier.fromNamespaceAndPath("dreamdisplayx", "bare_token")
        //?} else
        /*Identifier("dreamdisplayx", "bare_token")*/

    @Suppress("UNCHECKED_CAST")
    val NETWORK_FALLBACK: ArgumentType<Any> = StringArgumentType.greedyString() as ArgumentType<Any>

    override fun parse(reader: StringReader): String {
        val start = reader.cursor
        while (reader.canRead() && reader.peek() != ' ') reader.skip()
        if (reader.cursor == start) throw MISSING.create()
        return reader.string.substring(start, reader.cursor)
    }
}

/**
 * Registers [BareTokenArgumentType]'s sync info via `Fabric` API's public `ArgumentTypeRegistry`.
 *
 * 1.18.x has no `ArgumentTypeInfos` / `SingletonArgumentInfo` at all, so nothing is registered
 * there: [com.dreamdisplayx.platform.server.mixins.BareTokenArgumentTypeSyncMixin] already swaps the
 * type for [BareTokenArgumentType.NETWORK_FALLBACK] before the tree reaches a client, so a client
 * never has to resolve the custom type to begin with.
 */
@FabricOnly
object FabricBareTokenArgumentType {
    private var registered = false

    /** Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        //? if >=1.20.5 {
        val info = SingletonArgumentInfo.contextFree { BareTokenArgumentType }
        registerArgumentType(
            BareTokenArgumentType.ID, BareTokenArgumentType::class.java, info,
        )
        //?} else
        /*Unit*/
    }
}

/**
 * Registers [BareTokenArgumentType]'s sync via `NeoForge` reflection. Same 1.18.x caveat as
 * [FabricBareTokenArgumentType]: nothing to register before `ArgumentTypeInfos` exists.
 */
@NeoForgeOnly
object NeoForgeBareTokenArgumentType {
    private var registered = false

    /** Idempotent; call once, early, from mod init. */
    fun register() {
        if (registered) return
        registered = true
        //? if >=1.20.5 {
        val info = SingletonArgumentInfo.contextFree { BareTokenArgumentType }
        val field = ArgumentTypeInfos::class.java.getDeclaredField("BY_CLASS")
        field.isAccessible = true
        val byClass = field.get(null)!!
        byClass.javaClass.getMethod("putIfAbsent", Any::class.java, Any::class.java)
            .invoke(byClass, BareTokenArgumentType::class.java, info)
        Registry.register(BuiltInRegistries.COMMAND_ARGUMENT_TYPE, BareTokenArgumentType.ID, info)
        //?} else
        /*Unit*/
    }
}
