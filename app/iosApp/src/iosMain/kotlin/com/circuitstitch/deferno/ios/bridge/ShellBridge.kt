package com.circuitstitch.deferno.ios.bridge

import com.arkivanov.decompose.router.slot.ChildSlot
import com.arkivanov.decompose.router.stack.ChildStack
import com.arkivanov.decompose.value.Value
import com.circuitstitch.deferno.feature.calendar.CalendarComponent
import com.circuitstitch.deferno.feature.calendar.CalendarState
import com.circuitstitch.deferno.feature.profile.ProfileComponent
import com.circuitstitch.deferno.feature.profile.ProfileState
import com.circuitstitch.deferno.feature.settings.InferenceEngineSettings
import com.circuitstitch.deferno.feature.settings.SettingsCategory
import com.circuitstitch.deferno.feature.settings.SettingsComponent
import com.circuitstitch.deferno.feature.settings.SpeechEngineSettings
import com.circuitstitch.deferno.feature.signin.SignInComponent
import com.circuitstitch.deferno.feature.signin.SignInState
import com.circuitstitch.deferno.feature.tasks.SearchComponent
import com.circuitstitch.deferno.feature.tasks.SearchState
import com.circuitstitch.deferno.core.agent.InferenceEngineAvailability
import com.circuitstitch.deferno.core.agent.InferenceEngineId
import com.circuitstitch.deferno.core.agent.InferenceEngineOrigin
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.SpeechEngineOption
import com.circuitstitch.deferno.feature.tasks.TasksComponent
import com.circuitstitch.deferno.ios.TasksRoot
import com.circuitstitch.deferno.shell.AuthShellComponent
import com.circuitstitch.deferno.shell.ChromeActionKind
import com.circuitstitch.deferno.shell.ChromeSpec
import com.circuitstitch.deferno.shell.Destination
import com.circuitstitch.deferno.shell.FeedbackCategory
import com.circuitstitch.deferno.shell.FeedbackComponent
import com.circuitstitch.deferno.shell.FeedbackFile
import com.circuitstitch.deferno.shell.FeedbackState
import com.circuitstitch.deferno.shell.FeedbackStatus
import com.circuitstitch.deferno.shell.MainShellComponent
import com.circuitstitch.deferno.shell.NavSlot
import com.circuitstitch.deferno.shell.NewComponent
import com.circuitstitch.deferno.shell.NewState
import com.circuitstitch.deferno.shell.NewStatus
import com.circuitstitch.deferno.shell.OverlayRoute
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import platform.Foundation.NSData
import kotlinx.datetime.DateTimeUnit
import kotlinx.datetime.LocalDate
import kotlinx.datetime.LocalTime
import kotlinx.datetime.TimeZone
import kotlinx.datetime.isoDayNumber
import kotlinx.datetime.minus
import kotlinx.datetime.plus
import kotlinx.datetime.toLocalDateTime
import kotlin.time.Instant
import com.circuitstitch.deferno.shell.ActivityComponent
import com.circuitstitch.deferno.shell.ActivityFeedRow
import com.circuitstitch.deferno.shell.ActivityFeedState
import com.circuitstitch.deferno.shell.BrainDumpComponent
import com.circuitstitch.deferno.shell.BrainDumpState
import com.circuitstitch.deferno.shell.DictationField
import com.circuitstitch.deferno.shell.DictationStatus
import com.circuitstitch.deferno.shell.Phase
import com.circuitstitch.deferno.core.model.BrainDumpDraft
import com.circuitstitch.deferno.feature.braindumps.InboxComponent
import com.circuitstitch.deferno.feature.braindumps.InboxState
import com.circuitstitch.deferno.feature.tasks.ShakeOutcome

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
fun inferenceEngineBridge(component: SettingsComponent): StateFlowBridge<InferenceEngineSettings> = StateFlowBridge(component.inferenceEngine)

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
 * Wrap a [TasksComponent] into the Swift-facing [TasksRoot] handle (flattened list/detail/tree slots +
 * `activePane` recency) the existing `TasksScreen` renders. [TasksRoot]'s constructor is `internal`
 * (the demo builds it directly), so the shell obtains one through this same-module factory.
 */
fun tasksRoot(component: TasksComponent): TasksRoot = TasksRoot(component)

