package com.circuitstitch.deferno.core.database.driver

import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.native.NativeSqliteDriver
import app.cash.sqldelight.driver.native.wrapConnection
import co.touchlab.sqliter.DatabaseConfiguration
import com.circuitstitch.deferno.core.database.DatabaseKeyProvider
import com.circuitstitch.deferno.core.database.SqlDriverFactory
import com.circuitstitch.deferno.core.database.databaseFileName
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.AccountId

/**
 * iOS production driver (ADR-0001/0002/0009, issue #21): a [NativeSqliteDriver] over SQLiter with
 * SQLCipher encryption ([DatabaseConfiguration.Encryption]), opened on a per-Account file with a
 * per-Account key from the secure vault via [keyProvider]. The vault passphrase is already a
 * String, so it goes straight into SQLiter's encryption hook (no lossy byte decoding).
 *
 * Bound per Account (Account DI scope, ADR-0008). Linking the SQLCipher pod happens in the iOS app
 * (`linkSqlite=false` + the SQLCipher CocoaPod); this runs on a device/simulator, not the headless
 * JVM gate (excluded in `CoverageConfig`).
 */
class IosSqlDriverFactory(
    private val account: AccountId,
    private val keyProvider: DatabaseKeyProvider,
) : SqlDriverFactory {
    override fun create(): SqlDriver {
        val schema = DefernoDatabase.Schema
        val passphrase = keyProvider.databaseKey(account)
        val configuration = DatabaseConfiguration(
            name = databaseFileName(account),
            version = schema.version.toInt(),
            create = { connection -> wrapConnection(connection) { schema.create(it) } },
            upgrade = { connection, oldVersion, newVersion ->
                wrapConnection(connection) { schema.migrate(it, oldVersion.toLong(), newVersion.toLong()) }
            },
            encryptionConfig = DatabaseConfiguration.Encryption(key = passphrase),
        )
        return NativeSqliteDriver(configuration)
    }
}
