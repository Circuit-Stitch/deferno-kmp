package com.circuitstitch.deferno.core.di

import com.circuitstitch.deferno.core.data.outbox.OutboxProcessor
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.CommandExecutor
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.scopes.AccountScope
import me.tatarka.inject.annotations.Component
import me.tatarka.inject.annotations.Provides
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent
import software.amazon.lastmile.kotlin.inject.anvil.MergeComponent.CreateComponent
import software.amazon.lastmile.kotlin.inject.anvil.SingleIn

/**
 * The Active Account scope ([AccountScope], ADR-0002 / ADR-0008) — a child of [AppScope]. Takes
 * the [AppComponent] as a kotlin-inject `@Component` parent, so process-singleton bindings resolve
 * through it.
 *
 * Its real binding is the **Active [Account] this scope is bound to** (issue #14): supplied at
 * creation as a `@Provides` value, it is resolvable by every Account- and Scene-scoped binding —
 * "Account context resolvable per scene scope" (ADR-0008 G3). The window/scene graph is created
 * over the active Account, and a future second window can build a second [AccountComponent] over a
 * *different* Account while sharing the one [AppComponent], so this is a scope, not a hard global.
 *
 * Switching the Active Account re-points by tearing down + rebuilding this component for the new
 * Account — cheap, because presentation is scene-scoped and the data layer above (in [AppScope]) is
 * shared. The process-global `AccountManager` (the authority that produces the Active Account) is
 * an [AppScope] binding; wiring it as a contribution is deferred until its data-layer collaborators
 * have production implementations.
 */
@MergeComponent(AccountScope::class)
@SingleIn(AccountScope::class)
abstract class AccountComponent(
    @Component val app: AppComponent,
    @get:Provides val activeAccount: Account,
) {
    // Re-exposed from AppScope to show parent-scoped bindings resolve through a child.
    abstract val appScaffold: AppScaffold

    /**
     * The per-Account data layer (ADR-0014). The scene builds its ViewModels over these; the whole
     * subtree (this Account's encrypted DB + stores + repositories + outbox) is rebuilt when the
     * Active Account switches, because this component is disposed + recreated for the new Account.
     * Exposing them also anchors anvil's compile-time validation of the entire AccountScope chain
     * (DB → driver → key/Context/databasesDir, repositories → AppScope remote sources).
     */
    abstract val taskRepository: TaskRepository
    abstract val planRepository: PlanRepository

    /** The command-registry dispatch site (ADR-0007) over this Account's write seams. */
    abstract val commandExecutor: CommandExecutor

    /** The offline outbox replay engine (#23) for this Account; the app drives [OutboxProcessor.flush]. */
    abstract val outboxProcessor: OutboxProcessor
}

@CreateComponent
expect fun createAccountComponent(app: AppComponent, activeAccount: Account): AccountComponent
