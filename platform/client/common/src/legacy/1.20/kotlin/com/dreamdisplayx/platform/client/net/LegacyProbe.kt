package com.dreamdisplayx.platform.client.net

import com.dreamdisplayx.platform.client.DreamIdentifiers
import com.dreamdisplayx.platform.client.Initializer
import net.fabricmc.fabric.api.networking.v1.FabricPacket
import net.fabricmc.fabric.api.networking.v1.PacketType
import net.minecraft.network.FriendlyByteBuf

/**
 * The only protocol-v1 wire formats left, kept purely to detect outdated peers. Their wire format
 * must never change: v1-only servers answer [Version] with [Premium], v1-only clients send [Version].
 *
 * 1.20.1 has no `CustomPacketPayload`, so both packets implement `FabricPacket`.
 */
object LegacyProbe {
    /** Serverbound v1 handshake; sent by every client so v1-only servers reveal themselves. */
    data class Version(val version: String) : FabricPacket {
        /** Writes the reported mod version. */
        override fun write(buf: FriendlyByteBuf) {
            buf.writeUtf(version)
        }

        /** The registered packet type. */
        override fun getType(): PacketType<Version> = PACKET_ID

        companion object {
            /** Registered packet type for the v1 handshake. */
            val PACKET_ID: PacketType<Version> = PacketType.create(
                DreamIdentifiers.of(Initializer.MOD_ID, "version"),
            ) { buf -> Version(buf.readUtf()) }
        }
    }

    /** Clientbound v1 reply that only v1-only servers still send; its arrival marks the server outdated. */
    data class Premium(val premium: Boolean) : FabricPacket {
        /** Writes the premium flag. */
        override fun write(buf: FriendlyByteBuf) {
            buf.writeBoolean(premium)
        }

        /** The registered packet type. */
        override fun getType(): PacketType<Premium> = PACKET_ID

        companion object {
            /** Registered packet type for the v1 reply. */
            val PACKET_ID: PacketType<Premium> = PacketType.create(
                DreamIdentifiers.of(Initializer.MOD_ID, "premium"),
            ) { buf -> Premium(buf.readBoolean()) }
        }
    }
}