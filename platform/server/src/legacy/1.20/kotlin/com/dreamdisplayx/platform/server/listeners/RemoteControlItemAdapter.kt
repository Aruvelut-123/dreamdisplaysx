package com.dreamdisplayx.platform.server.listeners

import com.dreamdisplayx.platform.server.ModLoaderOnly
import net.minecraft.nbt.CompoundTag
import net.minecraft.network.chat.Component
import net.minecraft.world.item.ItemStack
import java.util.UUID

/**
 * Legacy (1.20.1) variant of the remote-control stick's item adapter.
 *
 * 1.20.1 has no data components: the linked display id lives in the stack's plain `tag`
 * (`CompoundTag`) and the "bound" look is applied through `ItemMeta`-equivalent vanilla NBT
 * (`display.Name`, `Enchantments`). The typed surface stays identical so the rest of the
 * remote-control flow does not need to change.
 */
@ModLoaderOnly
internal object RemoteControlItemAdapter {
    private const val LINK_KEY = "dreamdisplayx_remote_display"

    /** Reads the linked display id from [stack], or `null` when it is not a bound remote. */
    fun readLinked(stack: ItemStack): UUID? {
        val tag = stack.tag ?: return null
        if (!tag.contains(LINK_KEY)) return null
        val raw = tag.getString(LINK_KEY)
        if (raw.isEmpty()) return null
        return runCatching { UUID.fromString(raw) }.getOrNull()
    }

    /** Binds [stack] to [displayId]: stores the id in the stack tag and applies the remote look. */
    fun writeLinked(stack: ItemStack, displayId: UUID, label: String) {
        val tag = stack.orCreateTag
        tag.putString(LINK_KEY, displayId.toString())
        val display = CompoundTag()
        display.putString("Name", Component.Serializer.toJson(Component.literal(label)))
        tag.put("display", display)
        // Enchantment glint override: a level-1 dummy enchantment reproduces the Paper/1.21 look.
        val enchantments = CompoundTag()
        enchantments.putShort("minecraft:unbreaking", 1)
        tag.put("Enchantments", enchantments)
    }
}
