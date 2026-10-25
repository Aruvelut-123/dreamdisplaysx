package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.shared.capability.ServerFeature
import com.dreamdisplays.core.protocol.common.packets.ClientHello
import com.dreamdisplays.core.protocol.common.packets.ServerHello
import com.dreamdisplays.core.protocol.common.hasFeature
import com.dreamdisplays.media.player.nativebridge.NativeMedia
import com.dreamdisplays.platform.client.net.ProtocolRouter
import com.dreamdisplays.util.GeneralUtil
import org.slf4j.LoggerFactory

/**
 * Default [CapabilityNegotiationService]. Local capabilities are probed once via the [ClientCapabilityDetector] and cached
 * for the process lifetime.
 */
class DefaultCapabilityNegotiationService(
    private val detector: ClientCapabilityDetector,
) : CapabilityNegotiationService {
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedLocal: ClientHello? = null

    override val localCapabilities: ClientHello
        get() {
            cachedLocal?.let { return it }
            // Sample before detecting: a probe finishing mid-detect must not get a "probing" hello cached
            val final = NativeMedia.probesFinished
            return detector.detect().copy(modVersion = GeneralUtil.getModVersion())
                .also { if (final) cachedLocal = it }
        }

    @Volatile
    override var serverCapabilities: ServerHello? = null; private set

    override val isNegotiated: Boolean get() = serverCapabilities != null

    override fun advertise() {
        runCatching { ProtocolRouter.sendV2(localCapabilities) }
            .onFailure { e -> logger.error("Unable to send v2 hello.", e) }

        runCatching { ProtocolRouter.sendLegacyProbe(localCapabilities.modVersion) }
            .onFailure { logger.debug("v1 probe not deliverable.", it) }
    }

    override fun onServerCapabilities(capabilities: ServerHello) {
        serverCapabilities = capabilities
    }

    override fun isFeatureEnabled(feature: ServerFeature): Boolean =
        serverCapabilities?.hasFeature(feature) == true
}
