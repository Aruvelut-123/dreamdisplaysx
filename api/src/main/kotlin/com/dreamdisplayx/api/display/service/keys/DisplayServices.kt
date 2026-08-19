package com.dreamdisplayx.api.display.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.service.DisplayService
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey

/**
 * Display service keys. Modules should prefer these keys over ad-hoc class lookups when depending on public display
 * services.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object DisplayServices {
    /** Public display registry and command surface. */
    val DISPLAY: ServiceKey<DisplayService> = serviceKey("dreamdisplayx:display")
}
