package com.circuitstitch.deferno.core.di

import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The Active Account scope ([AccountScope], ADR-0002 / ADR-0008) — a child of
 * [AppScope]. Takes the [AppComponent] as a kotlin-inject @Component parent, so
 * process-singleton bindings resolve through it. Trivial stand-in binding for now.
 */
data class AccountScaffold(val value: String)

@ContributesTo(AccountScope::class)
interface AccountScaffoldBindings {
    @Provides
    @SingleIn(AccountScope::class)
    fun provideAccountScaffold(): AccountScaffold = AccountScaffold("account")
}

@MergeComponent(AccountScope::class)
@SingleIn(AccountScope::class)
abstract class AccountComponent(
    @Component val app: AppComponent,
) {
    abstract val accountScaffold: AccountScaffold

    // Re-exposed from AppScope to show parent-scoped bindings resolve through a child.
    abstract val appScaffold: AppScaffold
}

@CreateComponent
expect fun createAccountComponent(app: AppComponent): AccountComponent
