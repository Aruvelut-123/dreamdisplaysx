package com.dreamdisplayx.api.storage.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.runtime.registry.model.serviceKey
import com.dreamdisplayx.api.storage.service.DisplayStorageService

/**
 * Storage service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object StorageServices {
    /** Server-authoritative display snapshot registry. */
    val DISPLAY_STORAGE: ServiceKey<DisplayStorageService> = serviceKey("dreamdisplayx:display_storage")

    /** Client-local per-display settings store. */
    val CLIENT_SETTINGS: ServiceKey<ClientSettingsStorage> = serviceKey("dreamdisplayx:client_settings_storage")
}
