package com.dreamdisplays.api.storage.service.keys

import com.dreamdisplays.api.Unstable
import com.dreamdisplays.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplays.api.runtime.registry.model.ServiceKey
import com.dreamdisplays.api.storage.service.DisplayStorageService

/**
 * Storage service keys.
 *
 * @since 1.8.x
 */
@Unstable
object StorageServices {
    /** Server-authoritative display snapshot registry. */
    val DISPLAY_STORAGE: ServiceKey<DisplayStorageService> =
        ServiceKey("dreamdisplays:display_storage", DisplayStorageService::class.java)

    /** Client-local per-display settings store. */
    val CLIENT_SETTINGS: ServiceKey<ClientSettingsStorage> =
        ServiceKey("dreamdisplays:client_settings_storage", ClientSettingsStorage::class.java)
}
