package com.dreamdisplayx.api.watchparty.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey
import com.dreamdisplayx.api.watchparty.service.WatchPartyService

/**
 * Watch party service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object WatchPartyServices {
    /** Public Watch party session command surface. */
    val WATCH_PARTY: ServiceKey<WatchPartyService> = serviceKey("dreamdisplayx:watch_party")
}
