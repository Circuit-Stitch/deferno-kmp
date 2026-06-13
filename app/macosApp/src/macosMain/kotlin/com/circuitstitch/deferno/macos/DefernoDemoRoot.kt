package com.circuitstitch.deferno.macos

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.arkivanov.essenty.lifecycle.destroy
import com.arkivanov.essenty.lifecycle.resume
import com.circuitstitch.deferno.core.common.log.Logger
import com.circuitstitch.deferno.core.common.log.LogLevel
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.auth.AuthRepository
import com.circuitstitch.deferno.core.data.auth.MeResult
import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.data.auth.SignInService
import com.circuitstitch.deferno.core.data.calendar.CalendarRepository
import com.circuitstitch.deferno.core.data.outbox.FlushResult
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.domain.command.CommandKind
import com.circuitstitch.deferno.core.domain.command.CommandResult
import com.circuitstitch.deferno.core.domain.command.CreateItem
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.CalendarItem
import com.circuitstitch.deferno.core.model.CalendarSource
import com.circuitstitch.deferno.core.model.ItemKind
import com.circuitstitch.deferno.core.model.OccurrenceAction
import com.circuitstitch.deferno.core.model.OrgId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.User
import com.circuitstitch.deferno.core.model.UserId
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.model.WorkingState
import com.circuitstitch.deferno.core.speech.SpeechToText
import com.circuitstitch.deferno.core.speech.UnavailableSpeechToText
import com.circuitstitch.deferno.feature.calendar.OccurrenceEditor
import com.circuitstitch.deferno.feature.settings.SettingsEditor
import com.circuitstitch.deferno.feature.tasks.WorkingStateEditor
import com.circuitstitch.deferno.macos.agent.DraftTasksBridge
import com.circuitstitch.deferno.macos.agent.NativeInference
import com.circuitstitch.deferno.macos.demo.DemoPlanRepository
import com.circuitstitch.deferno.macos.demo.DemoTaskRepository
import com.circuitstitch.deferno.macos.demo.SampleData
import com.circuitstitch.deferno.macos.speech.NativeDictation
import com.circuitstitch.deferno.macos.speech.NativeSpeechToText
import com.circuitstitch.deferno.shell.AccountSession
import com.circuitstitch.deferno.shell.DefaultRootComponent
import com.circuitstitch.deferno.shell.RootComponent
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.datetime.LocalDate
import kotlinx.datetime.TimeZone
import kotlinx.datetime.todayIn
import kotlin.time.Clock
import kotlin.time.Instant

/**
 * The macOS **demo host** (ADR-0029, Phase 1): the macOS analogue of iOS's `DefernoRoot`, but built
 * over **in-memory fakes** instead of the real DI graph — so the SwiftUI Views render the *whole*
 * shared shell ([RootComponent] → Auth/Main → the five Destinations + the Search/New overlays,
 * ADR-0013/0017) with no backend, no network, and no encrypted database (so Phase 1 needs neither
 * sign-in nor SQLCipher). It seeds one Active Account, so the app opens straight into the **Main
 * shell**. The real `DefernoRoot` over the DI graph + paste-PAT sign-in is Phase 1b.
 *
 * This follows the iOS precedent of a demo harness living in `main` source (app/iosApp's `DefernoDemo`
 * + its `demo` package): the Views are genuine renderers of the shared Decompose components — only the
 * data source is a fixture. State is produced on `Dispatchers.Main` (the [DefaultRootComponent] default),
 * so observation hops straight to the SwiftUI main thread.
 *
 * [dictation] is the Phase-2 in-process speech seam (ADR-0029): the Swift app passes a [NativeDictation]
 * over `SidecarKit.SpeechTranscriber`, so the New surface's mic dictates on-device. `null` (the default)
 * leaves dictation unavailable (the mic stays hidden) — keeping the harness runnable without it.
 *
 * [inference] is the Phase-3 in-process inference seam (ADR-0029): the Swift app passes a
 * [NativeInference] over Apple Intelligence's Foundation Models, exposed to SwiftUI as [draftTasks] (the
 * propose-only Brain-dump Extractor over the on-device model). `null` (the default) leaves [draftTasks]
 * `null` — the harness still runs, the Apple-Intelligence panel just reports the engine unavailable.
 */
