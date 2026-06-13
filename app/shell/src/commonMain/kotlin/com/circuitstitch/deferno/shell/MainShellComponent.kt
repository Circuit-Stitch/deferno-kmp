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
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackRepository
import com.circuitstitch.deferno.core.data.feedback.FeedbackResult
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.data.task.TaskSearchResult
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.speech.EmptySpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.UnavailableSpeechToText
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import com.circuitstitch.deferno.feature.calendar.DefaultCalendarComponent
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
import com.circuitstitch.deferno.feature.plan.DefaultPlanComponent
import com.circuitstitch.deferno.feature.plan.PlanComponent
import com.circuitstitch.deferno.feature.profile.DefaultProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.settings.DefaultSettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SettingsEditor
import com.circuitstitch.deferno.feature.tasks.DefaultSearchComponent
import com.circuitstitch.deferno.feature.tasks.DefaultTasksComponent
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.SearchTasks
import com.circuitstitch.deferno.feature.tasks.TaskPane
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
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

    /**
     * Request sign-out of the Active Account from a **shell-chrome** affordance (the desktop Account
     * menu, ADR-0017) — emits [Output.SignOutRequested] for the host/root to secure-wipe the Account
     * (ADR-0009/0012). It is the same intent the Profile Destination's sign-out button raises, surfaced
     * at shell level so chrome outside the Destination graph (a menu bar) can raise it too.
     */
    fun signOut()

    /** A live Destination instance, tagged with which [Destination] it is so the View can render it. */
    sealed interface DestinationChild {
        val destination: Destination

        class Plan(val component: PlanComponent) : DestinationChild {
            override val destination: Destination = Destination.Plan
        }

        /** The Calendar Destination (#74): a single-pane month grid + day agenda over Occurrences. */
        class Calendar(val component: CalendarComponent) : DestinationChild {
            override val destination: Destination = Destination.Calendar
        }

        class Tasks(val component: TasksComponent) : DestinationChild {
            override val destination: Destination = Destination.Tasks
        }

        class Profile(val component: ProfileComponent) : DestinationChild {
            override val destination: Destination = Destination.Profile
        }

        /** The Settings tier-3 drill-down (#72): a category list → per-category detail (ADR-0007 t3). */
        class Settings(val component: SettingsComponent) : DestinationChild {
            override val destination: Destination = Destination.Settings
        }

        /**
         * A Destination whose own slice isn't built yet (Calendar #74) — a logic-less child the View
         * renders as a placeholder body. It is still a real tier-1 Destination with its own retained
         * back-stack entry, so it drops in its slice later with no structural change.
         */
        class Placeholder(override val destination: Destination) : DestinationChild
    }

    /** A shell-level overlay instance the View renders above the foreground Destination (ADR-0015). */
    sealed interface OverlayChild {
        /** The v1 stand-in (both Search #73 and New #71 are real routes over the same primitive). */
        data object Placeholder : OverlayChild

        /** The global Search overlay (#73): a real route over the same overlay primitive. */
        class Search(val component: SearchComponent) : OverlayChild

        /** The New create surface (#71): the kind picker + per-kind form, online-only create (ADR-0016). */
        class New(val component: NewComponent) : OverlayChild

        /** The in-app Help → Feedback surface (#375): comment + attachments, online-only submit. */
        class Feedback(val component: FeedbackComponent) : OverlayChild

        /**
         * The **Brain dump** surface (ADR-0027): the home for the dictation-driven Extractor. A
         * stateless placeholder for now — the [InferenceEngine] ships with no credential and returns
         * `Failure.NotConfigured` until #150 wires the relay, so there is nothing to capture/extract
         * yet. When #150 lands this grows a real component (`class BrainDump(val component: …)`).
         */
        data object BrainDump : OverlayChild
    }

    sealed interface Output {
        /** A Tasks "add to plan" intent, re-emitted for the host (the Plan write isn't the shell's job). */
        data class AddToPlanRequested(val id: TaskId) : Output

        /** A Profile "sign out" intent — the host secure-wipes the Active Account (ADR-0009/0012). */
        data object SignOutRequested : Output

        /** A Settings "App Permissions" tap — the host deep-links to the OS app-settings screen (#72). */
        data object OpenOsAppSettings : Output

        /** A Settings "Data & Privacy → export/import" tap — the host deep-links the web app (#72, AC #3). */
        data object OpenDataExportImport : Output

        /** A Settings "Security & 2FA" tap — the host opens the Zitadel console URL when present (#72). */
        data object OpenConsoleUrl : Output

        /** A Settings "Account → View profile" tap — switch laterally to the Profile Destination (#72). */
        data object OpenProfile : Output
    }
}

