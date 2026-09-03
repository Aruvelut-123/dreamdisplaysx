package com.dreamdisplayx.platform.server.utils.net

import com.dreamdisplayx.core.protocol.common.packets.ClientHello
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

/** Tracks client capability hello data for diagnostics and scheduling. */
object V2PlayerTracker {
    private val players = ConcurrentHashMap<UUID, ClientHello>()

    /** Records the latest capability hello for [uuid]. */
    fun markV2(uuid: UUID, hello: ClientHello) { players[uuid] = hello }

    /** Returns the capabilities advertised by [uuid]. */
    fun helloOf(uuid: UUID): ClientHello? = players[uuid]

    /** Returns whether [uuid] negotiated the generation-3 transport. */
    fun isV3(uuid: UUID): Boolean =
        (players[uuid]?.generation ?: com.dreamdisplayx.api.protocol.ProtocolGeneration.V2) >=
            com.dreamdisplayx.api.protocol.ProtocolGeneration.V3

    /** Returns a snapshot for diagnostics. */
    fun snapshot(): Map<UUID, ClientHello> = players.toMap()

    /** Drops per-player state on disconnect. */
    fun clear(uuid: UUID) { players.remove(uuid) }
}
