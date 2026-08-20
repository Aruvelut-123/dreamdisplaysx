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
 */
object CredentialStore {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CredentialStore")

    private const val KEY_SIZE_BITS = 256
    private const val IV_BYTES = 12
    private const val GCM_TAG_BITS = 128

    /** In-memory plaintext cache, keyed `"<playerUuid>:<platform>"`. */
    private val cache = ConcurrentHashMap<String, String>()

    @Volatile
    private var key: SecretKey? = null

    @Volatile
    private var dataFile: File? = null

    @Volatile
    private var initialized = false

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

    /** Iterates every stored Bilibili credential, calling [block] with `(playerUuid, sessdata, refreshToken)`. */
    fun forEachBilibili(block: (playerUuid: String, sessdata: String, refreshToken: String) -> Unit) {
        val bilibiliPrefix = ":bilibili"
        val refreshPrefix = ":bilibili_refresh"
        for ((key, value) in cache) {
            if (key.endsWith(bilibiliPrefix) && !key.endsWith(refreshPrefix)) {
                val uuid = key.substringBefore(bilibiliPrefix)
                val refresh = cache["$uuid$refreshPrefix"] ?: ""
                block(uuid, value, refresh)
            }
        }
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
                decrypt(cipherText, k)?.let { cache[entryKey] = it }
            }
        }.onFailure { logger.error("Failed to load credentials file.", it) }
    }

    private fun persist() {
        val file = dataFile ?: return
        val k = key ?: return
        runCatching {
            val obj = buildJsonObject {
                for ((entryKey, value) in cache) {
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
