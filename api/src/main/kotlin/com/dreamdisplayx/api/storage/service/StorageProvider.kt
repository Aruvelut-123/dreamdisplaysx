package com.dreamdisplayx.api.storage.service

import com.dreamdisplayx.api.DreamDisplaysXUnstableApi
import com.dreamdisplayx.api.display.model.settings.ClientSettingsStorage

/**
 * Supplies the storage backends.
 *
 * @since 1.8.x
 */
@DreamDisplaysXUnstableApi
interface StorageProvider {
    /** The server-authoritative display snapshot registry. */
    fun displayStorage(): DisplayStorageService

    /** The client-local per-display settings store. */
    fun clientSettingsStorage(): ClientSettingsStorage
}
