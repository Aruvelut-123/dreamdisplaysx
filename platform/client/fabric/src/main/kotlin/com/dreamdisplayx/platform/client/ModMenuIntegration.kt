package com.dreamdisplayx.platform.client

import com.dreamdisplayx.platform.client.ui.ClientConfigScreen
import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.minecraft.client.gui.screens.Screen

/**
 * ModMenu integration (soft dependency). When ModMenu is installed, this provides a "Configure"
 * button that opens [ClientConfigScreen] — a Configured-like screen generated from the config entries
 * and their comments. ModMenu is optional: this class is only loaded through the `modmenu` entrypoint.
 */
class ModMenuIntegration : ModMenuApi {
    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return ConfigScreenFactory { parent -> ClientConfigScreen(parent) }
    }
}
