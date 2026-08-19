package com.dreamdisplayx.platform.client.storage

import com.dreamdisplayx.api.display.model.settings.ClientSettingsStorage
import com.dreamdisplayx.api.storage.service.DisplayStorageService
import com.dreamdisplayx.api.storage.service.StorageProvider
import com.dreamdisplayx.core.services.DisplayStorage as CoreDisplayStorage

/** Supplies the client's storage backends: the core display snapshot registry and the JSON settings store. */
object DefaultStorageProvider : StorageProvider {
    override fun displayStorage(): DisplayStorageService = CoreDisplayStorage
    override fun clientSettingsStorage(): ClientSettingsStorage = ClientSettingsStore
}