class DefernoDemoRoot(
    dictation: NativeDictation? = null,
    inference: NativeInference? = null,
) {

    init {
        // Configure the uniform logger once (ADR-0029): os_log-backed on macOS, identical call shape to
        // the rest of the fleet. Debug-friendly minimum for the spike.
        Logger.configure(minLogLevel = LogLevel.DEBUG, prefix = "Deferno")
        Logger("DefernoDemoRoot").i { "Deferno (macOS) starting — demo harness" }
    }

    private val lifecycle = LifecycleRegistry()
    private val timeZone = TimeZone.currentSystemDefault()
    private val today = Clock.System.todayIn(timeZone)
    private val session = DemoAccountSession()

    // In-process dictation (Phase 2): wrap the injected Swift transcriber, or the Unavailable floor.
    private val speechToText: SpeechToText =
        dictation?.let { NativeSpeechToText(it) } ?: UnavailableSpeechToText

    /**
     * In-process inference (Phase 3): the on-device Brain-dump Extractor over the injected Foundation
     * Models engine, or `null` when no engine is injected. Demo-direct wiring — the real DI graph binds
     * the same engine via `MacosAgentBindings` once the engine-choice App setting lands (#150 / Phase 1b).
     */
    val draftTasks: DraftTasksBridge? =
        inference?.let { DraftTasksBridge(it, today, timeZone.id) }

    /** The shared navigation root the SwiftUI `RootView` renders. Opens on the Main shell (seeded Account). */
    val root: RootComponent = DefaultRootComponent(
        componentContext = DefaultComponentContext(lifecycle),
        accountManager = DemoAccountManager(active = demoAccount),
        authRepository = DemoAuthRepository,
        accountSession = { session },
        signInService = DemoSignInService,
        today = today,
        timeZone = timeZone.id,
        // Phase 2: the New surface's mic drives this on-device engine (ADR-0029). Locale stays the
        // DefaultRootComponent en-US default for the spike.
        speechToText = speechToText,
    )

    init {
        lifecycle.resume()
    }

    /** Tears down the retained component tree when the SwiftUI app scene goes away. */
    fun destroy() {
        lifecycle.destroy()
    }
}

// ---------------------------------------------------------------------------------------------------
// Demo fixtures — a seeded identity + Account so the shell opens on the Main shell over sample data.
// ---------------------------------------------------------------------------------------------------

private val demoAccount = Account(AccountId("work"), "Work")

private val demoUser = User(
    id = UserId("1d35f62e-eed9-44de-96e8-e61a307af83f"),
    username = "sampleuser",
    displayName = "Sample User",
    role = "admin",
    personalOrgId = OrgId("ebca93e5-d663-4624-9fe9-c5361b5b4390"),
    orgSlug = "u-e4h2qk",
    isAdmin = false,
    consoleUrl = "https://auth2.defernowork.com/ui/console",
)

private val demoSettings = UserSettings.Default.copy(
    themeFamily = ThemeFamily.Deferno,
    themeMode = ThemeMode.Auto,
    username = "sampleuser",
)

// ---------------------------------------------------------------------------------------------------
// In-memory seams the demo Root is built over (ADR-0014). Mirror the shell's own test fakes, but in
// `main` so the macOS app renders the full shell without a backend (the iOS DefernoDemo precedent).
// ---------------------------------------------------------------------------------------------------

private object DemoAuthRepository : AuthRepository {
    override suspend fun loadMe(): MeResult = MeResult.Authenticated(demoUser)
}

private object DemoSignInService : SignInService {
    override suspend fun signInWithBrowser(): SignInResult = SignInResult.Unavailable
    override suspend fun signIn(token: String): SignInResult = SignInResult.Unavailable
}

/** A single seeded Account so the shell opens on the Main shell; sign-out drops back to Auth. */
private class DemoAccountManager(active: Account) : AccountManager {
    private val _accounts = MutableStateFlow(listOf(active))
    override val accounts: StateFlow<List<Account>> = _accounts

    private val _activeAccount = MutableStateFlow<Account?>(active)
    override val activeAccount: StateFlow<Account?> = _activeAccount

    override suspend fun addAccount(account: Account, token: String) {
        if (_accounts.value.none { it.id == account.id }) _accounts.value = _accounts.value + account
        _activeAccount.value = account
    }

    override suspend fun removeAccount(id: AccountId) {
        _accounts.value = _accounts.value.filterNot { it.id == id }
        if (_activeAccount.value?.id == id) _activeAccount.value = _accounts.value.firstOrNull()
    }

    override suspend fun switchTo(id: AccountId) {
        _activeAccount.value = _accounts.value.first { it.id == id }
    }

    override suspend fun load() = Unit
}

