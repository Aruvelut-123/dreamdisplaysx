package com.dreamdisplayx.platform.server.utils

import net.minecraft.network.chat.TextColor

/**
 * Version-neutral `TextColor` parsing.
 *
 * 1.21 turned `TextColor.parseColor(String)` into a `DataResult` so a malformed value can report a
 * failure through the usual result pipeline; 1.20.1 hands back a nullable `TextColor` directly.
 * Both spellings end up as "null when the input is not a colour", which is the only thing the
 * callers here care about.
 */
object TextColorCompat {
    @Suppress("NULLABILITY_MISMATCH_BASED_ON_JAVA_ANNOTATIONS")
    fun parse(value: String): TextColor? =
//? if >=1.21.11 {
        TextColor.parseColor(value).result().orElse(null)
//?} else
        /*TextColor.parseColor(value)*/
}
