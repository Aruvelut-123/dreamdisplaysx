package com.dreamdisplayx.core.protocol.proxy

/**
 * Direction a `dreamdisplayx:proxy` packet travels (registry metadata, never serialized).
 * Separate from [com.dreamdisplayx.api.protocol.model.PacketDirection] which handles `dreamdisplayx:v2`.
 */
enum class ProxyPacketDirection {
    /** Sent by a backend server, handled on the proxy. */
    BACKEND_TO_PROXY,

    /** Sent by the proxy, handled on a backend server. */
    PROXY_TO_BACKEND,

    /** Valid in both directions. */
    BIDIRECTIONAL,
}
