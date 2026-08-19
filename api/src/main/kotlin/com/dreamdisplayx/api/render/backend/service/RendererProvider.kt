package com.dreamdisplayx.api.render.backend.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.render.service.DisplayRenderer

/**
 * Supplies the [DisplayRenderer] runtime renders registered surfaces with, so module
 * installers depend on this contract instead of the concrete renderer implementation in the platform module.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
fun interface RendererProvider {
    /** Creates the renderer instance. */
    fun create(): DisplayRenderer
}
