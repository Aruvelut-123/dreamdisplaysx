package com.dreamdisplayx.api.render.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.render.backend.service.RenderContext
import com.dreamdisplayx.api.render.backend.service.RenderSurface
import com.dreamdisplayx.api.render.model.RenderStats

/**
 * Registry and dispatcher for display render surfaces in one render context.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface DisplayRenderer {
    /** Adds [surface] to the render set. */
    fun register(surface: RenderSurface)

    /** Removes [surface] from the render set. */
    fun unregister(surface: RenderSurface)

    /** Renders all registered visible surfaces with [context]. */
    fun renderAll(context: RenderContext)

    /** Number of currently registered surfaces. */
    val registeredCount: Int

    /** Latest aggregate render / upload statistics. */
    val stats: RenderStats
}
