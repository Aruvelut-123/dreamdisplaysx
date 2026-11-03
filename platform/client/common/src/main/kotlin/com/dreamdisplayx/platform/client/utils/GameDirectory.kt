package com.dreamdisplayx.platform.client.utils

import net.minecraft.client.Minecraft
import java.io.File

/**
 * Resolves the running game's directory once, for both the log mirror and the natives cache.
 *
 * `Minecraft.gameDirectory` is a public field on every Minecraft version this fork builds (it was
 * never turned into a getter), but the field's declaring type differs between the Mojang and MCP
 * mapped targets, so it is read reflectively — the same approach
 * [com.dreamdisplayx.platform.client.render.ComplementaryShaderPatcher] already uses. A missing or
 * unreadable directory is not an error: every caller falls back to its own candidate search.
 */
object GameDirectory {

    /** `<gameDir>/logs`, i.e. the folder that holds `latest.log`. */
    fun logs(): File? = resolve()?.let { File(it, "logs") }

    /** The game directory, or `null` when Minecraft has not initialized one yet. */
    fun resolve(): File? = runCatching {
        val mc = Minecraft.getInstance()
        val accessor = mc.javaClass.methods.firstOrNull { it.name == "gameDirectory" && it.parameterCount == 0 }
        val value = accessor?.invoke(mc)
            ?: runCatching { mc.javaClass.getField("gameDirectory").get(mc) }.getOrNull()
        (value as? File)?.takeIf { it.isDirectory }
    }.getOrNull()
}