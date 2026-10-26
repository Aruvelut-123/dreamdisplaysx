package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.DreamIdentifiers

//? if >=1.21.11 {
import net.minecraft.resources.Identifier

//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Version-neutral construction of namespaced resource identifiers.
 *
 * Minecraft 1.21 replaced `ResourceLocation`'s `(namespace, path)` constructors with the static
 * `fromNamespaceAndPath` factory and moved the default-namespace helper to `withDefaultNamespace`.
 * Source that targets both eras cannot call either spelling directly, so every call site goes
 * through these two functions instead of branching at each of them.
 */
object DreamIdentifiers {
    /** Builds `namespace:path`. */
    fun of(namespace: String, path: String): Identifier =
        //? if >=1.21.11 {
        DreamIdentifiers.of(namespace, path)
    //?} else
    /*Identifier(namespace, path)*/

    /** Builds `minecraft:path`. */
    fun vanilla(path: String): Identifier =
        //? if >=1.21.11 {
        DreamIdentifiers.vanilla(path)
    //?} else
    /*Identifier(path)*/
}
