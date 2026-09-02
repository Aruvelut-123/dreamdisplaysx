package com.dreamdisplayx.platform.client

import com.dreamdisplayx.api.media.audio.model.AcousticQuality
import org.tomlj.Toml
import org.tomlj.TomlParseResult
import org.tomlj.TomlTable
import java.io.File
import java.nio.file.Files
import kotlin.math.roundToInt

/**
 * Client configuration persisted to `config.toml` in the mod's config directory.
 *
 * `TomlWriter` produces standard TOML, which other config editors can
 * parse. A legacy `config.yml` file, if present, is read once and merged into `config.toml`, then
 * deleted — so existing installs keep their settings without keeping the old YAML format around.
 */
class Config(private val baseDir: File) {
    /** The backing `config.toml` file on disk. */
    private val file = File(baseDir, "config.toml")

    /** Legacy `config.yml` migrated on first load, then removed. */
    private val legacyFile = File(baseDir, "config.yml")

    /** Whether to mute all displays while the game window is not focused. */
    var muteOnAltTab: Boolean = false

    /** Default render distance for new displays, in blocks (snapped to a multiple of 16). */
    var defaultDistance: Int = 96

    /** Default volume for new displays, in range `0.0`..`1.0`. */
    var defaultDisplayVolume: Double = 0.5

    /** Whether displays are enabled at all. */
    var displaysEnabled: Boolean = true

    /** Whether Dream DisplaysX HUD overlays are rendered during replay/export. */
    var flashbackRenderHud: Boolean = true

    /** Whether world displays are rendered during replay/export. */
    var flashbackRenderDisplays: Boolean = true

    /** Global multiplier applied to every display's audio output. */
    var globalAudioMultiplier: Double = 1.0
        set(value) {
            field = value.coerceIn(0.0, 2.0)
            System.setProperty("dreamdisplayx.audio.globalMultiplier", field.toString())
        }

    /** Whether to use hardware-accelerated video decoding (libvlc auto-detects). */
    var useHwAccel: Boolean = true

    /**
     * Whether to prefer 60 fps streams when the video offers them (e.g. Bilibili 1080p60).
     * Videos without a 60 fps variant still play at their native framerate. Toggle via `prefer-fps60`.
     */
    var preferFps60: Boolean = true

    /** 3D acoustics rendering tier applied to every display's audio (`off` / `basic` / `advanced` / `ultra`). */
    var audioAcoustics: AcousticQuality = AcousticQuality.ADVANCED

    /** Output profile for spatialized audio: `true` renders binaural for headphones, `false` a plain stereo pan for speakers. */
    var audioBinauralOutput: Boolean = true

    /**
     * Preferred Bilibili CDN mirror, or `"auto"` to select by bandwidth probe at playback time.
     * See [com.dreamdisplayx.media.player.cdn.BilibiliCdnMirror] for the known mirror hostnames
     * (`"upos-sz-mirrorcos.bilivideo.com"`, `"upos-sz-mirrorhw.bilivideo.com"`, ...) and the
     * special values `"BASE_URL"` / `"BACKUP_URL"`.
     */
    var bilibiliCdnMirror: String = "auto"

    init {
        load()
    }

    /** Re-reads values from disk, replacing any in-memory state. */
    fun reload() = load()

    /**
     * Loads the configuration from disk, applying default values for missing or malformed entries.
     * A legacy `config.yml` is migrated into `config.toml` (and removed) if the TOML does not exist yet.
     * If neither file exists, a `config.toml` is created with default values.
     */
    private fun load() {
        migrateLegacyYaml()
        if (!file.exists()) {
            save(); return
        }
        val t = runCatching { Toml.parse(file.toPath()) }.getOrNull()
        if (t == null || !t.hasErrors()) {
            readToml(t)
        } else {
            save()
        }
    }

    /**
     * Migrates a legacy `config.yml` into `config.toml` when the TOML doesn't exist yet, then deletes
     * the YAML. When a `config.toml` already exists, any stale `config.yml` is simply removed (the TOML
     * is authoritative). This keeps config editors on one canonical TOML file.
     */
    private fun migrateLegacyYaml() {
        if (!legacyFile.exists()) return
        if (!file.exists()) {
            val yaml = parseLegacyYaml(legacyFile)
            readLegacy(yaml)
            save()
        }
        runCatching { Files.delete(legacyFile.toPath()) }
    }

