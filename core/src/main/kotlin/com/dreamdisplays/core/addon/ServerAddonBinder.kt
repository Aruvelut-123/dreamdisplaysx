package com.dreamdisplays.core.addon

import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.addon.OnDisplayCreated
import com.dreamdisplays.api.addon.OnDisplayRemoved
import com.dreamdisplays.api.addon.OnUrlChanged
import com.dreamdisplays.api.server.ServerDisplay
import com.dreamdisplays.api.server.ServerDisplayEvent
import org.slf4j.LoggerFactory
import java.util.function.Consumer

/** Binds `@OnDisplayCreated`, `@OnDisplayRemoved` and `@OnUrlChanged` on the server. */
class ServerAddonBinder(private val events: (Consumer<ServerDisplayEvent>) -> AutoCloseable) : AddonBinder {
    private val logger = LoggerFactory.getLogger(ServerAddonBinder::class.java)

    override fun bind(addon: Any, context: AddonContext) {
        val created = findAnnotated(addon, OnDisplayCreated::class.java)
        val removed = findAnnotated(addon, OnDisplayRemoved::class.java)
        val urlChanged = findAnnotated(addon, OnUrlChanged::class.java)
        created.forEach { it.requireParameters("OnDisplayCreated", ServerDisplay::class.java) }
        removed.forEach { it.requireParameters("OnDisplayRemoved", ServerDisplay::class.java) }
        urlChanged.forEach { it.requireParameters("OnUrlChanged", ServerDisplay::class.java, String::class.java) }
        if (created.isEmpty() && removed.isEmpty() && urlChanged.isEmpty()) return

        context.own(events(Consumer { event ->
            when (event) {
                is ServerDisplayEvent.Created -> created.forEach { safely(it) { it.call(event.display) } }
                is ServerDisplayEvent.Removed -> removed.forEach { safely(it) { it.call(event.display) } }
                is ServerDisplayEvent.UrlChanged -> urlChanged.forEach { safely(it) { it.call(event.display, event.url) } }
                is ServerDisplayEvent.Moved, is ServerDisplayEvent.DataChanged -> Unit
            }
        }))
    }

    private fun safely(method: AddonMethod, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("Addon handler {} threw.", method.description, t)
        }
    }
}
