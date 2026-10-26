//? if <1.19 {
package com.dreamdisplays.platform.server.utils

import net.minecraft.Util
import net.minecraft.commands.CommandSourceStack
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerPlayer

fun CommandSourceStack.sendSystemMessage(message: Component) = sendSuccess(message, false)

fun ServerPlayer.sendSystemMessage(message: Component) = sendMessage(message, Util.NIL_UUID)
//?}
