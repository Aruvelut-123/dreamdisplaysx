package com.dreamdisplayx.platform.client

import com.terraformersmc.modmenu.api.ConfigScreenFactory
import com.terraformersmc.modmenu.api.ModMenuApi
import net.minecraft.client.gui.screens.Screen
import org.slf4j.LoggerFactory

/**
 * ModMenu integration (soft dependency). When ModMenu is installed, this provides a "Configure"
 * button. If Cloth Config is also on the classpath, the button opens a Cloth Config screen;
 * otherwise the button is not shown (ModMenu gets a null factory).
 *
 * Both ModMenu and Cloth Config are optional: ModMenu is loaded through the `modmenu` entrypoint,
 * and Cloth Config is detected via reflection at runtime so no class loading errors occur.
 */
class ModMenuIntegration : ModMenuApi {

    private companion object {
        private val logger = LoggerFactory.getLogger("DreamDisplaysX/ModMenu")
        private const val CLOTH_CONFIG_CLASS = "me.shedaniel.clothconfig2.api.ConfigBuilder"

        /** Whether Cloth Config is available at runtime. */
        private val clothConfigAvailable: Boolean by lazy {
            try {
                Class.forName(CLOTH_CONFIG_CLASS)
                true
            } catch (_: ClassNotFoundException) {
                logger.info("Cloth Config not found — ModMenu config button will be hidden.")
                false
            }
        }
    }

    override fun getModConfigScreenFactory(): ConfigScreenFactory<*> {
        return if (clothConfigAvailable) {
            ConfigScreenFactory { parent: Screen? -> ClothConfigScreenProvider.create(parent) }
        } else {
            // Return null factory — ModMenu skips the config button entirely
            ConfigScreenFactory { null }
        }
    }
}