package com.dreamdisplays.platform.client.core

import com.dreamdisplays.core.runtime.DreamDisplaysModule
import com.dreamdisplays.core.runtime.ServiceRegistry
import com.dreamdisplays.api.internal.ApiHolder
import com.dreamdisplays.api.addon.AddonSide
import com.dreamdisplays.core.addon.AddonManager
import com.dreamdisplays.core.addon.ClientAddonBinder
import com.dreamdisplays.core.runtime.DefaultApiBackend
import com.dreamdisplays.core.runtime.DefaultRuntime
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Default [ClientApplication]: a module host over a [ClientContext]. Modules are installed in dependency order and
 * torn down in reverse on stop.
 */
class DefaultClientApplication(override val context: ClientContext) : ClientApplication {
    private val runtime = DefaultRuntime(context)

    private val addons = AddonManager(
        AddonSide.CLIENT,
        [ClientAddonBinder { ApiHolder.current!!.displays }],
    )
    private val clientModules = LinkedHashMap<String, ClientModule>()
    private val listeners = CopyOnWriteArrayList<(ClientLifecycleEvent) -> Unit>()

    @Volatile
    private var started = false

    override val services: ServiceRegistry; get() = runtime.services

    override val registeredModuleIds: Set<String>; get() = runtime.registeredModuleIds

    override val installedModuleIds: Set<String>; get() = runtime.installedModuleIds

    @Synchronized
    override fun registerModule(module: DreamDisplaysModule) {
        if (module is ClientModule) {
            require(module.id !in clientModules) { "Client module '${module.id}' is already registered." }
            clientModules[module.id] = module
        }
        runtime.registerModule(module)
    }

    @Synchronized
    override fun start() {
        if (started) return
        emit(ClientLifecycleEvent.Initializing)
        runCatching { runtime.start() }
            .onFailure {
                runtime.stop()
                throw it
            }
        started = true
        val backend = DefaultApiBackend(services, ClientDisplayBudget, addons)
        ApiHolder.attach(backend)
        addons.start()
        emit(ClientLifecycleEvent.Ready)
    }

    @Synchronized
    override fun stop() {
        if (!started) return
        emit(ClientLifecycleEvent.ShuttingDown)
        addons.stop()
        ApiHolder.detach()
        runtime.stop()
        started = false
    }

    override fun emit(event: ClientLifecycleEvent) {
        listeners.forEach { it(event) }
        installedModuleIds.forEach { clientModules[it]?.onEvent(event) }
    }

    override fun onEvent(listener: (ClientLifecycleEvent) -> Unit): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }
}