/**
 * The Plan Destination child — now a tier-3 host (#51): Swift reads its [stack][planStackBridge]
 * (Dashboard base + drilled Task detail) and routes inner back through [planBack]. Returns the child
 * itself (not a single component), so the View can reach both.
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

// The in-app Help → Feedback overlay (#375), opened from Settings → Help & Feedback. The SwiftUI
// FeedbackView renders this the same way it renders New, including file attachments — the Swift side
// picks files (`.fileImporter`) and hands their bytes here as `NSData` (the iOS twin of Android's SAF
// + ContentResolver read); category/subject/body + the send lifecycle bind to the component directly.
fun overlayFeedback(child: MainShellComponent.OverlayChild) =
    (child as? MainShellComponent.OverlayChild.Feedback)?.component

fun feedbackStateBridge(component: FeedbackComponent): StateFlowBridge<FeedbackState> = StateFlowBridge(component.state)

/**
 * Add a file the iOS picker resolved (#375). Swift can't build a [FeedbackFile] (its `bytes` is a
 * Kotlin `ByteArray`), so it passes the picked file's [data] as `NSData` and this copies it across —
 * the iOS counterpart to Android's `ContentResolver`-read in `FeedbackScreen`.
 */
@OptIn(ExperimentalForeignApi::class)
fun feedbackAddAttachment(
    component: FeedbackComponent,
    filename: String,
    contentType: String,
    data: NSData,
) {
    val bytes = data.bytes?.reinterpret<ByteVar>()?.readBytes(data.length.toInt()) ?: ByteArray(0)
    component.addAttachments(listOf(FeedbackFile(filename = filename, contentType = contentType, bytes = bytes)))
}

// FeedbackStatus is a sealed type; Swift reads these instead of casting Kotlin/Native class names (as New does).
fun feedbackStatusIsSubmitting(state: FeedbackState): Boolean = state.status is FeedbackStatus.Submitting
fun feedbackStatusIsOffline(state: FeedbackState): Boolean = state.status is FeedbackStatus.Offline
fun feedbackStatusFailedMessage(state: FeedbackState): String? = (state.status as? FeedbackStatus.Failed)?.message

// The ordered categories the chip picker renders (Swift reads label + equality without naming the enum).
fun feedbackCategories(): List<FeedbackCategory> = FeedbackCategory.entries
fun feedbackCategoryLabel(category: FeedbackCategory): String = category.label
fun feedbackCategoriesEqual(a: FeedbackCategory, b: FeedbackCategory): Boolean = a == b

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

// Agent / inference-engine settings (#150) — index-based accessors so no core:agent type crosses into
// Swift (core:agent isn't in the framework's `export` list, unlike core:speech). Mirrors Android's
// AgentDetail: an "Off" row first, then each registered engine, a cloud engine shown disabled until the
// Account is entitled (RequiresPremium).
fun inferenceOffSelected(state: InferenceEngineSettings): Boolean = state.selected == InferenceEngineId.Off
fun inferenceSelectOff(component: SettingsComponent) = component.onInferenceEngineSelected(InferenceEngineId.Off)
fun inferenceOptionCount(state: InferenceEngineSettings): Int = state.options.size
fun inferenceOptionSelected(state: InferenceEngineSettings, index: Int): Boolean =
    state.selected == state.options[index].id
fun inferenceOptionLocked(state: InferenceEngineSettings, index: Int): Boolean =
    state.options[index].availability is InferenceEngineAvailability.RequiresPremium
fun inferenceSelectOption(component: SettingsComponent, state: InferenceEngineSettings, index: Int) =
    component.onInferenceEngineSelected(state.options[index].id)
fun inferenceOptionLabel(state: InferenceEngineSettings, index: Int): String =
    when (state.options[index].id) {
        InferenceEngineId.DefernoCloud -> "Deferno cloud AI"
        else -> state.options[index].id.value
    }
fun inferenceOptionNote(state: InferenceEngineSettings, index: Int): String {
    val option = state.options[index]
    return when (option.availability) {
        is InferenceEngineAvailability.RequiresPremium -> "Premium — not available for your account yet"
        is InferenceEngineAvailability.Available -> when (option.origin) {
            InferenceEngineOrigin.OnDevice -> "Runs on this device"
            InferenceEngineOrigin.DefernoCloud -> "Sends your text off-device to Deferno's hosted AI"
        }
    }
}

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

/** Open the Brain dump record overlay (the shell top-bar voice action, ADR-0027). */
fun openBrainDumpOverlay(component: MainShellComponent) = component.openOverlay(OverlayRoute.BrainDump)

// ---------------------------------------------------------------------------------------------------
// Inbox + Activity Destinations (ADR-0015 amendment / #260) — the two Secondary Destinations the iOS
// drawer now reaches. Inbox lives in feature:braindumps (newly exported); Activity in app:shell.
// ---------------------------------------------------------------------------------------------------

fun destInbox(child: MainShellComponent.DestinationChild) =
    (child as? MainShellComponent.DestinationChild.Inbox)?.component

fun destActivity(child: MainShellComponent.DestinationChild) =
    (child as? MainShellComponent.DestinationChild.Activity)?.component

fun inboxStateBridge(component: InboxComponent): StateFlowBridge<InboxState> = StateFlowBridge(component.state)

