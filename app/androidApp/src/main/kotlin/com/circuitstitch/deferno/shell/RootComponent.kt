package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.tasks.SearchTasks
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * The per-scene navigation root (ADR-0013 / ADR-0008 / ADR-0014): a two-state [[Shell]] selected by
 * the **Active Account**. It shows the **Auth shell** (pre-[[Account]]) when there is none and the
 * **Main shell** (post-Account) when one is active, swapping reactively as the [AccountManager]'s
 * `activeAccount` changes — first sign-in, sign-out, and fast user switching all flow through it.
 *
 * The Main shell is keyed by the Active Account's id ([Config.Main]), so switching A→B re-keys the
 * child, which rebuilds the per-Account data layer ([AccountSession], from a fresh
 * [com.circuitstitch.deferno.core.di.AccountComponent]) for the new Account — the per-Account
 * isolation boundary enforced structurally by the scope graph (ADR-0002/0014). Switching needs no
 * re-auth: every Account's PAT already lives in the AppScope vault (ADR-0012).
 *
 * One root per scene/window (ADR-0008 G2/G3). It is Compose-free — the View renders it.
 */
interface RootComponent {
    /** The active shell (exactly one of [Child.Auth] / [Child.Main]). */
    val stack: Value<ChildStack<*, Child>>

    /** Route Android system-back to the active shell. `false` → the host lets the platform exit. */
    fun onBackClicked(): Boolean

    sealed interface Child {
        class Auth(val component: AuthShellComponent) : Child
        class Main(val component: MainShellComponent) : Child
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val accountManager: AccountManager,
    private val authRepository: AuthRepository,
    private val accountSession: (Account) -> AccountSession,
    private val onSignIn: () -> Unit,
    private val today: LocalDate,
    private val timeZone: String,
    coroutineContext: CoroutineContext = Dispatchers.Main,
) : RootComponent, ComponentContext by componentContext {

    private val scope = CoroutineScope(coroutineContext + SupervisorJob())
        .also { s -> lifecycle.doOnDestroy { s.cancel() } }

    private sealed interface Config {
        data object Auth : Config

        /** The Main shell bound to the Active Account; its id re-keys the child on a switch. */
        data class Main(val accountId: String) : Config
    }

    private val navigation = StackNavigation<Config>()

    /** The foreground Main child's session, used to apply per-Account writes (add-to-plan). */
    private var activeSession: AccountSession? = null

    override val stack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = configFor(accountManager.activeAccount.value),
            key = "ShellStack",
            handleBackButton = false, // back is routed via onBackClicked(), not a stack pop
            childFactory = ::createChild,
        )

    init {
        // Follow the Active Account: swap shells (and re-key the Main child per Account) as it changes.
        // replaceAll retains the child when the target config is unchanged (data-class equality), so a
        // no-op emission doesn't rebuild the current shell.
        scope.launch {
            accountManager.activeAccount.collect { account ->
                val target = configFor(account)
                if (stack.value.active.configuration != target) {
                    navigation.replaceAll(target)
                }
            }
        }
    }

    override fun onBackClicked(): Boolean =
        when (val child = stack.value.active.instance) {
            is RootComponent.Child.Auth -> false // can't go back out of the Auth shell — exit the scene
            is RootComponent.Child.Main -> child.component.onBack()
        }

    private fun createChild(config: Config, childContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Auth -> {
                activeSession = null
                RootComponent.Child.Auth(
                    DefaultAuthShellComponent(componentContext = childContext, onSignIn = onSignIn),
                )
            }

            is Config.Main -> {
                // The Active Account this Main child is keyed to. It is in the roster by construction
                // (the config came from an emitted activeAccount), so a miss is a broken invariant.
                val account = accountManager.accounts.value.first { it.id.value == config.accountId }
                val session = accountSession(account)
                activeSession = session
                // Pull this Account's tasks + today's plan into the local cache on open / switch
                // (offline-first: the repositories swallow failures, leaving the cache intact). The
                // Views observe the local DB, so this is what surfaces real data on first open.
                scope.launch { session.taskRepository.refresh() }
                scope.launch { session.planRepository.refreshPlan(today, timeZone) }
                RootComponent.Child.Main(
                    DefaultMainShellComponent(
                        componentContext = childContext,
                        taskRepository = session.taskRepository,
                        planRepository = session.planRepository,
                        authRepository = authRepository,
                        account = account,
                        today = today,
                        timeZone = timeZone,
                        workingStateEditor = session.workingStateEditor,
                        searchTasks = SearchTasks.of(session.taskRepository),
                        accounts = accountManager.accounts,
                        activeAccount = accountManager.activeAccount,
                        onSwitchAccount = ::switchAccount,
                        output = ::onMainOutput,
                        coroutineContext = scope.coroutineContext,
                    ),
                )
            }
        }

    private fun onMainOutput(output: MainShellComponent.Output) {
        when (output) {
            // Add-to-plan applies through the Active Account's offline write path (optimistic apply +
            // outbox enqueue), not a host mirror — the real per-Account command (ADR-0001/0007/0014).
            is MainShellComponent.Output.AddToPlanRequested -> onAddToPlan(output.id)
            // Sign out crosses the Account-isolation boundary (ADR-0002), so it lands at the root.
            MainShellComponent.Output.SignOutRequested -> onSignOut()
        }
    }

    private fun onSignOut() {
        val id = accountManager.activeAccount.value?.id ?: return
        // Secure-wipe the Active Account locally (encrypted DB + DB key + bearer token, in the crash-safe
        // order, ADR-0009) via removeAccount, which re-points activeAccount to another Account or null.
        // The collector above then swaps the shell back to the Auth shell when it hits null, or re-keys
        // Main for the remaining sibling — the "return to Auth, or to another signed-in Account" rule.
        // Server-side PAT revocation (DELETE /auth/tokens/{id}) is deferred to in-app sign-in (#15),
        // where the token id is captured at mint time (a dev-pasted opaque PAT carries no id to revoke).
        scope.launch { accountManager.removeAccount(id) }
    }

    private fun onAddToPlan(taskId: TaskId) {
        val session = activeSession ?: return
        scope.launch { session.addToPlan(taskId, today, timeZone) }
    }

    private fun switchAccount(id: AccountId) {
        // Re-points the Active Account; the activeAccount collector above re-keys the Main child for
        // the new Account. No re-auth — the PAT is already vaulted (ADR-0002/0012).
        scope.launch { accountManager.switchTo(id) }
    }

    private fun configFor(account: Account?): Config =
        if (account != null) Config.Main(account.id.value) else Config.Auth
}
