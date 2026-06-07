package com.circuitstitch.deferno.core.database

import com.circuitstitch.deferno.core.database.driver.IosDatabaseKeyProvider
import com.circuitstitch.deferno.core.database.driver.IosSqlDriverFactory
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * iOS AppScope database bindings (ADR-0002/0009/0014):
 *  - the Keychain-backed [DatabaseKeyProvider] ([IosDatabaseKeyProvider]), a process-singleton minting
 *    + holding each Account's SQLCipher passphrase;
 *  - the [AccountDatabaseFactory] that opens a per-Account SQLiter/SQLCipher-encrypted database,
 *    closing over the key provider so the child AccountScope only supplies the AccountId.
 */
@ContributesTo(AppScope::class)
interface IosDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun databaseKeyProvider(): DatabaseKeyProvider = IosDatabaseKeyProvider()

    @Provides
    @SingleIn(AppScope::class)
    fun accountDatabaseFactory(keyProvider: DatabaseKeyProvider): AccountDatabaseFactory =
        AccountDatabaseFactory { account ->
            createDefernoDatabase(IosSqlDriverFactory(account, keyProvider))
        }
}
