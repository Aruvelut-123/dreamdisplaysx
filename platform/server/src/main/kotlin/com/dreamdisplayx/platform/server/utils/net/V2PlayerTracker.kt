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

    /** Returns a snapshot for diagnostics. */
    fun snapshot(): Map<UUID, ClientHello> = players.toMap()

    /** Drops per-player state on disconnect. */
    fun clear(uuid: UUID) { players.remove(uuid) }
}
