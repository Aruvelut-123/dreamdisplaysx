package com.dreamdisplayx.platform.server.api

import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.api.internal.ServerHolder
import com.dreamdisplays.core.addon.AddonManager
import com.dreamdisplays.core.addon.ServerAddonBinder
import com.dreamdisplayx.core.addon.ServerAddonBackend
import com.dreamdisplayx.core.addon.ServerAddonEvents
import com.dreamdisplayx.core.addon.ServerAddonHost
import com.dreamdisplayx.core.addon.ServerAddonHooks
import com.dreamdisplayx.core.addon.ServerAddonVetoBridge

/**
 * Brings the server half of the addon API up and down for one server lifetime.
 *
 * Both server backends call [start] with their own [ServerAddonHost] and [stop] when the server unloads; everything else
 * is shared, so Paper and the vanilla loaders cannot drift apart on ordering:
 *
 *  1. install the host, so a mutation that happens during bring-up still finds it;
 *  2. build the backend around the host and the veto bridge, and attach it — `DreamDisplaysServer` answers from here;
 *  3. start the manager, which enables every addon that was registered while the server was still booting.
 *
 * Ordering matters for the same reason it does on the client: `ServerHolder.detach` only clears `current` and does not
 * drain its pending queue, so starting the manager first would strand registrations made before the attach.
 *
 * The manager lives here rather than in a loader class so a `/reload` keeps its registrations: [stop] disables the
 * addons but the map survives until the process ends.
 *
 * @since 1.10.x
 */
object ServerAddonRuntime {
    /** Logger. */
    private val logger = DreamLog.of("ServerAddonRuntime")

    /** The hosted addons; kept across a reload so the same instances come back up. */
    private val addons: AddonManager by lazy { AddonManager(AddonSide.SERVER, listOf(ServerAddonBinder { ServerAddonEvents.on(it) })) }

    /** Converts addon vetoes into entries of the fork's own registry. */
    private var vetoBridge: ServerAddonVetoBridge? = null

    /** IDs of the addons that are enabled right now; empty while no server runs. */
    val enabledIds: Set<String> get() = addons.enabledIds

    /**
     * Wires [host] and enables the addons.
     *
     * Also installs the [ServerVetoes.PositionResolver] the matching loader supplies, so a veto target carries the place
     * the display stands without `ServerVetoes` having to know any loader's position type.
     */
    fun start(host: ServerAddonHost, resolver: ServerVetoes.PositionResolver) {
        ServerVetoes.positionResolver = resolver
        ServerAddonHooks.install(host)
        val bridge = ServerAddonVetoBridge(host) { host.vetoes() }
        vetoBridge = bridge
        ServerHolder.attach(ServerAddonBackend(host, bridge, addons::register))
        addons.start()
        logger.info("Dream Displays server addon API attached ({} addons enabled).", addons.enabledIds.size)
    }

    /** Disables the addons and withdraws the API, so a late caller sees `DreamDisplaysServer.isAvailable == false`. */
    fun stop() {
        addons.stop()
        vetoBridge?.clear()
        vetoBridge = null
        ServerHolder.detach()
        ServerAddonHooks.install(null)
        ServerAddonEvents.clear()
        ServerVetoes.positionResolver = null
        logger.info("Dream Displays server addon API detached.")
    }
}