package com.circuitstitch.deferno.macos.bridge

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import com.circuitstitch.deferno.feature.calendar.CalendarState
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SpeechEngineSettings
import com.circuitstitch.deferno.feature.signin.SignInComponent
import com.circuitstitch.deferno.feature.signin.SignInState
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.SearchState
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.macos.TasksRoot
import com.circuitstitch.deferno.shell.AuthShellComponent
import com.circuitstitch.deferno.shell.ChromeActionKind
import com.circuitstitch.deferno.shell.ChromeSpec
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.DictationField
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.NavSlot
import com.circuitstitch.deferno.shell.NewComponent
import com.circuitstitch.deferno.shell.NewState
import com.circuitstitch.deferno.shell.NewStatus
import com.circuitstitch.deferno.shell.OverlayRoute
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlin.time.Instant

/**
 * The **shell half of the SKIE-free bridge** (#35) — the navigation frame + the new Destinations the
 * SwiftUI Views render over the shared shell ([RootComponent] → Auth/Main → the five Destinations + the
 * Search/New overlays, ADR-0013/0017). It extends the Tasks/Plan-only [Bridge.kt] with:
 *  - flattened, **concrete** wrappers for the Decompose `Value<ChildStack<*, C>>` / `Value<ChildSlot<*, C>>`
 *    navigation containers (Root Auth↔Main, the Destination stack, the overlay slot, the Settings
 *    drill-down) — concrete (not generic) because every child type is a Kotlin **sealed interface**, which
 *    Obj-C lightweight generics can't carry as a type argument (it would erase to `id`);
 *  - `*StateBridge` factories for the new leaf states (SignIn / Calendar / Search / New / Settings) that
 *    pin each `StateFlow`'s element so Swift gets a strongly-typed [StateFlowBridge] (same rule as
 *    `planStateBridge`); [ProfileState] is sealed so it gets a concrete bridge too;
 *  - `as?`-cascade accessors + value-class `.value` unwrap seams + `LocalDate`/`Instant` parse/format
 *    helpers, so Swift never names a sealed subclass, a star-projected generic, or an opaque date type.
 *
 * When SKIE supports Kotlin 2.4.0 (ADR-0003) this whole package can be deleted and the Views can observe
 * the components' `StateFlow`/`Value`/sealed types directly.
 */

// ---------------------------------------------------------------------------------------------------
// Decompose container wrappers — concrete (sealed child types can't be Obj-C generic args)
// ---------------------------------------------------------------------------------------------------

/** Flattens the root Auth↔Main stack to its single active [RootComponent.Child]. */
class RootStackBridge internal constructor(private val delegate: Value<ChildStack<*, RootComponent.Child>>) {
    val active: RootComponent.Child get() = delegate.value.active.instance

    fun subscribe(onEach: (RootComponent.Child) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it.active.instance) }
        return Subscription { cancellation.cancel() }
    }
}

/** Flattens the Main Destination stack to the foreground [MainShellComponent.DestinationChild]. */
class DestinationStackBridge internal constructor(
    private val delegate: Value<ChildStack<*, MainShellComponent.DestinationChild>>,
) {
    val active: MainShellComponent.DestinationChild get() = delegate.value.active.instance

    fun subscribe(onEach: (MainShellComponent.DestinationChild) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it.active.instance) }
        return Subscription { cancellation.cancel() }
    }
}

/** Flattens the shell overlay slot to its (nullable) open [MainShellComponent.OverlayChild]. */
class OverlaySlotBridge internal constructor(
    private val delegate: Value<ChildSlot<*, MainShellComponent.OverlayChild>>,
) {
    val current: MainShellComponent.OverlayChild? get() = delegate.value.child?.instance

    fun subscribe(onEach: (MainShellComponent.OverlayChild?) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it.child?.instance) }
        return Subscription { cancellation.cancel() }
    }
}

/** Flattens the Settings tier-3 drill-down stack to the active [SettingsComponent.SettingsChild]. */
class SettingsStackBridge internal constructor(
    private val delegate: Value<ChildStack<*, SettingsComponent.SettingsChild>>,
) {
    val active: SettingsComponent.SettingsChild get() = delegate.value.active.instance

    fun subscribe(onEach: (SettingsComponent.SettingsChild) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it.active.instance) }
        return Subscription { cancellation.cancel() }
    }
}

