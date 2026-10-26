package com.dreamdisplays.platform.client.utils

//? if >=1.21.11 {
import net.minecraft.resources.Identifier
//?} else
/*import net.minecraft.resources.ResourceLocation as Identifier*/

fun identifier(namespace: String, path: String): Identifier =
    //? if >=1.21.1 {
    Identifier.fromNamespaceAndPath(namespace, path)
    //?} else
    /*Identifier(namespace, path)*/

fun vanillaIdentifier(path: String): Identifier = identifier("minecraft", path)
