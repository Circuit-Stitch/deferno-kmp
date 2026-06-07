package com.circuitstitch.deferno.core.database

import android.content.Context
import com.circuitstitch.deferno.core.database.driver.AndroidDatabaseKeyProvider
import com.circuitstitch.deferno.core.database.driver.AndroidSqlDriverFactory
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android AppScope database bindings (ADR-0002/0009/0014):
 *  - the [DatabaseKeyStore] ([AndroidDatabaseKeyProvider]) — a process-singleton minting + holding each
 *    Account's SQLCipher passphrase under a device-bound Keystore key — re-exposed as the narrower
 *    [DatabaseKeyProvider] the driver resolves;
 *  - the [AccountDatabaseFactory] that opens a per-Account SQLCipher-encrypted database, closing over
 *    the application `Context` + key provider so the child AccountScope only supplies the AccountId.
 *
 * `@Provides` because the key provider has default-arg constructor params.
 */
@ContributesTo(AppScope::class)
interface AndroidDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun databaseKeyStore(context: Context): DatabaseKeyStore = AndroidDatabaseKeyProvider(context)

    @Provides
    fun databaseKeyProvider(keyStore: DatabaseKeyStore): DatabaseKeyProvider = keyStore

    @Provides
    @SingleIn(AppScope::class)
    fun accountDatabaseFactory(
        context: Context,
        keyProvider: DatabaseKeyProvider,
    ): AccountDatabaseFactory = AccountDatabaseFactory { account ->
        createDefernoDatabase(AndroidSqlDriverFactory(context, account, keyProvider))
    }
}
