package com.dreamdisplayx.api.protocol

/** Major wire generations for the envelope transport. */
object ProtocolGeneration {
    /** Original single-packet envelope generation. */
    const val V2 = 2

    /** Versioned, batch-capable envelope generation. */
    const val V3 = 3

    /** Generation implemented by this release. */
    const val CURRENT = V3
}
