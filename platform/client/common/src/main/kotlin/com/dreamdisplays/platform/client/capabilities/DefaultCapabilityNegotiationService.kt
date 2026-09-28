package com.dreamdisplays.platform.client.capabilities

import com.dreamdisplays.api.capability.ServerFeature
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
    /** Logger. */
    private val logger = LoggerFactory.getLogger(javaClass)

    @Volatile
    private var cachedLocal: ClientHello? = null

    /**
     * Capability detection is stable for the process lifetime, so it is cached — but only once the background native
     * probe has finished; a hello taken earlier reports the backend as still probing and is re-taken next time.
     */
    override val localCapabilities: ClientHello
        get() {
            cachedLocal?.let { return it }
            // Sample before detecting: a probe finishing mid-detect must not get a "probing" hello cached
            val final = NativeMedia.probesFinished
            return detector.detect().copy(modVersion = GeneralUtil.getModVersion())
                .also { if (final) cachedLocal = it }
        }

    /** Updated as handshake packets arrive; null until the first arrives. */
    @Volatile
    override var serverCapabilities: ServerHello? = null; private set

    /** True, once any server capability information has arrived. */
    override val isNegotiated: Boolean get() = serverCapabilities != null

    /**
     * Starts the handshake: first the blind v2 [ClientHello], then the v1 `version` probe that only
     * v1-only servers answer, so they can be reported as outdated. Order matters — a v2 server must
     * mark the player as v2 before it sees the probe.
     */
    override fun advertise() {
        runCatching { ProtocolRouter.sendV2(localCapabilities) }
            .onFailure { e -> logger.error("Unable to send v2 hello.", e) }

        runCatching { ProtocolRouter.sendLegacyProbe(localCapabilities.modVersion) }
            .onFailure { logger.debug("v1 probe not deliverable.", it) }
    }

    /** Replaces the negotiated [serverCapabilities] snapshot wholesale. */
    override fun onServerCapabilities(capabilities: ServerHello) {
        serverCapabilities = capabilities
    }

    /** True if the negotiated server allows [feature]; false before negotiation completes. */
    override fun isFeatureEnabled(feature: ServerFeature): Boolean =
        serverCapabilities?.hasFeature(feature) == true
}
