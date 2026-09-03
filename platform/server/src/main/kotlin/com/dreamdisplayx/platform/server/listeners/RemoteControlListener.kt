package com.dreamdisplayx.platform.server.listeners

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.platform.server.PaperServer
import com.dreamdisplayx.platform.server.datatypes.display.PaperDisplayData
import com.dreamdisplayx.platform.server.managers.DisplayManager
import com.dreamdisplayx.platform.server.utils.net.PaperV3Networking
import com.dreamdisplayx.core.protocol.common.packets.RemoteControlOpen
import io.github.arnodoelinger.platformweaver.PaperOnly
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.NamespacedKey
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.block.Action
import org.bukkit.event.player.PlayerInteractEvent
import org.bukkit.inventory.EquipmentSlot
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataType
import org.bukkit.util.Vector
import java.util.UUID

/** Experimental Paper remote-control stick binding and activation. */
@PaperOnly
@DreamDisplaysXUnstableApi
class RemoteControlListener(private val plugin: PaperServer) : Listener {
    private val key = NamespacedKey(plugin, "remote_display_id")

    @EventHandler
    fun onInteract(event: PlayerInteractEvent) {
        if (event.hand != EquipmentSlot.HAND) return
        if (event.action != Action.RIGHT_CLICK_AIR && event.action != Action.RIGHT_CLICK_BLOCK) return
        val player = event.player
        val item = player.inventory.itemInMainHand
        if (!isStick(item)) return

        val linked = linkedId(item)
        if (player.isSneaking && linked != null) {
            val display = DisplayManager.getDisplayData(linked) as? PaperDisplayData ?: run {
                player.sendMessage(Component.text("The linked display no longer exists."))
                return
            }
            event.isCancelled = true
            PaperV3Networking.send(listOf(player), RemoteControlOpen(display.id, display.name ?: display.id.toString().take(8)))
            return
        }

        if (player.isSneaking) return
        val display = target(player) ?: return
        event.isCancelled = true
        bind(item, display)
        player.sendMessage(Component.text("Remote linked to display ${display.name ?: display.id.toString().take(8)}."))
    }

    private fun isStick(item: ItemStack): Boolean = item.type == Material.STICK

    private fun linkedId(item: ItemStack): UUID? = item.itemMeta?.persistentDataContainer?.get(key, PersistentDataType.STRING)
        ?.let { runCatching { UUID.fromString(it) }.getOrNull() }

    private fun bind(item: ItemStack, display: PaperDisplayData) {
        val meta = item.itemMeta ?: return
        meta.persistentDataContainer.set(key, PersistentDataType.STRING, display.id.toString())
        meta.displayName(Component.text("Display Remote — ${display.name ?: display.id.toString().take(8)}"))
        meta.setEnchantmentGlintOverride(true)
        item.itemMeta = meta
    }

    private fun target(player: Player): PaperDisplayData? {
        val eye = player.eyeLocation
        val origin = eye.toVector()
        val direction = eye.direction.normalize()
        return DisplayManager.getDisplays().asSequence()
            .filterIsInstance<PaperDisplayData>()
            .filter { !it.virtual && it.pos1.world == eye.world }
            .mapNotNull { display ->
                val hit = display.box.rayTrace(origin, direction, 64.0) ?: return@mapNotNull null
                display to origin.distanceSquared(hit.hitPosition)
            }
            .minByOrNull { it.second }
            ?.first
    }
}