/** Stable identity of a draft for SwiftUI list diffing (BrainDumpDraftId value class is header-erased). */
fun inboxDraftKey(draft: BrainDumpDraft): String = draft.id.value

// Accept / dismiss / undo / clear-note take the draft (Swift can't name its BrainDumpDraftId).
fun acceptInboxDraft(component: InboxComponent, draft: BrainDumpDraft) = component.onAccept(draft.id)
fun dismissInboxDraft(component: InboxComponent, draft: BrainDumpDraft) = component.onDismiss(draft.id)
fun clearInboxNote(component: InboxComponent, draft: BrainDumpDraft) = component.onClearNote(draft.id)

/** A draft's deadline subtitle (LocalDate + optional LocalTime), or empty when undated. */
fun inboxDraftDeadlineLabel(draft: BrainDumpDraft): String {
    val date = draft.completeBy?.toString() ?: return ""
    val time = draft.deadlineTimeOfDay?.let { " ${it}" } ?: ""
    return date + time
}

fun activityStateBridge(component: ActivityComponent): StateFlowBridge<ActivityFeedState> =
    StateFlowBridge(component.state)

/** A render-ready "when" label for an Activity row (Instant → local "yyyy-MM-dd HH:mm"). */
fun activityWhenLabel(row: ActivityFeedRow): String {
    val dt = row.recordedAt.toLocalDateTime(TimeZone.currentSystemDefault())
    val hh = dt.hour.toString().padStart(2, '0')
    val mm = dt.minute.toString().padStart(2, '0')
    return "${dt.date} $hh:$mm"
}

/** The Inbox nav badge — the live count of Ready drafts (shown even before the Inbox is first visited). */
fun inboxReadyCountBridge(component: MainShellComponent): StateFlowBridge<Int> =
    StateFlowBridge(component.inboxReadyCount)

// ---------------------------------------------------------------------------------------------------
// Brain dump overlay (ADR-0027) — the recorder surface; Phase is sealed, so Swift reads its name.
// ---------------------------------------------------------------------------------------------------

fun overlayBrainDump(child: MainShellComponent.OverlayChild) =
    (child as? MainShellComponent.OverlayChild.BrainDump)?.component

fun brainDumpStateBridge(component: BrainDumpComponent): StateFlowBridge<BrainDumpState> =
    StateFlowBridge(component.state)

/** The active recorder phase as a stable String the View maps to its UI. */
fun brainDumpPhaseName(state: BrainDumpState): String = when (state.phase) {
    Phase.Idle -> "Idle"
    Phase.Recording -> "Recording"
    Phase.Enqueued -> "Enqueued"
    Phase.Failed -> "Failed"
    Phase.PermissionDenied -> "PermissionDenied"
    Phase.PermissionPermanentlyDenied -> "PermissionPermanentlyDenied"
}

// ---------------------------------------------------------------------------------------------------
// Item-tree Move + Undo (#228/#230) — MoveMode/MoveUndo are plain data classes Swift reads directly;
// only ShakeOutcome is sealed, so Swift reads the confirm operation through this discriminator.
// ---------------------------------------------------------------------------------------------------

/** The operation a device-shake offers to undo, or null when the shake had nothing to undo. */
fun shakeConfirmOperation(outcome: ShakeOutcome): String? =
    (outcome as? ShakeOutcome.Confirm)?.operation

// ---------------------------------------------------------------------------------------------------
// New form — deadline time-of-day (#348) + dictation (#92). LocalTime/DictationStatus aren't built or
// discriminated in Swift, so these seams own them.
// ---------------------------------------------------------------------------------------------------

fun newDeadlineTimeHour(state: NewState): Int = state.deadlineTime?.hour ?: -1
fun newDeadlineTimeMinute(state: NewState): Int = state.deadlineTime?.minute ?: -1
fun setNewDeadlineTime(component: NewComponent, hour: Int, minute: Int) =
    component.setDeadlineTime(LocalTime(hour, minute))
fun clearNewDeadlineTime(component: NewComponent) = component.setDeadlineTime(null)

fun newDictationIsListening(state: NewState): Boolean = state.dictation is DictationStatus.Listening
fun newDictationIsPermissionDenied(state: NewState): Boolean = state.dictation is DictationStatus.PermissionDenied
fun newDictationIsPermanentlyDenied(state: NewState): Boolean =
    state.dictation is DictationStatus.PermissionPermanentlyDenied
fun newDictationFieldIsTitle(state: NewState): Boolean = state.dictationField == DictationField.Title
fun startNewDictationTitle(component: NewComponent) = component.startDictation(DictationField.Title)
fun startNewDictationNotes(component: NewComponent) = component.startDictation(DictationField.Notes)
fun stopNewDictation(component: NewComponent) = component.stopDictation()
