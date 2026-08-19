package com.dreamdisplayx.platform.client.core.modules

import com.dreamdisplayx.api.runtime.module.DreamDisplaysXModule
import com.dreamdisplayx.api.runtime.module.ModuleContext
import com.dreamdisplayx.api.storage.service.keys.StorageServices
import com.dreamdisplayx.platform.client.storage.DefaultStorageProvider

/** Installs the display snapshot registry and client settings store behind the storage service keys. */
object ClientStorageModule : DreamDisplaysXModule {
    /** The ID of this module. */
    override val id: String = "dreamdisplayx:client_storage"

    /** Installs the storage service providers. */
    override fun install(context: ModuleContext) {
        val services = context.services
        val provider = DefaultStorageProvider
        services.register(StorageServices.DISPLAY_STORAGE, provider.displayStorage())
        services.register(StorageServices.CLIENT_SETTINGS, provider.clientSettingsStorage())
    }
}
