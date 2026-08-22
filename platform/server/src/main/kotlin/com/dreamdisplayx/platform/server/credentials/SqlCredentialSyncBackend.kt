package com.dreamdisplayx.platform.server.credentials

import com.dreamdisplayx.platform.server.storage.StorageBackend
import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import org.jetbrains.exposed.v1.core.Table
import org.jetbrains.exposed.v1.core.eq
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.deleteWhere
import org.jetbrains.exposed.v1.jdbc.replace
import org.jetbrains.exposed.v1.jdbc.selectAll
import org.jetbrains.exposed.v1.jdbc.transactions.transaction
import org.jetbrains.exposed.v1.migration.jdbc.MigrationUtils
import org.slf4j.LoggerFactory
import java.io.File

/**
 * SQL-backed credential sync backend. Stores encrypted global credentials in a `credentials` table
 * in the same database as displays (SQLite or MySQL).
 *
 * - **SQLite**: credentials are stored in the local `dreamdisplayx.db` alongside displays.
 *   Single-server only — no cross-server sync.
 * - **MySQL**: credentials are stored in a shared MySQL database accessible by all servers
 *   in the network, enabling cross-server credential sync.
 */
class SqlCredentialSyncBackend(
    backend: StorageBackend,
    dataDir: File,
    tablePrefix: String = "",
    host: String = "",
    port: String = "",
    database: String = "",
    username: String = "",
    password: String = "",
    useSSL: Boolean = false,
    jdbcUrl: String = "",
) : CredentialSyncBackend {
    private val logger = LoggerFactory.getLogger("DreamDisplaysX/CredentialSync")

    private val table = CredentialTable(tablePrefix)

    private val dataSource = HikariDataSource(HikariConfig().apply {
        this.jdbcUrl = when {
            // A fully custom JDBC URL wins over the split host/port/database fields.
            jdbcUrl.isNotBlank() -> jdbcUrl
            backend == StorageBackend.SQLITE -> "jdbc:sqlite:${File(dataDir, "dreamdisplayx.db").absolutePath}"
            else -> "jdbc:mysql://$host:$port/$database?autoReconnect=true&useSSL=$useSSL&useInformationSchema=false"
        }
        if (backend != StorageBackend.SQLITE) {
            this.username = username
            this.password = password
        }
        maximumPoolSize = if (backend == StorageBackend.SQLITE) 1 else 2
        isAutoCommit = false
    })

    private val db = Database.connect(dataSource)

    init {
        transaction(db) {
            MigrationUtils.statementsRequiredForDatabaseMigration(table).forEach { stmt -> exec(stmt) }
        }
        logger.info("Credential sync table ready (backend: {}).", backend)
    }

    override fun setCredential(key: String, encryptedValue: String) {
        transaction(db) {
            table.replace {
                it[credentialKey] = key
                it[credentialValue] = encryptedValue
            }
        }
    }

    override fun getCredential(key: String): String? {
        return transaction(db) {
            table.selectAll().where { table.credentialKey eq key }
                .firstOrNull()?.let { it[table.credentialValue] }
        }
    }

    override fun removeCredential(key: String) {
        transaction(db) {
            table.deleteWhere { credentialKey eq key }
        }
    }

    override fun allCredentials(): Map<String, String> {
        return transaction(db) {
            table.selectAll().associate { it[table.credentialKey] to it[table.credentialValue] }
        }
    }

    fun disconnect() {
        dataSource.close()
    }

    /** SQL table for credential sync. */
    class CredentialTable(prefix: String) : Table("${prefix}credentials") {
        val credentialKey = varchar("credential_key", 128)
        val credentialValue = text("credential_value")
        override val primaryKey = PrimaryKey(credentialKey)
    }
}
