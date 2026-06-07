package com.circuitstitch.deferno.core.database

import app.cash.sqldelight.db.SqlDriver
import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.AccountId

/**
 * Opens the [SqlDriver] backing one Account's [DefernoDatabase] (ADR-0001/0002, issue #21). One
 * factory is bound per Account (the Account DI scope, ADR-0008): each Account gets its own database
 * file *and* its own encryption key, so nothing ever crosses the Account isolation boundary.
 *
 * The implementation is per-platform (the `driver` package): Android opens an SQLCipher-encrypted
 * database, iOS opens an SQLiter-encrypted one, and the JVM/desktop opens a file database. The
 * tests open an unencrypted in-memory database instead (encryption changes only how bytes hit
 * disk, not the SQL). Pair with [createDefernoDatabase] to get the typed database off the driver.
 */
fun interface SqlDriverFactory {
    /** Opens (creating/migrating as needed) the driver for this factory's Account. */
    fun create(): SqlDriver
}

/**
 * Supplies the SQLCipher passphrase for an Account's database from the secure vault
 * (ADR-0002/0009) — the "key from the secure vault" the encrypted DB is opened with. Kept a narrow
 * port so `core:database` stays free of the secure-storage capability module; the production
 * binding (a [com.circuitstitch.deferno.core.model.AccountId]-keyed, vault-backed provider that
 * generates and persists a random per-Account key on first use) is wired at the DI layer.
 */
fun interface DatabaseKeyProvider {
    /**
     * The SQLCipher **passphrase** for [account]'s database — a high-entropy string the production
     * provider generates and persists per Account on first use. A `String` (not raw bytes) so it
     * round-trips losslessly into both platform encryption APIs: Android encodes it to UTF-8 bytes
     * for `SupportOpenHelperFactory`, iOS hands it straight to SQLiter's `Encryption(key)`. Raw
     * binary would be mangled by iOS's String passphrase API. Never logged (ADR-0009).
     */
    fun databaseKey(account: AccountId): String
}

/**
 * A [DatabaseKeyProvider] that can also **destroy** an Account's key — the wipe side of the
 * per-Account key lifecycle (ADR-0002/0009). The production provider mints + persists keys on first
 * use; account removal ([com.circuitstitch.deferno.core.data.account.AccountDataStore]) calls
 * [deleteKey] alongside deleting the per-Account database file.
 */
interface DatabaseKeyStore : DatabaseKeyProvider {
    /** Destroys [account]'s stored database key. A no-op if none is stored. */
    fun deleteKey(account: AccountId)
}

/** Signals that the platform key store could not complete an operation. Never carries the key. */
class DatabaseKeyException(message: String, cause: Throwable? = null) : Exception(message, cause)

/** Builds the typed [DefernoDatabase] over a driver from [factory]. */
fun createDefernoDatabase(factory: SqlDriverFactory): DefernoDatabase =
    DefernoDatabase(factory.create())

/**
 * The on-disk database file name for [account] — `deferno-<id>.db`. Per-Account naming is half of
 * the hard isolation boundary (the per-Account key is the other half, ADR-0002); each Account's
 * data lives in a physically separate file.
 */
fun databaseFileName(account: AccountId): String = "deferno-${account.value}.db"