/** Flattens the Plan Destination's tier-3 stack (#51) to the active [MainShellComponent.PlanChild]. */
class PlanStackBridge internal constructor(
    private val delegate: Value<ChildStack<*, MainShellComponent.PlanChild>>,
) {
    val active: MainShellComponent.PlanChild get() = delegate.value.active.instance

    fun subscribe(onEach: (MainShellComponent.PlanChild) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it.active.instance) }
        return Subscription { cancellation.cancel() }
    }
}

/**
 * Flattens a detached detail window's stack (#196) to its foreground [TaskDetailComponent]. [canGoBack]
 * is true once a subtask has been drilled (the window's Back control shows only then). Mirrors
 * [PlanStackBridge] — the window is the Plan tier-3 stack minus the Dashboard base (ADR-0033).
 */
class DetailStackBridge internal constructor(
    private val delegate: Value<ChildStack<*, com.circuitstitch.deferno.feature.tasks.TaskDetailComponent>>,
) {
    val active: com.circuitstitch.deferno.feature.tasks.TaskDetailComponent get() = delegate.value.active.instance
    val canGoBack: Boolean get() = delegate.value.backStack.isNotEmpty()

    fun subscribe(onEach: (com.circuitstitch.deferno.feature.tasks.TaskDetailComponent) -> Unit): Subscription {
        val cancellation = delegate.subscribe { onEach(it.active.instance) }
        return Subscription { cancellation.cancel() }
    }
}

/** Observes the [ProfileComponent]'s sealed [ProfileState] (concrete — a sealed type, not a data class). */
class ProfileStateBridge internal constructor(private val flow: StateFlow<ProfileState>) {
    val value: ProfileState get() = flow.value

    fun subscribe(onEach: (ProfileState) -> Unit): Subscription {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { flow.collect { onEach(it) } }
        return Subscription { scope.cancel() }
    }
}

/** The in-shell account switcher (ADR-0014): the roster + the Active Account, re-read on either change. */
class AccountSwitcherBridge internal constructor(
    private val accountsFlow: StateFlow<List<Account>>,
    private val activeFlow: StateFlow<Account?>,
) {
    val accounts: List<Account> get() = accountsFlow.value
    val active: Account? get() = activeFlow.value

    fun subscribe(onChange: () -> Unit): Subscription {
        val scope = CoroutineScope(Dispatchers.Main)
        scope.launch { accountsFlow.collect { onChange() } }
        scope.launch { activeFlow.collect { onChange() } }
        return Subscription { scope.cancel() }
    }
}

// ---------------------------------------------------------------------------------------------------
// Bridge factories — pin each generic / wrap each component so Swift never names the reactive type
// ---------------------------------------------------------------------------------------------------

fun rootStackBridge(component: RootComponent): RootStackBridge = RootStackBridge(component.stack)
fun themeSettingsBridge(component: RootComponent): StateFlowBridge<UserSettings> = StateFlowBridge(component.themeSettings)

fun signInStateBridge(component: SignInComponent): StateFlowBridge<SignInState> = StateFlowBridge(component.state)

fun destinationStackBridge(component: MainShellComponent): DestinationStackBridge = DestinationStackBridge(component.stack)
fun overlaySlotBridge(component: MainShellComponent): OverlaySlotBridge = OverlaySlotBridge(component.overlay)

// ---------------------------------------------------------------------------------------------------
// Adaptive top-bar chrome (Cand 1) — Swift renders the shell-computed ChromeSpec as the one shell bar
// (the SwiftUI twin of Android's ShellTopBar). ChromeSpec/ChromeAction are Compose-free in app/shell;
// these mirror the index-based accessor pattern (see inference options) so Swift never names a sealed
// type or invokes a Kotlin lambda property directly.
// ---------------------------------------------------------------------------------------------------

