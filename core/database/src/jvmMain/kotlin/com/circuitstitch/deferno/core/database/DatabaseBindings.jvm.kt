package com.circuitstitch.deferno.core.database

import com.circuitstitch.deferno.core.database.driver.JvmSqlDriverFactory
import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Desktop (JVM) AppScope database binding (ADR-0009/0014): the [AccountDatabaseFactory] opens a
 * per-Account file-backed database under the AppScope-provided databases dir (the `PlatformContext`
 * unwrap in core:di), closing over it so the child AccountScope only supplies the AccountId. No key
 * provider — the v1 desktop posture relies on OS-level disk protection (SQLCipher-on-JVM is a tracked
 * follow-up).
 */
@ContributesTo(AppScope::class)
interface JvmDatabaseBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun accountDatabaseFactory(databasesDir: String): AccountDatabaseFactory =
        AccountDatabaseFactory { account ->
            createDefernoDatabase(JvmSqlDriverFactory(databasesDir, account))
        }
}
