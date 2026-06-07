package com.circuitstitch.deferno.desktop.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.router.stack.replaceAll
import com.arkivanov.decompose.value.Value
import com.arkivanov.essenty.lifecycle.doOnDestroy
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.TaskId
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * The per-scene navigation root (ADR-0013 / ADR-0008 G2): a two-state [[Shell]] selected by auth
 * state. It swaps its single child between the **Auth shell** (pre-[[Account]]) and the **Main shell**
 * (post-Account) as the [AuthGate] flips — re-entrant in both directions, so fast user switching /
 * sign-out drop back to Auth and a completed sign-in returns to Main (account isolation, ADR-0002).
 *
 * One root is instantiated **per scene/window** (G2/G3); the data layer it is handed
 * ([TaskRepository], [PlanRepository]) is a process-global singleton shared across windows, while this
 * presentation tree and the [AuthGate] are scene-scoped. It is Compose-free — the View renders it.
 */
interface RootComponent {
    /** The active shell (exactly one of [Child.Auth] / [Child.Main]). */
    val stack: Value<ChildStack<*, Child>>

    /** Route shell-level back (desktop: the Esc key) to the active shell. `false` → nothing to dismiss. */
    fun onBackClicked(): Boolean

    sealed interface Child {
        class Auth(val component: AuthShellComponent) : Child
        class Main(val component: MainShellComponent) : Child
    }

    sealed interface Output {
        /** A Tasks "add to plan" intent bubbled from the Main shell for the host to mirror. */
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultRootComponent(
    componentContext: ComponentContext,
    private val authGate: AuthGate,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val today: LocalDate,
    private val timeZone: String,
    private val output: (RootComponent.Output) -> Unit = {},
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : RootComponent, ComponentContext by componentContext {

    private sealed interface Config {
        data object Auth : Config
        data object Main : Config
    }

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, RootComponent.Child>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = configFor(authGate.signedIn.value),
            key = "ShellStack",
            handleBackButton = false, // back is routed via onBackClicked(), not a stack pop
            childFactory = ::createChild,
        )

    init {
        // Follow the gate: when auth state changes, swap shells. `replaceAll` retains the child if the
        // target shell is already active (config equality), so the immediate on-subscribe emission and
        // any no-op change don't rebuild the current shell.
        val cancellation = authGate.signedIn.subscribe { signedIn ->
            val target = configFor(signedIn)
            if (stack.value.active.configuration != target) {
                navigation.replaceAll(target)
            }
        }
        lifecycle.doOnDestroy(cancellation::cancel)
    }

    override fun onBackClicked(): Boolean =
        when (val child = stack.value.active.instance) {
            is RootComponent.Child.Auth -> false // can't go back out of the Auth shell
            is RootComponent.Child.Main -> child.component.onBack()
        }

    private fun createChild(config: Config, childContext: ComponentContext): RootComponent.Child =
        when (config) {
            Config.Auth ->
                RootComponent.Child.Auth(
                    DefaultAuthShellComponent(
                        componentContext = childContext,
                        onSignIn = authGate::signIn,
                    ),
                )

            Config.Main ->
                RootComponent.Child.Main(
                    DefaultMainShellComponent(
                        componentContext = childContext,
                        taskRepository = taskRepository,
                        planRepository = planRepository,
                        today = today,
                        timeZone = timeZone,
                        output = ::onMainOutput,
                        coroutineContext = coroutineContext,
                    ),
                )
        }

    private fun onMainOutput(output: MainShellComponent.Output) {
        when (output) {
            is MainShellComponent.Output.AddToPlanRequested ->
                this.output(RootComponent.Output.AddToPlanRequested(output.id))
        }
    }

    private fun configFor(signedIn: Boolean): Config = if (signedIn) Config.Main else Config.Auth
}