fun chromeBridge(component: MainShellComponent): StateFlowBridge<ChromeSpec> = StateFlowBridge(component.chrome)
fun chromeTitle(spec: ChromeSpec): String = spec.title
fun chromeDrilled(spec: ChromeSpec): Boolean = spec.drilled
fun chromeActionCount(spec: ChromeSpec): Int = spec.actions.size

/** The trailing action's kind at [index], as a stable String the View maps to a glyph + a11y label. */
fun chromeActionKind(spec: ChromeSpec, index: Int): String = when (spec.actions[index].kind) {
    ChromeActionKind.Refresh -> "Refresh"
    ChromeActionKind.BrainDump -> "BrainDump"
    ChromeActionKind.New -> "New"
}

/** Run the trailing action at [index] (a shell/component handler — e.g. New opens the create overlay). */
fun chromeInvoke(spec: ChromeSpec, index: Int) = spec.actions[index].onInvoke()
fun accountSwitcherBridge(component: MainShellComponent): AccountSwitcherBridge =
    AccountSwitcherBridge(component.accounts, component.activeAccount)

fun calendarStateBridge(component: CalendarComponent): StateFlowBridge<CalendarState> = StateFlowBridge(component.state)
fun profileStateBridge(component: ProfileComponent): ProfileStateBridge = ProfileStateBridge(component.state)

fun settingsStackBridge(component: SettingsComponent): SettingsStackBridge = SettingsStackBridge(component.stack)
fun settingsStateBridge(component: SettingsComponent): StateFlowBridge<UserSettings> = StateFlowBridge(component.settings)
fun speechEngineBridge(component: SettingsComponent): StateFlowBridge<SpeechEngineSettings> = StateFlowBridge(component.speechEngine)

fun searchStateBridge(component: SearchComponent): StateFlowBridge<SearchState> = StateFlowBridge(component.state)
fun newStateBridge(component: NewComponent): StateFlowBridge<NewState> = StateFlowBridge(component.state)

// ---------------------------------------------------------------------------------------------------
// Sealed discriminators — Swift reads these instead of casting Kotlin/Native flattened class names
// ---------------------------------------------------------------------------------------------------

fun rootChildAuth(child: RootComponent.Child): AuthShellComponent? =
    (child as? RootComponent.Child.Auth)?.component

fun rootChildMain(child: RootComponent.Child): MainShellComponent? =
    (child as? RootComponent.Child.Main)?.component

/** Which Destination this foreground child is (the View keys the nav selection + body off it). */
fun destinationOf(child: MainShellComponent.DestinationChild): Destination = child.destination

/**
 * Wrap a [TasksComponent] into the Swift-facing [TasksRoot] handle (the Item [tree] + the co-resident
 * detail slot + `activePane` recency) the `TasksScreen` renders. [TasksRoot]'s constructor is `internal`
 * (the demo builds it directly), so the shell obtains one through this same-module factory.
 */
fun tasksRoot(component: TasksComponent): TasksRoot = TasksRoot(component)

/**
 * The Plan Destination child — now a tier-3 host (#51): Swift reads its [stack][planStackBridge]
 * (Dashboard base + drilled Task detail) and routes inner back through [planBack].
 */
fun destPlan(child: MainShellComponent.DestinationChild) =
    child as? MainShellComponent.DestinationChild.Plan

/** The Plan Destination's tier-3 stack (Dashboard → drilled Task detail), for the SwiftUI NavigationStack. */
fun planStackBridge(plan: MainShellComponent.DestinationChild.Plan): PlanStackBridge = PlanStackBridge(plan.stack)

/** Pop an open Plan detail toward the dashboard (system back / nav-bar back); false at the dashboard base. */
fun planBack(plan: MainShellComponent.DestinationChild.Plan): Boolean = plan.onBack()

/** The Plan dashboard at the base of the stack (the daily Plan list). */
fun planChildDashboard(child: MainShellComponent.PlanChild) =
    (child as? MainShellComponent.PlanChild.Dashboard)?.component

/** A drilled-in single-Task detail on the Plan stack (a Plan tap / subtask drill, #51). */
fun planChildDetail(child: MainShellComponent.PlanChild) =
    (child as? MainShellComponent.PlanChild.Detail)?.component

