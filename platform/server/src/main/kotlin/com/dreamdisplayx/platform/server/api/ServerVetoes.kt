package com.dreamdisplayx.platform.server.api

import com.dreamdisplayx.api.display.event.DisplayVeto
import com.dreamdisplayx.api.display.event.DisplayVetoRegistry
import com.dreamdisplayx.platform.server.datatypes.display.DisplayData
import org.slf4j.LoggerFactory
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
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ServerVetoes")

    /** The registry plugins subscribe to. */
    val registry = DisplayVetoRegistry()

    /** Flattens [DisplayData] into the platform-independent [DisplayVeto.Target]. */
    fun DisplayData.toVetoTarget(): DisplayVeto.Target = DisplayVeto.Target(
        id = id,
        ownerId = ownerId,
        width = width,
        height = height,
        url = url,
        name = name,
        virtual = virtual,
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