package com.dreamdisplayx.core.addon

import com.dreamdisplays.api.internal.ServerBackend
import com.dreamdisplays.api.server.DisplayVeto
import com.dreamdisplays.api.server.ServerDisplayEvent
import com.dreamdisplays.api.server.ServerDisplays
import java.util.function.Consumer

/**
 * Upstream's [ServerBackend] on top of a [ServerAddonHost].
 *
 * This is the whole server-side wiring: publish one of these through `ServerHolder.attach` and
 * `com.dreamdisplays.api.server.DreamDisplaysServer` starts answering, so an addon written against upstream's server
 * API works here without a recompile. The loader-specific part is the host, not this class.
 *
 * The two veto families meet in [ServerAddonVetoBridge] rather than here: upstream's `DisplayVeto` is the question
 * itself, while this fork's registry is the place every gate already consults, so the bridge has to publish *into* the
 * registry instead of the backend relaying around it.
 *
 * @param host what this side can actually do
 * @param vetoBridge converts an addon's veto listener into a registry entry
 * @param addonSink where `DreamDisplaysServer.register(addon)` lands; normally the server `AddonManager`'s `register`
 *
 * @since 1.10.x
 */
class ServerAddonBackend(
    private val host: ServerAddonHost,
    private val vetoBridge: ServerAddonVetoBridge,
    private val addonSink: (Any) -> Unit,
) : ServerBackend {

    /** All displays, re-read on every access. */
    override val displays: ServerDisplays get() = ServerAddonDisplaysView(host)

    override fun on(listener: Consumer<ServerDisplayEvent>): AutoCloseable = host.listen(listener)

    override fun onVeto(listener: Consumer<DisplayVeto>): AutoCloseable = vetoBridge.on(listener)

    override fun registerAddon(addon: Any) {
        addonSink(addon)
    }
}