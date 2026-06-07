package com.circuitstitch.deferno.core.database

import com.circuitstitch.deferno.core.database.sql.DefernoDatabase
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.scopes.AccountScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The AccountScope database binding (ADR-0002/0014): the typed [DefernoDatabase] is opened once per
 * Account graph for the Active [Account], via the AppScope [AccountDatabaseFactory] (whose per-target
 * impl closes over the host deps — Context / databases dir / key provider). Disposed + rebuilt on an
 * Active-Account switch, so nothing crosses the isolation boundary.
 */
@ContributesTo(AccountScope::class)
interface DatabaseBindings {
    @Provides
    @SingleIn(AccountScope::class)
    fun defernoDatabase(factory: AccountDatabaseFactory, account: Account): DefernoDatabase =
        factory.create(account.id)
}
