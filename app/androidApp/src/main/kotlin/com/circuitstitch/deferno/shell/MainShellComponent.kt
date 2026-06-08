package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.ComponentContext
import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.slot.SlotNavigation
import com.arkivanov.decompose.router.slot.activate
import com.arkivanov.decompose.router.slot.childSlot
import com.arkivanov.decompose.router.slot.dismiss
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.router.stack.StackNavigation
import com.arkivanov.decompose.router.stack.bringToFront
import com.arkivanov.decompose.router.stack.childStack
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.feature.plan.DefaultPlanComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.profile.DefaultProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlin.coroutines.CoroutineContext

/**
 * The **Main shell** (ADR-0013): the post-[[Account]] surface that hosts the **Destination graph**.
 * It exposes the [Destination] registry ([destinations]) the View renders as a nav suite, the active
 * Destination as a Decompose [ChildStack], and a single shell-level **overlay route** ([overlay]) that
 * sits above the whole graph (ADR-0015).
 *
 * Tier-1 switching ([selectDestination]) is **lateral and state-preserving — multiple back stacks**
 * (ADR-0007 tier 1 / ADR-0008 G5): each Destination's component (and its own tier-2 panes / tier-3
 * drill-downs) is **retained** while another Destination is foreground, so leaving Tasks for Plan and
 * returning restores Tasks exactly. This is realized with Decompose `bringToFront`, which reuses the
 * existing child for a Destination already on the stack rather than recreating it. Destinations are
 * created lazily (only on first visit), so the registry scales to a dozen-plus without eager cost.
 *
 * The **overlay route** ([openOverlay] / [dismissOverlay]) is the shared mechanism Search and New will
 * reuse (ADR-0015): a route pushed *above* the foreground Destination and dismissed back to origin,
 * leaving the Destination's retained state untouched. v1 ships only a trivial [OverlayRoute.Placeholder]
 * so the mechanism — render position and back precedence — exists and is exercised; the real routes
 * land with #71 (New) and #73 (Search).
 *
 * The shell also routes the cross-feature intents the Destinations emit (a Plan tap opens that Task in
 * the Tasks Destination; a Tasks "add to plan" and a Profile "sign out" bubble up via [Output] for the
 * host to apply against the Active Account — the same Output-up routing the demo host owned).
 */
interface MainShellComponent {
    /** The ordered Destination registry the nav suite renders — not a fixed count. */
    val destinations: List<Destination>

    /** The foreground Destination + the retained, state-preserving back stack of visited Destinations. */
    val stack: Value<ChildStack<*, DestinationChild>>

    /**
     * The shell-level overlay route, or empty when none is open (ADR-0015). The View renders the
     * [OverlayChild] above the foreground Destination; [onBack] dismisses it first.
     */
    val overlay: Value<ChildSlot<*, OverlayChild>>

    /** Switch foreground Destination laterally, preserving every Destination's retained state. */
    fun selectDestination(destination: Destination)

    /** Push [route] as a shell-level overlay above the foreground Destination (ADR-0015). */
    fun openOverlay(route: OverlayRoute)

    /** Dismiss the open overlay back to the foreground Destination (a no-op if none is open). */
    fun dismissOverlay()

    /**
     * Route Android system-back within the Main shell: dismiss an open [overlay] first, then let the
     * active Destination dismiss its own tier-2/tier-3 state, then fall back from a non-home Destination
     * to the [Destination.Plan] home. Returns whether back was consumed (`false` → the host lets the
     * platform exit the scene).
     */
    fun onBack(): Boolean

    /** The Accounts on this device + the Active one, for the in-shell account switcher (ADR-0014). */
    val accounts: StateFlow<List<Account>>
    val activeAccount: StateFlow<Account?>

    /** Switch the Active Account — re-keys the shell for the new Account, no re-auth (ADR-0002/0012). */
    fun switchAccount(id: AccountId)

    /** A live Destination instance, tagged with which [Destination] it is so the View can render it. */
    sealed interface DestinationChild {
        val destination: Destination

        class Plan(val component: PlanComponent) : DestinationChild {
            override val destination: Destination = Destination.Plan
        }

        class Tasks(val component: TasksComponent) : DestinationChild {
            override val destination: Destination = Destination.Tasks
        }

        class Profile(val component: ProfileComponent) : DestinationChild {
            override val destination: Destination = Destination.Profile
        }

        /**
         * A Destination whose own slice isn't built yet (Calendar #74, Settings #72) — a logic-less
         * child the View renders as a placeholder body. It is still a real tier-1 Destination with its
         * own retained back-stack entry, so it drops in its slice later with no structural change.
         */
        class Placeholder(override val destination: Destination) : DestinationChild
    }

    /** A shell-level overlay instance the View renders above the foreground Destination (ADR-0015). */
    sealed interface OverlayChild {
        /** The v1 stand-in until Search (#73) / New (#71) supply real overlay content. */
        data object Placeholder : OverlayChild
    }

