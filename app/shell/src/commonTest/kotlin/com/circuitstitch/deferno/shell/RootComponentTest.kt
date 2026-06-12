package com.circuitstitch.deferno.shell

import com.arkivanov.decompose.DefaultComponentContext
import com.arkivanov.essenty.lifecycle.LifecycleRegistry
import com.circuitstitch.deferno.core.data.account.AccountManager
import com.circuitstitch.deferno.core.data.auth.SignInResult
import com.circuitstitch.deferno.core.data.auth.SignInService
import com.circuitstitch.deferno.core.data.connectivity.AssumeOnlineConnectivity
import com.circuitstitch.deferno.core.data.connectivity.Connectivity
import com.circuitstitch.deferno.core.data.plan.PlanRepository
import com.circuitstitch.deferno.core.data.settings.SettingsRepository
import com.circuitstitch.deferno.core.data.task.TaskRepository
import com.circuitstitch.deferno.core.model.Account
import com.circuitstitch.deferno.core.model.AccountId
import com.circuitstitch.deferno.core.model.TaskId
import com.circuitstitch.deferno.core.model.ThemeFamily
import com.circuitstitch.deferno.core.model.ThemeMode
import com.circuitstitch.deferno.core.model.UserSettings
import com.circuitstitch.deferno.core.speech.DefaultSpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.EmptySpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.InMemorySpeechEnginePreference
import com.circuitstitch.deferno.core.speech.SpeechEngineCatalog
import com.circuitstitch.deferno.core.speech.SpeechEngineId
import com.circuitstitch.deferno.core.data.outbox.FlushResult
import com.circuitstitch.deferno.demo.DemoPlanRepository
import com.circuitstitch.deferno.demo.DemoTaskRepository
import com.circuitstitch.deferno.demo.SampleData
import com.circuitstitch.deferno.ui.FakeAuthRepository
import com.circuitstitch.deferno.ui.FakeSettingsEditor
import com.circuitstitch.deferno.ui.FakeSettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.datetime.LocalDate
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotSame
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds
import kotlin.time.Instant

/**
 * The per-scene navigation root (ADR-0013 / ADR-0014): the two-state Shell selected by the Active
 * Account, re-entrant in both directions, with system-back routed to the active Shell and a Tasks
 * "add to plan" applied through the Active Account's offline write path. Driven here by a fake
 * AccountManager + AccountSession (no real graph/network), with an Unconfined context so the
 * activeAccount collector reacts synchronously.
 */
class RootComponentTest {

    private val today = LocalDate(2026, 6, 6)
    private val t0 = Instant.parse("2026-06-06T08:00:00Z")
    private val account = Account(AccountId("work"), "Work")