    /** Parses the old flat `key: value` YAML into a map, tolerating quotes around values. */
    private fun parseLegacyYaml(f: File): Map<String, String> = runCatching {
        f.readLines()
            .map { it.trim() }
            .filter { it.isNotEmpty() && !it.startsWith('#') }
            .mapNotNull { line ->
                val colon = line.indexOf(':')
                if (colon < 0) null
                else line.substring(0, colon).trim() to
                        line.substring(colon + 1).trim().removeSurrounding("'").removeSurrounding("\"")
            }
            .toMap()
    }.getOrDefault(emptyMap())

    /** Applies the migrated YAML values onto the in-memory defaults before the TOML is written. */
    private fun readLegacy(data: Map<String, String>) {
        muteOnAltTab = data["mute-on-alt-tab"]?.toBooleanStrictOrNull() ?: muteOnAltTab
        data["default-render-distance"]?.toIntOrNull()?.let { defaultDistance = ((it / 16.0).roundToInt().coerceIn(2, 12)) * 16 }
        data["default-default-display-volume"]?.toDoubleOrNull()?.let { defaultDisplayVolume = it }
        data["displays-enabled"]?.toBooleanStrictOrNull()?.let { displaysEnabled = it }
        data["use-hw-accel"]?.toBooleanStrictOrNull()?.let { useHwAccel = it }
        data["prefer-fps60"]?.toBooleanStrictOrNull()?.let { preferFps60 = it }
        data["audio-acoustics"]?.let { token ->
            AcousticQuality.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }?.let { audioAcoustics = it }
        }
        data["bilibili-cdn-mirror"]?.let { bilibiliCdnMirror = it }
        when (data["audio-output-profile"]?.lowercase()) {
            "speakers" -> audioBinauralOutput = false
            "headphones", "auto" -> audioBinauralOutput = true
        }
    }

    /** Applies values from the parsed TOML table (null / wrong-typed entries fall back to defaults). */
    private fun readToml(t: TomlTable?) {
        muteOnAltTab = t?.getBoolean("mute-on-alt-tab") ?: muteOnAltTab
        t?.getLong("default-render-distance")?.let { raw ->
            defaultDistance = ((raw.toInt() / 16.0).roundToInt().coerceIn(2, 12)) * 16
        }
        t?.getDouble("default-display-volume")?.let { defaultDisplayVolume = it }
        displaysEnabled = t?.getBoolean("displays-enabled") ?: displaysEnabled
        flashbackRenderHud = t?.getBoolean("flashback-render-hud") ?: flashbackRenderHud
        flashbackRenderDisplays = t?.getBoolean("flashback-render-displays") ?: flashbackRenderDisplays
        t?.getDouble("global-audio-multiplier")?.let { globalAudioMultiplier = it.coerceIn(0.0, 2.0) }
        useHwAccel = t?.getBoolean("use-hw-accel") ?: useHwAccel
        preferFps60 = t?.getBoolean("prefer-fps60") ?: preferFps60
        t?.getString("audio-acoustics")?.let { token ->
            AcousticQuality.entries.firstOrNull { it.name.equals(token, ignoreCase = true) }?.let { audioAcoustics = it }
        }
        t?.getString("bilibili-cdn-mirror")?.let { bilibiliCdnMirror = it }
        when (t?.getString("audio-output-profile")?.lowercase()) {
            "speakers" -> audioBinauralOutput = false
            "headphones", "auto" -> audioBinauralOutput = true
        }
        // Expose client-owned runtime options to the media player without a compile-time client dependency.
        globalAudioMultiplier = globalAudioMultiplier
        System.setProperty("dreamdisplayx.stream.preferFps60", preferFps60.toString())
        System.setProperty("dreamdisplayx.flashback.renderHud", flashbackRenderHud.toString())
        System.setProperty("dreamdisplayx.flashback.renderDisplays", flashbackRenderDisplays.toString())
    }

    /**
     * Declarative list of every editable setting, so the config screen can render controls (and their
     * comments) from this definition instead of hand-writing a row per field.
     */
    fun configEntries(): List<ConfigEntry<*>> = listOf(
        ConfigEntry(
            "displays-enabled", "Displays enabled",
            "Whether displays are enabled at all.",
            ConfigEntryType.BOOLEAN,
            get = { displaysEnabled },
            apply = { displaysEnabled = it; save() },
        ),
        ConfigEntry(
            "flashback-render-hud", "Flashback HUD rendering",
            "Render Dream DisplaysX HUD overlays during Flashback replay/export.",
            ConfigEntryType.BOOLEAN,
            get = { flashbackRenderHud },
            apply = { flashbackRenderHud = it; save() },
        ),
        ConfigEntry(
            "flashback-render-displays", "Flashback display rendering",
            "Render world displays during Flashback replay/export.",
            ConfigEntryType.BOOLEAN,
            get = { flashbackRenderDisplays },
            apply = { flashbackRenderDisplays = it; save() },
        ),
        ConfigEntry(
            "prefer-fps60", "Prefer 60fps",
            "Prefer 60 fps streams when the video supports them. Videos without a 60fps variant fall back to native framerate.",
            ConfigEntryType.BOOLEAN,
            get = { preferFps60 },
            apply = { preferFps60 = it; save() },
        ),
        ConfigEntry(
            "use-hw-accel", "Hardware acceleration",
            "Whether to use hardware-accelerated video decoding.",
            ConfigEntryType.BOOLEAN,
            get = { useHwAccel },
            apply = { useHwAccel = it; save() },
        ),
        ConfigEntry(
            "mute-on-alt-tab", "Mute on alt-tab",
            "Mute all displays while the game window is not focused.",
            ConfigEntryType.BOOLEAN,
            get = { muteOnAltTab },
            apply = { muteOnAltTab = it; save() },
        ),
        ConfigEntry(
            "audio-output-profile", "Binaural audio",
            "Render binaural audio for headphones (ON) or a plain stereo pan for speakers (OFF).",
            ConfigEntryType.BOOLEAN,
            get = { audioBinauralOutput },
            apply = { audioBinauralOutput = it; save() },
        ),
        ConfigEntry(
            "default-render-distance", "Default render distance",
            "Default render distance for new displays, in blocks.",
            ConfigEntryType.INT,
            get = { defaultDistance },
            apply = { defaultDistance = it; save() },
        ),
        ConfigEntry(
            "default-display-volume", "Default volume",
            "Default volume for new displays, in range 0.0 to 1.0.",
            ConfigEntryType.DOUBLE,
            get = { defaultDisplayVolume },
            apply = { defaultDisplayVolume = it; save() },
        ),
        ConfigEntry(
            "audio-acoustics", "Audio acoustics",
            "3D acoustics rendering tier applied to every display's audio.",
            ConfigEntryType.ENUM,
            values = AcousticQuality.entries.toList(),
            get = { audioAcoustics },
            apply = { audioAcoustics = it; save() },
        ),
        ConfigEntry(
            "bilibili-cdn-mirror", "Bilibili CDN mirror",
            "Preferred Bilibili CDN mirror host for stream URLs, or `auto` to pick the fastest by bandwidth probe at playback time. Special values: `BASE_URL` (keep API URL), `BACKUP_URL` (use first backup URL).",
            ConfigEntryType.STRING,
            get = { bilibiliCdnMirror },
            apply = { bilibiliCdnMirror = it; save() },
        ),
    )

    /** Persists the current configuration values to disk as standard TOML. */
    fun save() {
        baseDir.mkdirs()
        globalAudioMultiplier = globalAudioMultiplier
        System.setProperty("dreamdisplayx.flashback.renderHud", flashbackRenderHud.toString())
        System.setProperty("dreamdisplayx.flashback.renderDisplays", flashbackRenderDisplays.toString())
        file.writeText(buildString {
            appendLine("# Dream DisplaysX client configuration")
            appendLine("mute-on-alt-tab = $muteOnAltTab")
            appendLine("default-render-distance = $defaultDistance")
            appendLine("default-display-volume = $defaultDisplayVolume")
            appendLine("displays-enabled = $displaysEnabled")
            appendLine("flashback-render-hud = $flashbackRenderHud")
            appendLine("flashback-render-displays = $flashbackRenderDisplays")
            appendLine("global-audio-multiplier = $globalAudioMultiplier")
            appendLine("use-hw-accel = $useHwAccel")
            appendLine("prefer-fps60 = $preferFps60")
            appendLine("audio-acoustics = \"${audioAcoustics.name.lowercase()}\"")
            appendLine("audio-output-profile = \"${if (audioBinauralOutput) "headphones" else "speakers"}\"")
            appendLine("bilibili-cdn-mirror = \"${tomlQuote(bilibiliCdnMirror)}\"")
        })
    }

    /** Escapes a string for inclusion in a TOML basic string literal. */
    private fun tomlQuote(s: String): String =
        s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")
}
