package com.dreamdisplayx.platform.server.listeners

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.platform.server.ModLoaderOnly
import com.dreamdisplayx.core.protocol.common.packets.RemoteControlOpen
import com.dreamdisplayx.platform.server.datatypes.display.VanillaDisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.utils.RegionUtil
import com.dreamdisplayx.platform.server.utils.net.VanillaNetworking
import io.github.arnodoelinger.platformweaver.FabricOnly
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.network.chat.Component
import net.minecraft.server.level.ServerLevel
import net.minecraft.server.level.ServerPlayer
import net.minecraft.world.InteractionHand
import net.minecraft.world.InteractionResult
import net.minecraft.world.item.Items
import net.fabricmc.fabric.api.event.player.UseBlockCallback
import net.fabricmc.fabric.api.event.player.UseItemCallback
import net.neoforged.bus.api.SubscribeEvent
import net.neoforged.neoforge.event.entity.player.PlayerInteractEvent

/** Experimental cross-loader remote-control stick behavior. */
@ModLoaderOnly
@DreamDisplaysXUnstableApi
object VanillaRemoteControlListener {
    fun handle(player: ServerPlayer, level: ServerLevel): Boolean {
        val stack = player.mainHandItem
        if (stack.item != Items.STICK) return false
        val linked = RemoteControlItemAdapter.readLinked(stack)
        if (player.isShiftKeyDown) {
            val display = linked?.let { DisplayManager.getDisplayData(it) as? VanillaDisplayData } ?: return true
            VanillaNetworking.adapter.sendV2(listOf(player), RemoteControlOpen(display.id, display.name ?: display.id.toString().take(8)))
            return true
        }
        val display = target(player, level) ?: return false
        RemoteControlItemAdapter.writeLinked(stack, display.id, "Display Remote — ${display.name ?: display.id.toString().take(8)}")
        player.sendSystemMessage(Component.literal("Remote linked to display ${display.name ?: display.id.toString().take(8)}."))
        return true
    }

    private fun target(player: ServerPlayer, level: ServerLevel): VanillaDisplayData? {
        val origin = player.getEyePosition(1.0f)
        val end = origin.add(player.lookAngle.scale(64.0))
        val worldKey = RegionUtil.getLevelKey(level)
        return DisplayManager.getDisplays().asSequence().filterIsInstance<VanillaDisplayData>()
            .filter { !it.virtual && it.worldKey == worldKey }
            .mapNotNull { display -> display.box.clip(origin, end).orElse(null)?.let { display to origin.distanceToSqr(it) } }
            .minByOrNull { it.second }?.first
    }
}

/** Fabric adapters for block and air right-clicks. */
@FabricOnly
@ModLoaderOnly
object FabricVanillaRemoteControlListener {
    fun register() {
        UseBlockCallback.EVENT.register { player, world, hand, _ ->
            if (hand != InteractionHand.MAIN_HAND || player !is ServerPlayer || world !is ServerLevel) InteractionResult.PASS
            else if (VanillaRemoteControlListener.handle(player, world)) InteractionResult.SUCCESS else InteractionResult.PASS
        }
        // 1.21.1's Fabric API still returns `InteractionResultHolder<ItemStack>` here; 1.21.2+
        // removed `TypedActionResult`, so those versions take the plain `InteractionResult`.
        //? if <1.21.2 {
        UseItemCallback.EVENT.register { player, world, hand ->
            val stack = player.getItemInHand(hand)
            if (hand != InteractionHand.MAIN_HAND || player !is ServerPlayer || world !is ServerLevel) net.minecraft.world.InteractionResultHolder.pass(stack)
            else if (VanillaRemoteControlListener.handle(player, world)) net.minecraft.world.InteractionResultHolder.success(stack) else net.minecraft.world.InteractionResultHolder.pass(stack)
        }
        //?} else
        /*
        UseItemCallback.EVENT.register { player, world, hand ->
            if (hand != InteractionHand.MAIN_HAND || player !is ServerPlayer || world !is ServerLevel) InteractionResult.PASS
            else if (VanillaRemoteControlListener.handle(player, world)) InteractionResult.SUCCESS else InteractionResult.PASS
        }
        */
        //?}
    }
}

/** NeoForge adapters for block and air right-clicks. */
@NeoForgeOnly
@ModLoaderOnly
object NeoForgeVanillaRemoteControlListener {
    @SubscribeEvent fun onBlock(event: PlayerInteractEvent.RightClickBlock) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val level = event.level as? ServerLevel ?: return
        if (VanillaRemoteControlListener.handle(player, level)) event.cancellationResult = InteractionResult.SUCCESS
    }

    @SubscribeEvent fun onItem(event: PlayerInteractEvent.RightClickItem) {
        if (event.hand != InteractionHand.MAIN_HAND) return
        val player = event.entity as? ServerPlayer ?: return
        val level = event.level as? ServerLevel ?: return
        if (VanillaRemoteControlListener.handle(player, level)) event.cancellationResult = InteractionResult.SUCCESS
    }
}