private class DemoSettingsRepository(initial: UserSettings = demoSettings) : SettingsRepository {
    val state = MutableStateFlow(initial)
    override fun observeSettings(): Flow<UserSettings> = state
    override suspend fun refresh() { /* offline demo */ }
}

private class DemoSettingsEditor(private val repo: DemoSettingsRepository) : SettingsEditor {
    override suspend fun setTheme(family: ThemeFamily, mode: ThemeMode) {
        repo.state.value = repo.state.value.copy(themeFamily = family, themeMode = mode)
    }

    override suspend fun setTracking(enabled: Boolean) {
        repo.state.value = repo.state.value.copy(trackingEnabled = enabled)
    }

    override suspend fun setDragAndDrop(enabled: Boolean) {
        repo.state.value = repo.state.value.copy(dragAndDropEnabled = enabled)
    }

    override suspend fun setDoneVisibility(globalSeconds: Long?, dashboardSeconds: Long?) {
        repo.state.value = repo.state.value.copy(
            globalDoneVisibilitySeconds = globalSeconds,
            dashboardDoneVisibilitySeconds = dashboardSeconds,
        )
    }
}

/** A small, calm sample month for the Calendar Destination (mirrors the androidApp SampleCalendar). */
private object DemoCalendar {
    val day: LocalDate = LocalDate(2026, 6, 15)

    private fun item(id: String, kind: ItemKind?, seriesId: String?, title: String, status: WorkingState = WorkingState.Open) =
        CalendarItem(
            id = id,
            taskId = "task-$id",
            seriesId = seriesId,
            title = title,
            date = day,
            start = Instant.parse("2026-06-15T09:00:00Z"),
            end = Instant.parse("2026-06-15T09:30:00Z"),
            allDay = false,
            status = status,
            kind = kind,
            source = CalendarSource.Deferno,
        )

    val agenda: Map<LocalDate, List<CalendarItem>> = mapOf(
        day to listOf(
            item("h1", ItemKind.Habit, "hab-1", "Morning stretch"),
            item("c1", ItemKind.Chore, "cho-1", "Water the plants", status = WorkingState.Done),
            item("e1", ItemKind.Event, "evt-1", "Team standup"),
            item("t1", kind = null, seriesId = null, title = "Pay the rent"),
        ),
    )

    val markers: Map<LocalDate, Int> = mapOf(
        LocalDate(2026, 6, 3) to 1,
        LocalDate(2026, 6, 8) to 2,
        day to 4,
        LocalDate(2026, 6, 22) to 1,
    )
}

private class DemoCalendarRepository : CalendarRepository {
    override fun observeMarkers(from: LocalDate, to: LocalDate): Flow<Map<LocalDate, Int>> =
        MutableStateFlow(DemoCalendar.markers.filterKeys { it >= from && it < to })

    override fun observeDay(date: LocalDate): Flow<List<CalendarItem>> =
        MutableStateFlow(DemoCalendar.agenda[date] ?: emptyList())

    override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
    override suspend fun reconcile() {}
}

/** In-memory [AccountSession] over the demo repositories; writes apply optimistically, nothing leaves the app. */
private class DemoAccountSession : AccountSession {
    private val taskRepo = DemoTaskRepository(SampleData.tasks)
    private val planRepo = DemoPlanRepository(SampleData.planTasks)
    private val settingsRepo = DemoSettingsRepository()

    override val taskRepository: TaskRepository get() = taskRepo
    override val planRepository: PlanRepository get() = planRepo
    override val settingsRepository: SettingsRepository get() = settingsRepo
    override val calendarRepository: CalendarRepository = DemoCalendarRepository()
    override val settingsEditor: SettingsEditor = DemoSettingsEditor(settingsRepo)

    override val workingStateEditor: WorkingStateEditor =
        WorkingStateEditor { id, target, _ -> taskRepo.setWorkingState(id, target) }

    override val occurrenceEditor: OccurrenceEditor = object : OccurrenceEditor {
        override suspend fun mark(itemId: String, action: OccurrenceAction) {}
        override suspend fun clear(itemId: String) {}
        override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
    }

    override suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String) {
        planRepo.add(taskRepo.snapshot(taskId))
    }

    // Create is online-only (ADR-0016); the demo has no backend, so it honestly reports Offline
    // ("reconnect to save") rather than fabricating a created item.
    override suspend fun create(payload: CreateItem.Payload): CommandResult =
        CommandResult.Offline(CommandKind.CreateItem)

    override suspend fun flushOutbox(now: Instant): FlushResult =
        FlushResult(succeeded = 0, dropped = 0, retried = 0, remaining = 0)
}
