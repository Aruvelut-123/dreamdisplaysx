package com.dreamdisplays.core.runtime

import com.dreamdisplays.api.Unstable

/** API surface passed to modules during install and uninstall. */
@Unstable
interface ModuleContext {
    /** Registry of services available to modules. */
    val services: ServiceRegistry
}