/** The shell-level overlay routes (ADR-0015): the v1 [Placeholder], plus [Search] (#73) and [New] (#71). */
sealed interface OverlayRoute {
    /** The trivial v1 placeholder so the overlay mechanism is wired and testable. */
    data object Placeholder : OverlayRoute

    /** The global Search route (#73) — launched from the ⌕ in any Destination app bar. */
    data object Search : OverlayRoute

    /**
     * The New create surface (#71): the FAB pushes this above the foreground Destination. [date]
     * pre-dates the form to a chosen day (the Calendar FAB, #74) — `null` opens an undated form.
     */
    data class New(val date: LocalDate? = null) : OverlayRoute

    /** The in-app Help → Feedback surface (#375), opened from Settings → Help & Feedback. */
    data object Feedback : OverlayRoute

    /** The **Brain dump** surface (ADR-0027), opened from the shell top bar's voice_chat action. */
    data object BrainDump : OverlayRoute
}

class DefaultMainShellComponent(
    componentContext: ComponentContext,
    private val taskRepository: TaskRepository,
    private val planRepository: PlanRepository,
    private val authRepository: AuthRepository,
    private val settingsRepository: SettingsRepository,
    // The Settings Destination's User-setting write seam (#72/#173): command-backed in production
    // (AccountSession routes each intent through the command executor, ADR-0007).
    private val settingsEditor: SettingsEditor,
    private val account: Account,
    private val today: LocalDate,
    private val timeZone: String,
    // The Calendar Destination's read source + occurrence-act seam (#74). Defaulted to no-ops so the
    // many shell tests build without supplying them (mirrors workingStateEditor/searchTasks/create).
    private val calendarRepository: CalendarRepository = NoopCalendarRepository,
    private val occurrenceEditor: OccurrenceEditor = NoopOccurrenceEditor,
    // The Tasks working-state write seam (#73), threaded into the Tasks Destination's component so its
    // detail can issue lifecycle Commands. Defaults to a no-op so the many shell tests build without it.
    private val workingStateEditor: WorkingStateEditor = WorkingStateEditor.NONE,
    // The global-search seam (#73): a one-shot, online-only pull the Search overlay drives. Defaults
    // to "no results" so tests that don't exercise Search build without supplying it.
    private val searchTasks: SearchTasks = SearchTasks { _ -> TaskSearchResult.Success(emptyList()) },
    // The in-app Help → Feedback service (#375) the Feedback overlay submits through (presign → PUT →
    // submit). AppScope; defaulted to a no-op Offline so shell tests build without supplying it.
    private val feedbackRepository: FeedbackRepository = NoopFeedbackRepository,
    override val accounts: StateFlow<List<Account>> = MutableStateFlow(emptyList()),
    override val activeAccount: StateFlow<Account?> = MutableStateFlow(null),
    private val onSwitchAccount: (AccountId) -> Unit = {},
    private val output: (MainShellComponent.Output) -> Unit = {},
    private val coroutineContext: CoroutineContext = Dispatchers.Default,
    // The online-only create seam (#71, ADR-0016) the New overlay dispatches through. Defaulted to a
    // no-op Offline so a test that doesn't exercise create needn't supply it.
    private val create: suspend (com.circuitstitch.deferno.core.domain.command.CreateItem.Payload) -> com.circuitstitch.deferno.core.domain.command.CommandResult =
        { com.circuitstitch.deferno.core.domain.command.CommandResult.Offline(com.circuitstitch.deferno.core.domain.command.CommandKind.CreateItem) },
    // Dictation (#92, ADR-0018): the on-device SpeechToText the New surface's mic drives, and the device
    // locale it recognizes. Defaulted to the Unavailable floor / English so the many shell tests build
    // without supplying them — dictation simply offers no mic.
    private val speechToText: SpeechToText = UnavailableSpeechToText,
    private val locale: String = "en-US",
    // The device-local speech-engine [[App setting]] (#93, ADR-0018): the AppScope catalog the Settings
    // Destination reads (registered engines + availability + the device-local choice). Defaulted to the
    // inert [EmptySpeechEngineCatalog] (only "Automatic" → the Settings row hides) so the many shell tests
    // build without it; production threads the real catalog from the AppComponent (like speechToText).
    private val speechEngineCatalog: SpeechEngineCatalog = EmptySpeechEngineCatalog,
    // The New surface's PermissionPermanentlyDenied affordance (#120), host-routed to the OS settings
    // surface for the foreclosed dictation permission. Defaulted to a no-op like the other host actions.
    private val onOpenDictationPermissionSettings: () -> Unit = {},
) : MainShellComponent, ComponentContext by componentContext {

    override val destinations: List<Destination> = Destination.entries

    override fun switchAccount(id: AccountId) = onSwitchAccount(id)

    override fun signOut() = output(MainShellComponent.Output.SignOutRequested)

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
                MainShellComponent.DestinationChild.Calendar(
                    DefaultCalendarComponent(
                        componentContext = childContext,
                        calendarRepository = calendarRepository,
                        occurrenceEditor = occurrenceEditor,
                        today = today,
                        tz = timeZone,
                        output = ::onCalendarOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            Config.Tasks ->
                MainShellComponent.DestinationChild.Tasks(
                    DefaultTasksComponent(
                        componentContext = childContext,
                        taskRepository = taskRepository,
                        output = ::onTasksOutput,
                        workingStateEditor = workingStateEditor,
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
                MainShellComponent.DestinationChild.Settings(
                    DefaultSettingsComponent(
                        componentContext = childContext,
                        settingsRepository = settingsRepository,
                        settingsEditor = settingsEditor,
                        output = ::onSettingsOutput,
                        // The device-local speech-engine App setting (#93) + the device locale its
                        // availability is queried for — sourced from AppScope, not this Account's settings.
                        speechEngineCatalog = speechEngineCatalog,
                        locale = locale,
                        coroutineContext = coroutineContext,
                    ),
                )
        }

    // Scope for the shell-owned overlay work (the New create dispatch, #71). Tied to the injected
    // coroutineContext so a test drives it on its own scheduler.
    private val overlayScope = kotlinx.coroutines.CoroutineScope(coroutineContext + kotlinx.coroutines.SupervisorJob())

    private fun createOverlay(
        route: OverlayRoute,
        childContext: ComponentContext,
    ): MainShellComponent.OverlayChild =
        when (route) {
            OverlayRoute.Placeholder -> MainShellComponent.OverlayChild.Placeholder

            OverlayRoute.Search ->
                MainShellComponent.OverlayChild.Search(
                    DefaultSearchComponent(
                        componentContext = childContext,
                        searchTasks = searchTasks,
                        output = ::onSearchOutput,
                        coroutineContext = coroutineContext,
                    ),
                )

            is OverlayRoute.New -> MainShellComponent.OverlayChild.New(
                DefaultNewComponent(
                    create = create,
                    onCreated = ::dismissOverlay,
                    launch = { block -> overlayScope.launch { block() } },
                    tz = timeZone,
                    initialDate = route.date, // pre-date the form (the Calendar FAB, #74)
                    // Dictation (#92): the mic drives the AppScope SpeechToText on the overlay scope.
                    speech = speechToText,
                    locale = locale,
                    dictationScope = overlayScope,
                    // The foreclosed-permission deep-link (#120), routed through to the host.
                    onOpenDictationPermissionSettings = onOpenDictationPermissionSettings,
                ),
            )

            OverlayRoute.Feedback -> MainShellComponent.OverlayChild.Feedback(
                DefaultFeedbackComponent(
                    repository = feedbackRepository,
                    onDone = ::dismissOverlay,
                    launch = { block -> overlayScope.launch { block() } },
                ),
            )

            // Brain dump (ADR-0027): a stateless placeholder route until #150 wires the Extractor's
            // inference credential — nothing to construct yet.
            OverlayRoute.BrainDump -> MainShellComponent.OverlayChild.BrainDump
        }

    private fun onSearchOutput(output: SearchComponent.Output) {
        when (output) {
            // A result tap opens that Task in the Tasks Destination — dismiss the overlay, switch
            // laterally, then route the open through the list's public intent (the same path a real
            // list tap takes, mirroring onPlanOutput).
            is SearchComponent.Output.OpenTask -> {
                dismissOverlay()
                navigation.bringToFront(Config.Tasks)
                val tasks = stack.value.active.instance as MainShellComponent.DestinationChild.Tasks
                tasks.component.list.onTaskClicked(output.id)
            }
            SearchComponent.Output.Dismissed -> dismissOverlay()
        }
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

    private fun onCalendarOutput(output: CalendarComponent.Output) {
        when (output) {
            // The Calendar FAB opens New pre-dated to the selected day (#74 AC) — push the New overlay
            // carrying that date, reusing the same overlay primitive every create surface rides.
            is CalendarComponent.Output.CreateForDay -> openOverlay(OverlayRoute.New(output.date))
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

    private fun onSettingsOutput(output: SettingsComponent.Output) {
        when (output) {
            // OS / web deep-links cross the app boundary (an Android Intent), so they land at the host (#72).
            SettingsComponent.Output.OpenOsAppSettings ->
                this.output(MainShellComponent.Output.OpenOsAppSettings)
            // Export/import has no client endpoint at v0.1, so it stays a reachable web action: re-emit
            // for the host to deep-link the web app (AC #3, ADR-0015).
            SettingsComponent.Output.OpenDataExportImport ->
                this.output(MainShellComponent.Output.OpenDataExportImport)
            // Feedback is now an in-app surface (#375): open the Feedback overlay over the foreground
            // Destination, the same overlay primitive Search/New ride — no web round-trip.
            SettingsComponent.Output.OpenSubmitFeedback ->
                openOverlay(OverlayRoute.Feedback)
            SettingsComponent.Output.OpenConsoleUrl ->
                this.output(MainShellComponent.Output.OpenConsoleUrl)
            // "View profile" is a lateral switch within the shell — handle it here (the shell owns the
            // Destination graph), and also surface it for the host (parity with the other Outputs).
            SettingsComponent.Output.OpenProfile -> {
                navigation.bringToFront(Config.Profile)
                this.output(MainShellComponent.Output.OpenProfile)
            }
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
            // Calendar is single-pane (no tier-2/tier-3): nothing to dismiss inside it.
            is MainShellComponent.DestinationChild.Calendar -> false
            is MainShellComponent.DestinationChild.Tasks -> component.dismissForegroundPane()
            is MainShellComponent.DestinationChild.Profile -> false
            // Settings is a tier-3 drill-down: back pops an open category detail to the list first (#72).
            is MainShellComponent.DestinationChild.Settings -> component.onBack()
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

/** No-op Calendar read source — the shell's test default when no Account session supplies one (#74). */
private val NoopCalendarRepository = object : CalendarRepository {
    override fun observeMarkers(from: LocalDate, to: LocalDate) =
        MutableStateFlow<Map<LocalDate, Int>>(emptyMap())

    override fun observeDay(date: LocalDate) = MutableStateFlow<List<CalendarItem>>(emptyList())
    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
    override suspend fun reconcile() {}
}

/** No-op occurrence-act seam — the shell's test default (a real one is command-backed, #74). */
private val NoopOccurrenceEditor = object : OccurrenceEditor {
    override suspend fun mark(itemId: String, action: OccurrenceAction) {}
    override suspend fun clear(itemId: String) {}
    override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
}

/** No-op feedback service — the shell's test default when no AppComponent supplies one (#375). */
internal val NoopFeedbackRepository = object : FeedbackRepository {
    override suspend fun submit(draft: com.circuitstitch.deferno.core.data.feedback.FeedbackDraft): FeedbackResult =
        FeedbackResult.Offline
}
