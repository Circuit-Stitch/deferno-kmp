package com.circuitstitch.deferno.core.database.driver

import android.content.Context
import app.cash.sqldelight.db.SqlDriver
import app.cash.sqldelight.driver.android.AndroidSqliteDriver
import com.circuitstitch.deferno.core.database.DatabaseKeyProvider
import com.circuitstitch.deferno.core.database.SqlDriverFactory
import com.circuitstitch.deferno.core.database.databaseFileName
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.AccountId
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

/**
 * Android production driver (ADR-0001/0002/0009, issue #21): an [AndroidSqliteDriver] whose
 * underlying SQLite is SQLCipher-encrypted, opened on a per-Account file with a per-Account key
 * pulled from the secure vault via [keyProvider]. Per-Account file + per-Account key is the hard
 * isolation boundary (ADR-0002).
 *
 * Bound per Account (Account DI scope, ADR-0008). Runs only on a device, so it is exercised by
 * instrumented tests, not the headless JVM coverage gate (excluded in `CoverageConfig`).
 */
class AndroidSqlDriverFactory(
    private val context: Context,
    private val account: AccountId,
    private val keyProvider: DatabaseKeyProvider,
) : SqlDriverFactory {
    override fun create(): SqlDriver {
        // SQLCipher's native library must be loaded before the first encrypted open.
        System.loadLibrary("sqlcipher")
        // The vault passphrase is a String; encode it to UTF-8 bytes losslessly for SQLCipher.
        val passphrase = keyProvider.databaseKey(account).encodeToByteArray()
        val factory = SupportOpenHelperFactory(passphrase)
        return AndroidSqliteDriver(
            schema = DefernoDatabase.Schema,
            context = context,
            name = databaseFileName(account),
            factory = factory,
            // Exclude the encrypted DB from OS backup — a new device re-syncs rather than
            // restoring ciphertext whose device-bound key it no longer has (ADR-0009).
            useNoBackupDirectory = true,
        )
    }
}
