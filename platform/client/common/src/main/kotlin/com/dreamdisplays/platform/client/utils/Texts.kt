package com.dreamdisplays.platform.client.utils

import net.minecraft.network.chat.MutableComponent
//? if >=1.19 {
import net.minecraft.network.chat.Component
//?} else
/*import net.minecraft.network.chat.TextComponent
import net.minecraft.network.chat.TranslatableComponent*/

fun literalText(text: String): MutableComponent =
    //? if >=1.19 {
    Component.literal(text)
    //?} else
    /*TextComponent(text)*/

fun translatableText(key: String, vararg args: Any): MutableComponent =
    //? if >=1.19 {
    Component.translatable(key, *args)
    //?} else
    /*TranslatableComponent(key, *args)*/

fun emptyText(): MutableComponent =
    //? if >=1.19 {
    Component.empty()
    //?} else
    /*TextComponent("")*/
