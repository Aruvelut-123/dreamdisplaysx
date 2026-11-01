package com.dreamdisplays.core.addon

import com.dreamdisplays.api.Display
import com.dreamdisplays.api.Displays
import com.dreamdisplays.api.addon.AddonContext
import com.dreamdisplays.api.lifecycle
import com.dreamdisplays.api.addon.OnDisplay
import com.dreamdisplays.api.addon.OnFailed
import com.dreamdisplays.api.addon.OnPlaying
import com.dreamdisplays.api.display.model.property.DisplayState
import com.dreamdisplays.api.media.model.DreamMediaException
import org.slf4j.LoggerFactory

/** Binds `@OnDisplay`, `@OnPlaying` and `@OnFailed` on the client. A throwing handler is logged and skipped. */
class ClientAddonBinder(private val displays: () -> Displays) : AddonBinder {
    private val logger = LoggerFactory.getLogger(ClientAddonBinder::class.java)

    override fun bind(addon: Any, context: AddonContext) {
        findAnnotated(addon, OnDisplay::class.java).forEach { method ->
            method.requireParameters("OnDisplay", Display::class.java)
            context.own(displays().each { display -> safely(method) { method.call(display) } })
        }
        findAnnotated(addon, OnPlaying::class.java).forEach { method ->
            method.requireParameters("OnPlaying", Display::class.java, DisplayState.Playing::class.java)
            context.own(displays().each { display ->
                context.own(display.lifecycle { onPlaying { state -> safely(method) { method.call(display, state) } } })
            })
        }
        findAnnotated(addon, OnFailed::class.java).forEach { method ->
            method.requireParameters("OnFailed", Display::class.java, DreamMediaException::class.java)
            context.own(displays().each { display ->
                context.own(display.lifecycle { onFailed { error -> safely(method) { method.call(display, error) } } })
            })
        }
    }

    private fun safely(method: AddonMethod, block: () -> Unit) {
        try {
            block()
        } catch (t: Throwable) {
            logger.error("Addon handler {} threw.", method.description, t)
        }
    }
}
