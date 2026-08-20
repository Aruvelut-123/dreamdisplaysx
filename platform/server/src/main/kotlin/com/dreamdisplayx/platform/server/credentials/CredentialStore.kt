package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.util.json.DreamJson
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import org.slf4j.LoggerFactory
import java.io.File
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/**
 * Encrypted-at-rest store for platform login credentials (e.g. a Bilibili `SESSDATA`), keyed by
 * player UUID + platform. Values are encrypted with AES-256-GCM; the key lives in a separate file
 * next to the data file, so the ciphertext cannot be decrypted from the world or the mod jar alone.
 *
 * Supports per-player credentials (legacy) and a single global credential shared across all players.
 * When a [syncBackend] is configured (e.g. MySQL), the global credential is also persisted there
 * for cross-server synchronization.
 */
object CredentialStore {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CredentialStore")

    private const val KEY_SIZE_BITS = 256
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** In-memory plaintext cache, keyed `"<playerUuid>:<platform>"` or `"__global__:<platform>"`. */
    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var key: SecretKey? = null

    @Volatile
    private var dataFile: File? = null

    @Volatile
    private var initialized = false

    /** Optional cross-server sync backend (e.g. MySQL table). */
    @Volatile
    private var syncBackend: CredentialSyncBackend? = null

    /** Sets the sync backend for cross-server credential persistence. */
    fun setSyncBackend(backend: CredentialSyncBackend?) {
        syncBackend = backend
    }

    /** Loads (or creates) the key file and decrypts the stored credentials. Safe to call once. */
    fun init(dataDir: File) {
        if (initialized) return
        dataDir.mkdirs()
        val keyFile = File(dataDir, "credentials.key")
        dataFile = File(dataDir, "credentials.json")
        key = loadOrCreateKey(keyFile)
        load(dataFile!!)
        initialized = true
        logger.info("Credential store ready ({} credential(s) loaded).", cache.size)
    }

    /** Stores [token] for [playerUuid] on [platform] and persists it encrypted. */
    fun set(playerUuid: String, platform: String, token: String) {
        cache["$playerUuid:$platform"] = token
        persist()
    }

    /** The stored token for [playerUuid] on [platform], or null when none is saved. */
    fun get(playerUuid: String, platform: String): String? = cache["$playerUuid:$platform"]

    /** Removes the credential for [playerUuid] on [platform] and persists. */
    fun clear(playerUuid: String, platform: String) {
        cache.remove("$playerUuid:$platform")
        persist()
    }

    // ── Global credential (single shared Bilibili account for the whole server) ────────────────────

    private const val GLOBAL_PREFIX = "__global__"

    /** Stores a global credential on [platform] and persists. */
    fun setGlobal(platform: String, token: String) {
        cache["$GLOBAL_PREFIX:$platform"] = token
        persist()
        // Sync to cross-server backend if configured
        val k = this.key
        if (k != null) {
            val backend = this.syncBackend
            if (backend != null) {
                val encrypted = encrypt(token, k)
                backend.setCredential("$GLOBAL_PREFIX:$platform", encrypted)
            }
        }
    }

    /** The stored global credential for [platform], or null when not set. */
    fun getGlobal(platform: String): String? = cache["$GLOBAL_PREFIX:$platform"]

    /** Removes the global credential for [platform] and persists. */
    fun clearGlobal(platform: String) {
        cache.remove("$GLOBAL_PREFIX:$platform")
        persist()
        val backend = this.syncBackend
        if (backend != null) {
            backend.removeCredential("$GLOBAL_PREFIX:$platform")
        }
    }

    /** Iterates every stored Bilibili credential, calling [block] with `(playerUuid, sessdata, refreshToken)`. */
    fun forEachBilibili(block: (playerUuid: String, sessdata: String, refreshToken: String) -> Unit) {
        val bilibiliPrefix = ":bilibili"
        val refreshPrefix = ":bilibili_refresh"
        for ((entryKey, value) in cache) {
            if (entryKey.endsWith(bilibiliPrefix) && !entryKey.endsWith(refreshPrefix)) {
                val uuid = entryKey.substringBefore(bilibiliPrefix)
                if (uuid != GLOBAL_PREFIX) {
                    val refresh = cache["$uuid$refreshPrefix"] ?: ""
                    block(uuid, value, refresh)
                }
            }
        }
    }

    /** Loads global credentials from the sync backend into the local cache. */
    fun loadFromSyncBackend(backend: CredentialSyncBackend) {
        val k = key ?: return
        backend.allCredentials().forEach { (entryKey, encryptedValue) ->
            val decrypted = decrypt(encryptedValue, k)
            if (decrypted != null) {
                cache[entryKey] = decrypted
            }
        }
        logger.info("Loaded {} credential(s) from sync backend.", cache.size)
    }

    private fun loadOrCreateKey(file: File): SecretKey {
        if (file.isFile && file.length() >= 16) {
            return SecretKeySpec(file.readBytes(), "AES")
        }
        val k = KeyGenerator.getInstance("AES").apply { init(KEY_SIZE_BITS) }.generateKey()
        file.parentFile.mkdirs()
        file.writeBytes(k.encoded)
        logger.info("Generated new credential encryption key at {}.", file.absolutePath)
        return k
    }

    private fun load(file: File) {
        if (!file.isFile) return
        val k = key ?: return
        runCatching {
            val root = DreamJson.compact.parseToJsonElement(file.readText()) as? JsonObject ?: return
            for ((entryKey, value) in root) {
                val cipherText = (value as? JsonPrimitive)?.content ?: continue
                val decrypted = decrypt(cipherText, k)
                if (decrypted != null) {
                    cache[entryKey] = decrypted
                }
            }
        }.onFailure { logger.error("Failed to load credentials file.", it) }
    }

    private fun persist() {
        val file = dataFile ?: return
        val k = key ?: return
        val entries = cache.entries.toList()
        runCatching {
            val obj = buildJsonObject {
                for ((entryKey, value) in entries) {
                    put(entryKey, JsonPrimitive(encrypt(value, k)))
                }
            }
            file.parentFile.mkdirs()
            file.writeText(DreamJson.compact.encodeToString(JsonObject.serializer(), obj))
        }.onFailure { logger.error("Failed to persist credentials.", it) }
    }

    private fun encrypt(plain: String, key: SecretKey): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val iv = cipher.iv
        val cipherText = cipher.doFinal(plain.toByteArray(Charsets.UTF_8))
        return Base64.getEncoder().encodeToString(iv + cipherText)
    }

    private fun decrypt(encoded: String, key: SecretKey): String? = runCatching {
        val raw = Base64.getDecoder().decode(encoded)
        val iv = raw.copyOfRange(0, IV_BYTES)
        val cipherText = raw.copyOfRange(IV_BYTES, raw.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(GCM_TAG_BITS, iv))
        String(cipher.doFinal(cipherText), Charsets.UTF_8)
    }.getOrNull()
}