package com.dreamdisplayx.platform.server.credentials

/**
 * Optional cross-server credential persistence backend. When configured (e.g. MySQL), the global
 * Bilibili credential is stored here so that all servers in a network share the same login session.
 *
 * Implementations are responsible for connection management and error handling.
 */
interface CredentialSyncBackend {

    /**
     * Stores an encrypted credential value under [key].
     * The value is already AES-256-GCM encrypted by [CredentialStore].
     */
    fun setCredential(key: String, encryptedValue: String)

    /**
     * Retrieves the encrypted credential for [key], or null if not found.
     */
    fun getCredential(key: String): String?

    /**
     * Removes the credential for [key] from the backend.
     */
    fun removeCredential(key: String)

    /**
     * Returns all stored credentials as a map of key -> encrypted value.
     * Used to load credentials into the local cache on startup.
     */
    fun allCredentials(): Map<String, String>
}