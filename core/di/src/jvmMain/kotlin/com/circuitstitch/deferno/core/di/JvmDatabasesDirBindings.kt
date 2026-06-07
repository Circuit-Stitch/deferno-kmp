package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.scopes.AppScope
import com.circuitstitch.deferno.core.scopes.PlatformContext
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Unwraps the desktop databases directory from the [PlatformContext] the JVM app creates the AppScope
 * graph with (ADR-0014). The per-Account JvmSqlDriverFactory (AccountScope) resolves it to place each
 * Account's database file. The only `String` binding in the JVM graph, so it never collides.
 */
@ContributesTo(AppScope::class)
interface JvmDatabasesDirBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun databasesDir(platform: PlatformContext): String = platform.databasesDir
}
