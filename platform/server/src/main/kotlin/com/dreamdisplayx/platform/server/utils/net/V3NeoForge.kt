package com.dreamdisplayx.platform.server.utils.net

import io.github.arnodoelinger.platformweaver.NeoForgeOnly
import net.neoforged.neoforge.network.registration.PayloadRegistrar

/** NeoForge V3 registration seam; loader-specific wiring is kept isolated from shared builds. */
@NeoForgeOnly
object NeoForgeV3Networking {
    fun registerReceivers(registrar: PayloadRegistrar) {
        // V3 payload registration is enabled once the NeoForge-specific network layer is wired.
    }
}