fun destCalendar(child: MainShellComponent.DestinationChild) =
    (child as? MainShellComponent.DestinationChild.Calendar)?.component

fun destTasks(child: MainShellComponent.DestinationChild) =
    (child as? MainShellComponent.DestinationChild.Tasks)?.component

fun destProfile(child: MainShellComponent.DestinationChild) =
    (child as? MainShellComponent.DestinationChild.Profile)?.component

fun destSettings(child: MainShellComponent.DestinationChild) =
    (child as? MainShellComponent.DestinationChild.Settings)?.component

fun overlaySearch(child: MainShellComponent.OverlayChild) =
    (child as? MainShellComponent.OverlayChild.Search)?.component

fun overlayNew(child: MainShellComponent.OverlayChild) =
    (child as? MainShellComponent.OverlayChild.New)?.component

// The in-app Help → Feedback overlay (#375). The SwiftUI feedback View + its file picker are a macOS
// follow-up (this target builds its klib on any host but links only on a Mac); the component + its
// state are ready here so the Swift side can render the form the same way it renders New.
fun overlayFeedback(child: MainShellComponent.OverlayChild) =
    (child as? MainShellComponent.OverlayChild.Feedback)?.component

fun settingsChildIsList(child: SettingsComponent.SettingsChild): Boolean =
    child is SettingsComponent.SettingsChild.List

fun settingsChildCategory(child: SettingsComponent.SettingsChild): SettingsCategory? =
    (child as? SettingsComponent.SettingsChild.Detail)?.category

/** The Settings category catalog the list renders (one row each; the two unbacked ones are stubs). */
fun settingsCategories(): List<SettingsCategory> = SettingsCategory.entries
fun settingsCategoryName(category: SettingsCategory): String = category.name
fun settingsCategoryBacked(category: SettingsCategory): Boolean = category.backed
fun settingsCategoriesEqual(a: SettingsCategory, b: SettingsCategory): Boolean = a == b

// Done-visibility windows are nullable Longs ("Always" = null); encode null as -1 so Swift avoids
// boxing `KotlinLong?`, and set one window while preserving the other.
fun doneVisibilityGlobalSeconds(settings: UserSettings): Long = settings.globalDoneVisibilitySeconds ?: -1L
fun doneVisibilityDashboardSeconds(settings: UserSettings): Long = settings.dashboardDoneVisibilitySeconds ?: -1L
fun setGlobalDoneVisibility(component: SettingsComponent, settings: UserSettings, seconds: Long) =
    component.onDoneVisibilityChanged(seconds.takeIf { it >= 0 }, settings.dashboardDoneVisibilitySeconds)
fun setDashboardDoneVisibility(component: SettingsComponent, settings: UserSettings, seconds: Long) =
    component.onDoneVisibilityChanged(settings.globalDoneVisibilitySeconds, seconds.takeIf { it >= 0 })

fun profileUser(state: ProfileState): User? = (state as? ProfileState.SignedIn)?.user
fun profileIsLoading(state: ProfileState): Boolean = state is ProfileState.Loading
fun profileIsReauthRequired(state: ProfileState): Boolean = state is ProfileState.ReauthRequired
fun profileIsUnavailable(state: ProfileState): Boolean = state is ProfileState.Unavailable

fun newStatusIsSubmitting(state: NewState): Boolean = state.status is NewStatus.Submitting
fun newStatusIsOffline(state: NewState): Boolean = state.status is NewStatus.Offline
fun newStatusFailedMessage(state: NewState): String? = (state.status as? NewStatus.Failed)?.message

// Dictation (#92, ADR-0018 / ADR-0029 Phase 2) — Swift reads the lifecycle off the sealed DictationStatus
// without naming its subclasses, and toggles the mic per field.
/** Whether [field]'s mic is currently capturing (the View shows it active). */
fun dictationListeningField(state: NewState, field: DictationField): Boolean =
    state.dictation is DictationStatus.Listening && state.dictationField == field

/**
 * A gentle, non-PII message for a settled dictation problem (permission/engine), or `null` when idle /
 * listening — so the View shows it only when there's something honest to say (never a silent failure).
 */
