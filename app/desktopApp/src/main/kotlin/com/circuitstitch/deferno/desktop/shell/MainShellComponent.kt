package com.circuitstitch.deferno.desktop.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.plan.DefaultPlanComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.Dispatchers

/**
 * The **Main shell** (ADR-0013): the post-[[Account]] surface that hosts the **Destination graph**.
 * It exposes the [Destination] registry ([destinations]) the View renders as a nav suite, and the
 * active Destination as a Decompose [ChildStack].
 *
 * Tier-1 switching ([selectDestination]) is **lateral and state-preserving — multiple back stacks**
 * (ADR-0007 tier 1 / ADR-0008 G5): each Destination's component (and its own tier-2 panes / tier-3
 * drill-downs) is **retained** while another Destination is foreground, so leaving Tasks for Plan and
 * returning restores Tasks exactly. This is realized with Decompose `bringToFront`, which reuses the
 * existing child for a Destination already on the stack rather than recreating it. Destinations are
 * created lazily (only on first visit), so the registry scales to a dozen-plus without eager cost.
 *
 * The shell also routes the cross-feature intents the Destinations emit (a Plan tap opens that Task in
 * the Tasks Destination; a Tasks "add to plan" bubbles up via [Output] for the host to mirror).
 */
interface MainShellComponent {
    /** The ordered Destination registry the nav suite renders — not a fixed count. */
    val destinations: List<Destination>

    /** The foreground Destination + the retained, state-preserving back stack of visited Destinations. */
    val stack: Value<ChildStack<*, DestinationChild>>

    /** Switch foreground Destination laterally, preserving every Destination's retained state. */
    fun selectDestination(destination: Destination)

    /**
     * Route shell-level back within the Main shell (desktop: the Esc key): first let the active
     * Destination dismiss its own tier-2/tier-3 state, then fall back from a non-home Destination to
     * the [Destination.Plan] home. Returns whether back was consumed (`false` → the host has nothing
     * left to dismiss).
     */
    fun onBack(): Boolean

    /** A live Destination instance, tagged with which [Destination] it is so the View can render it. */
    sealed interface DestinationChild {
        val destination: Destination

        class Plan(val component: PlanComponent) : DestinationChild {
            override val destination: Destination = Destination.Plan
        }

        class Tasks(val component: TasksComponent) : DestinationChild {
            override val destination: Destination = Destination.Tasks
        }
    }

    sealed interface Output {
        /** A Tasks "add to plan" intent, re-emitted for the host (the Plan write isn't the shell's job). */
        data class AddToPlanRequested(val id: TaskId) : Output
    }
}

class DefaultMainShellComponent(
    componentContext: ComponentContext,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val today: LocalDate,
    private val timeZone: String,
    private val output: (MainShellComponent.Output) -> Unit = {},
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : MainShellComponent, ComponentContext by componentContext {

    override val destinations: List<Destination> = Destination.entries

    // Plain-data configs (serializer = null → no state restoration wired in v1, matching the feature
    // components). One per Destination; equality is what `bringToFront` matches to retain a child.
    private sealed interface Config {
        data object Plan : Config
        data object Tasks : Config
    }

    private val navigation = StackNavigation<Config>()

    override val stack: Value<ChildStack<*, MainShellComponent.DestinationChild>> =
        childStack(
            source = navigation,
            serializer = null,
            initialConfiguration = Config.Plan, // open into the Plan (design-principles.md)
            key = "DestinationStack",
            handleBackButton = false, // tier-1 back is routed via onBack(), not a global stack pop
            childFactory = ::createChild,
        )

    override fun selectDestination(destination: Destination) {
        // bringToFront reuses the child if this Destination is already on the stack (state-preserving),
        // otherwise creates it — the multiple-back-stack switch (ADR-0007 tier 1 / ADR-0008 G5).
        navigation.bringToFront(destination.toConfig())
    }

    override fun onBack(): Boolean {
        val active = stack.value.active.instance
        if (active.handleInnerBack()) return true
        // Nothing left inside the active Destination: from a non-home Destination, return to the Plan
        // home (its retained state is untouched); on the home Destination, nothing left to dismiss.
        if (active.destination != Destination.Plan) {
            navigation.bringToFront(Config.Plan)
            return true
        }
        return false
    }

    private fun createChild(
        config: Config,
        childContext: ComponentContext,
    ): MainShellComponent.DestinationChild =
        when (config) {
            Config.Plan ->
                MainShellComponent.DestinationChild.Plan(
                    DefaultPlanComponent(
                        componentContext = childContext,
                        planRepository = planRepository,
                        date = today,
                        tz = timeZone,
                        output = ::onPlanOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Tasks ->
                MainShellComponent.DestinationChild.Tasks(
                    DefaultTasksComponent(
                        componentContext = childContext,
                        taskRepository = taskRepository,
                        output = ::onTasksOutput,
                        coroutineContext = coroutineContext,
                    ),
                )
        }

    private fun onPlanOutput(output: PlanComponent.Output) {
        when (output) {
            // A Plan tap opens that Task in the Tasks Destination — switch laterally, then route the
            // selection through the list's public intent (the same path a real list tap takes).
            is PlanComponent.Output.OpenTask -> {
                navigation.bringToFront(Config.Tasks)
                // After bringToFront the Tasks Destination is synchronously the active child (Decompose
                // navigation is synchronous), so this cast holds — and is non-null on purpose: a silent
                // no-op would hide a broken invariant. Route the open through the list's public intent.
                val tasks = stack.value.active.instance as MainShellComponent.DestinationChild.Tasks
                tasks.component.list.onTaskClicked(output.id)
            }
        }
    }

    private fun onTasksOutput(output: TasksComponent.Output) {
        when (output) {
            is TasksComponent.Output.AddToPlanRequested ->
                this.output(MainShellComponent.Output.AddToPlanRequested(output.id))
        }
    }

    private fun Destination.toConfig(): Config =
        when (this) {
            Destination.Plan -> Config.Plan
            Destination.Tasks -> Config.Tasks
        }

    /**
     * Tier-2/tier-3 back for the active Destination, using only its public intents. Single-pane
     * Destinations (Plan) have no inner back; Tasks delegates to [dismissForegroundPane].
     */
    private fun MainShellComponent.DestinationChild.handleInnerBack(): Boolean =
        when (this) {
            is MainShellComponent.DestinationChild.Plan -> false
            is MainShellComponent.DestinationChild.Tasks -> component.dismissForegroundPane()
        }
}

/**
 * Dismiss the Tasks Destination's **foregrounded** co-resident pane first ([TasksComponent.activePane])
 * so back always matches what a single-pane View shows and reveals the slot beneath it — then any other
 * open slot, else not consumed. This is the demo host's reviewed back logic (#27), now in the shell.
 */
private fun TasksComponent.dismissForegroundPane(): Boolean {
    when (activePane.value) {
        TaskPane.Tree -> tree.value.child?.instance?.let { it.onCloseClicked(); return true }
        TaskPane.Detail -> detail.value.child?.instance?.let { it.onCloseClicked(); return true }
        TaskPane.List -> Unit
    }
    tree.value.child?.instance?.let { it.onCloseClicked(); return true }
    detail.value.child?.instance?.let { it.onCloseClicked(); return true }
    return false
}