    sealed interface Output {
        /** A Tasks "add to plan" intent, re-emitted for the host (the Plan write isn't the shell's job). */
        data class AddToPlanRequested(val id: TaskId) : Output

        /** A Profile "sign out" intent — the host secure-wipes the Active Account (ADR-0009/0012). */
        data object SignOutRequested : Output
    }
}

/** The shell-level overlay routes Search/New will become (ADR-0015); v1 ships only [Placeholder]. */
sealed interface OverlayRoute {
    /** The trivial v1 placeholder so the overlay mechanism is wired and testable. */
    data object Placeholder : OverlayRoute
}

class DefaultMainShellComponent(
    componentContext: ComponentContext,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val authRepository: AuthRepository,
    private val account: Account,
    private val today: LocalDate,
    private val timeZone: String,
    override val accounts: StateFlow<List<Account>> = MutableStateFlow(emptyList()),
    override val activeAccount: StateFlow<Account?> = MutableStateFlow(null),
    private val onSwitchAccount: (AccountId) -> Unit = {},
    private val output: (MainShellComponent.Output) -> Unit = {},
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
) : MainShellComponent, ComponentContext by componentContext {

    override val destinations: List<Destination> = Destination.entries

    override fun switchAccount(id: AccountId) = onSwitchAccount(id)

    // Plain-data configs (serializer = null → no state restoration wired in v1, matching the feature
    // components). One per Destination; equality is what `bringToFront` matches to retain a child.
    private sealed interface Config {
        data object Plan : Config
        data object Calendar : Config
        data object Tasks : Config
        data object Profile : Config
        data object Settings : Config
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

    private val overlayNavigation = SlotNavigation<OverlayRoute>()

    override val overlay: Value<ChildSlot<*, MainShellComponent.OverlayChild>> =
        childSlot(
            source = overlayNavigation,
            serializer = null,
            key = "OverlaySlot",
            handleBackButton = false, // overlay back is routed via onBack() with precedence
            childFactory = ::createOverlay,
        )

    override fun selectDestination(destination: Destination) {
        // bringToFront reuses the child if this Destination is already on the stack (state-preserving),
        // otherwise creates it — the multiple-back-stack switch (ADR-0007 tier 1 / ADR-0008 G5).
        navigation.bringToFront(destination.toConfig())
    }

    override fun openOverlay(route: OverlayRoute) = overlayNavigation.activate(route)

    override fun dismissOverlay() = overlayNavigation.dismiss()

    override fun onBack(): Boolean {
        // An open overlay sits above the whole graph (Search/New, ADR-0015), so it dismisses first.
        if (overlay.value.child != null) {
            dismissOverlay()
            return true
        }
        val active = stack.value.active.instance
        if (active.handleInnerBack()) return true
        // Nothing left inside the active Destination: from a non-home Destination, return to the Plan
        // home (its retained state is untouched); on the home Destination, let the platform exit.
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

            Config.Calendar ->
                MainShellComponent.DestinationChild.Placeholder(Destination.Calendar)

            Config.Tasks ->
                MainShellComponent.DestinationChild.Tasks(
                    DefaultTasksComponent(
                        componentContext = childContext,
                        taskRepository = taskRepository,
                        output = ::onTasksOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Profile ->
                MainShellComponent.DestinationChild.Profile(
                    DefaultProfileComponent(
                        componentContext = childContext,
                        authRepository = authRepository,
                        account = account,
                        output = ::onProfileOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Settings ->
                MainShellComponent.DestinationChild.Placeholder(Destination.Settings)
        }

    private fun createOverlay(
        route: OverlayRoute,
        @Suppress("UNUSED_PARAMETER") childContext: ComponentContext,
    ): MainShellComponent.OverlayChild =
        when (route) {
            OverlayRoute.Placeholder -> MainShellComponent.OverlayChild.Placeholder
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

    private fun onProfileOutput(output: ProfileComponent.Output) {
        when (output) {
            // Sign out is a host concern (it crosses the Account-isolation boundary, ADR-0002): re-emit
            // for the RootComponent to secure-wipe the Active Account and return to the Auth shell.
            ProfileComponent.Output.SignOutRequested ->
                this.output(MainShellComponent.Output.SignOutRequested)
        }
    }

    private fun Destination.toConfig(): Config =
        when (this) {
            Destination.Plan -> Config.Plan
            Destination.Calendar -> Config.Calendar
            Destination.Tasks -> Config.Tasks
            Destination.Profile -> Config.Profile
            Destination.Settings -> Config.Settings
        }

    /**
     * Tier-2/tier-3 back for the active Destination, using only its public intents. Single-pane and
     * placeholder Destinations have no inner back; Tasks delegates to [dismissForegroundPane].
     */
    private fun MainShellComponent.DestinationChild.handleInnerBack(): Boolean =
        when (this) {
            is MainShellComponent.DestinationChild.Plan -> false
            is MainShellComponent.DestinationChild.Tasks -> component.dismissForegroundPane()
            is MainShellComponent.DestinationChild.Profile -> false
            is MainShellComponent.DestinationChild.Placeholder -> false
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
