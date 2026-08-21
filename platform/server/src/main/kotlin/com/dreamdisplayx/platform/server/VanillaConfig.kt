package com.dreamdisplayx.platform.server

import net.minecraft.server.level.ServerPlayer
import org.slf4j.LoggerFactory
import org.tomlj.Toml
import org.tomlj.TomlTable
import java.io.File
import java.nio.file.Files

/**
 * Vanilla (`Fabric` / `NeoForge`) server-side configuration. Parsing and language-loading logic live in
 * [ServerConfigModel]; this class only supplies the classloader resource lookup and data directory.
 *
 * Different from the `Paper` implementation ([Config]) mainly in resource lookup and locale API.
 */
class VanillaConfig(private val configDir: File) {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/ServerConfig")

    private val configFile = File(configDir, "config.toml")

    private val langStore = LanguageStore(
        dataDir = configDir,
        resourceLookup = { VanillaConfig::class.java.classLoader.getResourceAsStream(it) },
        warn = { logger.warn(it) },
    )

    lateinit var language: LanguageSection; private set
    lateinit var settings: SettingsSection; private set
    lateinit var storage: StorageSection; private set
    lateinit var permissions: PermissionsSection; private set
    lateinit var proxy: ProxySection; private set

    val messages get() = langStore.messages
    val languages get() = langStore.languages

    init {
        createDefaultConfig()
        langStore.extractLangFiles(overwrite = true)
        load()
        langStore.loadMessages(logger)
    }

    private fun createDefaultConfig() {
        if (!configDir.exists()) configDir.mkdirs()
        if (!configFile.exists()) {
            val resource = VanillaConfig::class.java.classLoader
                .getResourceAsStream("assets/dreamdisplayx/lang/server/config.toml")
            if (resource != null) {
                resource.use { Files.copy(it, configFile.toPath()) }
            } else {
                logger.error("Could not find bundled config.toml; writing an empty file.")
                configFile.createNewFile()
            }
        } else {
            appendMissingStorageSection()
        }
    }

    /** Older configs predate the `[storage]` section; append the defaults so MySQL switching stays visible. */
    private fun appendMissingStorageSection() {
        val existing = runCatching { Toml.parse(configFile.toPath()) }.getOrNull() ?: return
        if (existing.getTable("storage") != null) return
        val sb = StringBuilder("\n")
        sb.append("[storage]\n")
        sb.append("# Storage type: SQLITE or MYSQL\n")
        sb.append("# If you choose MYSQL, fill in the details below\n")
        sb.append("type = \"SQLITE\"\n")
        sb.append("host = \"localhost\"\n")
        sb.append("port = \"3306\"\n")
        sb.append("database = \"database\"\n")
        sb.append("username = \"username\"\n")
        sb.append("password = \"password\"\n")
        sb.append("table_prefix = \"\"\n")
        sb.append("use_ssl = false\n")
        try {
            configFile.appendText(sb.toString())
            logger.info("Added missing [storage] section to config.toml.")
        } catch (e: Exception) {
            logger.warn("Could not append [storage] section to config.toml.", e)
        }
    }

    private fun load() {
        val t: TomlTable? = runCatching { Toml.parse(configFile.toPath()) }
            .onFailure { logger.error("Failed to parse config.toml.", it) }
            .getOrNull()
        val parsed = parseServerConfig(t)
        language = parsed.language
        settings = parsed.settings
        storage = parsed.storage
        permissions = parsed.permissions
        proxy = parsed.proxy
    }

    fun reload() {
        load()
        langStore.extractLangFiles(overwrite = false)
        langStore.loadMessages(logger)
    }

    fun getMessageForPlayer(player: ServerPlayer?, key: String): Any? {
        val locale = player?.clientInformation()?.language() ?: "en"
        return langStore.getMessage(locale, language.default_language, key)
    }
}

/** Registry name for the selection tool, e.g. `minecraft:diamond_axe`. */
val SettingsSection.selectionMaterialId: String get() = display.selection_material

/** Registry name for the display base, e.g. `minecraft:black_concrete`. */
val SettingsSection.baseMaterialId: String get() = display.base_material
