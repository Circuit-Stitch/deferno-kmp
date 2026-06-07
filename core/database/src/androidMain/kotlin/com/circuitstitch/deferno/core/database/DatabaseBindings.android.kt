package com.circuitstitch.deferno.core.database

import android.content.Context
import com.circuitstitch.deferno.core.database.driver.AndroidDatabaseKeyProvider
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Android AppScope key store binding (ADR-0009/0014): [AndroidDatabaseKeyProvider] is a process
 * singleton (it mints + holds each Account's SQLCipher passphrase under a device-bound Keystore key).
 * Bound as the full [DatabaseKeyStore] (the secure-wipe path needs `deleteKey`) and re-exposed as the
 * narrower [DatabaseKeyProvider] the per-Account [com.circuitstitch.deferno.core.database.SqlDriverFactory]
 * resolves. `@Provides` because the impl has default-arg constructor params (key alias / prefs name).
 */
@ContributesTo(AppScope::class)
interface AndroidDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun databaseKeyStore(context: Context): DatabaseKeyStore = AndroidDatabaseKeyProvider(context)

    @Provides
    fun databaseKeyProvider(keyStore: DatabaseKeyStore): DatabaseKeyProvider = keyStore
}
