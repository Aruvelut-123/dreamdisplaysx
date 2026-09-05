package com.dreamdisplayx.platform.server.listeners

import com.dreamdisplayx.platform.server.ModLoaderOnly
import net.minecraft.core.component.DataComponents
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import net.minecraft.world.item.component.CustomData
import java.util.UUID

/**
 * Version-specific adapter for the remote-control stick's item components.
 *
 * Persists the linked display id in the stack's [CustomData] component and applies the "bound" look
 * (custom name + enchantment glint). The vanilla item-component API (`DataComponents`, `CustomData`,
 * `ItemStack.get/set`) is isolated and chisel-gated here so the rest of the remote-control flow can
 * use plain typed calls instead of reflection.
 */
@ModLoaderOnly
internal object RemoteControlItemAdapter {
    private const val LINK_KEY = "dreamdisplayx_remote_display"

    /** Reads the linked display id from [stack], or `null` when it is not a bound remote. */
    fun readLinked(stack: ItemStack): UUID? {
        val tag = stack.get(DataComponents.CUSTOM_DATA)?.copyTag() ?: return null
        if (!tag.contains(LINK_KEY)) return null
        // `CompoundTag.getString` returns `Optional<String>` from 1.21.5 on, but a plain `String`
        // (empty when absent) before that — chisel-gated here.
        val raw =
            //? if >=1.21.11 {
            tag.getString(LINK_KEY).orElse(null)
        //?} else
        /*tag.getString(LINK_KEY)*/
        if (raw.isNullOrEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    /** Binds [stack] to [displayId]: stores the id in [CustomData] and applies the remote look. */
    fun writeLinked(stack: ItemStack, displayId: UUID, label: String) {
        val tag = CompoundTag()
        tag.putString(LINK_KEY, displayId.toString())
        stack.set(DataComponents.CUSTOM_DATA, CustomData.of(tag))
        stack.set(DataComponents.CUSTOM_NAME, Component.literal(label))
        stack.set(DataComponents.ENCHANTMENT_GLINT_OVERRIDE, true)
    }
}