fun dictationMessage(state: NewState): String? = when (state.dictation) {
    is DictationStatus.PermissionDenied ->
        "Microphone access is needed to dictate."
    is DictationStatus.PermissionPermanentlyDenied ->
        "Microphone access is off — enable it in System Settings › Privacy & Security."
    is DictationStatus.Error ->
        "Couldn't transcribe just now. Try again."
    else -> null
}

// ---------------------------------------------------------------------------------------------------
// Destination registry — Swift reads name/slot for label/icon + selection (View concern, ADR-0013)
// ---------------------------------------------------------------------------------------------------

/** The stable enum name ("Plan"/"Calendar"/"Tasks"/"Profile"/"Settings") the View maps to label + icon. */
fun destinationName(destination: Destination): String = destination.name
fun destinationIsPrimary(destination: Destination): Boolean = destination.slot == NavSlot.Primary
fun destinationsEqual(a: Destination, b: Destination): Boolean = a == b

// ---------------------------------------------------------------------------------------------------
// Value-class unwrap + intent seams — Swift holds opaque ids; Kotlin reads/builds the underlying value
// ---------------------------------------------------------------------------------------------------

/** Stable String identity of an [Account] for SwiftUI list diffing (AccountId value class is header-erased). */
fun accountKey(account: Account): String = account.id.value

/** Switch the Active Account without Swift constructing an `AccountId` (its `init` throws on blank). */
fun switchToAccount(component: MainShellComponent, account: Account) = component.switchAccount(account.id)

/** Stable String identity of a speech-engine option (SpeechEngineId value class is header-erased). */
fun speechOptionKey(option: SpeechEngineOption): String = option.id.value

/** The currently-selected speech engine id, as a String (for matching the selected option row). */
fun speechSelectedKey(settings: SpeechEngineSettings): String = settings.selected.value

/** Select a speech engine without Swift naming the `SpeechEngineId` value class. */
fun selectSpeechEngine(component: SettingsComponent, option: SpeechEngineOption) =
    component.onSpeechEngineSelected(option.id)

// ---------------------------------------------------------------------------------------------------
// Calendar — LocalDate is an opaque Kotlin type; Swift gets grid days from here & passes them back
// ---------------------------------------------------------------------------------------------------

/** The 6-week (42-day), Monday-start grid for [visibleMonth] (mirrors the component's monthGridWindow). */
fun calendarGridDays(visibleMonth: LocalDate): List<LocalDate> {
    val first = LocalDate(visibleMonth.year, visibleMonth.month, 1)
    val lead = first.dayOfWeek.isoDayNumber - 1
    val gridStart = first.minus(lead, DateTimeUnit.DAY)
    return (0 until 42).map { gridStart.plus(it, DateTimeUnit.DAY) }
}

fun localDateYear(date: LocalDate): Int = date.year
fun localDateMonthNumber(date: LocalDate): Int = date.month.ordinal + 1
fun localDateDay(date: LocalDate): Int = date.day

/** ISO day-of-week (1=Mon … 7=Sun) so the View can label the weekday header / day-agenda heading. */
fun localDateIsoDayOfWeek(date: LocalDate): Int = date.dayOfWeek.isoDayNumber
fun localDateEquals(a: LocalDate, b: LocalDate): Boolean = a == b
fun localDateInMonth(date: LocalDate, monthRef: LocalDate): Boolean =
    date.year == monthRef.year && date.month == monthRef.month

/** The cell dot count for [date] in the visible grid window (Map<LocalDate,Int> is awkward in Swift). */
fun markerCount(state: CalendarState, date: LocalDate): Int = state.markers[date] ?: 0

/** Occurrence-act gating for an agenda row (ADR — Habit is binary; only Events reschedule). */
fun calendarItemActionable(item: CalendarItem): Boolean = item.isActionableOccurrence
fun calendarItemIsHabit(item: CalendarItem): Boolean = item.kind == ItemKind.Habit
fun calendarItemIsEvent(item: CalendarItem): Boolean = item.kind == ItemKind.Event

// ---------------------------------------------------------------------------------------------------
// LocalDate / Instant parse + format — the New + Search forms type ISO text; Kotlin owns the codecs
// ---------------------------------------------------------------------------------------------------

