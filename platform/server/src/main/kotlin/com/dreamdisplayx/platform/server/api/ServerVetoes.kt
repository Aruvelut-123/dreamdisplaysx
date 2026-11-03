package com.dreamdisplayx.platform.server.api

import com.dreamdisplayx.util.log.DreamLog
import com.dreamdisplayx.api.display.event.DisplayVeto
import com.dreamdisplayx.api.display.event.DisplayVetoRegistry
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import java.util.UUID

/**
 * The server-wide [DisplayVeto] registry.
 *
 * Both loader backends (Paper and the vanilla NeoForge / Fabric pair) route their refusable requests
 * through here, so a plugin registers a listener once and it applies on every platform.
 *
 * @since 1.10.x
 */
object ServerVetoes {
    /** Logger. */
    private val logger = DreamLog.of("ServerVetoes")

    /** The registry plugins subscribe to. */
    val registry = DisplayVetoRegistry()

    /**
     * Resolves the world position of a display row.
     *
     * `DisplayData` deliberately carries no coordinates — they live on `PaperDisplayData.pos1` and
     * `VanillaDisplayData.worldKey` + `pos1`, two types that cannot both be referenced from one file
     * here. So each loader installs its own resolver at start-up, and a veto target gets its
     * position back without this file knowing either type.
     */
    fun interface PositionResolver {
        /** The place [data] stands, or `null` when it cannot be resolved. */
        fun resolve(data: DisplayData): DisplayVeto.Position?
    }

    /** The resolver installed by the running loader, or `null`. */
    @Volatile
    var positionResolver: PositionResolver? = null

    /** Flattens [DisplayData] into the platform-independent [DisplayVeto.Target]. */
    fun DisplayData.toVetoTarget(): DisplayVeto.Target = DisplayVeto.Target(
        id = id,
        ownerId = ownerId,
        width = width,
        height = height,
        url = url,
        name = name,
        virtual = virtual,
        position = positionResolver?.resolve(this),
    )

    /**
     * Asks listeners whether [actor] may change [data]'s video link to [url].
     *
     * @return `null` when allowed, otherwise the denial reason (possibly empty).
     */
    fun askSetUrl(data: DisplayData, url: String, lang: String, actor: UUID?): String? {
        if (registry.isEmpty) return null
        return registry.ask(DisplayVeto.SetUrl(data.toVetoTarget(), url, lang, actor)) { failure ->
            logger.error("A display veto listener threw while handling SetUrl.", failure)
        }
    }

    /**
     * Asks listeners whether the not yet registered [data] may be created.
     *
     * @return `null` when allowed, otherwise the denial reason (possibly empty).
     */
    fun askCreate(data: DisplayData, actor: UUID?): String? {
        if (registry.isEmpty) return null
        return registry.ask(DisplayVeto.Create(data.toVetoTarget(), actor)) { failure ->
            logger.error("A display veto listener threw while handling Create.", failure)
        }
    }

    /**
     * Asks listeners whether [actor] may remove [data].
     *
     * @return `null` when allowed, otherwise the denial reason (possibly empty).
     */
    fun askDelete(data: DisplayData, actor: UUID?): String? {
        if (registry.isEmpty) return null
        return registry.ask(DisplayVeto.Delete(data.toVetoTarget(), actor)) { failure ->
            logger.error("A display veto listener threw while handling Delete.", failure)
        }
    }

    /** Drops every listener; called when a server unloads so a reload starts clean. */
    fun clear() {
        registry.clear()
    }
}