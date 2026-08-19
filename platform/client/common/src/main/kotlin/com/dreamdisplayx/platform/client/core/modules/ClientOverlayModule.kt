package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.runtime.registry.service.register
import com.dreamdisplayx.platform.client.managers.ClientStateManager
import com.dreamdisplayx.platform.client.overlay.CrosshairPolicy
import com.dreamdisplayx.platform.client.overlay.OverlayManager
import com.dreamdisplayx.platform.client.popout.DefaultPopoutManager
import com.dreamdisplayx.platform.client.popout.PopoutManager
import com.dreamdisplayx.platform.client.ui.FullscreenOverlayManager
import com.dreamdisplayx.platform.client.ui.PipOverlayManager

/** Installs overlay, crosshair, and popout services. */
object ClientOverlayModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_overlay"

    /** Installs the overlay manager, crosshair policy, and popout manager. */
    override fun install(context: ModuleContext) {
        val services = context.services
        services.register<OverlayManager>(PipOverlayManager)
        services.register<CrosshairPolicy>(CrosshairPolicy {
            ClientStateManager.isOnScreen || FullscreenOverlayManager.isImmersiveActive
        })
        services.register<PopoutManager>(DefaultPopoutManager())
    }
}
