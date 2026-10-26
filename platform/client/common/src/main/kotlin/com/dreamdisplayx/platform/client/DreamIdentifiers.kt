package com.dreamdisplayx.platform.client

//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

/**
 * Version-neutral construction of namespaced resource identifiers.
 *
 * Minecraft 1.21 replaced `ResourceLocation`'s `(namespace, path)` constructors with the static
 * `fromNamespaceAndPath` factory and moved the default-namespace helper to `withDefaultNamespace`.
 * 1.20.1 still only has the constructors, so every call site goes through these two functions
 * instead of branching at each of them.
 */
object DreamIdentifiers {
    /** Builds `namespace:path`. */
    fun of(namespace: String, path: String): Identifier = construct(namespace, path)

    /** Builds `minecraft:path`. */
    fun vanilla(path: String): Identifier = construct("minecraft", path)

    /**
     * Parses `namespace:path` (or a bare `path`, which implies `minecraft`).
     *
     * 1.21 replaced the string-splitting `ResourceLocation` constructor with the
     * `Identifier.parse` factory; 1.20.1 only has the constructor that rejects a missing namespace,
     * so the split happens here once instead of at every call site.
     */
    fun parse(value: String): Identifier {
        val separator = value.indexOf(':')
        return if (separator >= 0) construct(value.substring(0, separator), value.substring(separator + 1))
        else construct("minecraft", value)
    }
}

/** Constructs an identifier from a namespace and a path (spelling differs by Minecraft era). */
@Suppress("UNUSED_PARAMETER")
private fun construct(namespace: String, path: String): Identifier =
//? if >=1.21.11 {
    Identifier.fromNamespaceAndPath(namespace, path)
//?} else
/*Identifier(namespace, path)*/