    private fun root(
        manager: AccountManager,
        session: AccountSession = FakeAccountSession(),
        signInService: SignInService = FakeSignInService { SignInResult.Unavailable },
    ) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        accountManager = manager,
        authRepository = FakeAuthRepository(),
        accountSession = { session },
        signInService = signInService,
        today = today,
        timeZone = "UTC",
        now = { t0 },
        coroutineContext = Dispatchers.Unconfined,
    )

    /** A root whose Main child is built from a per-Account [sessionFor] — for the theme re-pointing tests. */
    private fun rootWithSessions(
        manager: AccountManager,
        speechEngineCatalog: SpeechEngineCatalog = EmptySpeechEngineCatalog,
        sessionFor: (Account) -> AccountSession,
    ) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        accountManager = manager,
        authRepository = FakeAuthRepository(),
        accountSession = sessionFor,
        signInService = FakeSignInService { SignInResult.Unavailable },
        today = today,
        timeZone = "UTC",
        speechEngineCatalog = speechEngineCatalog,
        now = { t0 },
        coroutineContext = Dispatchers.Unconfined,
    )

    /**
     * A root on the [TestScope]'s virtual scheduler — for the outbox-driver tests (#143), whose
     * periodic flush loop is advanced deterministically with [advanceTimeBy].
     */
    private fun TestScope.rootWithVirtualTime(
        manager: AccountManager,
        connectivity: Connectivity = AssumeOnlineConnectivity(),
        sessionFor: (Account) -> AccountSession,
    ) = DefaultRootComponent(
        componentContext = DefaultComponentContext(LifecycleRegistry()),
        accountManager = manager,
        authRepository = FakeAuthRepository(),
        accountSession = sessionFor,
        signInService = FakeSignInService { SignInResult.Unavailable },
        today = today,
        timeZone = "UTC",
        now = { t0 },
        outboxFlushPeriod = 30.seconds,
        connectivity = connectivity,
        coroutineContext = UnconfinedTestDispatcher(testScheduler),
    )

    private fun RootComponent.activeChild() = stack.value.active.instance

    @Test
    fun noActiveAccount_showsTheAuthShell() {
        assertTrue(root(FakeAccountManager()).activeChild() is RootComponent.Child.Auth)
    }

    @Test
    fun anActiveAccount_showsTheMainShell() {
        assertTrue(root(FakeAccountManager(active = account)).activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun activatingAnAccount_swapsTheAuthShellForTheMainShell() {
        val manager = FakeAccountManager()
        val root = root(manager)
        assertTrue(root.activeChild() is RootComponent.Child.Auth)

        manager.signIn(account)

        assertTrue(root.activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun signingOut_isReentrant_swapsTheMainShellBackForTheAuthShell() {
        val manager = FakeAccountManager(active = account)
        val root = root(manager)
        assertTrue(root.activeChild() is RootComponent.Child.Main)

        manager.signOut()

        assertTrue(root.activeChild() is RootComponent.Child.Auth)
    }

    @Test
    fun signOutThenSignIn_rebuildsTheMainShellFresh_perAccountIsolation() {
        val manager = FakeAccountManager(active = account)
        val root = root(manager)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component
        // Drill into Tasks on the first Main shell.
        firstMain.selectDestination(Destination.Tasks)
        (firstMain.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks)
            .component.list.onTaskClicked(TaskId("t-1"))

        manager.signOut()
        manager.signIn(account)

        // The Main shell is rebuilt per Account, not mutated across the boundary (ADR-0013 / ADR-0002):
        // a fresh instance, opened at the Plan home — the prior Account's drill-down is gone.
        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(firstMain, secondMain)
        assertEquals(Destination.Plan, secondMain.stack.value.active.instance.destination)
    }

    @Test
    fun switchingAccount_rebuildsTheMainShellForTheNewAccount() {
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val root = root(manager)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component

        (firstMain as DefaultMainShellComponent).switchAccount(personal.id)

        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(firstMain, secondMain)
        assertEquals(personal, manager.activeAccount.value)
    }

    @Test
    fun submittingAValidTokenOnTheAuthShell_swapsToTheMainShell() {
        val manager = FakeAccountManager()
        // A valid pasted token establishes the Account (addAccount); the activeAccount collector then
        // swaps the Auth shell for the Main shell — the #15/ADR-0023 sign-in path through the shell.
        val root = root(
            manager,
            signInService = FakeSignInService { manager.signIn(account); SignInResult.Success(account) },
        )
        val auth = root.activeChild() as RootComponent.Child.Auth

        auth.component.signIn.onTokenChange("pat-xyz")
        auth.component.signIn.onSubmit()

        assertTrue(root.activeChild() is RootComponent.Child.Main)
    }

    @Test
    fun backOnAuthShell_isNotConsumed() {
        assertFalse(root(FakeAccountManager()).onBackClicked())
    }

    @Test
    fun backOnMainShell_delegatesToTheActiveDestination() {
        val root = root(FakeAccountManager(active = account))
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Tasks)
        (main.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks)
            .component.list.onTaskClicked(TaskId("t-1"))

        // Back dismisses the Tasks detail (delegated to the Main shell), so it is consumed.
        assertTrue(root.onBackClicked())
    }

    @Test
    fun addToPlanFromTasks_appliesThroughTheActiveAccountSession() {
        val session = FakeAccountSession()
        val root = root(FakeAccountManager(active = account), session = session)
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Tasks)
        val tasks = (main.stack.value.active.instance as MainShellComponent.DestinationChild.Tasks).component
        tasks.list.onTaskClicked(TaskId("t-1"))

        tasks.detail.value.child?.instance?.onAddToPlanClicked()

        assertEquals(listOf(TaskId("t-1")), session.addedToPlan)
    }

    @Test
    fun signingOutFromProfile_secureWipesTheActiveAccount_andReturnsToAuth() {
        val manager = FakeAccountManager(active = account)
        val root = root(manager)
        val main = (root.activeChild() as RootComponent.Child.Main).component
        main.selectDestination(Destination.Profile)
        val profile =
            (main.stack.value.active.instance as MainShellComponent.DestinationChild.Profile).component

        profile.onSignOut()

        // The only Account is removed (secure-wiped) → activeAccount null → swap back to the Auth shell.
        assertTrue(root.activeChild() is RootComponent.Child.Auth)
        assertNull(manager.activeAccount.value)
    }

    @Test
    fun signingOut_withAnotherAccountPresent_switchesToTheSibling_notAuth() {
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val root = root(manager)
        val firstMain = (root.activeChild() as RootComponent.Child.Main).component
        firstMain.selectDestination(Destination.Profile)
        (firstMain.stack.value.active.instance as MainShellComponent.DestinationChild.Profile).component.onSignOut()

        // removeAccount re-points active to the remaining sibling → still Main, but the shell is REBUILT
        // per Account, not mutated across the boundary (ADR-0013 / ADR-0002): a fresh instance bound to
        // Personal, which we prove by reading the new Profile's bound Account.
        assertTrue(root.activeChild() is RootComponent.Child.Main)
        val secondMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(firstMain, secondMain, "the Main shell is re-keyed for the sibling, not mutated")
        secondMain.selectDestination(Destination.Profile)
        assertEquals(
            personal,
            (secondMain.stack.value.active.instance as MainShellComponent.DestinationChild.Profile)
                .component.account,
        )
        assertEquals(personal, manager.activeAccount.value)
    }

    // --- device-local speech-engine App setting (#93, ADR-0018, AC2: "never changes on Account switch") ---

    @Test
    fun speechEngineChoice_survivesAccountSwitch_isDeviceLocalNotPerAccount() {
        // The inverse of the synced theme (which re-points per Account): the speech engine is a device-local
        // App setting, so the choice must survive an Account switch unchanged (AC2). One catalog is shared
        // across every Main rebuild — the AppScope catalog production threads, not a per-Account one.
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val catalog = DefaultSpeechEngineCatalog(emptySet(), InMemorySpeechEnginePreference())
        val root = rootWithSessions(manager, speechEngineCatalog = catalog) { FakeAccountSession() }

        // On Work, choose "Automatic" via the Settings Destination.
        val workMain = (root.activeChild() as RootComponent.Child.Main).component
        workMain.selectDestination(Destination.Settings)
        val workSettings =
            (workMain.stack.value.active.instance as MainShellComponent.DestinationChild.Settings).component
        workSettings.onSpeechEngineSelected(SpeechEngineId.Automatic)
        assertEquals(SpeechEngineId.Automatic, workSettings.speechEngine.value.selected)

        // Switch to Personal: the Main shell is re-keyed (per-Account isolation, ADR-0002), but the
        // device-local speech choice persists — it is NOT rebuilt per Account.
        workMain.switchAccount(personal.id)
        val personalMain = (root.activeChild() as RootComponent.Child.Main).component
        assertNotSame(workMain, personalMain, "the Main shell is re-keyed for the switch")
        personalMain.selectDestination(Destination.Settings)
        val personalSettings =
            (personalMain.stack.value.active.instance as MainShellComponent.DestinationChild.Settings).component
        assertEquals(
            SpeechEngineId.Automatic,
            personalSettings.speechEngine.value.selected,
            "the device-local speech choice survives the Account switch (it is not per-Account)",
        )
    }

    // --- app-wide live-theme StateFlow re-pointing (#72, AC: "Appearance applies live app-wide") ---

    @Test
    fun themeSettings_defaultsToDefault_inTheAuthShell() {
        // No Active Account → the app-wide theme falls back to the default so the Auth shell is themed.
        val root = root(FakeAccountManager())
        assertTrue(root.activeChild() is RootComponent.Child.Auth)
        assertEquals(UserSettings.Default, root.themeSettings.value)
    }

    @Test
    fun themeSettings_pointsAtTheActiveAccountsSettings_onOpen() {
        val mono = UserSettings.Default.copy(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark)
        val session = FakeAccountSession(settingsRepository = FakeSettingsRepository(mono))
        val root = rootWithSessions(FakeAccountManager(active = account)) { session }

        // The root mirrors the active session's settings into the app-wide theme StateFlow on open.
        assertEquals(mono, root.themeSettings.value)
    }

    @Test
    fun themeSettings_updatesLive_whenTheActiveAccountsSettingsChange() {
        // This is the actual "applies live app-wide" mechanism: the root's themeSettings StateFlow
        // re-emits when the Active Account's settings Flow changes (the seam behind DefernoTheme).
        val repo = FakeSettingsRepository(UserSettings.Default)
        val session = FakeAccountSession(settingsRepository = repo)
        val root = rootWithSessions(FakeAccountManager(active = account)) { session }
        assertEquals(ThemeFamily.Deferno, root.themeSettings.value.themeFamily)

        // A live Appearance write (the same Flow the Settings writer mutates) re-points the theme.
        repo.state.value = repo.state.value.copy(themeFamily = ThemeFamily.Mono, themeMode = ThemeMode.Dark)

        assertEquals(ThemeFamily.Mono, root.themeSettings.value.themeFamily)
        assertEquals(ThemeMode.Dark, root.themeSettings.value.themeMode)
    }

    @Test
    fun themeSettings_rePointsOnAccountSwitch_andTheOldAccountsLaterChangeDoesNotBleed() {
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val workRepo = FakeSettingsRepository(UserSettings.Default.copy(themeFamily = ThemeFamily.Deferno))
        val personalRepo = FakeSettingsRepository(UserSettings.Default.copy(themeFamily = ThemeFamily.Mono))
        val sessions = mapOf(
            account.id to FakeAccountSession(settingsRepository = workRepo),
            personal.id to FakeAccountSession(settingsRepository = personalRepo),
        )
        val root = rootWithSessions(manager) { sessions.getValue(it.id) }
        assertEquals(ThemeFamily.Deferno, root.themeSettings.value.themeFamily, "opens pointed at Work's theme")

        // Switch to Personal: the theme re-points to Personal's settings (account isolation, ADR-0002).
        (root.activeChild() as RootComponent.Child.Main).component.let {
            (it as DefaultMainShellComponent).switchAccount(personal.id)
        }
        assertEquals(ThemeFamily.Mono, root.themeSettings.value.themeFamily, "re-points to Personal's theme on switch")

        // The prior account's collector must be cancelled: a later Work-side change must NOT bleed through.
        workRepo.state.value = workRepo.state.value.copy(themeMode = ThemeMode.Light)
        assertEquals(ThemeFamily.Mono, root.themeSettings.value.themeFamily, "Work's collector is cancelled — no cross-account bleed")
        assertEquals(ThemeMode.Auto, root.themeSettings.value.themeMode)
    }

    // --- outbox driver (#143: offline writes must sync; the cold-start reconcile must not clobber) ---

    @Test
    fun openingMain_flushesTheOutboxBeforeTheSettingsReconcile() {
        // The #143 startup order: the queued offline writes reach the server FIRST, so the settings
        // pull that follows can't fetch a snapshot that predates them (the cold-start theme revert).
        val events = mutableListOf<String>()
        val settingsRepo = object : SettingsRepository {
            override fun observeSettings(): Flow<UserSettings> = MutableStateFlow(UserSettings.Default)
            override suspend fun refresh() { events += "settings.refresh" }
        }
        val session = FakeAccountSession(settingsRepository = settingsRepo, onFlush = { events += "flush" })

        root(FakeAccountManager(active = account), session = session)

        assertEquals(listOf("flush", "settings.refresh"), events)
    }

    @Test
    fun outboxKeepsFlushingPeriodically_whileTheSessionIsActive() = runTest {
        // Writes made DURING a session (not just at activation) must sync without waiting for the
        // next cold start — the periodic re-flush is what picks them up (#143).
        val manager = FakeAccountManager(active = account)
        val session = FakeAccountSession()
        rootWithVirtualTime(manager) { session }
        assertEquals(1, session.flushes.size, "the activation flush")

        advanceTimeBy(31.seconds)
        assertEquals(2, session.flushes.size, "one periodic re-flush per period")

        advanceTimeBy(30.seconds)
        assertEquals(3, session.flushes.size)

        // Stop the driver before runTest's run-until-idle cleanup — an active periodic loop on the
        // virtual scheduler would otherwise never go idle.
        manager.signOut()
    }

    @Test
    fun signingOut_stopsTheOutboxDriver() = runTest {
        val manager = FakeAccountManager(active = account)
        val session = FakeAccountSession()
        rootWithVirtualTime(manager) { session }
        assertEquals(1, session.flushes.size)

        manager.signOut()
        advanceTimeBy(120.seconds)

        // No Active Account → no driver: the signed-out Account's outbox is never flushed again.
        assertEquals(1, session.flushes.size)
    }

    @Test
    fun switchingAccount_rePointsTheOutboxDriver_atTheNewAccountsSession() = runTest {
        val personal = Account(AccountId("personal"), "Personal")
        val manager = FakeAccountManager(active = account).also { it.signIn(personal); it.activate(account.id) }
        val workSession = FakeAccountSession()
        val personalSession = FakeAccountSession()
        val sessions = mapOf(account.id to workSession, personal.id to personalSession)
        val root = rootWithVirtualTime(manager) { sessions.getValue(it.id) }
        assertEquals(1, workSession.flushes.size)

        (root.activeChild() as RootComponent.Child.Main).component
            .let { (it as DefaultMainShellComponent).switchAccount(personal.id) }
        advanceTimeBy(31.seconds)

        // The old driver is cancelled (account isolation); the new session flushes on activation
        // and keeps the periodic cadence.
        assertEquals(1, workSession.flushes.size)
        assertEquals(2, personalSession.flushes.size)

        // Stop the driver before runTest's run-until-idle cleanup (see the periodic test above).
        manager.signOut()
    }

    // --- reconnect-triggered flush (#158: the offline→online edge; known-offline burns no attempts) ---

    @Test
    fun reconnect_flushesTheOutboxImmediately_withoutWaitingOutTheTick() = runTest {
        val manager = FakeAccountManager(active = account)
        val session = FakeAccountSession()
        val connectivity = FakeConnectivity(online = true)
        rootWithVirtualTime(manager, connectivity = connectivity) { session }
        assertEquals(1, session.flushes.size, "the activation flush")

        connectivity.online.value = false
        advanceTimeBy(5.seconds) // well inside the tick — recovery must not be bounded by it
        connectivity.online.value = true
        runCurrent()

        assertEquals(2, session.flushes.size, "the reconnect edge flushes immediately, not at the next tick")

        manager.signOut()
    }

    @Test
    fun whileKnownOffline_periodicPassesAreSkipped_soQueuedWritesCannotBurnAttempts() = runTest {
        val manager = FakeAccountManager(active = account)
        val session = FakeAccountSession()
        val connectivity = FakeConnectivity(online = true)
        rootWithVirtualTime(manager, connectivity = connectivity) { session }
        assertEquals(1, session.flushes.size)

        connectivity.online.value = false
        advanceTimeBy(95.seconds) // three ticks' worth of flight mode

        // No pass runs while known-offline: a long offline stretch must not walk the queued writes
        // into the replay engine's give-up policy (#158) — maxAttempts measures real server failures.
        assertEquals(1, session.flushes.size, "offline ticks burn no attempts")

        connectivity.online.value = true
        runCurrent()
        assertEquals(2, session.flushes.size, "the reconnect edge")

        advanceTimeBy(31.seconds)
        assertEquals(3, session.flushes.size, "the periodic cadence resumes once online")

        manager.signOut()
    }

    @Test
    fun activationWhileOffline_skipsTheActivationFlush_untilTheReconnectEdge() = runTest {
        val events = mutableListOf<String>()
        val settingsRepo = object : SettingsRepository {
            override fun observeSettings(): Flow<UserSettings> = MutableStateFlow(UserSettings.Default)
            override suspend fun refresh() { events += "settings.refresh" }
        }
        val manager = FakeAccountManager(active = account)
        val session = FakeAccountSession(settingsRepository = settingsRepo, onFlush = { events += "flush" })
        val connectivity = FakeConnectivity(online = false)
        rootWithVirtualTime(manager, connectivity = connectivity) { session }

        // Known-offline at activation: the flush is skipped (it could only burn an attempt); the
        // settings refresh still runs — and fails harmlessly offline, unchanged from #143.
        assertEquals(listOf("settings.refresh"), events)

        connectivity.online.value = true
        runCurrent()

        // The reconnect edge re-runs the activation sequence — flush FIRST, then the settings
        // reconcile, preserving the #143 ordering (the pull can't predate the just-flushed writes).
        assertEquals(listOf("settings.refresh", "flush", "settings.refresh"), events)

        manager.signOut()
    }

    @Test
    fun themeSettings_fallsBackToDefault_onSignOut() {
        val mono = UserSettings.Default.copy(themeFamily = ThemeFamily.Mono)
        val manager = FakeAccountManager(active = account)
        val root = rootWithSessions(manager) { FakeAccountSession(settingsRepository = FakeSettingsRepository(mono)) }
        assertEquals(ThemeFamily.Mono, root.themeSettings.value.themeFamily)

        manager.signOut()

        // Back at the Auth shell → the app-wide theme resets to the default (no stale account theme).
        assertTrue(root.activeChild() is RootComponent.Child.Auth)
        assertEquals(UserSettings.Default, root.themeSettings.value)
    }
}

/**
 * A [SignInService] double whose [signIn] / [signInWithBrowser] both run [onSignIn] and return its
 * result — lets the Auth-shell test drive a sign-in through to an established Account without a real
 * `/auth/me` call or browser leg.
 */
private class FakeSignInService(private val onSignIn: () -> SignInResult) : SignInService {
    override suspend fun signInWithBrowser(): SignInResult = onSignIn()
    override suspend fun signIn(token: String): SignInResult = onSignIn()
}

/** A [Connectivity] whose online/offline state the test drives (`online.value = …`) (#158). */
private class FakeConnectivity(online: Boolean) : Connectivity {
    override val online = MutableStateFlow(online)
}

/**
 * In-memory [AccountManager] for the shell tests. State changes propagate synchronously to the
 * RootComponent's Unconfined collector, so the helpers ([signIn] / [signOut] / [activate]) drive the
 * shell without coroutine plumbing.
 */
private class FakeAccountManager(active: Account? = null) : AccountManager {
    private val _accounts = MutableStateFlow(listOfNotNull(active))
    override val accounts: StateFlow<List<Account>> = _accounts

    private val _activeAccount = MutableStateFlow(active)
    override val activeAccount: StateFlow<Account?> = _activeAccount

    override suspend fun addAccount(account: Account, token: String) = signIn(account)

    override suspend fun removeAccount(id: AccountId) {
        _accounts.value = _accounts.value.filterNot { it.id == id }
        if (_activeAccount.value?.id == id) _activeAccount.value = _accounts.value.firstOrNull()
    }

    override suspend fun switchTo(id: AccountId) = activate(id)

    override suspend fun load() = Unit

    fun signIn(account: Account) {
        if (_accounts.value.none { it.id == account.id }) _accounts.value = _accounts.value + account
        _activeAccount.value = account
    }

    fun activate(id: AccountId) {
        _activeAccount.value = _accounts.value.first { it.id == id }
    }

    fun signOut() {
        _activeAccount.value = null
    }
}

/** In-memory [AccountSession] over the in-memory demo repositories; records add-to-plan + create + flush calls. */
private class FakeAccountSession(
    override val taskRepository: TaskRepository = DemoTaskRepository(SampleData.tasks),
    override val planRepository: PlanRepository = DemoPlanRepository(emptyList()),
    override val settingsRepository: SettingsRepository = FakeSettingsRepository(),
    override val settingsEditor: com.circuitstitch.deferno.feature.settings.SettingsEditor = FakeSettingsEditor(),
    /** Hook for the driver-ordering test (#143) — runs inside [flushOutbox] before it returns. */
    private val onFlush: () -> Unit = {},
) : AccountSession {
    val addedToPlan = mutableListOf<TaskId>()
    val flushes = mutableListOf<Instant>()

    override suspend fun flushOutbox(now: Instant): FlushResult {
        flushes += now
        onFlush()
        return FlushResult(succeeded = 0, dropped = 0, retried = 0, remaining = 0)
    }
    val workingStateSets = mutableListOf<Pair<TaskId, com.circuitstitch.deferno.core.model.WorkingState>>()
    val created = mutableListOf<com.circuitstitch.deferno.core.domain.command.CreateItem.Payload>()

    override suspend fun addToPlan(taskId: TaskId, date: LocalDate, tz: String) {
        addedToPlan += taskId
    }

    override val workingStateEditor: com.circuitstitch.deferno.feature.tasks.WorkingStateEditor =
        com.circuitstitch.deferno.feature.tasks.WorkingStateEditor { id, target, _ ->
            workingStateSets += id to target
        }

    override val calendarRepository: com.circuitstitch.deferno.core.data.calendar.CalendarRepository =
        object : com.circuitstitch.deferno.core.data.calendar.CalendarRepository {
            override fun observeMarkers(from: LocalDate, to: LocalDate) =
                kotlinx.coroutines.flow.MutableStateFlow<Map<LocalDate, Int>>(emptyMap())
            override fun observeDay(date: LocalDate) =
                kotlinx.coroutines.flow.MutableStateFlow<List<com.circuitstitch.deferno.core.model.CalendarItem>>(emptyList())
            override suspend fun refreshWindow(from: LocalDate, to: LocalDate, tz: String) {}
            override suspend fun reconcile() {}
        }

    override val occurrenceEditor: com.circuitstitch.deferno.feature.calendar.OccurrenceEditor =
        object : com.circuitstitch.deferno.feature.calendar.OccurrenceEditor {
            override suspend fun mark(itemId: String, action: com.circuitstitch.deferno.core.model.OccurrenceAction) {}
            override suspend fun clear(itemId: String) {}
            override suspend fun reschedule(itemId: String, newDate: LocalDate) {}
        }

    override suspend fun create(
        payload: com.circuitstitch.deferno.core.domain.command.CreateItem.Payload,
    ): com.circuitstitch.deferno.core.domain.command.CommandResult {
        created += payload
        return com.circuitstitch.deferno.core.domain.command.CommandResult.Accepted(
            com.circuitstitch.deferno.core.domain.command.CommandKind.CreateItem,
        )
    }
}
