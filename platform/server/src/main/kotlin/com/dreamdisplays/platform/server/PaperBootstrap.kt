package com.dreamdisplays.platform.server

import io.github.arnodoelinger.platformweaver.PaperOnly
import io.papermc.paper.plugin.bootstrap.BootstrapContext
import io.papermc.paper.plugin.bootstrap.PluginBootstrap
import io.papermc.paper.plugin.bootstrap.PluginProviderContext
import org.bukkit.plugin.java.JavaPlugin

/** Picks which build of the plugin the one `Paper` jar runs. `Paper` only runs on Mojang names (and remaps plugins). */
@PaperOnly
@Suppress("UnstableApiUsage")
class PaperBootstrap : PluginBootstrap {
    override fun bootstrap(context: BootstrapContext) {}

    fun bootstrap(context: PluginProviderContext) {}

    override fun createPlugin(context: PluginProviderContext): JavaPlugin {
        val main = if (isMojangMappedRuntime()) PaperServer::class.java.name else LEGACY_MAIN
        return Class.forName(main, true, javaClass.classLoader).getDeclaredConstructor().newInstance() as JavaPlugin
    }

    private fun isMojangMappedRuntime(): Boolean = runCatching {
        Class.forName("net.minecraft.server.level.ServerPlayer", false, PluginBootstrap::class.java.classLoader)
    }.isSuccess

    private companion object {
        const val LEGACY_MAIN = "com.dreamdisplays.platform.v1_20_1.server.PaperServer"
    }
}