/** Parse an ISO `yyyy-MM-dd` string, or `null` if blank/unparseable (the form clears the field then). */
fun parseLocalDate(text: String): LocalDate? = runCatching { LocalDate.parse(text.trim()) }.getOrNull()
fun formatLocalDate(date: LocalDate?): String = date?.toString() ?: ""

/** Parse an ISO-8601 / RFC-3339 instant string, or `null` if blank/unparseable. */
fun parseInstant(text: String): Instant? = runCatching { Instant.parse(text.trim()) }.getOrNull()
fun formatInstant(instant: Instant?): String = instant?.toString() ?: ""

// ---------------------------------------------------------------------------------------------------
// Search — Set<WorkingState>/Set<String> are awkward in Swift; expose membership + a list
// ---------------------------------------------------------------------------------------------------

fun searchHasStatus(state: SearchState, status: com.circuitstitch.deferno.core.model.WorkingState): Boolean =
    status in state.statuses

fun searchLabels(state: SearchState): List<String> = state.labels.toList()

// SearchSort lives in (non-exported) core:data, so Swift can't name its entries; these framework-module
// helpers pull it into the header and let Swift pass the opaque sort tokens back without naming them.
fun searchSortValues(): List<com.circuitstitch.deferno.core.data.task.SearchSort> =
    com.circuitstitch.deferno.core.data.task.SearchSort.entries
fun searchSortKey(sort: com.circuitstitch.deferno.core.data.task.SearchSort): String = sort.name
fun searchCurrentSortKey(state: SearchState): String = state.sort.name
fun setSearchSort(component: SearchComponent, sort: com.circuitstitch.deferno.core.data.task.SearchSort) =
    component.onSortChanged(sort)

// ---------------------------------------------------------------------------------------------------
// New — the explicit kind picker (ADR-0015): the ordered kinds the segmented control renders
// ---------------------------------------------------------------------------------------------------

fun itemKinds(): List<ItemKind> = ItemKind.entries
fun itemKindsEqual(a: ItemKind, b: ItemKind): Boolean = a == b

// ---------------------------------------------------------------------------------------------------
// Overlay routes — open Search / New without Swift naming the `OverlayRoute` sealed type
// ---------------------------------------------------------------------------------------------------

/** Open the global Search overlay (the ⌕ in the shell top bar, #73). */
fun openSearchOverlay(component: MainShellComponent) = component.openOverlay(OverlayRoute.Search)

/** Open the New create overlay, undated (the shell FAB on a non-Calendar Destination, #71). */
fun openNewOverlay(component: MainShellComponent) = component.openOverlay(OverlayRoute.New(null))

/** The active Main shell, or null when the Auth shell is foreground. */
private fun RootComponent.activeMain(): MainShellComponent? =
    (stack.value.active.instance as? RootComponent.Child.Main)?.component

/**
 * Open New on the active shell — pre-dated to the Calendar's selected day when Calendar is foreground
 * (#74), undated elsewhere. The ⌘N menu command's seam: it fires outside the View tree, so it reaches
 * the foreground Destination through the root (the desktop twin of the FAB's `onNewTapped`).
 */
fun openNewOnActiveShell(root: RootComponent) {
    val main = root.activeMain() ?: return
    when (val active = main.stack.value.active.instance) {
        is MainShellComponent.DestinationChild.Calendar -> active.component.onNewForSelectedDay()
        else -> main.openOverlay(OverlayRoute.New(null))
    }
}

/** Refresh whichever Destination is foreground — the View → Refresh / ⌘R menu command. No-op elsewhere. */
fun refreshActiveDestination(root: RootComponent) {
    val main = root.activeMain() ?: return
    when (val active = main.stack.value.active.instance) {
        // Plan is a tier-3 stack now (#51): refresh only the dashboard (a drilled detail has none).
        is MainShellComponent.DestinationChild.Plan ->
            (active.stack.value.active.instance as? MainShellComponent.PlanChild.Dashboard)
                ?.component?.onRefresh() ?: Unit
        is MainShellComponent.DestinationChild.Tasks -> active.component.tree.onRefresh()
        else -> Unit
    }
}
