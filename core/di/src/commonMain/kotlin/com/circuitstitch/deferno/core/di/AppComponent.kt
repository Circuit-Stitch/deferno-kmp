package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.scopes.AppScope
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.ContributesTo
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * Process-global root scope ([AppScope], ADR-0008). Its bindings are process-singletons
 * shared across every window/scene — the data layer will contribute here. Trivial
 * stand-in binding for now.
 */
data class AppScaffold(val value: String)

@ContributesTo(AppScope::class)
interface AppScaffoldBindings {
    @Provides
    @SingleIn(AppScope::class)
    fun provideAppScaffold(): AppScaffold = AppScaffold("app")
}

@MergeComponent(AppScope::class)
@SingleIn(AppScope::class)
abstract class AppComponent {
    abstract val appScaffold: AppScaffold
}

// Creation from common code (KMP); anvil generates the per-platform `actual`. One
// @CreateComponent per file — anvil names the generated actual after the containing file.
@CreateComponent
expect fun createAppComponent(): AppComponent
