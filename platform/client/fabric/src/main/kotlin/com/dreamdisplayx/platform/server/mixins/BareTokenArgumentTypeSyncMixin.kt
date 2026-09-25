package com.dreamdisplayx.platform.server.mixins

import com.dreamdisplayx.platform.server.registrar.BareTokenArgumentType
import com.mojang.brigadier.arguments.ArgumentType
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.tree.ArgumentCommandNode
import org.spongepowered.asm.mixin.Mixin
import org.spongepowered.asm.mixin.injection.At
import org.spongepowered.asm.mixin.injection.Redirect

/** `Fabric` only. `Fabric` 26.3's Mixin variant widened `@Redirect.at` to an array. */
@Suppress("NonJavaMixin")
@Mixin(ArgumentCommandNode::class)
open class BareTokenArgumentTypeSyncMixin {
    @Redirect(
        method = ["createBuilder"],
        //? if >=26.3 {
        at = [At(
            value = "INVOKE",
            target = "Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;" +
                    "argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)" +
                    "Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;",
        )],
        //?} else
        /*at = At(
            value = "INVOKE",
            target = "Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;" +
                    "argument(Ljava/lang/String;Lcom/mojang/brigadier/arguments/ArgumentType;)" +
                    "Lcom/mojang/brigadier/builder/RequiredArgumentBuilder;",
        ),*/
    )
    open fun networkSafeArgumentType(name: String, type: ArgumentType<Any>): RequiredArgumentBuilder<Any, Any> {
        val networkType: ArgumentType<Any> =
            if (type === BareTokenArgumentType) BareTokenArgumentType.NETWORK_FALLBACK else type
        return RequiredArgumentBuilder.argument(name, networkType)
    }
}
