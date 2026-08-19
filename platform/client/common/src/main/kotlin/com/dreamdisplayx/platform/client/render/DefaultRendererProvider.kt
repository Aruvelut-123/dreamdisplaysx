package com.dreamdisplayx.platform.client.render

import com.dreamdisplayx.api.render.service.DisplayRenderer
import com.dreamdisplayx.api.render.backend.service.RendererProvider

/** Supplies the GPU-backed [DisplayRenderer] used to render registered surfaces. */
object DefaultRendererProvider : RendererProvider {
    override fun create(): DisplayRenderer = DefaultDisplayRenderer()
}
