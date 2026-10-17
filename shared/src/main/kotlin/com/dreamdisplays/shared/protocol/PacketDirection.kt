package com.dreamdisplays.shared.protocol

import com.dreamdisplays.api.Unstable

/**
 * The direction a packet is allowed to travel. Registry metadata used for send-side
 * validation; never serialized.
 */
@Unstable
enum class PacketDirection {
    /** Sent by the client, handled on the server. */
    CLIENT_TO_SERVER,

    /** Sent by the server, handled on the client. */
    SERVER_TO_CLIENT,

    /** Valid in both directions. */
    BIDIRECTIONAL,
}
