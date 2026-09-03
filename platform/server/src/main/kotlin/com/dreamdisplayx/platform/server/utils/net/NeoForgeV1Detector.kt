package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.platform.server.utils.MessageUtil
import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.minecraft.server.level.ServerPlayer
import net.neoforged.neoforge.network.registration.PayloadRegistrar
import java.util.concurrent.ConcurrentHashMap

/** Detects legacy v1 payloads on NeoForge without restoring v1 handling. */
@NeoForgeOnly
object NeoForgeV1Detector {
    private val notified = ConcurrentHashMap.newKeySet<java.util.UUID>()

    fun registerReceivers(registrar: PayloadRegistrar) {
        LegacyV1Payload.CHANNELS.forEach { path ->
            val type = LegacyV1Payload.type(path)
            registrar.playBidirectionalCompat(
                type,
                LegacyV1Payload.codec(type),
                { _, context ->
                    val player = context.player() as? ServerPlayer ?: return@playBidirectionalCompat
                    if (notified.add(player.uuid)) {
                        MessageUtil.sendColoredMessage(player, "V1 protocol is not supported anymore. Please update your client.")
                    }
                },
                { _, _ -> },
            )
        }
    }
}
