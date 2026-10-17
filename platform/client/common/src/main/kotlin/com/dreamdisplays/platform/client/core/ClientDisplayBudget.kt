package com.dreamdisplays.platform.client.core

import com.dreamdisplays.api.DisplayBudget
import com.dreamdisplays.platform.client.Config
import com.dreamdisplays.platform.client.managers.ClientStateManager

/** [DisplayBudget] backed by the client config. */
object ClientDisplayBudget : DisplayBudget {
    override var maxActive: Int
        get() = ClientStateManager.config.maxActiveDisplays
        set(value) {
            val config = ClientStateManager.config
            config.maxActiveDisplays = value.coerceIn(0, maxActiveLimit)
            config.save()
        }

    override val maxActiveLimit: Int get() = Config.MAX_ACTIVE_DISPLAYS
}
