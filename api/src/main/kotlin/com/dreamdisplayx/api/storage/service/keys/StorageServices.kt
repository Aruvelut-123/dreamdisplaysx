package com.dreamdisplayx.api.storage.service.keys

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplayx.api.runtime.registry.model.ServiceKey
import com.dreamdisplayx.api.storage.service.DisplayStorageService

/**
 * Storage service keys.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
object StorageServices {
    /** Server-authoritative display snapshot registry. */
    val DISPLAY_STORAGE: ServiceKey<DisplayStorageService> =
        ServiceKey("dreamdisplayx:display_storage", DisplayStorageService::class.java)

    /** Client-local per-display settings store. */
    val CLIENT_SETTINGS: ServiceKey<ClientSettingsStorage> =
        ServiceKey("dreamdisplayx:client_settings_storage", ClientSettingsStorage::class.java)
}
