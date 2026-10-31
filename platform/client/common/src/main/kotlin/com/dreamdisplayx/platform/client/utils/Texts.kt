package com.dreamdisplayx.platform.client.utils

import net.minecraft.ChatFormatting
import net.minecraft.network.chat.MutableComponent
import net.minecraft.network.chat.Style
//? if >=1.19 {
import net.minecraft.network.chat.Component
//?} else
/*import net.minecraft.network.chat.TextColor
import net.minecraft.network.chat.TextComponent
import net.minecraft.network.chat.TranslatableComponent*/

/** Builds a plain text component. `Component`'s static factories only exist on 1.19+. */
fun literalText(text: String): MutableComponent =
    //? if >=1.19 {
    Component.literal(text)
    //?} else
    /*TextComponent(text)*/

/** Builds a translatable component with [args] substituted. */
fun translatableText(key: String, vararg args: Any): MutableComponent =
    //? if >=1.19 {
    Component.translatable(key, *args)
    //?} else
    /*TranslatableComponent(key, *args)*/

/** Builds an empty component. */
fun emptyText(): MutableComponent =
    //? if >=1.19 {
    Component.empty()
    //?} else
    /*TextComponent("")*/

/**
 * Colours a style with a legacy [ChatFormatting]. `Style.withColor(ChatFormatting)` only exists on 1.19+;
 * older versions have to go through [TextColor].
 */
fun Style.withChatColor(color: ChatFormatting): Style =
    //? if >=1.19 {
    withColor(color)
    //?} else
    /*withColor(TextColor.fromLegacyFormat(color))*/

/**
 * Translatable component that falls back to [fallback] (raw English) when the key is missing.
 * `Component.translatableWithFallback` only exists from 1.19.2, so older versions just use the key
 * and let vanilla render the untranslated fallback text.
 */
fun translatableWithFallbackCompat(key: String, fallback: String): MutableComponent =
    //? if >=1.19.2 {
    Component.translatableWithFallback(key, fallback)
    //?} else
    /*TranslatableComponent(key)*/
